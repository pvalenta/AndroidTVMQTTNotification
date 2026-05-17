package nz.valenta.androidtvmqtt.model

data class MqttSettings(
    val brokerHost: String = "",
    val brokerPort: Int = 1883,
    val topic: String = "",
    val username: String = "",
    val password: String = "",
    val autoStart: Boolean = false,
    val overlayDurationMs: Long = 5000L
) {
    val isValid: Boolean get() = brokerHost.isNotBlank() && topic.isNotBlank()
    val serverUri: String get() = "tcp://$brokerHost:$brokerPort"
}
