package com.blurr.voice.data

import android.content.Context
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

/**
 * Lightweight local persistence for user memories and task history so Memories, Moments,
 * and automatic memory extraction work seamlessly without Firebase Authentication.
 */
object LocalUserDataStore {

    private const val PREFS_NAME = "BlurrLocalUserData"
    private const val KEY_MEMORIES = "memories_json"
    private const val KEY_TASK_HISTORY = "task_history_json"
    private const val MAX_TASKS = 100

    @Synchronized
    fun getMemories(context: Context): List<UserMemory> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MEMORIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<UserMemory>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    UserMemory(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        text = obj.optString("text", ""),
                        source = obj.optString("source", "User"),
                        createdAt = Date(obj.optLong("createdAtMs", System.currentTimeMillis()))
                    )
                )
            }
            list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addMemory(context: Context, text: String, source: String = "User"): UserMemory {
        val memory = UserMemory(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            source = source,
            createdAt = Date()
        )
        val current = getMemories(context).toMutableList()
        current.add(0, memory)
        saveMemories(context, current)
        return memory
    }

    @Synchronized
    fun updateMemory(context: Context, id: String, newText: String) {
        val current = getMemories(context).map { mem ->
            if (mem.id == id) mem.copy(text = newText.trim()) else mem
        }
        saveMemories(context, current)
    }

    @Synchronized
    fun deleteMemory(context: Context, id: String) {
        val current = getMemories(context).filterNot { it.id == id }
        saveMemories(context, current)
    }

    private fun saveMemories(context: Context, memories: List<UserMemory>) {
        val arr = JSONArray()
        memories.forEach { mem ->
            arr.put(JSONObject().apply {
                put("id", mem.id)
                put("text", mem.text)
                put("source", mem.source)
                put("createdAtMs", mem.createdAt.time)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEMORIES, arr.toString())
            .apply()
    }

    @Synchronized
    fun getTaskHistory(context: Context): List<TaskHistoryItem> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TASK_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<TaskHistoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val startedMs = obj.optLong("startedAtMs", 0L)
                val completedMs = obj.optLong("completedAtMs", 0L)
                val hasSuccess = obj.has("success") && !obj.isNull("success")
                list.add(
                    TaskHistoryItem(
                        task = obj.optString("task", ""),
                        status = obj.optString("status", "started"),
                        startedAt = if (startedMs > 0L) Timestamp(Date(startedMs)) else null,
                        completedAt = if (completedMs > 0L) Timestamp(Date(completedMs)) else null,
                        success = if (hasSuccess) obj.optBoolean("success") else null,
                        errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
            list.sortedByDescending { it.startedAt?.toDate()?.time ?: it.completedAt?.toDate()?.time ?: 0L }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun recordTaskStart(context: Context, task: String) {
        val list = getTaskHistory(context).toMutableList()
        list.add(
            0,
            TaskHistoryItem(
                task = task,
                status = "started",
                startedAt = Timestamp.now(),
                completedAt = null,
                success = null,
                errorMessage = null
            )
        )
        saveTaskHistory(context, list.take(MAX_TASKS))
    }

    @Synchronized
    fun recordTaskCompletion(context: Context, task: String, success: Boolean, errorMessage: String? = null) {
        val list = getTaskHistory(context).toMutableList()
        val idx = list.indexOfFirst { it.task == task && it.status == "started" }
        val now = Timestamp.now()
        if (idx != -1) {
            val existing = list[idx]
            list[idx] = existing.copy(
                status = if (success) "completed" else "failed",
                completedAt = now,
                success = success,
                errorMessage = errorMessage
            )
        } else {
            list.add(
                0,
                TaskHistoryItem(
                    task = task,
                    status = if (success) "completed" else "failed",
                    startedAt = now,
                    completedAt = now,
                    success = success,
                    errorMessage = errorMessage
                )
            )
        }
        saveTaskHistory(context, list.take(MAX_TASKS))
    }

    private fun saveTaskHistory(context: Context, items: List<TaskHistoryItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("task", item.task)
                put("status", item.status)
                put("startedAtMs", item.startedAt?.toDate()?.time ?: 0L)
                put("completedAtMs", item.completedAt?.toDate()?.time ?: 0L)
                if (item.success != null) put("success", item.success)
                if (item.errorMessage != null) put("errorMessage", item.errorMessage)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TASK_HISTORY, arr.toString())
            .apply()
    }
}
