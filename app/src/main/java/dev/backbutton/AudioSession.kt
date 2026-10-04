package dev.backbutton

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Microphone and playback resources owned by the shared dictation session. */
class AudioSession(
    private val context: Context,
    private val onRecordingError: (String) -> Unit = {},
) {
    private val recording = File(context.filesDir, "latest-recording.m4a")
    private val pending = File(context.filesDir, "recording-in-progress.m4a")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    var isRecording by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isPreparingPlayback by mutableStateOf(false)
        private set
    var hasRecording by mutableStateOf(recording.exists())
        private set
    var durationMillis by mutableLongStateOf(0L)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        // A process killed during capture can leave an unfinished container.
        pending.delete()
        if (hasRecording) durationMillis = readDuration()
    }

    fun permissionDenied() {
        message = "Microphone access is needed to record. Try again, or enable it in app settings."
    }

    /** Peak microphone level since the previous sample, only while capture is active. */
    fun peakLevel(): Float {
        if (!isRecording) return 0f
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return kotlin.math.sqrt(amplitude.coerceIn(0, 32767) / 32767f)
    }

    fun startRecording() {
        if (isRecording) return
        stopPlayback()
        message = null
        val next = MediaRecorder(context)
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(44100)
            next.setAudioEncodingBitRate(128000)
            next.setOutputFile(pending.absolutePath)
            next.setOnErrorListener { active, _, _ -> recordingFailed(active) }
            next.prepare()
            next.start()
            recorder = next
            // Replace the previous clip only after capture starts successfully.
            recording.delete()
            hasRecording = false
            durationMillis = 0
            startedAt = SystemClock.elapsedRealtime()
            isRecording = true
            runCatching {
                context.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            }
        } catch (_: Exception) {
            next.release()
            pending.delete()
            message = "Could not start the microphone. Check microphone access and try again."
        }
    }

    fun stopRecording(
        interrupted: Boolean = false,
        interruptionMessage: String = "Recording stopped because you left the app.",
    ) {
        val active = recorder ?: return
        recorder = null
        isRecording = false
        try {
            active.stop()
            if (!pending.renameTo(recording)) error("Could not save recording")
            hasRecording = true
            durationMillis = SystemClock.elapsedRealtime() - startedAt
            message = if (interrupted) interruptionMessage else null
        } catch (_: Exception) {
            pending.delete()
            hasRecording = recording.exists()
            message = "Could not save this recording. It may have been too short. Try recording for a few seconds."
        } finally {
            active.release()
        }
    }

    private fun recordingFailed(active: MediaRecorder) {
        if (recorder !== active) return
        abandonRecording()
        val reason = "Recording was interrupted. Please try again."
        message = reason
        onRecordingError(reason)
    }

    private fun abandonRecording() {
        val active = recorder
        recorder = null
        isRecording = false
        active?.release()
        pending.delete()
    }

    fun play() {
        if (!hasRecording || isRecording || player != null) return
        message = null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) stopPlayback()
            }.build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            message = "Audio is busy. Try playback again in a moment."
            return
        }
        focus = request
        val next = MediaPlayer()
        player = next
        isPreparingPlayback = true
        try {
            next.setAudioAttributes(attributes)
            next.setDataSource(recording.absolutePath)
            next.setOnPreparedListener { ready ->
                if (player === ready) {
                    try {
                        ready.start()
                        isPreparingPlayback = false
                        isPlaying = true
                    } catch (_: Exception) {
                        stopPlayback()
                        message = "Could not play this recording. Try recording again."
                    }
                }
            }
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ ->
                stopPlayback()
                message = "Could not play this recording. Try recording again."
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            message = "Could not play this recording. Try recording again."
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        isPlaying = false
        isPreparingPlayback = false
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun deleteRecording() {
        if (isRecording) return
        stopPlayback()
        if (recording.exists() && !recording.delete()) {
            message = "Could not delete the recording. Please try again."
            return
        }
        hasRecording = false
        durationMillis = 0
        message = null
    }

    fun pause() {
        stopRecording(interrupted = true)
        stopPlayback()
    }

    private fun readDuration(): Long {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(recording.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            metadata.release()
        }
    }
}
