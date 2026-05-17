package nz.valenta.androidtvmqtt.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nz.valenta.androidtvmqtt.R
import java.util.LinkedList
import java.util.Queue

private data class QueuedMessage(
    val topic: String,
    val title: String,
    val description: String,
    val durationMs: Long
)

/**
 * Manages system overlay notifications using WindowManager.
 * Falls back to system notifications if SYSTEM_ALERT_WINDOW is not granted.
 * Must be used with applicationContext to avoid leaks.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
        private const val NOTIF_CHANNEL_ID = "mqtt_messages"
        private const val NOTIF_BASE_ID = 10000
        private var notifCounter = 0
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messageQueue: Queue<QueuedMessage> = LinkedList()

    @Volatile
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null

    fun showMessage(topic: String, title: String, description: String, durationMs: Long = 5000L) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) {
                showFallbackNotification(topic, title, description)
                return@post
            }
            if (currentView != null) {
                messageQueue.offer(QueuedMessage(topic, title, description, durationMs))
            } else {
                displayOverlay(topic, title, description, durationMs)
            }
        }
    }

    private fun displayOverlay(topic: String, title: String, description: String, durationMs: Long) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_notification, null)
        view.findViewById<TextView>(R.id.tv_topic).text = topic
        view.findViewById<TextView>(R.id.tv_title).text = title
        val tvDesc = view.findViewById<TextView>(R.id.tv_description)
        if (description.isNotBlank()) {
            tvDesc.text = description
            tvDesc.visibility = View.VISIBLE
        } else {
            tvDesc.visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            y = 0
        }

        try {
            windowManager.addView(view, params)
            currentView = view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}")
            showFallbackNotification(topic, title, description)
            return
        }

        // Slide in after the view is laid out so we know its height
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                view.translationY = -view.height.toFloat()
                view.animate().translationY(0f).setDuration(300).start()
                return true
            }
        })

        val dismiss = Runnable { dismissCurrent() }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, durationMs)
    }

    private fun dismissCurrent() {
        val view = currentView ?: return
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null

        view.animate()
            .translationY(-view.height.toFloat())
            .setDuration(250)
            .withEndAction {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.w(TAG, "removeView failed: ${e.message}")
                }
                currentView = null
                // Show next queued message
                messageQueue.poll()?.let { next ->
                    displayOverlay(next.topic, next.title, next.description, next.durationMs)
                }
            }
            .start()
    }

    private fun showFallbackNotification(topic: String, title: String, description: String) {
        try {
            val id = NOTIF_BASE_ID + (notifCounter++ % 10)
            val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(description.ifBlank { topic })
                .setStyle(NotificationCompat.BigTextStyle().bigText(description.ifBlank { title }))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback notification failed: ${e.message}")
        }
    }

    fun cleanup() {
        mainHandler.post {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null
            currentView?.let {
                try { windowManager.removeView(it) } catch (e: Exception) {}
            }
            currentView = null
            messageQueue.clear()
        }
    }
}
