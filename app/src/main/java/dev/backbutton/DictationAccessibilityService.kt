package dev.backbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import java.lang.ref.WeakReference

class DictationAccessibilityService : AccessibilityService() {
    companion object {
        private var connection: WeakReference<DictationAccessibilityService>? = null
        val current: DictationAccessibilityService? get() = connection?.get()
    }

    private data class Target(val text: String, val start: Int, val end: Int, val offset: Int, val generation: Long, var valid: Boolean = true)
    private var editorGeneration = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(WindowManager::class.java) }
    private var pill: DictationStatusView? = null
    private var params: WindowManager.LayoutParams? = null
    private var captured: Target? = null
    private var insertionInProgress = false
    private val refresh = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, if (dictation.audio.isRecording || dictation.transcription.isTranscribing) 80L else 250L)
        }
    }

    override fun onServiceConnected() {
        connection = WeakReference(this)
        dictation.accessibilityConnected = true
        handler.post(refresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window changes from every app are needed to dismiss the control on app switches.
        // Text is read locally when recording starts and when a result arrives;
        // focused field bounds also keep the active indicator clear of growing composers.
        update()
    }

    override fun onInterrupt() {
        dictation.interrupt("Recording stopped because accessibility was interrupted.")
        cancelTarget()
        removePill()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (current === this) connection = null
        cancelPresses()
        dictation.accessibilityConnected = false
        if (dictation.recordingInField) dictation.interrupt("Recording stopped because accessibility was turned off.")
        cancelTarget()
        removePill()
        super.onDestroy()
    }

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            editorChanged()
        }

        override fun onFinishInput() {
            // Do not change composing text when another editor closes.
            editorChanged()
        }

        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) captured?.valid = false
        }
    }

    private fun editorChanged() {
        cancelPresses()
        editorGeneration++
        cancelTarget()
        if (dictation.recordingInField) dictation.interrupt("Recording stopped because the text field changed.")
        handler.post { update() }
    }

    private fun eligibleEditor(): Boolean {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return false
        val method = inputMethod ?: return false
        val info = method.currentInputEditorInfo ?: return false
        if (!method.currentInputStarted || method.currentInputConnection == null) return false
        return EditorEligibility.accepts(info.packageName, packageName, info.inputType)
    }

    private fun snapshot(): Target? {
        if (!eligibleEditor()) return null
        val surrounding = inputMethod?.currentInputConnection?.getSurroundingText(2048, 2048, 0) ?: return null
        val text = surrounding.text.toString()
        if (surrounding.selectionStart !in 0..text.length || surrounding.selectionEnd !in 0..text.length) return null
        return Target(text, surrounding.selectionStart, surrounding.selectionEnd, surrounding.offset, editorGeneration)
    }

    fun toggle() {
        if (!dictation.crossAppEnabled || dictation.transcription.isTranscribing) return
        if (dictation.audio.isRecording) {
            dictation.finish()
            update()
            return
        }
        start()
    }

    fun start(heldBy: DictationSession.Input? = null): Boolean {
        if (!dictation.crossAppEnabled || dictation.transcription.isTranscribing || dictation.audio.isRecording) return false
        if (!eligibleEditor() || keyboardBounds() == null) {
            tell("Tap a text field and open the keyboard first.")
            return false
        }
        val target = snapshot()
        if (target == null) {
            tell("This field does not expose its cursor. Try a standard text field.", "Field unavailable")
            return false
        }
        captured = target
        val started = dictation.start(insert = { result ->
            runCatching { insert(target, result) }.onFailure {
                tell("Could not insert here. Your transcript is saved in tap.", "Transcript saved")
            }
        }, heldBy = heldBy)
        if (!started) {
            cancelTarget()
            tell(dictation.audio.message ?: "Could not start recording. Open tap and enable dictation again.")
        }
        update()
        return started
    }

    private fun insert(target: Target, result: String) {
        captured = null
        if (result.isBlank()) { tell("No speech detected."); return }
        val latest = snapshot()
        if (!dictation.crossAppEnabled || !target.valid || latest == null || target.generation != latest.generation ||
            target.offset != latest.offset || !TextInsertion.unchanged(target.text, target.start, target.end,
                latest.text, latest.start, latest.end)) {
            tell("Field changed. Your transcript is saved in tap; copy it from there.", "Transcript saved")
            return
        }
        val inserted = TextInsertion.replacement(target.text, target.start, target.end, result) ?: return
        insertionInProgress = true
        try {
            // The accessibility input connection finishes existing IME composition, then commits
            // only the replacement at the selection. It never resets the entire field's text.
            inputMethod?.currentInputConnection?.commitText(inserted, 1, null)
            dictation.notice = null
            dictation.showStatus("Inserted")
        } finally {
            insertionInProgress = false
            update()
        }
    }

    private fun keyboardBounds(): Rect? = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }?.let { Rect().apply { it.getBoundsInScreen(this) } }?.takeIf { it.height() > dp(80) }

    private fun composerBounds(keyboard: Rect): OverlayBounds? {
        val editor = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!editor.isEditable || !editor.isVisibleToUser ||
            editor.packageName?.toString() != inputMethod?.currentInputEditorInfo?.packageName) return null
        val bounds = Rect().apply { editor.getBoundsInScreen(this) }
        // Only follow a compact editor beside the keyboard, not a full-page note or search field.
        if (bounds.isEmpty || bounds.height() > dp(280) ||
            kotlin.math.abs(bounds.bottom - keyboard.top) > dp(48)) return null
        return OverlayBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    fun cancelTarget() {
        captured?.valid = false
        captured = null
    }

    private fun cancelPresses() {
        dictation.cancelPress(DictationSession.Input.BUTTON)
    }

    private fun update() {
        if (insertionInProgress) return
        if (!dictation.crossAppEnabled || getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            if (dictation.recordingInField) dictation.interrupt("Recording stopped because dictation became unavailable.")
            cancelTarget()
            removePill()
            return
        }
        val eligible = eligibleEditor()
        captured?.let { if (!eligible || it.generation != editorGeneration) it.valid = false }
        if (dictation.recordingInField && !dictation.audio.isRecording) {
            dictation.interrupt("Recording was interrupted.")
            captured = null
        }
        if (dictation.recordingInField && !eligible) {
            dictation.interrupt("Recording stopped because you changed text fields.")
            captured = null
            tell("Recording stopped because the text field changed. The clip is saved without uploading.")
        }
        val keyboard = keyboardBounds()
        if (!dictation.audio.isRecording && !dictation.transcription.isTranscribing) captured = null
        if (!eligible || keyboard == null) { removePill(); return }
        val mode: DictationStatusView.Mode
        val label: String
        when {
            dictation.recordingInField && dictation.audio.isRecording -> {
                mode = DictationStatusView.Mode.RECORDING
                label = "Recording"
            }
            dictation.transcription.isTranscribing && captured?.valid == true -> {
                mode = DictationStatusView.Mode.TRANSCRIBING
                label = "Transcribing…"
            }
            dictation.statusMessage != null && SystemClock.elapsedRealtime() < dictation.statusUntil -> {
                mode = if (dictation.statusIsError) DictationStatusView.Mode.ERROR else DictationStatusView.Mode.SUCCESS
                label = dictation.statusMessage!!
            }
            else -> { removePill(); return }
        }
        showPill(keyboard, mode, label)
    }

    @SuppressLint("RtlHardcoded") // Keyboard bounds and overlay placement use absolute screen coordinates.
    private fun showPill(keyboard: Rect, mode: DictationStatusView.Mode, label: String) {
        val metrics = manager.currentWindowMetrics
        val bounds = metrics.bounds
        val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val view = pill ?: DictationStatusView(this)
        val width = view.desiredWidth(mode, label).coerceAtMost(bounds.width() - dp(16))
        val height = dp(30)
        val placement = StatusPlacement.aboveKeyboard(
            OverlayBounds(bounds.left + safe.left, bounds.top + safe.top,
                bounds.right - safe.right, bounds.bottom - safe.bottom),
            OverlayBounds(keyboard.left, keyboard.top, keyboard.right, keyboard.bottom),
            // Leave room for the app's message composer above the keyboard.
            width, height, dp(96), dp(8),
            composer = composerBounds(keyboard), composerGap = dp(12),
        ) ?: run { removePill(); return }
        val layout = params
        view.update(mode, label, if (mode == DictationStatusView.Mode.RECORDING) dictation.audio.peakLevel() else 0f)
        if (layout == null) {
            val next = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = placement.x
                y = placement.y
            }
            try {
                manager.addView(view, next)
                pill = view
                params = next
            } catch (_: WindowManager.BadTokenException) { pill = null; params = null }
        } else if (layout.x != placement.x || layout.y != placement.y || layout.width != width || layout.height != height) {
            layout.x = placement.x
            layout.y = placement.y
            layout.width = width
            layout.height = height
            runCatching { manager.updateViewLayout(view, layout) }
        }
    }

    private fun removePill() {
        pill?.let { runCatching { manager.removeView(it) } }
        pill = null
        params = null
    }

    private fun tell(message: String, status: String = message) {
        dictation.notice = message
        dictation.showStatus(status, error = true)
        if (!eligibleEditor() || keyboardBounds() == null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
