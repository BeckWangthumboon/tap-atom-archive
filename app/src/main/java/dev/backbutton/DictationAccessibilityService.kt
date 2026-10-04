package dev.backbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
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
    private var pill: View? = null
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
        // Window changes invalidate insertion targets; microphone capture follows the button.
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
            if (EditorEligibility.isSensitive(attribute.inputType) && dictation.audio.isRecording) {
                dictation.interrupt("Recording stopped because a password or PIN field was selected.")
            }
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
        editorGeneration++
        cancelTarget()
        Log.i("TapDictation", "Input changed; insertion target invalidated; recording=${dictation.audio.isRecording}")
        handler.post { update() }
    }

    private fun eligibleEditor(): Boolean {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return false
        val method = inputMethod ?: return false
        val info = method.currentInputEditorInfo ?: return false
        if (!method.currentInputStarted || method.currentInputConnection == null) return false
        return EditorEligibility.accepts(info.packageName, packageName, info.inputType)
    }

    private fun sensitiveEditor(): Boolean {
        val method = inputMethod ?: return false
        return method.currentInputStarted && method.currentInputEditorInfo?.let {
            EditorEligibility.isSensitive(it.inputType)
        } == true
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
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked || sensitiveEditor()) {
            tell("Unlock your phone and leave password or PIN fields before recording.")
            return false
        }
        val target = if (keyboardBounds() != null) snapshot() else null
        captured = target
        val started = dictation.start(insert = { result ->
            if (result.isBlank()) tell("No speech detected.")
            else if (target == null) recover(result, "Transcript ready to copy. No input field was selected when recording started.")
            else runCatching { insert(target, result) }.onFailure {
                recover(result, "Could not insert here. Copy your saved transcript or find it in Tap settings.")
            }
        }, heldBy = heldBy)
        Log.i("TapDictation", "Capture start: started=$started insertionTarget=${target != null} held=${heldBy != null}")
        if (!started) {
            cancelTarget()
            tell(dictation.audio.message ?: "Could not start recording. Open Tap and enable dictation again.")
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
            recover(result, "Field changed. Copy your saved transcript or find it in Tap settings.")
            return
        }
        val inserted = TextInsertion.replacement(target.text, target.start, target.end, result)
            ?: run { recover(result, "Could not insert here. Your transcript is saved in Tap."); return }
        insertionInProgress = true
        try {
            // The accessibility input connection finishes existing IME composition, then commits
            // only the replacement at the selection. It never resets the entire field's text.
            inputMethod?.currentInputConnection?.commitText(inserted, 1, null)
            dictation.notice = null
            Log.i("TapDictation", "Result inserted")
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
            if (dictation.audio.isRecording) dictation.interrupt("Recording stopped because dictation became unavailable.")
            cancelTarget()
            removePill()
            return
        }
        val eligible = eligibleEditor()
        if (sensitiveEditor()) {
            if (dictation.audio.isRecording) dictation.interrupt("Recording stopped because a password or PIN field was selected.")
            cancelTarget()
            removePill()
            return
        }
        captured?.let { if (!eligible || it.generation != editorGeneration) it.valid = false }
        if (dictation.recordingInField && !dictation.audio.isRecording) {
            dictation.interrupt("Recording was interrupted.")
            captured = null
        }
        val keyboard = keyboardBounds()
        if (!dictation.audio.isRecording && !dictation.transcription.isTranscribing) captured = null
        val mode: DictationStatusView.Mode
        val label: String
        when {
            dictation.audio.isRecording -> {
                mode = DictationStatusView.Mode.RECORDING
                label = "Recording"
            }
            dictation.transcription.isTranscribing -> {
                mode = DictationStatusView.Mode.PROCESSING
                label = "Processing"
            }
            dictation.recoveryTranscript != null -> {
                mode = DictationStatusView.Mode.RECOVERY
                label = "Transcript ready to copy"
            }
            dictation.statusMessage != null && SystemClock.elapsedRealtime() < dictation.statusUntil -> {
                mode = DictationStatusView.Mode.ERROR
                label = "Dictation error. ${dictation.statusMessage}"
            }
            else -> { removePill(); return }
        }
        showPill(keyboard, mode, label)
    }

    @SuppressLint("RtlHardcoded") // Keyboard bounds and overlay placement use absolute screen coordinates.
    private fun showPill(keyboard: Rect?, mode: DictationStatusView.Mode, label: String) {
        val metrics = manager.currentWindowMetrics
        val bounds = metrics.bounds
        val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val recovery = mode == DictationStatusView.Mode.RECOVERY
        if (pill != null && (pill is TranscriptRecoveryView) != recovery) removePill()
        val view = pill ?: if (recovery) TranscriptRecoveryView(this, ::copyRecovery, ::dismissRecovery)
            else DictationStatusView(this)
        val width = dp(96).coerceAtMost(bounds.width() - dp(16))
        val height = dp(if (recovery) 48 else 30)
        val screen = OverlayBounds(bounds.left + safe.left, bounds.top + safe.top,
            bounds.right - safe.right, bounds.bottom - safe.bottom)
        val placement = keyboard?.let {
            StatusPlacement.aboveKeyboard(screen, OverlayBounds(it.left, it.top, it.right, it.bottom),
                width, height, dp(96), dp(8), composer = composerBounds(it), composerGap = dp(12))
        } ?: StatusPlacement.atScreenEdge(screen, width, height, dp(24), atTop = keyboard != null)
            ?: run { removePill(); return }
        val layout = params
        (view as? DictationStatusView)?.update(mode, label,
            if (mode == DictationStatusView.Mode.RECORDING) dictation.audio.peakLevel() else 0f)
        if (layout == null) {
            val next = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    (if (recovery) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
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

    private fun tell(message: String) {
        Log.i("TapDictation", "Error: $message")
        dictation.showError(message)
    }

    private fun recover(text: String, reason: String) {
        Log.i("TapDictation", "Result offered for copy")
        dictation.showRecovery(text, reason)
        update()
    }

    private fun copyRecovery() {
        val text = dictation.recoveryTranscript ?: return
        runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcript", text))
        }.onSuccess { Log.i("TapDictation", "Recovery copied"); dismissRecovery() }.onFailure {
            dictation.dismissRecovery()
            dictation.showError("Could not copy. Your transcript is saved in Tap settings.")
            update()
        }
    }

    private fun dismissRecovery() {
        Log.i("TapDictation", "Recovery dismissed")
        dictation.dismissRecovery()
        update()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
