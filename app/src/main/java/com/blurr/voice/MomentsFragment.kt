package com.blurr.voice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.data.LocalUserDataStore
import com.blurr.voice.data.TaskHistoryItem
import com.blurr.voice.utilities.FirebaseProviders
import com.blurr.voice.utilities.Logger
import com.blurr.voice.utilities.UserIdManager
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MomentsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var adapter: MomentsAdapter
    private val isFirebaseConfigured: Boolean
        get() = FirebaseApp.getApps(requireContext().applicationContext).isNotEmpty()
    private val db by lazy { FirebaseProviders.firestore(requireContext().applicationContext) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_moments, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.task_history_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = MomentsAdapter(emptyList())
        recyclerView.adapter = adapter

        loadTaskHistory()
    }

    override fun onResume() {
        super.onResume()
        if (::recyclerView.isInitialized) {
            loadTaskHistory()
        }
    }

    private fun loadTaskHistory() {
        val localHistory = LocalUserDataStore.getTaskHistory(requireContext())
        if (!isFirebaseConfigured) {
            if (localHistory.isNotEmpty()) {
                showTaskHistory(localHistory)
            } else {
                showEmptyState()
            }
            return
        }

        val localUserId = UserIdManager(requireContext()).getOrCreateUserId()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val document = db.collection("users").document(localUserId).get().await()
                if (document.exists()) {
                    val taskHistoryData = document.get("taskHistory") as? List<Map<String, Any>>
                    if (!taskHistoryData.isNullOrEmpty()) {
                        val taskHistory = taskHistoryData.mapNotNull { taskData ->
                            try {
                                TaskHistoryItem(
                                    task = taskData["task"] as? String ?: "",
                                    status = taskData["status"] as? String ?: "",
                                    startedAt = taskData["startedAt"] as? Timestamp,
                                    completedAt = taskData["completedAt"] as? Timestamp,
                                    success = taskData["success"] as? Boolean,
                                    errorMessage = taskData["errorMessage"] as? String
                                )
                            } catch (e: Exception) {
                                Logger.e("MomentsFragment", "Error parsing task history item", e)
                                null
                            }
                        }

                        val sortedTaskHistory = (taskHistory + localHistory)
                            .distinctBy { "${it.task}_${it.status}_${it.startedAt?.seconds}" }
                            .sortedByDescending { it.startedAt?.toDate() ?: java.util.Date(0) }

                        if (sortedTaskHistory.isNotEmpty()) {
                            showTaskHistory(sortedTaskHistory)
                        } else {
                            showEmptyState()
                        }
                    } else if (localHistory.isNotEmpty()) {
                        showTaskHistory(localHistory)
                    } else {
                        showEmptyState()
                    }
                } else if (localHistory.isNotEmpty()) {
                    showTaskHistory(localHistory)
                } else {
                    showEmptyState()
                }
            } catch (e: Exception) {
                FirebaseProviders.handleFirestoreError(
                    e,
                    FirebaseProviders.OperationType.GET,
                    "users/$localUserId"
                )
                Logger.e("MomentsFragment", "Error loading task history", e)
                if (localHistory.isNotEmpty()) {
                    showTaskHistory(localHistory)
                } else {
                    showEmptyState()
                }
            }
        }
    }

    private fun showTaskHistory(taskHistory: List<TaskHistoryItem>) {
        adapter = MomentsAdapter(taskHistory)
        recyclerView.adapter = adapter
        recyclerView.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
    }
}
