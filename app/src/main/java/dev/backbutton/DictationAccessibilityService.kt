package dev.backbutton

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import kotlin.math.abs
import java.lang.ref.WeakReference

class DictationAccessibilityService : AccessibilityService() {
    companion object {
        private var connection: WeakReference<DictationAccessibilityService>? = null
        val current: DictationAccessibilityService? get() = connection?.get()
        private val SUPPORTED = setOf("jp.naver.line.android", "com.android.chrome", "com.sec.android.app.sbrowser")
    }

    private data class Target(val text: String, val start: Int, val end: Int, val offset: Int, val generation: Long, var valid: Boolean = true)
    private var editorGeneration = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(WindowManager::class.java) }
    private val position by lazy { getSharedPreferences("dictation-control", MODE_PRIVATE) }
    private var pill: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var captured: Target? = null
    private var drag = false
    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0
    private var keyboardTop: Int? = null
    private var insertionInProgress = false
    private val refresh = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 250)
        }
    }

    override fun onServiceConnected() {
        connection = WeakReference(this)
        dictation.accessibilityConnected = true
        handler.post(refresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window changes from every app are needed to dismiss the control on app switches.
        // We only inspect editable content in the explicit LINE/browser allowlist.
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
        editorGeneration++
        cancelTarget()
        if (dictation.recordingInField) dictation.interrupt("Recording stopped because the text field changed.")
        handler.post { update() }
    }

    private fun eligibleEditor(): Boolean {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return false
        val method = inputMethod ?: return false
        val info = method.currentInputEditorInfo ?: return false
        if (!method.currentInputStarted || info.packageName !in SUPPORTED || method.currentInputConnection == null) return false
        val kind = info.inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = info.inputType and android.text.InputType.TYPE_MASK_VARIATION
        if (kind == android.text.InputType.TYPE_NULL) return false
        return !(kind == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ) || kind == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
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
            dictation.toggle()
            update()
            return
        }
        if (!eligibleEditor() || keyboardBounds() == null) {
            tell("Tap a LINE or browser text field first.")
            return
        }
        val target = snapshot()
        if (target == null) {
            tell("This field does not expose its cursor. Use dictation in Back Button and copy the result.")
            return
        }
        captured = target
        dictation.toggle { result ->
            runCatching { insert(target, result) }.onFailure {
                tell("Could not insert here. Your transcript is saved in Back Button.")
            }
        }
        if (!dictation.audio.isRecording) {
            cancelTarget()
            tell(dictation.audio.message ?: "Could not start recording. Open Back Button and enable dictation again.")
        }
        update()
    }

    private fun insert(target: Target, result: String) {
        captured = null
        if (result.isBlank()) { tell("No speech detected."); return }
        val latest = snapshot()
        if (!dictation.crossAppEnabled || !target.valid || latest == null || target.generation != latest.generation ||
            target.offset != latest.offset || !TextInsertion.unchanged(target.text, target.start, target.end,
                latest.text, latest.start, latest.end)) {
            tell("Field changed. Your transcript is saved in Back Button; copy it from there.")
            return
        }
        val inserted = TextInsertion.replacement(target.text, target.start, target.end, result) ?: return
        insertionInProgress = true
        try {
            // The accessibility input connection finishes existing IME composition, then commits
            // only the replacement at the selection. It never resets the entire field's text.
            inputMethod?.currentInputConnection?.commitText(inserted, 1, null)
            dictation.notice = "Transcript inserted."
        } finally {
            insertionInProgress = false
            update()
        }
    }

    private fun keyboardBounds(): Rect? = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }?.let { Rect().apply { it.getBoundsInScreen(this) } }?.takeIf { it.height() > dp(80) }

    fun cancelTarget() {
        captured?.valid = false
        captured = null
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
        val active = dictation.recordingInField || dictation.transcription.isTranscribing && captured?.valid == true
        if (!dictation.audio.isRecording && !dictation.transcription.isTranscribing) captured = null
        if (!active && (!eligible || keyboard == null)) { removePill(); return }
        showPill(keyboard?.top)
    }

    @SuppressLint("RtlHardcoded") // Accessibility bounds and drag coordinates are absolute screen coordinates.
    private fun showPill(top: Int?) {
        val bounds = manager.currentWindowMetrics.bounds
        val width = dp(112)
        val height = dp(40)
        if (pill == null) {
            val control = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                elevation = dp(3).toFloat()
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isClickable = true
                setOnClickListener { toggle() }
                setOnTouchListener { view, event ->
                    val layout = params ?: return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY
                            originX = layout.x; originY = layout.y; drag = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downX; val dy = event.rawY - downY
                            if (abs(dx) + abs(dy) > ViewConfiguration.get(this@DictationAccessibilityService).scaledTouchSlop) drag = true
                            if (drag) {
                                layout.x = (originX + dx.toInt()).coerceIn(dp(4), (bounds.width() - width - dp(4)).coerceAtLeast(dp(4)))
                                layout.y = (originY + dy.toInt()).coerceIn(dp(32), (bounds.height() - height - dp(40)).coerceAtLeast(dp(32)))
                                runCatching { manager.updateViewLayout(view, layout) }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (drag) position.edit().putFloat("horizontal", layout.x.toFloat() / bounds.width())
                                .putInt("above-keyboard", (keyboardTop ?: bounds.height() - dp(40)) - layout.y - height).apply()
                            else view.performClick()
                            drag = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> { drag = false; true }
                        else -> false
                    }
                }
            }
            val layout = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = (position.getFloat("horizontal", 0.5f) * bounds.width() - if (position.contains("horizontal")) 0 else width / 2).toInt()
            }
            params = layout
            pill = control
            keyboardTop = null
            place(top, bounds, width, height)
            try { manager.addView(control, layout) }
            catch (_: WindowManager.BadTokenException) { pill = null; params = null; return }
        }
        if (!drag) place(top, bounds, width, height)
        val control = pill ?: return
        val recording = dictation.recordingInField
        val processing = dictation.transcription.isTranscribing && captured != null
        val label = when {
            recording -> "■ Stop ${((SystemClock.elapsedRealtime() - dictation.audio.startedAt) / 1000)}s"
            processing -> "Working…"
            else -> "● Record"
        }
        if (control.text.toString() != label) {
            control.text = label
            control.contentDescription = if (recording) "Stop dictation" else if (processing) "Transcribing recording" else "Start dictation. Drag to move."
            control.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (recording) Color.rgb(169, 35, 51) else Color.rgb(43, 46, 60))
            }
        }
        control.isEnabled = !processing
        runCatching { manager.updateViewLayout(control, params) }
    }

    private fun place(top: Int?, screen: Rect, width: Int, height: Int) {
        val layout = params ?: return
        layout.x = layout.x.coerceIn(dp(4), (screen.width() - width - dp(4)).coerceAtLeast(dp(4)))
        if (keyboardTop != top || pill?.isAttachedToWindow != true) {
            keyboardTop = top
            val edge = top ?: screen.height() - dp(40)
            layout.y = (edge - height - position.getInt("above-keyboard", dp(4)))
                .coerceIn(dp(32), (screen.height() - height - dp(40)).coerceAtLeast(dp(32)))
        }
    }

    private fun removePill() {
        pill?.let { runCatching { manager.removeView(it) } }
        pill = null
        params = null
        keyboardTop = null
        drag = false
    }

    private fun tell(message: String) {
        dictation.notice = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
