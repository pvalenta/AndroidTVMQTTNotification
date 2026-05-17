package nz.valenta.androidtvmqtt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import nz.valenta.androidtvmqtt.R
import nz.valenta.androidtvmqtt.data.MessageRepository
import nz.valenta.androidtvmqtt.data.SettingsRepository
import nz.valenta.androidtvmqtt.model.MqttSettings
import nz.valenta.androidtvmqtt.model.ReceivedMessage
import nz.valenta.androidtvmqtt.overlay.OverlayManager
import nz.valenta.androidtvmqtt.ui.ConfigActivity
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MqttForegroundService : Service() {

    companion object {
        const val ACTION_START = "nz.valenta.androidtvmqtt.START"
        const val ACTION_STOP = "nz.valenta.androidtvmqtt.STOP"
        const val EXTRA_SETTINGS_CHANGED = "settings_changed"

        const val BROADCAST_STATUS = "nz.valenta.androidtvmqtt.STATUS"
        const val EXTRA_STATUS = "status"

        private const val NOTIF_CHANNEL_ID = "mqtt_service"
        private const val NOTIF_CHANNEL_MSG_ID = "mqtt_messages"
        private const val NOTIF_ID = 1

        private const val TAG = "MqttService"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var overlayManager: OverlayManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var messageRepo: MessageRepository

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        messageRepo = MessageRepository.getInstance(applicationContext)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            // ACTION_START or restart (null intent from START_STICKY)
            else -> {
                isRunning = true
                startForeground(NOTIF_ID, buildNotification("Connecting…"))
                scope.launch { runConnectionLoop() }
            }
        }
        return START_STICKY
    }

    private suspend fun runConnectionLoop() {
        val settings = settingsRepo.getSettings()
        if (!settings.isValid) {
            updateNotification("Not configured")
            broadcastStatus("Not configured")
            return
        }

        var backoffMs = 1_000L

        while (true) {
            try {
                updateNotification("Connecting to ${settings.brokerHost}…")
                broadcastStatus("Connecting")

                connectAndSubscribe(settings)

                // connectAndSubscribe suspends until connection is lost
                backoffMs = 1_000L // reset on clean disconnect
            } catch (e: CancellationException) {
                throw e // propagate cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                val waitSec = backoffMs / 1000
                updateNotification("Reconnecting in ${waitSec}s…")
                broadcastStatus("Reconnecting in ${waitSec}s")
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 30_000L)
            }
        }
    }

    /**
     * Connects to the broker, subscribes to the topic, and suspends until the connection is lost.
     */
    private suspend fun connectAndSubscribe(settings: MqttSettings) = suspendCancellableCoroutine<Unit> { cont ->
        val clientId = "AndroidTV_${android.os.Process.myPid()}"
        val client = MqttClient(settings.serverUri, clientId, MemoryPersistence())

        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "Connection lost: ${cause?.message}")
                if (cont.isActive) cont.resume(Unit)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload, Charsets.UTF_8)
                Log.d(TAG, "Message on $topic: $payload")
                val (title, description) = parsePayload(payload)
                val received = ReceivedMessage.create(topic = topic, title = title, description = description)
                messageRepo.add(received)
                overlayManager.showMessage(topic, title, description, settings.overlayDurationMs)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            if (settings.username.isNotBlank()) {
                userName = settings.username
                password = settings.password.toCharArray()
            }
        }

        try {
            client.connect(options)
            client.subscribe(settings.topic, 1)
            updateNotification("Connected · ${settings.brokerHost} · ${settings.topic}")
            broadcastStatus("Connected")
            cont.invokeOnCancellation {
                try { client.disconnect() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
        } catch (e: MqttException) {
            try { client.close() } catch (_: Exception) {}
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private fun parsePayload(payload: String): Pair<String, String> {
        return try {
            val json = JSONObject(payload)
            json.optString("title", payload) to json.optString("description", "")
        } catch (_: JSONException) {
            payload to ""
        }
    }

    private fun stopForegroundService() {
        isRunning = false
        scope.cancel()
        overlayManager.cleanup()
        broadcastStatus("Stopped")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        overlayManager.cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // region Notifications

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "MQTT Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Persistent MQTT connection status" }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_MSG_ID,
                    "MQTT Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Incoming MQTT message alerts (overlay fallback)" }
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, MqttForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, ConfigActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("MQTT Notification")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(status))
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
        }
        sendBroadcast(intent)
    }

    // endregion
}
