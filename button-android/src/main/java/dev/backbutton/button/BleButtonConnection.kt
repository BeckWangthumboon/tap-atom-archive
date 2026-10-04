package dev.backbutton.button

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
import java.util.UUID

/**
 * Protocol-v1 BLE input connection. Call methods on the main thread; callbacks also run there.
 * The host owns permissions and lifecycle, including any service needed for background listening.
 * An initial state read never emits a press; interrupted continuity is reported to the host.
 */
@SuppressLint("MissingPermission") // Each operation is gated by runtime permissions; revocation is handled.
class BleButtonConnection(
    context: Context,
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    private val onSignalLost: () -> Unit,
    private val onStatusChanged: (Status) -> Unit = {},
) {
    enum class Status {
        NOT_CONNECTED, PERMISSION_REQUIRED, PAUSED, CLOSED, DISCONNECTED,
        BLUETOOTH_DISABLED, SCANNER_UNAVAILABLE, SCANNING, SCAN_FAILED, NOT_FOUND,
        CONNECTING, CONNECTION_LOST, PREPARING, SERVICE_READ_FAILED, INCOMPATIBLE_DEVICE,
        SUBSCRIPTION_FAILED, STATE_READ_FAILED, UNSUPPORTED_SIGNAL, CONNECTED,
        CONNECTION_FAILED, CONNECTION_TIMED_OUT, PERMISSION_REVOKED,
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("b8b10001-64df-4f6d-b7d1-86a6e72f8d21")
        val EVENT: UUID = UUID.fromString("b8b10002-64df-4f6d-b7d1-86a6e72f8d21")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val context = context.applicationContext
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

    var status = Status.NOT_CONNECTED
        private set(value) {
            field = value
            onStatusChanged(value)
        }
    val connected: Boolean get() = status == Status.CONNECTED
    val busy: Boolean get() = status in setOf(Status.SCANNING, Status.CONNECTING, Status.PREPARING)
    private var presses = 0

    fun hasPermissions(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun permissionDenied() { status = Status.PERMISSION_REQUIRED }

    fun resume() {
        visible = true
        if (wanted && !busy && !connected && hasPermissions()) beginScan()
    }

    fun pause() {
        visible = false
        cleanup()
        status = Status.PAUSED
    }

    fun close() {
        closed = true
        visible = false
        cleanup()
        status = Status.CLOSED
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
        status = Status.DISCONNECTED
    }

    private fun beginScan() {
        if (!visible || closed) return
        cleanup()
        if (!hasPermissions()) { permissionDenied(); return }
        try {
            if (adapter == null || !adapter.isEnabled) {
                status = Status.BLUETOOTH_DISABLED
                return
            }
            val available = adapter.bluetoothLeScanner ?: run {
                status = Status.SCANNER_UNAVAILABLE
                return
            }
            status = Status.SCANNING
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
                        } catch (_: SecurityException) { fail(Status.PERMISSION_REVOKED) }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post { if (scan === this) fail(Status.SCAN_FAILED) }
                }
            }
            scanner = available
            scan = callback
            available.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback,
            )
            setTimeout { fail(Status.NOT_FOUND) }
        } catch (_: SecurityException) { fail(Status.PERMISSION_REVOKED) }
        catch (_: IllegalStateException) { fail(Status.BLUETOOTH_DISABLED) }
    }

    private fun connectDevice(device: BluetoothDevice) {
        status = Status.CONNECTING
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(current: BluetoothGatt, code: Int, newState: Int) = dispatch(current) {
                if (code != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasReady = connected
                    fail(Status.CONNECTION_LOST)
                    // One bounded automatic attempt, only while the host has resumed listening.
                    if (wasReady && wanted && visible) {
                        val retry = Runnable { if (visible && wanted && !closed) beginScan() }
                        reconnect = retry
                        handler.postDelayed(retry, 1500)
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = Status.PREPARING
                    if (!current.discoverServices()) fail(Status.SERVICE_READ_FAILED)
                }
            }

            override fun onServicesDiscovered(current: BluetoothGatt, code: Int) = dispatch(current) {
                val characteristic = current.getService(SERVICE)?.getCharacteristic(EVENT)
                val descriptor = characteristic?.getDescriptor(CCCD)
                if (code != BluetoothGatt.GATT_SUCCESS || characteristic == null || descriptor == null) {
                    fail(Status.INCOMPATIBLE_DEVICE)
                } else if (!current.setCharacteristicNotification(characteristic, true) ||
                    current.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                    fail(Status.SUBSCRIPTION_FAILED)
                }
            }

            override fun onDescriptorWrite(current: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) = dispatch(current) {
                if (descriptor.uuid != CCCD) return@dispatch
                val characteristic = current.getService(SERVICE)?.getCharacteristic(EVENT)
                if (code != BluetoothGatt.GATT_SUCCESS || characteristic == null || !current.readCharacteristic(characteristic)) {
                    fail(Status.STATE_READ_FAILED)
                }
            }

            override fun onCharacteristicRead(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, code: Int) = dispatch(current) {
                if (characteristic.uuid != EVENT) return@dispatch
                if (code != BluetoothGatt.GATT_SUCCESS || !events.baseline(value)) {
                    fail(Status.UNSUPPORTED_SIGNAL)
                } else {
                    cancelTimeout()
                    preferences.edit().putString("address", current.device.address).apply()
                    status = Status.CONNECTED
                    Log.i("BackButtonBLE", "Connected; initial button state read")
                }
            }

            override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = dispatch(current) {
                if (characteristic.uuid != EVENT || !connected) return@dispatch
                when (events.accept(value)) {
                    ButtonEvents.Action.PRESS -> {
                        presses++
                        Log.i("BackButtonBLE", "PRESS count=$presses")
                        onPress()
                    }
                    ButtonEvents.Action.RELEASE -> {
                        Log.i("BackButtonBLE", "RELEASE")
                        onRelease()
                    }
                    ButtonEvents.Action.GAP -> {
                        onSignalLost()
                    }
                    ButtonEvents.Action.INVALID -> fail(Status.UNSUPPORTED_SIGNAL)
                    ButtonEvents.Action.IGNORE -> Unit
                }
            }
        }
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail(Status.CONNECTION_FAILED)
            else setTimeout { fail(Status.CONNECTION_TIMED_OUT) }
        } catch (_: SecurityException) { fail(Status.PERMISSION_REVOKED) }
    }

    private fun dispatch(current: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (current !== gatt || !visible || closed) return@post
            try { action() } catch (_: SecurityException) { fail(Status.PERMISSION_REVOKED) }
        }
    }

    private fun fail(reason: Status) {
        cleanup()
        status = reason
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
        status = Status.NOT_CONNECTED
    }
}
