package dev.backbutton

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.backbutton.button.BleButtonConnection
import dev.backbutton.button.BleButtonConnection.Status

/** Adapts generic connection state for this client's settings UI. */
class PhysicalButtonClient(
    context: Context,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onSignalLost: () -> Unit,
) {
    private var connectionStatus by mutableStateOf(Status.NOT_CONNECTED)
    private val connection = BleButtonConnection(context, onPress, onRelease, onSignalLost,
        onStatusChanged = { connectionStatus = it })

    val connected: Boolean get() = connectionStatus == Status.CONNECTED
    val busy: Boolean get() = connectionStatus in setOf(Status.SCANNING, Status.CONNECTING, Status.PREPARING)
    val status: String get() = when (connectionStatus) {
        Status.NOT_CONNECTED -> "Button not connected"
        Status.PERMISSION_REQUIRED -> "Allow Nearby devices access to connect the button."
        Status.PAUSED -> "Button paused while app is closed"
        Status.CLOSED -> "Button connection closed"
        Status.DISCONNECTED -> "Button disconnected"
        Status.BLUETOOTH_DISABLED -> "Turn on Bluetooth, then tap Connect button."
        Status.SCANNER_UNAVAILABLE -> "Bluetooth scanning is unavailable."
        Status.SCANNING -> "Looking for your button…"
        Status.SCAN_FAILED -> "Could not scan for the button. Tap Connect to retry."
        Status.NOT_FOUND -> "Button not found. Check its power and tap Connect to retry."
        Status.CONNECTING -> "Connecting to button…"
        Status.CONNECTION_LOST -> "Button connection lost. Tap Connect to retry."
        Status.PREPARING -> "Preparing button…"
        Status.SERVICE_READ_FAILED -> "Could not read the button services."
        Status.INCOMPATIBLE_DEVICE -> "This device does not support the button protocol."
        Status.SUBSCRIPTION_FAILED -> "Could not subscribe to button presses."
        Status.STATE_READ_FAILED -> "Could not read the initial button state."
        Status.UNSUPPORTED_SIGNAL -> "Unsupported button signal."
        Status.CONNECTED -> "Button connected"
        Status.CONNECTION_FAILED -> "Could not connect to the button."
        Status.CONNECTION_TIMED_OUT -> "Button connection timed out. Tap Connect to retry."
        Status.PERMISSION_REVOKED -> "Bluetooth permission was removed."
    }

    fun hasPermissions(): Boolean = connection.hasPermissions()
    fun permissionDenied() = connection.permissionDenied()
    fun resume() = connection.resume()
    fun pause() = connection.pause()
    fun connect() = connection.connect()
    fun disconnect() = connection.disconnect()
}
