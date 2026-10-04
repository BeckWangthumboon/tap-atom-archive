package dev.backbutton

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
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
    enum class Input { CONTROL, BUTTON }
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
    private var delivery: ((String) -> Unit)? = null
    var recordingInField = false
        private set
    var heldBy by mutableStateOf<Input?>(null)
        private set

    fun press(input: Input) {
        val enabled = !transcription.isTranscribing && when (input) {
            Input.CONTROL -> crossAppEnabled && DictationAccessibilityService.current != null
            Input.BUTTON -> activityVisible || crossAppEnabled && DictationAccessibilityService.current != null
        }
        if (gestures.getValue(input).down(audio.isRecording, enabled)) {
            val timer = Runnable {
                holdTimers.remove(input)
                handle(input, gestures.getValue(input).hold())
            }
            holdTimers[input] = timer
            handler.postDelayed(timer, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    fun release(input: Input, tap: (() -> Unit)? = null) {
        holdTimers.remove(input)?.let { handler.removeCallbacks(it) }
        handle(input, gestures.getValue(input).release(), tap)
    }

    fun cancelPress(input: Input) {
        holdTimers.remove(input)?.let { handler.removeCallbacks(it) }
        handle(input, gestures.getValue(input).cancel())
    }

    private fun handle(input: Input, action: RecordingGesture.Action, tap: (() -> Unit)? = null) {
        when (action) {
            RecordingGesture.Action.TAP -> {
                if (tap != null) tap()
                else if (activityVisible) toggle()
                else if (crossAppEnabled) DictationAccessibilityService.current?.toggle()
            }
            RecordingGesture.Action.START_HOLD -> {
                val started = if (input == Input.BUTTON && activityVisible) start(heldBy = input)
                    else DictationAccessibilityService.current?.start(input) == true
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
        audio.startRecording()
        if (!audio.isRecording) return false
        transcription.clear()
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
        if (audio.hasRecording) transcription.transcribe(completed)
    }

    fun interrupt(reason: String) {
        resetGestures()
        delivery = null
        recordingInField = false
        heldBy = null
        if (audio.isRecording) audio.stopRecording(interrupted = true, interruptionMessage = reason)
    }

    fun leaveActivity() {
        cancelPress(Input.BUTTON)
        cancelPress(Input.CONTROL)
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
