package io.hyperaudio.bridge

import android.content.Context
import android.net.*
import android.os.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class NearbyComputer(val id: String, val name: String, val host: String, val port: Int)
class Discovery(context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    @Volatile private var generation = 0
    private val seen = mutableMapOf<NearbyComputer, Long>()
    private val mdnsAddresses = mutableSetOf<NearbyComputer>()
    private val restart: Runnable = Runnable {
        if (started) {
            generation++; resolving = false; queue.clear()
            synchronized(this) { seen.clear(); mdnsAddresses.clear(); names.clear(); devices.value = emptyList() }
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { nsd.discoverServices("_hyperaudio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private fun changed() { handler.removeCallbacks(restart); handler.postDelayed(restart, 500) }
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = changed()
    }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicast = wifi.createMulticastLock("hyperaudio-discovery").apply { setReferenceCounted(false) }
    val devices = MutableStateFlow<List<NearbyComputer>>(emptyList())
    private val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var started = false
    @Volatile private var closed = false
    private val names = mutableMapOf<String, String>()
    private val listener: NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { handler.postDelayed(restart, 3000) }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) { synchronized(this@Discovery) { queue.add(info); resolveNext() } }
        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(this@Discovery) {
                val id = names.remove(info.serviceName)
                mdnsAddresses.removeAll { it.id == id }
                seen.entries.removeAll { it.key.id == id && SystemClock.elapsedRealtime() - it.value > 20000 }
                devices.value = seen.keys.toList()
            }
        }
    }
    fun start() {
        if (started) return
        closed = false; started = true; multicast.acquire()
        try { connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), networkCallback) }
        catch (e: Exception) { close(); throw e }
        polling = scope.launch {
            while (isActive) {
                runCatching { probe() }
                synchronized(this@Discovery) {
                    seen.entries.removeAll { it.key !in mdnsAddresses && SystemClock.elapsedRealtime() - it.value > 20000 }
                    devices.value = seen.keys.toList()
                }
                delay(3000)
            }
        }
        try { nsd.discoverServices("_hyperaudio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { close(); throw e }
    }
    @Suppress("DEPRECATION")
    @Synchronized private fun resolveNext() {
        if (resolving || closed) return
        val info = queue.poll() ?: return
        val version = generation
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) { synchronized(this@Discovery) { if (version == generation) { resolving = false; resolveNext() } } }
            override fun onServiceResolved(service: NsdServiceInfo) {
                synchronized(this@Discovery) {
                    if (!closed && version == generation) {
                        val id = service.attributes["id"]?.toString(Charsets.UTF_8)
                        val host = service.host?.hostAddress
                        if (id != null && host != null && id.matches(Regex("[0-9a-f]{32}")) && service.port in 1..65535) {
                            val name = service.attributes["name"]?.toString(Charsets.UTF_8)?.take(80) ?: service.serviceName
                            names[service.serviceName] = id
                            val pc = NearbyComputer(id, name, host, service.port)
                            mdnsAddresses.add(pc); found(pc)
                        }
                    }
                    if (version == generation) { resolving = false; resolveNext() }
                }
            }
        })
    }
    override fun close() {
        closed = true
        generation++; polling?.cancel(); handler.removeCallbacks(restart)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (started) { runCatching { nsd.stopServiceDiscovery(listener) }; started = false }
        if (multicast.isHeld) multicast.release()
        resolving = false; queue.clear(); synchronized(this) { seen.clear(); mdnsAddresses.clear(); devices.value = emptyList() }
    }
    @Synchronized private fun found(pc: NearbyComputer) {
        if (closed) return
        if (seen.size >= 64 && pc !in seen) return
        seen[pc] = SystemClock.elapsedRealtime()
        devices.value = seen.keys.toList()
    }
    private fun probe() {
        val version = generation
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val links = connectivity.getLinkProperties(network) ?: continue
            DatagramSocket().use { socket ->
                network.bindSocket(socket); socket.broadcast = true; socket.soTimeout = 250
                val nonce = UUID.randomUUID().toString().replace("-", "")
                val query = "HYPERAUDIO_DISCOVER_V1 $nonce".toByteArray(Charsets.US_ASCII)
                val targets = links.linkAddresses.mapNotNull { link -> broadcastAddress(link.address.address, link.prefixLength)?.let { InetAddress.getByAddress(it) } }.toSet() + InetAddress.getByName("255.255.255.255")
                for (target in targets) runCatching { socket.send(DatagramPacket(query, query.size, target, 38471)) }
                val until = SystemClock.elapsedRealtime() + 1000
                while (!closed && version == generation && SystemClock.elapsedRealtime() < until) {
                    try {
                        val packet = DatagramPacket(ByteArray(2048), 2048); socket.receive(packet)
                        val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        val id = json.optString("id"); val port = json.optInt("port")
                        if (version == generation && json.optInt("version") == 1 && json.optString("nonce") == nonce && id.matches(Regex("[0-9a-f]{32}")) && port in 1..65535)
                            found(NearbyComputer(id, json.optString("name").take(80), packet.address.hostAddress!!, port))
                    } catch (_: SocketTimeoutException) { } catch (_: Exception) { }
                }
            }
        }
    }
}

fun broadcastAddress(address: ByteArray, prefix: Int): ByteArray? {
    if (address.size != 4 || prefix !in 1..30) return null
    return ByteArray(4) { i -> (address[i].toInt() or (255 ushr (prefix - i * 8).coerceIn(0, 8))).toByte() }
}
