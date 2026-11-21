package com.blurr.voice.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.blurr.voice.triggers.ui.TriggersActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed. Rescheduling alarms.")
            val triggerManager = TriggerManager.getInstance(context)
            val prefs = context.getSharedPreferences(TriggersActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val triggersEnabled = prefs.getBoolean(TriggersActivity.KEY_TRIGGERS_ENABLED, false)

            if (triggersEnabled) {
                val serviceIntent = Intent(context, TriggerMonitoringService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "Started TriggerMonitoringService on boot.")
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to start TriggerMonitoringService on boot: ${e.message}")
                }
            }

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val triggers = triggerManager.getTriggers()
                    val scheduledTriggers = triggers.filter { it.isEnabled && it.type == TriggerType.SCHEDULED_TIME }
                    scheduledTriggers.forEach { trigger ->
                        triggerManager.updateTrigger(trigger)
                    }
                    Log.d(TAG, "Finished rescheduling ${scheduledTriggers.size} alarms.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error rescheduling alarms on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
