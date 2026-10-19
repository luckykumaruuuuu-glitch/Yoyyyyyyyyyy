package com.wedfaces.cameraoperator.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import android.content.Intent

class CameraNetworkController(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun connectToCameraWifi(
        ssid: String,
        passphrase: String,
        onConnected: (Network) -> Unit,
        onNetworkLost: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onFailure("Android 10 or later is required for an in-app Wi-Fi request. Connect to the camera Wi-Fi in system settings, then tap Use current Wi-Fi.")
            return
        }
        if (ssid.isBlank()) {
            onFailure("Enter the camera Wi-Fi name shown by the camera.")
            return
        }
        release()
        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid.trim())
            if (passphrase.isNotBlank()) {
                if (passphrase.length !in 8..63) {
                    onFailure("The camera Wi-Fi password must be 8–63 characters.")
                    return
                }
                specifierBuilder.setWpa2Passphrase(passphrase)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
            val requestCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onConnected(network)
                }

                override fun onLost(network: Network) {
                    onNetworkLost()
                }

                override fun onUnavailable() {
                    onFailure("Android could not connect to that camera Wi-Fi network. Check the SSID/password and try again.")
                }
            }
            callback = requestCallback
            connectivityManager.requestNetwork(request, requestCallback, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (_: SecurityException) {
            onFailure("Android did not grant permission to request the camera Wi-Fi network.")
        } catch (error: Exception) {
            onFailure(error.message ?: "Could not request the camera Wi-Fi network.")
        }
    }

    fun useCurrentWifi(
        onConnected: (Network) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val network = connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getNetworkCapabilities(candidate)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (network == null) {
            onFailure("Connect to the camera Wi-Fi in Android settings first, then return and retry.")
        } else {
            onConnected(network)
        }
    }

    fun openWifiSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    fun release() {
        val active = callback ?: return
        try {
            connectivityManager.unregisterNetworkCallback(active)
        } catch (_: Exception) {
        }
        callback = null
    }

    private companion object {
        const val NETWORK_REQUEST_TIMEOUT_MS = 45_000
    }
}
