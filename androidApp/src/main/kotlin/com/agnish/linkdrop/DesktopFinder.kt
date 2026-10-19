package com.agnish.linkdrop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.agnish.linkdrop.protocol.DiscoveredDesktop
import com.agnish.linkdrop.protocol.DiscoveredDesktops
import com.agnish.linkdrop.protocol.SERVICE_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds LinkDrop desktops on the Wi-Fi with Android's NsdManager (mDNS / DNS-SD, PROTOCOL.md
 * "Discovery"). Run it only while the screen is visible: [start] in onStart, [stop] in onStop.
 */
class DesktopFinder(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    private val found = DiscoveredDesktops()
    private val _desktops = MutableStateFlow<List<DiscoveredDesktop>>(emptyList())
    val desktops: StateFlow<List<DiscoveredDesktop>> = _desktops.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var discovery: NsdManager.DiscoveryListener? = null

    /** Android 14+: one callback per service; it also reports address changes. */
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    /** Android 12-13: the old API resolves one service at a time, so the rest wait here. */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (discovery != null) return
        _problem.value = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@DesktopFinder) { if (discovery === this) discovery = null }
                _problem.value = "Cannot search for desktops (error $errorCode). You can still enter the address."
            }

            override fun onServiceFound(info: NsdServiceInfo) = resolve(this, info)

            override fun onServiceLost(info: NsdServiceInfo) = lost(this, info.serviceName)
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
        resolving = false
        found.clear()
        publish()
    }

    @Synchronized
    private fun resolve(listener: NsdManager.DiscoveryListener, info: NsdServiceInfo) {
        if (listener !== discovery) return // a late callback from a stopped search
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            watch(info)
        } else {
            resolveQueue.addLast(info)
            resolveNext()
        }
    }

    private fun watch(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val name = info.serviceName
        if (name in infoCallbacks) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit

            override fun onServiceUpdated(updated: NsdServiceInfo) =
                accept(name, updated.hostAddresses, updated.port, updated.attributes)

            override fun onServiceLost() = lost(discovery, name)
        }
        infoCallbacks[name] = callback
        runCatching { nsd.registerServiceInfoCallback(info, appContext.mainExecutor, callback) }
            .onFailure { infoCallbacks.remove(name) }
    }

    @Suppress("DEPRECATION") // the replacement (registerServiceInfoCallback) needs Android 14
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                accept(info.serviceName, listOfNotNull(info.host), info.port, info.attributes)
                done()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()

            private fun done() = synchronized(this@DesktopFinder) {
                resolving = false
                resolveNext()
            }
        })
    }

    /** Validates an announcement (shared rules) and lists it, or drops it if it is not acceptable. */
    @Synchronized
    private fun accept(name: String, hosts: List<InetAddress>, port: Int, attributes: Map<String, ByteArray?>) {
        if (discovery == null) return
        val host = hosts.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        val txt = attributes.mapValues { (_, value) -> value?.decodeToString() }
        val desktop = host?.let { DiscoveredDesktop.fromAnnouncement(name, it, port, txt) }
        if (desktop != null) found.found(name, desktop) else found.lost(name)
        publish()
    }

    @Synchronized
    private fun lost(listener: NsdManager.DiscoveryListener?, name: String) {
        if (listener == null || listener !== discovery) return
        found.lost(name)
        // Forget its watcher too, so the desktop is picked up again if it comes back.
        val callback = infoCallbacks.remove(name)
        if (callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
        }
        publish()
    }

    private fun publish() {
        _desktops.value = found.list()
    }
}
