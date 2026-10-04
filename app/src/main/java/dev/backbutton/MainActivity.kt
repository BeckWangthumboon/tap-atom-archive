package dev.backbutton

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var audio: AudioSession
    private lateinit var transcription: TranscriptionSession
    private lateinit var button: BleButtonConnection
    private lateinit var session: DictationSession
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) audio.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        session = dictation
        audio = session.audio
        transcription = session.transcription
        button = session.button
        setContent {
            MaterialTheme {
                RecordingScreen(session, ::toggleRecording, ::enableCrossApp, ::openAccessibility, ::openSettings) { text ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcript", text))
                }
            }
        }
    }

    private fun toggleRecording() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || transcription.isTranscribing) return
        if (audio.isRecording || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            session.toggle()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun enableCrossApp() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (session.crossAppEnabled) DictationService.disable(this)
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            try { DictationService.enable(this) }
            catch (_: IllegalStateException) { session.notice = "Keep Back Button open and tap Enable again." }
            catch (_: SecurityException) { session.notice = "Allow microphone access, then tap Enable again." }
        }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.activityVisible = true
            button.resume()
        }
    }

    override fun onPause() {
        if (::session.isInitialized) session.leaveActivity()
        super.onPause()
    }
}

@Composable
private fun RecordingScreen(
    session: DictationSession,
    toggleRecording: () -> Unit,
    enableCrossApp: () -> Unit,
    openAccessibility: () -> Unit,
    openSettings: () -> Unit,
    copyTranscript: (String) -> Unit,
) {
    val audio = session.audio
    val transcription = session.transcription
    val button = session.button
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it } && button.hasPermissions()) button.connect()
        else button.permissionDenied()
    }
    var elapsed by remember(audio.startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(audio.isRecording) {
        while (audio.isRecording) {
            elapsed = SystemClock.elapsedRealtime() - audio.startedAt
            delay(200)
        }
    }
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Back Button", style = MaterialTheme.typography.headlineLarge)
            Text("Record a short clip and turn it into text.", style = MaterialTheme.typography.bodyLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dictate in other apps", style = MaterialTheme.typography.titleLarge)
                    Text(if (session.crossAppEnabled) "Dictation ready" else "Cross-app dictation is off")
                    Text(if (session.accessibilityConnected) "Text insertion access enabled" else "Enable Back Button dictation in Accessibility settings.")
                    OutlinedButton(onClick = openAccessibility) { Text("Accessibility settings") }
                    Button(onClick = enableCrossApp) {
                        Text(if (session.crossAppEnabled) "Turn off cross-app dictation" else "Enable cross-app dictation")
                    }
                    if (!notificationsAllowed) OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Allow status notifications")
                    }
                    Text("Tap Record, then Stop, or hold until the vibration, speak, and release to insert text. Drag before recording starts to move the control; move during a hold to cancel.")
                    Text("The microphone stays off until you record. Enable dictation again after the app restarts.", style = MaterialTheme.typography.bodySmall)
                    session.notice?.let { Text(it) }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Physical button", style = MaterialTheme.typography.titleLarge)
                    Text(button.status)
                    button.lastEvent?.let { Text(it) }
                    Text("Presses received: ${button.presses}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        if (button.connected || button.busy) button.disconnect()
                        else if (button.hasPermissions()) button.connect()
                        else bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }) {
                        Text(if (button.connected) "Disconnect button" else if (button.busy) "Cancel connection" else "Connect button")
                    }
                    Text("Click once to record, again to stop. Or hold until the vibration, speak, and release to transcribe. Enable cross-app dictation to use the button in LINE or your browser.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            transcription.isTranscribing -> "Transcribing…"
                            audio.isRecording -> "● Recording"
                            audio.isPlaying -> "Playing recording"
                            audio.isPreparingPlayback -> "Preparing playback…"
                            audio.hasRecording -> "Recording saved"
                            else -> "Ready to record"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (audio.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        formatTime(if (audio.isRecording) elapsed else audio.durationMillis),
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(if (audio.isRecording) "Speak now. Leaving the app stops recording." else "Tap Stop to send this clip to Fish Audio for transcription.")
                }
            }
            Button(
                onClick = toggleRecording,
                enabled = !transcription.isTranscribing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (audio.isRecording) "Stop recording" else if (audio.hasRecording) "Record again" else "Start recording")
            }
            if (audio.hasRecording && !audio.isRecording) {
                OutlinedButton(
                    onClick = { if (audio.isPlaying || audio.isPreparingPlayback) audio.stopPlayback() else audio.play() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (audio.isPlaying || audio.isPreparingPlayback) "Stop playback" else "Play recording")
                }
                OutlinedButton(
                    onClick = { transcription.transcribe() },
                    enabled = !transcription.isTranscribing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (transcription.error != null) "Retry transcription" else "Transcribe recording")
                }
                TextButton(
                    onClick = {
                        audio.deleteRecording()
                        if (!audio.hasRecording) transcription.clear()
                    },
                    enabled = !transcription.isTranscribing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete recording")
                }
                Text("Recording again replaces this clip.", style = MaterialTheme.typography.bodySmall)
            }
            transcription.transcript?.let { text ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Transcript", style = MaterialTheme.typography.titleLarge)
                        SelectionContainer { Text(text.ifBlank { "No speech detected. Try another recording." }) }
                        if (text.isNotBlank()) TextButton(onClick = { copyTranscript(text) }) { Text("Copy transcript") }
                    }
                }
            }
            transcription.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            audio.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = openSettings) { Text("Microphone settings") }
            Text("On first use, allow microphone access, then tap Start recording again. Leaving the app or locking your phone stops recording without uploading.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
