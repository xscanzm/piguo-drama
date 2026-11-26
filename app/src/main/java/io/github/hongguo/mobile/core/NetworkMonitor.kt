// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    val isConnected: Boolean = true,
    val isWifi: Boolean = true
)

class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = runCatching {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.getOrNull()

    private val _status = MutableStateFlow(readCurrentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    init {
        registerCallback()
    }

    private fun readCurrentStatus(): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus(isConnected = true, isWifi = true)
        return runCatching {
            val active = cm.activeNetwork ?: return@runCatching NetworkStatus(isConnected = false, isWifi = false)
            val caps = cm.getNetworkCapabilities(active)
                ?: return@runCatching NetworkStatus(isConnected = false, isWifi = false)
            statusFromCapabilities(caps)
        }.getOrDefault(NetworkStatus(isConnected = true, isWifi = true))
    }

    private fun statusFromCapabilities(caps: NetworkCapabilities): NetworkStatus {
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return NetworkStatus(isConnected = hasInternet, isWifi = isWifi)
    }

    private fun registerCallback() {
        val cm = connectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _status.value = readCurrentStatus()
                }

                override fun onLost(network: Network) {
                    _status.value = readCurrentStatus()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    _status.value = statusFromCapabilities(networkCapabilities)
                }
            })
        }
    }
}
