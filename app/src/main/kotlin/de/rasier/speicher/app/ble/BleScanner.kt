package de.rasier.speicher.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object BlePermissions {
    /** Permissions to request at runtime, depending on the Android version. */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean =
        required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/**
 * Scans for B2500 batteries. They do not advertise a service UUID, so the scan is unfiltered and
 * results are matched by name ("HM_..." / "HMJ..."); all named devices are reported so the user
 * can pick manually if the name differs.
 */
class BleScanner(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null || !BlePermissions.granted(context)) {
            close(IllegalStateException("Bluetooth nicht verfügbar oder keine Berechtigung"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                trySend(FoundDevice(result.device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth-Suche fehlgeschlagen ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object {
        fun looksLikeB2500(name: String) = name.startsWith("HM_") || name.startsWith("HMJ") || name.contains("B2500")
    }
}
