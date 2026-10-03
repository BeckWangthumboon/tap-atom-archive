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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var audio: AudioSession
    private lateinit var transcription: TranscriptionSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        audio = AudioSession(this)
        transcription = ViewModelProvider(this)[TranscriptionSession::class.java]
        setContent {
            MaterialTheme {
                RecordingScreen(audio, transcription, ::requestRecording, ::openSettings) { text ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcript", text))
                }
            }
        }
    }

    private fun requestRecording(requestPermission: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audio.startRecording()
            if (audio.isRecording) transcription.clear()
        } else {
            requestPermission()
        }
    }

    private fun openSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onPause() {
        if (::audio.isInitialized) audio.pause()
        super.onPause()
    }
}

@Composable
private fun RecordingScreen(
    audio: AudioSession,
    transcription: TranscriptionSession,
    start: (() -> Unit) -> Unit,
    openSettings: () -> Unit,
    copyTranscript: (String) -> Unit,
) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Require a fresh tap after the system dialog closes; never capture unexpectedly.
        if (!granted) audio.permissionDenied()
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
                onClick = {
                    if (audio.isRecording) {
                        audio.stopRecording()
                        if (audio.hasRecording) transcription.transcribe()
                    } else start { permission.launch(Manifest.permission.RECORD_AUDIO) }
                },
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
                    onClick = transcription::transcribe,
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
