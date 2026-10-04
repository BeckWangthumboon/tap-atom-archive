package dev.backbutton

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
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
    enum class Input { BUTTON }
    private val handler = Handler(Looper.getMainLooper())
    private val gestures = Input.entries.associateWith { RecordingGesture() }
    private val holdTimers = mutableMapOf<Input, Runnable>()
    val audio = AudioSession(context)
    val transcription = TranscriptionSession(context)
    val button = BleButtonConnection(context, { press(Input.BUTTON) }, { release(Input.BUTTON) },
        { interrupt("Recording stopped because the button signal was lost.") })

    var crossAppEnabled by mutableStateOf(false)
        internal set
    var accessibilityConnected by mutableStateOf(false)
        internal set
    var notice by mutableStateOf<String?>(null)
        internal set
    var activityVisible = false
    var statusMessage: String? = null
        private set
    var statusIsError = false
        private set
    var statusUntil = 0L
        private set
    private var delivery: ((String) -> Unit)? = null
    var recordingInField = false
        private set
    var heldBy by mutableStateOf<Input?>(null)
        private set

    fun press(input: Input) {
        val enabled = !transcription.isTranscribing && crossAppEnabled && DictationAccessibilityService.current != null
        if (!enabled && activityVisible && !crossAppEnabled) notice = "Enable dictation, then open a text field in another app."
        if (gestures.getValue(input).down(audio.isRecording, enabled)) {
            val timer = Runnable {
                holdTimers.remove(input)
                handle(input, gestures.getValue(input).hold())
            }
            holdTimers[input] = timer
            handler.postDelayed(timer, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    fun release(input: Input) {
        holdTimers.remove(input)?.let { handler.removeCallbacks(it) }
        handle(input, gestures.getValue(input).release())
    }

    fun cancelPress(input: Input) {
        holdTimers.remove(input)?.let { handler.removeCallbacks(it) }
        handle(input, gestures.getValue(input).cancel())
    }

    private fun handle(input: Input, action: RecordingGesture.Action) {
        when (action) {
            RecordingGesture.Action.TAP -> {
                if (crossAppEnabled) DictationAccessibilityService.current?.toggle()
            }
            RecordingGesture.Action.START_HOLD -> {
                val started = DictationAccessibilityService.current?.start(input) == true
                if (!started) gestures.getValue(input).rejectHold()
            }
            RecordingGesture.Action.FINISH_HOLD -> if (heldBy == input) finish()
            RecordingGesture.Action.CANCEL_HOLD -> if (heldBy == input) interrupt("Hold cancelled. The clip was not uploaded.")
            RecordingGesture.Action.NONE -> Unit
        }
    }

    private fun resetGestures() {
        holdTimers.values.forEach { handler.removeCallbacks(it) }
        holdTimers.clear()
        gestures.values.forEach { it.reset() }
    }

    fun toggle(insert: ((String) -> Unit)? = null) {
        if (transcription.isTranscribing) return
        if (audio.isRecording) finish() else start(insert)
    }

    fun start(insert: ((String) -> Unit)? = null, heldBy: Input? = null): Boolean {
        if (transcription.isTranscribing || audio.isRecording) return false
        notice = null
        statusMessage = null
        audio.startRecording()
        if (!audio.isRecording) return false
        transcription.prepareRecording()
        delivery = insert
        recordingInField = insert != null
        this.heldBy = heldBy
        return true
    }

    fun finish() {
        if (!audio.isRecording) return
        resetGestures()
        audio.stopRecording()
        val completed = delivery
        delivery = null
        recordingInField = false
        heldBy = null
        if (audio.hasRecording) transcription.transcribe(completed) {
            showStatus("Transcription failed", error = true)
        } else showStatus("Recording too short", error = true)
    }

    fun interrupt(reason: String) {
        val wasRecording = audio.isRecording
        resetGestures()
        delivery = null
        recordingInField = false
        heldBy = null
        if (audio.isRecording) audio.stopRecording(interrupted = true, interruptionMessage = reason)
        if (wasRecording) showStatus("Recording stopped", error = true)
    }

    fun showStatus(message: String, error: Boolean = false) {
        statusMessage = message
        statusIsError = error
        statusUntil = SystemClock.elapsedRealtime() + if (error) 3500L else 1200L
    }

    fun leaveActivity() {
        cancelPress(Input.BUTTON)
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
