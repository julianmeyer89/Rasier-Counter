package de.rasier.speicher.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramSocket

/**
 * Creates UDP sockets bound to the Wi-Fi network, so requests to the CT002 never go out over
 * mobile data (where 192.168.178.x is unreachable anyway).
 */
class WifiSockets(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isOnWifi(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun newSocket(): DatagramSocket {
        val socket = DatagramSocket()
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi == null) {
            socket.close()
            throw IllegalStateException("Kein WLAN verbunden")
        }
        wifi.bindSocket(socket)
        return socket
    }
}
