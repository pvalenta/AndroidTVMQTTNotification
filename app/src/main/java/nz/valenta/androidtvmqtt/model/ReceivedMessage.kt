package nz.valenta.androidtvmqtt.model

data class ReceivedMessage(
    val id: Long,
    val topic: String,
    val title: String,
    val description: String,
    val timestamp: Long
) {
    companion object {
        fun create(topic: String, title: String, description: String): ReceivedMessage {
            val now = System.currentTimeMillis()
            return ReceivedMessage(id = now, topic = topic, title = title, description = description, timestamp = now)
        }
    }
}
