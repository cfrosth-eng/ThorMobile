package com.example.thormobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

class NetworkSentinel(context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var isConnected: Boolean = false
        private set

    // This function will be triggered later in Phase 4 to flush the Outbox
    var onConnectionRestored: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d("NetworkSentinel", "Connection Established. Dead zone exited.")

            val wasDisconnected = !isConnected
            isConnected = true

            if (wasDisconnected) {
                // We just regained a signal. Trigger the flush sequence.
                onConnectionRestored?.invoke()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d("NetworkSentinel", "Connection Lost. Entering dead zone. Outbox active.")
            isConnected = false
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            isConnected = hasInternet && hasValidated
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Lock in the initial state upon booting the app
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkSentinel", "Error unregistering callback", e)
        }
    }
}