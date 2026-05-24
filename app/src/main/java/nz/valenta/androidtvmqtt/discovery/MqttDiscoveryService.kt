package nz.valenta.androidtvmqtt.discovery

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

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
        private const val SERVICE_TYPE = "_mqtt._tcp.local."
    }

    /**
     * Discovers MQTT brokers on the local network via mDNS using jmDNS.
     * Emits brokers as they are found. Collect for the desired duration then cancel.
     */
    @RequiresPermission(
        allOf = [
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        ]
    )
    fun discoverBrokers(): Flow<DiscoveredBroker> = callbackFlow {
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

        val bindAddress = connectivityManager
            ?.getLinkProperties(connectivityManager.activeNetwork)
            ?.linkAddresses
            ?.asSequence()
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { !it.isLoopbackAddress }

        if (bindAddress == null) {
            Log.e(TAG, "No IPv4 address available for mDNS binding")
            close(Exception("No IPv4 address available for mDNS binding"))
            return@callbackFlow
        }

        val multicastLock = wifiManager?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        if (multicastLock == null) {
            Log.i(TAG, "WifiManager unavailable; continuing discovery without multicast lock (expected on some Ethernet-only devices)")
        }

        val serviceListener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    Log.d(TAG, "Service added: ${event.name}")
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    Log.d(TAG, "Service removed: ${event.name}")
                }

                override fun serviceResolved(event: ServiceEvent) {
                    try {
                        val serviceInfo = event.info ?: return
                        val addresses = serviceInfo.inetAddresses
                        if (addresses.isEmpty()) {
                            Log.w(TAG, "Resolved service ${serviceInfo.name} has no addresses")
                            return
                        }

                        val host = addresses.first().hostAddress
                        if (host.isNullOrBlank()) {
                            Log.w(TAG, "Resolved service ${serviceInfo.name} has blank host address")
                            return
                        }

                        val broker = DiscoveredBroker(
                            name = serviceInfo.name,
                            host = host,
                            port = serviceInfo.port
                        )
                        Log.d(TAG, "Broker resolved: $broker")
                        trySend(broker)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resolving service: ${e.message}")
                    }
                }
        }

        var jmDNS: JmDNS? = null

        try {

            jmDNS = withContext(Dispatchers.IO) {
                JmDNS.create(bindAddress)
            }
            jmDNS?.addServiceListener(SERVICE_TYPE, serviceListener)
            Log.d(TAG, "MQTT service discovery started")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing JmDNS: ${e.message}", e)
            try {
                withContext(Dispatchers.IO) {
                    jmDNS?.close()
                }
            } catch (closeError: Exception) {
                Log.w(TAG, "Error closing JmDNS after init failure: ${closeError.message}")
            }
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
            close(Exception("Failed to initialize mDNS discovery: ${e.message}"))
            return@callbackFlow
        }

        awaitClose {
            try {
                jmDNS?.removeServiceListener(SERVICE_TYPE, serviceListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing service listener: ${e.message}")
            }
            try {
                jmDNS?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing JmDNS: ${e.message}")
            }
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
            Log.d(TAG, "MQTT service discovery stopped")
        }
    }

}

