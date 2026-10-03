package dev.backbutton

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

@SuppressLint("MissingPermission") // Each operation is gated by runtime permissions; revocation is handled.
class BleButtonConnection(
    private val context: Context,
    private val onPress: () -> Unit,
    private val onSignalLost: () -> Unit,
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("b8b10001-64df-4f6d-b7d1-86a6e72f8d21")
        val EVENT: UUID = UUID.fromString("b8b10002-64df-4f6d-b7d1-86a6e72f8d21")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("physical-button", Context.MODE_PRIVATE)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val events = ButtonEvents()
    private var scanner: BluetoothLeScanner? = null
    private var scan: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var visible = false
    private var closed = false
    private var wanted = preferences.contains("address")
    private var timeout: Runnable? = null
    private var reconnect: Runnable? = null

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Button not connected")
        private set
    var presses by mutableIntStateOf(0)
        private set
    var lastEvent by mutableStateOf<String?>(null)
        private set

    fun hasPermissions(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun permissionDenied() { status = "Allow Nearby devices access to connect the button." }

    fun resume() {
        visible = true
        if (wanted && !busy && !connected && hasPermissions()) beginScan()
    }

    fun pause() {
        visible = false
        cleanup()
        status = "Button paused while app is closed"
    }

    fun close() {
        closed = true
        visible = false
        cleanup()
    }

    fun connect() {
        wanted = true
        if (visible && !closed && !busy && !connected) beginScan()
    }

    fun disconnect() {
        wanted = false
        preferences.edit().remove("address").apply()
        cleanup()
        onSignalLost()
        status = "Button disconnected"
    }

    private fun beginScan() {
        if (!visible || closed) return
        cleanup()
        if (!hasPermissions()) { permissionDenied(); return }
        try {
            if (adapter == null || !adapter.isEnabled) {
                status = "Turn on Bluetooth, then tap Connect button."
                return
            }
            val available = adapter.bluetoothLeScanner ?: run {
                status = "Bluetooth scanning is unavailable."
                return
            }
            busy = true
            status = "Looking for your ATOM…"
            val expectedAddress = preferences.getString("address", null)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (scan !== this || !visible) return@post
                        try {
                            if (expectedAddress == null || result.device.address == expectedAddress) {
                                stopScan()
                                cancelTimeout()
                                connectDevice(result.device)
                            }
                        } catch (_: SecurityException) { fail("Bluetooth permission was removed.") }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post { if (scan === this) fail("Could not scan for the button. Tap Connect to retry.") }
                }
            }
            scanner = available
            scan = callback
            available.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback,
            )
            setTimeout { fail("Button not found. Check USB power and tap Connect to retry.") }
        } catch (_: SecurityException) { fail("Bluetooth permission was removed.") }
        catch (_: IllegalStateException) { fail("Turn on Bluetooth, then tap Connect button.") }
    }

    private fun connectDevice(device: BluetoothDevice) {
        status = "Connecting to ATOM…"
        busy = true
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(current: BluetoothGatt, code: Int, newState: Int) = dispatch(current) {
                if (code != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasReady = connected
                    fail("Button connection lost. Tap Connect to retry.")
                    // One bounded automatic attempt, only while the app is visible.
                    if (wasReady && wanted && visible) {
                        val retry = Runnable { if (visible && wanted && !closed) beginScan() }
                        reconnect = retry
                        handler.postDelayed(retry, 1500)
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = "Preparing button…"
                    if (!current.discoverServices()) fail("Could not read the button services.")
                }
            }

            override fun onServicesDiscovered(current: BluetoothGatt, code: Int) = dispatch(current) {
                val characteristic = current.getService(SERVICE)?.getCharacteristic(EVENT)
                val descriptor = characteristic?.getDescriptor(CCCD)
                if (code != BluetoothGatt.GATT_SUCCESS || characteristic == null || descriptor == null) {
                    fail("This device does not have the Back Button firmware.")
                } else if (!current.setCharacteristicNotification(characteristic, true) ||
                    current.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                    fail("Could not subscribe to button presses.")
                }
            }

            override fun onDescriptorWrite(current: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) = dispatch(current) {
                if (descriptor.uuid != CCCD) return@dispatch
                val characteristic = current.getService(SERVICE)?.getCharacteristic(EVENT)
                if (code != BluetoothGatt.GATT_SUCCESS || characteristic == null || !current.readCharacteristic(characteristic)) {
                    fail("Could not read the initial button state.")
                }
            }

            override fun onCharacteristicRead(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, code: Int) = dispatch(current) {
                if (characteristic.uuid != EVENT) return@dispatch
                if (code != BluetoothGatt.GATT_SUCCESS || !events.baseline(value)) {
                    fail("Unsupported button signal.")
                } else {
                    cancelTimeout()
                    connected = true
                    busy = false
                    preferences.edit().putString("address", current.device.address).apply()
                    status = "Button connected"
                    lastEvent = "Ready for a new press"
                    Log.i("BackButtonBLE", "Connected; initial button state read")
                }
            }

            override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = dispatch(current) {
                if (characteristic.uuid != EVENT || !connected) return@dispatch
                when (events.accept(value)) {
                    ButtonEvents.Action.PRESS -> {
                        presses++
                        lastEvent = "Press received ($presses)"
                        Log.i("BackButtonBLE", "PRESS count=$presses")
                        onPress()
                    }
                    ButtonEvents.Action.RELEASE -> {
                        lastEvent = "Button released"
                        Log.i("BackButtonBLE", "RELEASE")
                    }
                    ButtonEvents.Action.GAP -> {
                        lastEvent = "Signal skipped; recording stopped safely"
                        onSignalLost()
                    }
                    ButtonEvents.Action.INVALID -> fail("Unsupported button signal.")
                    ButtonEvents.Action.IGNORE -> Unit
                }
            }
        }
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("Could not connect to the button.")
            else setTimeout { fail("Button connection timed out. Tap Connect to retry.") }
        } catch (_: SecurityException) { fail("Bluetooth permission was removed.") }
    }

    private fun dispatch(current: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (current !== gatt || !visible || closed) return@post
            try { action() } catch (_: SecurityException) { fail("Bluetooth permission was removed.") }
        }
    }

    private fun fail(message: String) {
        cleanup()
        status = message
        onSignalLost()
    }

    private fun setTimeout(action: () -> Unit) {
        cancelTimeout()
        val timer = Runnable { action() }
        timeout = timer
        handler.postDelayed(timer, 15_000)
    }

    private fun cancelTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private fun stopScan() {
        val old = scan
        scan = null
        if (old != null) runCatching { scanner?.stopScan(old) }
        scanner = null
    }

    private fun cleanup() {
        cancelTimeout()
        reconnect?.let { handler.removeCallbacks(it) }
        reconnect = null
        stopScan()
        val old = gatt
        gatt = null
        if (old != null) {
            runCatching { old.disconnect() }
            old.close()
        }
        events.reset()
        connected = false
        busy = false
    }
}
