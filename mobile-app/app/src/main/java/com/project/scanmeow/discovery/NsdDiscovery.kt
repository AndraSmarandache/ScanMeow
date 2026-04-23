package com.project.scanmeow.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val SERVICE_TYPE = "_scanmeow._tcp."

suspend fun discoverScanApiBase(context: Context, timeoutMs: Long = 4000L): String? =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            var listener: NsdManager.DiscoveryListener? = null

            listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String, e: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onServiceLost(s: NsdServiceInfo) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val host = s.host?.hostAddress ?: return
                            val port = s.port
                            runCatching { nsd.stopServiceDiscovery(listener!!) }
                            if (cont.isActive) cont.resume("http://$host:$port")
                        }
                    })
                }
            }

            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener!!) } }
        }
    }
