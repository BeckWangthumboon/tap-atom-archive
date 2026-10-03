package dev.backbutton

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Direct file-upload API: no key in the APK and no audio conversion needed. */
class FishAudioClient {
    suspend fun transcribe(audio: File, key: String): String = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Fish Audio key is not configured yet." }
        require(audio.isFile && audio.length() > 0) { "Record a clip first." }
        val boundary = "BackButton-${UUID.randomUUID()}"
        val connection = URL("https://api.fish.audio/v1/asr").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("model", "transcribe-1-pro")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val prefix = buildString {
                for ((name, value) in listOf("ignore_timestamps" to "true", "tag_audio_events" to "false", "diarize" to "false")) {
                    append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                append("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"recording.m4a\"\r\n")
                append("Content-Type: audio/mp4\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(prefix.size.toLong() + audio.length() + suffix.size)
            connection.outputStream.use { output ->
                output.write(prefix)
                audio.inputStream().use { it.copyTo(output) }
                output.write(suffix)
            }
            val status = connection.responseCode
            if (status != 200) {
                // Never expose provider response bodies or credentials in UI/logs.
                error(when (status) {
                    401 -> "Fish Audio rejected the API key."
                    402 -> "Your Fish Audio account needs API credits."
                    429 -> "Fish Audio is busy. Please try again."
                    else -> "Transcription failed (HTTP $status). Please try again."
                })
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = JSONObject(response).getString("text")
            // diarize=false omits speaker turns but does not remove inline markers.
            text.replace(Regex("<\\|speaker:\\d+\\|>"), "").trim()
        } finally {
            connection.disconnect()
        }
    }
}
