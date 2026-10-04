package dev.backbutton

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private val Paper = Color(0xFFFCFCFC)
private val Ink = Color(0xFF222222)
private val Muted = Color(0xFF737373)
private val Divider = Color(0xFFE5E5E5)
private val SettingsColors = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    primaryContainer = Color(0xFFEEEEEE), onPrimaryContainer = Ink,
    secondary = Muted, onSecondary = Paper,
    secondaryContainer = Color(0xFFEEEEEE), onSecondaryContainer = Ink,
    tertiary = Ink, onTertiary = Paper,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink, onSurfaceVariant = Muted,
    surfaceVariant = Color(0xFFF2F2F2), surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFF2F2F2),
    outline = Muted, outlineVariant = Divider,
    error = Color(0xFF9B3434), onError = Color.White,
)

class MainActivity : ComponentActivity() {
    private lateinit var session: DictationSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        session = dictation
        setContent {
            MaterialTheme(colorScheme = SettingsColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper, contentColor = Ink) {
                    SettingsScreen(session, ::setCrossAppEnabled, ::openAccessibility, ::openAppSettings) { text ->
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Transcript", text))
                    }
                }
            }
        }
    }

    private fun setCrossAppEnabled(enabled: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!enabled) DictationService.disable(this)
        else try { DictationService.enable(this) }
        catch (_: IllegalStateException) { session.notice = "Keep tap open and enable dictation again." }
        catch (_: SecurityException) { session.notice = "Allow microphone access, then enable dictation again." }
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun openAppSettings() = startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
    )

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.activityVisible = true
            session.transcription.refreshKeyStatus()
            session.button.resume()
        }
    }

    override fun onPause() {
        if (::session.isInitialized) session.leaveActivity()
        super.onPause()
    }
}

@Composable
private fun SettingsScreen(
    session: DictationSession,
    setCrossAppEnabled: (Boolean) -> Unit,
    openAccessibility: () -> Unit,
    openAppSettings: () -> Unit,
    copyTranscript: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = (context as ComponentActivity).lifecycle
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var microphoneAllowed by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var notificationsAllowed by remember { mutableStateOf(granted(Manifest.permission.POST_NOTIFICATIONS)) }
    var enableAfterPermission by remember { mutableStateOf(false) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showButton by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val transcription = session.transcription
    val button = session.button
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        microphoneAllowed = allowed
        if (allowed && enableAfterPermission) setCrossAppEnabled(true)
        else if (!allowed) session.notice = "Allow microphone access in Android app settings."
        enableAfterPermission = false
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
        if (!it) session.notice = "You can allow notifications in Android app settings."
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it } && button.hasPermissions()) button.connect() else button.permissionDenied()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                microphoneAllowed = granted(Manifest.permission.RECORD_AUDIO)
                notificationsAllowed = granted(Manifest.permission.POST_NOTIFICATIONS)
                transcription.refreshKeyStatus()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Scaffold(containerColor = Paper) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.bodyMedium, color = Muted)
                Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            }
            SettingsSection("Permissions") {
                val dictationReady = microphoneAllowed && session.accessibilityConnected && session.crossAppEnabled
                SettingsRow("Can dictate in other apps", when {
                    dictationReady -> "Ready"
                    !session.accessibilityConnected -> "Set up"
                    !microphoneAllowed -> "Allow"
                    else -> "Enable"
                }, onClick = if (dictationReady) null else ({
                    when {
                        !session.accessibilityConnected -> openAccessibility()
                        !microphoneAllowed -> {
                            enableAfterPermission = true
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        else -> setCrossAppEnabled(true)
                    }
                }))
                SettingsRow("Microphone", if (microphoneAllowed) "Allowed" else "Allow", onClick = {
                    if (microphoneAllowed) openAppSettings() else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                })
                SettingsRow("Text insertion", if (session.accessibilityConnected) "Enabled" else "Set up", onClick = openAccessibility)
                SettingsRow("Notifications", if (notificationsAllowed) "Allowed" else "Allow", onClick = {
                    if (notificationsAllowed) openAppSettings() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }
            session.notice?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            SettingsSection("Transcription") {
                SettingsRow("Provider", "Fish Audio")
                SettingsRow("API key", if (transcription.hasApiKey) "Configured" else "Add key", onClick = { showKey = true })
                if (transcription.transcriptDismissed && !transcription.transcript.isNullOrBlank()) {
                    SettingsRow("Last transcript", "View", onClick = transcription::revealTranscript)
                }
                transcription.error?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp))
                    if (session.audio.hasRecording) TextButton(
                        onClick = { transcription.transcribe() },
                        enabled = !transcription.isTranscribing && !session.audio.isRecording,
                    ) { Text(if (transcription.isTranscribing) "Transcribing…" else "Retry last recording") }
                }
            }
            SettingsSection("Physical button") {
                SettingsRow("Bluetooth button", when {
                    button.connected -> "Connected"
                    button.busy -> "Searching…"
                    else -> "Not connected"
                }, onClick = { showButton = true })
            }
            transcription.transcript?.takeIf { it.isNotBlank() && !transcription.transcriptDismissed }?.let { text ->
                LastTranscript(text, { copyTranscript(text) }, transcription::dismissTranscript)
            }
            SettingsRow("Help & setup", onClick = { showHelp = true })
        }
    }
    if (showKey) ApiKeyDialog(transcription) { showKey = false }
    if (showButton) AlertDialog(
        onDismissRequest = { showButton = false },
        title = { Text("Physical button") },
        text = { Text(button.status) },
        confirmButton = {
            TextButton(onClick = {
                if (button.connected || button.busy) button.disconnect()
                else if (button.hasPermissions()) button.connect()
                else bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            }) { Text(if (button.connected) "Disconnect" else if (button.busy) "Cancel search" else "Connect") }
        },
        dismissButton = { TextButton(onClick = { showButton = false }) { Text("Done") } },
    )
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text("Help & setup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Allow text insertion and microphone access, add your Fish Audio API key, then select Can dictate in other apps → Enable.")
                Text("Connect your physical button. Click to start, then click to stop, or hold until the vibration and release to finish. You can record without selecting a field. If a compatible field stays unchanged, text is inserted there; otherwise use × and Copy.")
                Text("The waveform shows recording and processing. Red signals an error; details appear in settings. If text could not be inserted, use the small Copy icon or close the floating result with ×.")
                Text("Password and PIN fields are excluded. If insertion fails, copy your last transcript here.")
                Text("Audio is sent to Fish Audio when you finish recording. The microphone stays off until you press the physical button. Enable dictation again after the app restarts.")
                TextButton(onClick = openAppSettings) { Text("Android app settings") }
            }
        },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Done") } },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun SettingsRow(title: String, value: String? = null, onClick: (() -> Unit)? = null) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 56.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = Muted, style = MaterialTheme.typography.bodyMedium)
            if (onClick != null) Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = Divider)
    }
}

@Composable
private fun LastTranscript(text: String, copy: () -> Unit, dismiss: () -> Unit) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var hasMore by remember(text) { mutableStateOf(false) }
    Surface(color = Color(0xFFF2F2F2), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Last transcript", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = copy) { Text("Copy") }
                IconButton(onClick = dismiss, modifier = Modifier.semantics { contentDescription = "Close last transcript" }) {
                    Canvas(Modifier.size(14.dp)) {
                        drawLine(Ink, Offset.Zero, Offset(size.width, size.height), 1.5.dp.toPx(), StrokeCap.Round)
                        drawLine(Ink, Offset(size.width, 0f), Offset(0f, size.height), 1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp),
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) hasMore = it.hasVisualOverflow })
            }
            if (hasMore || expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
        }
    }
}

@Composable
private fun ApiKeyDialog(transcription: TranscriptionSession, dismiss: () -> Unit) {
    // Credentials remain in transient memory, never saved instance state or UI diagnostics.
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Fish Audio API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (transcription.hasApiKey) "Enter a new key to replace the configured key." else "Add your Fish Audio key to enable transcription.")
                OutlinedTextField(value = key, onValueChange = { key = it; error = null }, singleLine = true,
                    label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank() && !transcription.isTranscribing, onClick = {
                if (transcription.saveApiKey(key)) dismiss() else error = "Could not save the key. Please try again."
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
