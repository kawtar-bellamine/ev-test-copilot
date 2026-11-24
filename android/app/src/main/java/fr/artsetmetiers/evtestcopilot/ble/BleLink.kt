package fr.artsetmetiers.evtestcopilot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Liaison Bluetooth Low Energy avec le module HM-10 du boîtier (UART transparent) :
 * service 0xFFE0, caractéristique 0xFFE1 en notification. Les octets reçus sont passés à [onBytes].
 * Les permissions (BLUETOOTH_SCAN et BLUETOOTH_CONNECT) sont demandées par l'activité avant l'appel à [connect].
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val onBytes: (ByteArray) -> Unit,
    private val onState: (String) -> Unit,
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 12_000L
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            onState("Boîtier trouvé, connexion…")
            open(result.device)
        }

        override fun onScanFailed(errorCode: Int) = onState("Recherche Bluetooth impossible (code $errorCode).")
    }

    fun connect() {
        if (adapter == null || !adapter.isEnabled) {
            onState("Activez le Bluetooth du téléphone.")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return onState("Bluetooth Low Energy indisponible.")
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        onState("Recherche du boîtier…")
        scanner.startScan(filters, settings, scanCallback)
        handler.postDelayed({ if (scanning) { stopScan(); onState("Aucun boîtier trouvé.") } }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private fun open(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState("Connecté, découverte des services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onState("Boîtier déconnecté.")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
                ?: return onState("Service du boîtier introuvable.")
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: return onState("Notifications indisponibles.")
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            onState("Boîtier connecté")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onBytes(value)
        }

        @Deprecated("Android 12 et antérieur")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ch.value?.let(onBytes)
        }
    }
}
