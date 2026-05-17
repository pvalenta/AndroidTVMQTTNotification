package nz.valenta.androidtvmqtt.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import nz.valenta.androidtvmqtt.data.SettingsRepository
import nz.valenta.androidtvmqtt.discovery.DiscoveredBroker
import nz.valenta.androidtvmqtt.discovery.MqttDiscoveryService
import nz.valenta.androidtvmqtt.model.MqttSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

sealed class UiState {
    data object Idle : UiState()
    data object Discovering : UiState()
    data object ScanningTopics : UiState()
    data object Saving : UiState()
    data class Error(val message: String) : UiState()
    data class Success(val message: String) : UiState()
}

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)
    private val nsdService = MqttDiscoveryService(application)

    val settings: StateFlow<MqttSettings> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MqttSettings())

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _discoveredBrokers = MutableStateFlow<List<DiscoveredBroker>>(emptyList())
    val discoveredBrokers: StateFlow<List<DiscoveredBroker>> = _discoveredBrokers.asStateFlow()

    private val _scannedTopics = MutableStateFlow<List<String>>(emptyList())
    val scannedTopics: StateFlow<List<String>> = _scannedTopics.asStateFlow()

    private var discoveryJob: Job? = null

    // Discover MQTT brokers on LAN via mDNS for up to 8 seconds
    fun discoverBrokers() {
        if (_uiState.value is UiState.Discovering) return
        _discoveredBrokers.value = emptyList()
        _uiState.value = UiState.Discovering
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            try {
                withTimeout(8_000L) {
                    nsdService.discoverBrokers().collect { broker ->
                        _discoveredBrokers.update { current ->
                            if (current.none { it.host == broker.host && it.port == broker.port })
                                current + broker
                            else current
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Normal timeout — discovery complete
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Discovery error: ${e.message}")
                return@launch
            }
            _uiState.value = if (_discoveredBrokers.value.isEmpty())
                UiState.Error("No brokers found on local network")
            else
                UiState.Idle
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        if (_uiState.value is UiState.Discovering) _uiState.value = UiState.Idle
    }

    // Connect with wildcard '#' for 5s to collect active topics
    fun scanTopics(host: String, port: Int, username: String = "", password: String = "") {
        if (_uiState.value is UiState.ScanningTopics) return
        _scannedTopics.value = emptyList()
        _uiState.value = UiState.ScanningTopics

        viewModelScope.launch(Dispatchers.IO) {
            val topics = mutableSetOf<String>()
            var tempClient: MqttClient? = null
            try {
                val serverUri = "tcp://$host:$port"
                val clientId = "scan_${System.currentTimeMillis()}"
                tempClient = MqttClient(serverUri, clientId, MemoryPersistence())
                tempClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        topics.add(topic)
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val options = MqttConnectOptions().apply {
                    connectionTimeout = 10
                    isCleanSession = true
                    if (username.isNotBlank()) {
                        userName = username
                        this.password = password.toCharArray()
                    }
                }
                tempClient.connect(options)
                tempClient.subscribe("#", 0)
                delay(5_000L)
                _scannedTopics.value = topics.toList().sorted()
                _uiState.value = if (topics.isEmpty())
                    UiState.Error("No topics observed (try publishing something)")
                else
                    UiState.Idle
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Scan failed: ${e.message}")
            } finally {
                try { tempClient?.disconnect() } catch (_: Exception) {}
                try { tempClient?.close() } catch (_: Exception) {}
            }
        }
    }

    fun saveSettings(settings: MqttSettings) {
        viewModelScope.launch {
            _uiState.value = UiState.Saving
            try {
                repo.saveSettings(settings)
                _uiState.value = UiState.Success("Settings saved")
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Save failed: ${e.message}")
            }
        }
    }

    fun clearState() {
        _uiState.value = UiState.Idle
    }
}
