package nz.valenta.androidtvmqtt.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import nz.valenta.androidtvmqtt.model.MqttSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mqtt_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_HOST = stringPreferencesKey("broker_host")
        private val KEY_PORT = intPreferencesKey("broker_port")
        private val KEY_TOPIC = stringPreferencesKey("topic")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        private val KEY_OVERLAY_DURATION = longPreferencesKey("overlay_duration_ms")
    }

    val settingsFlow: Flow<MqttSettings> = context.dataStore.data.map { prefs ->
        MqttSettings(
            brokerHost = prefs[KEY_HOST] ?: "",
            brokerPort = prefs[KEY_PORT] ?: 1883,
            topic = prefs[KEY_TOPIC] ?: "",
            username = prefs[KEY_USERNAME] ?: "",
            password = prefs[KEY_PASSWORD] ?: "",
            autoStart = prefs[KEY_AUTO_START] ?: false,
            overlayDurationMs = prefs[KEY_OVERLAY_DURATION] ?: 5000L
        )
    }

    suspend fun getSettings(): MqttSettings = settingsFlow.first()

    suspend fun saveSettings(settings: MqttSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HOST] = settings.brokerHost
            prefs[KEY_PORT] = settings.brokerPort
            prefs[KEY_TOPIC] = settings.topic
            prefs[KEY_USERNAME] = settings.username
            prefs[KEY_PASSWORD] = settings.password
            prefs[KEY_AUTO_START] = settings.autoStart
            prefs[KEY_OVERLAY_DURATION] = settings.overlayDurationMs
        }
    }
}
