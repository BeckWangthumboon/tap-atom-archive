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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    var showSetup by rememberSaveable { mutableStateOf(false) }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text("Speak. Put it into words.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when {
                        !session.accessibilityConnected -> "Set up dictation in other apps"
                        session.crossAppEnabled -> "Dictation ready in other apps"
                        else -> "Dictation in other apps is off"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        !session.accessibilityConnected -> "Enable text insertion in Accessibility settings."
                        session.crossAppEnabled -> "Open a text field to use the compact control."
                        else -> "Enable it to record from your keyboard."
                    }, style = MaterialTheme.typography.bodySmall)
                    if (!session.accessibilityConnected) {
                        Button(onClick = openAccessibility) { Text("Set up text insertion") }
                        if (session.crossAppEnabled) TextButton(onClick = enableCrossApp) { Text("Turn off dictation") }
                    } else {
                        Button(onClick = enableCrossApp) {
                            Text(if (session.crossAppEnabled) "Turn off dictation" else "Enable cross-app dictation")
                        }
                    }
                }
            }
            session.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            transcription.isTranscribing -> "Transcribing…"
                            audio.isRecording -> "● Recording"
                            audio.isPlaying -> "Playing recording"
                            audio.isPreparingPlayback -> "Preparing playback…"
                            audio.hasRecording -> "Recording saved"
                            else -> "Ready to record"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (audio.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        formatTime(if (audio.isRecording) elapsed else audio.durationMillis),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(when {
                        audio.isRecording -> "Speak now. Keep this screen open while recording here."
                        transcription.isTranscribing -> "Your transcript will appear below."
                        audio.hasRecording -> "Record again to replace this clip."
                        else -> "Tap to start. Stop to transcribe."
                    }, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = toggleRecording,
                        enabled = !transcription.isTranscribing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (audio.isRecording) "Stop recording" else if (audio.hasRecording) "Record again" else "Start recording")
                    }
                    transcription.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    audio.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            transcription.transcript?.let { text ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Latest transcript", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (text.isNotBlank()) TextButton(onClick = { copyTranscript(text) }) { Text("Copy") }
                        }
                        SelectionContainer { Text(text.ifBlank { "No speech detected. Try another recording." }) }
                    }
                }
            }
            if (audio.hasRecording && !audio.isRecording) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (audio.isPlaying || audio.isPreparingPlayback) audio.stopPlayback() else audio.play() },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (audio.isPlaying || audio.isPreparingPlayback) "Stop playback" else "Play recording") }
                        OutlinedButton(
                            onClick = { transcription.transcribe() },
                            enabled = !transcription.isTranscribing,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (transcription.error != null) "Retry" else "Transcribe") }
                    }
                    TextButton(onClick = {
                        audio.deleteRecording()
                        if (!audio.hasRecording) transcription.clear()
                    }, enabled = !transcription.isTranscribing) { Text("Delete recording") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Physical button", style = MaterialTheme.typography.titleMedium)
                    Text(button.status, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        if (button.connected || button.busy) button.disconnect()
                        else if (button.hasPermissions()) button.connect()
                        else bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }) {
                        Text(if (button.connected) "Disconnect button" else if (button.busy) "Cancel connection" else "Connect button")
                    }
                }
            }
            TextButton(onClick = { showSetup = !showSetup }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showSetup) "Hide setup & help" else "Setup & help")
            }
            if (showSetup) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Dictate from a text field", style = MaterialTheme.typography.titleMedium)
                        Text("Open a text field with the keyboard visible. Tap Record, speak, then tap Stop. Or hold until the vibration, speak, and release.")
                        Text("Move the control before recording starts to reposition it. Moving during a hold cancels that recording.")
                        Text("Password and PIN fields are excluded. Some custom editors need manual copying.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = openAccessibility) { Text("Accessibility settings") }
                        if (!notificationsAllowed) OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("Allow status notifications")
                        }
                        Text("The microphone stays off until you record. Enable cross-app dictation again after the app restarts.", style = MaterialTheme.typography.bodySmall)
                        Text("Use the physical button", style = MaterialTheme.typography.titleMedium)
                        Text("Click to start and click again to stop. Or hold until the vibration, speak, and release. Enable cross-app dictation to use it in other apps.")
                        button.lastEvent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("Presses received: ${button.presses}", style = MaterialTheme.typography.bodySmall)
                        Text("Recording here", style = MaterialTheme.typography.titleMedium)
                        Text("On first use, allow microphone access, then tap Start recording again. Leaving this screen or locking the phone stops a recording made here without uploading.", style = MaterialTheme.typography.bodySmall)
                        Text("Stopping normally sends your audio to Fish Audio for transcription. Only the latest clip and transcript are kept.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = openSettings) { Text("Microphone settings") }
                    }
                }
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
