package com.livecontrol.iotmod.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

interface NetworkMonitor {
    fun start(onAvailable: () -> Unit)
    fun stop()
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var cb: ConnectivityManager.NetworkCallback? = null

    override fun start(onAvailable: () -> Unit) {
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
        }
        cb = c
        cm.registerDefaultNetworkCallback(c)
    }

    override fun stop() {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
    }
}
