package nz.valenta.androidtvmqtt.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import nz.valenta.androidtvmqtt.data.SettingsRepository
import nz.valenta.androidtvmqtt.service.MqttForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — checking autoStart setting")

        // Use goAsync so we can launch a coroutine without ANR risk
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context.applicationContext).getSettings()
                if (settings.autoStart && settings.isValid) {
                    Log.d(TAG, "AutoStart enabled — starting MqttForegroundService")
                    val serviceIntent = Intent(context, MqttForegroundService::class.java).apply {
                        action = MqttForegroundService.ACTION_START
                    }
                    context.startForegroundService(serviceIntent)
                } else {
                    Log.d(TAG, "AutoStart disabled or settings invalid — skipping")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
