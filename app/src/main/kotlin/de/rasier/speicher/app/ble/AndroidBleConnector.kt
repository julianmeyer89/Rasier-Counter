package de.rasier.speicher.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import de.rasier.speicher.protocol.b2500.B2500Protocol
import de.rasier.speicher.protocol.b2500.BleConnector
import de.rasier.speicher.protocol.b2500.BleException
import de.rasier.speicher.protocol.b2500.BleSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BluetoothGatt-based implementation of [BleConnector] for the B2500.
 * One connection at a time for the whole app (the batteries are polled one after the other).
 */
class AndroidBleConnector(private val context: Context) : BleConnector {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val globalLock = Mutex()

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String): BleSession {
        if (!BlePermissions.granted(context)) throw BleException("Bluetooth-Berechtigung fehlt")
        val adapter = manager?.adapter ?: throw BleException("Kein Bluetooth vorhanden")
        if (!adapter.isEnabled) throw BleException("Bluetooth ist ausgeschaltet")
        val device = try {
            adapter.getRemoteDevice(address.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BleException("Ungültige Bluetooth-Adresse: $address")
        }
        globalLock.lock()
        val session = GattSession(device)
        try {
            session.open()
            return session
        } catch (e: Throwable) {
            session.close()
            throw if (e is BleException) e else BleException("Verbindung zu $address fehlgeschlagen: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private inner class GattSession(private val device: BluetoothDevice) : BleSession {
        private var gatt: BluetoothGatt? = null
        private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
        private var connected = CompletableDeferred<Boolean>()
        private var mtu = CompletableDeferred<Int>()
        private var services = CompletableDeferred<Boolean>()
        private var descriptorWritten = CompletableDeferred<Boolean>()
        private val opLock = Mutex()
        private var closed = false

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected.complete(false)
                    notifications.close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
                mtu.complete(value)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                services.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            // Android 13+
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (c.uuid == NOTIFY) notifications.trySend(value.copyOf())
            }

            // Android 12 and older
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && c.uuid == NOTIFY) {
                    @Suppress("DEPRECATION")
                    c.value?.let { notifications.trySend(it.copyOf()) }
                }
            }
        }

        suspend fun open() {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val g = gatt ?: throw BleException("connectGatt fehlgeschlagen")
            if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } != true) {
                throw BleException("${device.address} nicht erreichbar (außer Reichweite oder anderes Gerät verbunden?)")
            }
            g.requestMtu(517)
            withTimeoutOrNull(3_000) { mtu.await() }
            if (!g.discoverServices()) throw BleException("Dienstsuche fehlgeschlagen")
            if (withTimeoutOrNull(8_000) { services.await() } != true) throw BleException("Dienste nicht gefunden")
            val service = g.getService(SERVICE) ?: throw BleException("Kein B2500-Dienst gefunden – richtiges Gerät?")
            val notify = service.getCharacteristic(NOTIFY) ?: throw BleException("Status-Kanal fehlt")
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD) ?: throw BleException("CCCD fehlt")
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (withTimeoutOrNull(3_000) { descriptorWritten.await() } != true) throw BleException("Benachrichtigungen nicht aktiviert")
            // The battery needs a moment after enabling notifications before it answers.
            delay(SETTLE_MS)
        }

        override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? = opLock.withLock {
            val g = gatt ?: return null
            val ch = g.getService(SERVICE)?.getCharacteristic(WRITE) ?: return null
            // Drop stale notifications from earlier requests.
            while (notifications.tryReceive().isSuccess) Unit
            if (!writeCharacteristic(g, ch, frame)) return null
            return withTimeoutOrNull(timeoutMs) {
                try {
                    notifications.receive()
                } catch (e: Exception) {
                    null
                }
            }
        }

        private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = value
                    g.writeCharacteristic(ch)
                }
            }

        private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value)
            } else {
                @Suppress("DEPRECATION")
                run {
                    d.value = value
                    g.writeDescriptor(d)
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
            notifications.close()
            if (globalLock.isLocked) globalLock.unlock()
        }
    }

    companion object {
        private val SERVICE: UUID = UUID.fromString(B2500Protocol.SERVICE_UUID)
        private val WRITE: UUID = UUID.fromString(B2500Protocol.WRITE_UUID)
        private val NOTIFY: UUID = UUID.fromString(B2500Protocol.NOTIFY_UUID)
        private val CCCD: UUID = UUID.fromString(B2500Protocol.CCCD_UUID)
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val SETTLE_MS = 1_500L
    }
}
