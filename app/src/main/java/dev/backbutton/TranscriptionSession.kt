package dev.backbutton

import android.content.Context
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

class TranscriptionSession(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = FishAudioClient()
    private val keyFile = File(context.filesDir, "fish-audio-key")
    private val recordingFile = File(context.filesDir, "latest-recording.m4a")
    private val transcriptFile = File(context.filesDir, "latest-transcript.txt")

    var isTranscribing by mutableStateOf(false)
        private set
    var transcript by mutableStateOf(runCatching { transcriptFile.readText() }.getOrNull())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun clear() {
        if (isTranscribing) return
        transcriptFile.delete()
        transcript = null
        error = null
    }

    fun transcribe(onResult: ((String) -> Unit)? = null) {
        if (isTranscribing) return
        val key = runCatching { keyFile.readText().trim() }.getOrDefault("")
        if (key.isBlank()) {
            error = "Fish Audio key is not configured yet."
            return
        }
        error = null
        isTranscribing = true
        scope.launch {
            try {
                val result = client.transcribe(recordingFile, key)
                transcriptFile.writeText(result)
                transcript = result
                onResult?.invoke(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SocketTimeoutException) {
                error = "Transcription timed out. Tap Retry to try this recording again."
            } catch (_: IOException) {
                error = "Could not finish transcription. Check your connection and tap Retry."
            } catch (failure: Exception) {
                error = if (failure is IllegalStateException || failure is IllegalArgumentException) {
                    failure.message ?: "Transcription failed. Please try again."
                } else {
                    "Transcription failed. Please try again."
                }
            } finally {
                isTranscribing = false
            }
        }
    }
}
