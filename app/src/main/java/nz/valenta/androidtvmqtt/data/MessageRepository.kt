package nz.valenta.androidtvmqtt.data

import android.content.Context
import android.util.Log
import nz.valenta.androidtvmqtt.model.ReceivedMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MessageRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "MessageRepository"
        private const val MAX_MESSAGES = 100
        private const val FILE_NAME = "message_history.json"

        @Volatile
        private var INSTANCE: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val file = File(context.filesDir, FILE_NAME)

    private val _messages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val messages: StateFlow<List<ReceivedMessage>> = _messages.asStateFlow()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun add(message: ReceivedMessage) {
        val updated = (_messages.value + message).takeLast(MAX_MESSAGES)
        _messages.value = updated
        saveToDisk(updated)
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        try {
            val json = JSONArray(file.readText())
            val list = (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                ReceivedMessage(
                    id = obj.getLong("id"),
                    topic = obj.getString("topic"),
                    title = obj.getString("title"),
                    description = obj.getString("description"),
                    timestamp = obj.getLong("timestamp")
                )
            }
            _messages.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load message history: ${e.message}")
        }
    }

    private fun saveToDisk(messages: List<ReceivedMessage>) {
        try {
            val json = JSONArray()
            messages.forEach { msg ->
                json.put(JSONObject().apply {
                    put("id", msg.id)
                    put("topic", msg.topic)
                    put("title", msg.title)
                    put("description", msg.description)
                    put("timestamp", msg.timestamp)
                })
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save message history: ${e.message}")
        }
    }
}
