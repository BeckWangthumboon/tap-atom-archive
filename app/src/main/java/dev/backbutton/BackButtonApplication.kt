package dev.backbutton

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class BackButtonApplication : Application() {
    val dictation by lazy { DictationSession(this) }
}

val Context.dictation: DictationSession
    get() = (applicationContext as BackButtonApplication).dictation

/** One owner for the microphone, uploads, and BLE, shared by the screen and service. */
class DictationSession(context: Context) {
    val audio = AudioSession(context)
    val transcription = TranscriptionSession(context)
    val button = BleButtonConnection(context, {
        if (activityVisible) toggle()
        else if (crossAppEnabled) DictationAccessibilityService.current?.toggle()
    }, { interrupt("Recording stopped because the button signal was lost.") })

    var crossAppEnabled by mutableStateOf(false)
        internal set
    var accessibilityConnected by mutableStateOf(false)
        internal set
    var notice by mutableStateOf<String?>(null)
        internal set
    var activityVisible = false
    private var delivery: ((String) -> Unit)? = null
    var recordingInField = false
        private set

    fun toggle(insert: ((String) -> Unit)? = null) {
        if (transcription.isTranscribing) return
        if (audio.isRecording) {
            audio.stopRecording()
            val completed = delivery
            delivery = null
            recordingInField = false
            if (audio.hasRecording) transcription.transcribe(completed)
        } else {
            notice = null
            audio.startRecording()
            if (audio.isRecording) {
                transcription.clear()
                delivery = insert
                recordingInField = insert != null
            }
        }
    }

    fun interrupt(reason: String) {
        delivery = null
        recordingInField = false
        if (audio.isRecording) audio.stopRecording(interrupted = true, interruptionMessage = reason)
    }

    fun leaveActivity() {
        activityVisible = false
        audio.stopPlayback()
        if (!crossAppEnabled) {
            interrupt("Recording stopped because you left the app.")
            button.pause()
        } else if (audio.isRecording && !recordingInField) {
            interrupt("Recording stopped because you left the app.")
        }
    }
}
