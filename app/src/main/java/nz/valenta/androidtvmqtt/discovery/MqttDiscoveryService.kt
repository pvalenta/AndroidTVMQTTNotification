package nz.valenta.androidtvmqtt.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredBroker(
    val name: String,
    val host: String,
    val port: Int
) {
    override fun toString(): String = "$name ($host:$port)"
}

class MqttDiscoveryService(private val context: Context) {

    companion object {
        private const val TAG = "MqttDiscovery"
        private const val SERVICE_TYPE = "_mqtt._tcp."
    }

    /**
     * Discovers MQTT brokers on the local network via mDNS.
     * Emits brokers as they are found. Collect for the desired duration then cancel.
     */
    fun discoverBrokers(): Flow<DiscoveredBroker> = callbackFlow {
        val nsdManager = context.getSystemService(NsdManager::class.java)
        val pendingResolves = mutableSetOf<String>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                pendingResolves.remove(serviceInfo.serviceName)
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                pendingResolves.remove(serviceInfo.serviceName)
                val host = serviceInfo.host?.hostAddress ?: return
                val broker = DiscoveredBroker(
                    name = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port
                )
                trySend(broker)
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                close(Exception("NSD discovery failed with code $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (pendingResolves.add(serviceInfo.serviceName)) {
                    nsdManager.resolveService(serviceInfo, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery: ${e.message}")
            }
        }
    }
}
