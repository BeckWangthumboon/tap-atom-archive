package dev.backbutton

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.SocketTimeoutException
import java.io.IOException

class TranscriptionSession(
    context: Context,
    private val transcribeAudio: suspend (File, String) -> String = FishAudioClient()::transcribe,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyFile = File(context.filesDir, "fish-audio-key")
    private val recordingFile = File(context.filesDir, "latest-recording.m4a")
    private val transcriptFile = File(context.filesDir, "latest-transcript.txt")
    private val preferences = context.getSharedPreferences("transcript", Context.MODE_PRIVATE)

    var isTranscribing by mutableStateOf(false)
        private set
    var transcript by mutableStateOf(runCatching { transcriptFile.readText() }.getOrNull())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var persistenceWarning by mutableStateOf<String?>(null)
        private set
    var hasApiKey by mutableStateOf(false)
        private set
    var transcriptDismissed by mutableStateOf(preferences.getBoolean("dismissed", false))
        private set

    init { refreshKeyStatus() }

    fun refreshKeyStatus() {
        hasApiKey = runCatching { keyFile.readText().isNotBlank() }.getOrDefault(false)
    }

    fun saveApiKey(key: String): Boolean {
        if (key.isBlank() || isTranscribing) return false
        val pending = File(keyFile.parentFile, "fish-audio-key.pending")
        return runCatching {
            pending.writeText(key.trim())
            check(pending.renameTo(keyFile))
            refreshKeyStatus()
            error = null
            true
        }.getOrElse { pending.delete(); false }
    }

    fun dismissTranscript() {
        transcriptDismissed = true
        preferences.edit().putBoolean("dismissed", true).apply()
    }

    fun revealTranscript() {
        transcriptDismissed = false
        preferences.edit().putBoolean("dismissed", false).apply()
    }

    fun prepareRecording() { error = null }

    fun clear() {
        if (isTranscribing) return
        transcriptFile.delete()
        transcript = null
        error = null
        persistenceWarning = null
    }

    fun transcribe(onResult: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        if (isTranscribing) return
        val key = runCatching { keyFile.readText().trim() }.getOrDefault("")
        if (key.isBlank()) {
            error = "Fish Audio key is not configured yet."
            onError?.invoke(error!!)
            return
        }
        error = null
        isTranscribing = true
        Log.i("TapDictation", "Processing started")
        scope.launch {
            try {
                val result = transcribeAudio(recordingFile, key)
                Log.i("TapDictation", "Processing completed: speechDetected=${result.isNotBlank()}")
                if (result.isNotBlank()) {
                    transcript = result
                    transcriptDismissed = false
                    preferences.edit().putBoolean("dismissed", false).apply()
                    persistenceWarning = null
                    val pending = File(transcriptFile.parentFile, "latest-transcript.pending")
                    try {
                        pending.writeText(result)
                        if (!pending.renameTo(transcriptFile)) throw IOException("Could not save transcript")
                    } catch (_: IOException) {
                        // A usable recognition result must still reach the editor/recovery UI.
                        persistenceWarning = "The latest transcript could not be saved. Copy it before closing the app."
                        Log.w("TapDictation", "Transcript persistence failed; result is available in memory")
                    } finally {
                        pending.delete()
                    }
                }
                onResult?.invoke(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SocketTimeoutException) {
                error = "Transcription timed out. Retry the last recording in settings."
            } catch (_: IOException) {
                error = "Could not finish transcription. Check your connection and retry in settings."
            } catch (failure: Exception) {
                error = if (failure is IllegalStateException || failure is IllegalArgumentException) {
                    failure.message ?: "Transcription failed. Please try again."
                } else {
                    "Transcription failed. Please try again."
                }
            } finally {
                isTranscribing = false
                if (error != null) Log.i("TapDictation", "Processing failed")
                error?.let { onError?.invoke(it) }
            }
        }
    }
}
