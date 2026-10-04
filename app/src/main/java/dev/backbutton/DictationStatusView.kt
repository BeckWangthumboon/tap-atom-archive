package dev.backbutton

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** A passive indicator: no focus, clicks, dragging, or recording gestures. */
class DictationStatusView(context: Context) : View(context) {
    enum class Mode { RECORDING, TRANSCRIBING, SUCCESS, ERROR }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.configuration.fontScale * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val levels = FloatArray(9)
    private var mode = Mode.RECORDING
    private var label = ""
    private var phase = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = false
        isFocusable = false
    }

    fun desiredWidth(mode: Mode, label: String): Int = (
        if (mode == Mode.RECORDING || mode == Mode.TRANSCRIBING) 96f * density
        else textPaint.measureText(label) + 28 * density
    ).toInt()

    fun update(mode: Mode, label: String, level: Float) {
        if (this.mode != mode || this.label != label) {
            this.mode = mode
            this.label = label
            contentDescription = if (mode == Mode.RECORDING) "Recording" else label
            levels.fill(0f)
        }
        if (mode == Mode.RECORDING) {
            for (i in 0 until levels.lastIndex) levels[i] = levels[i + 1]
            levels[levels.lastIndex] = level.coerceIn(0f, 1f)
        }
        phase = (phase + 0.55f) % (Math.PI.toFloat() * 2f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(34, 34, 34)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        val centerY = height / 2f
        if (mode == Mode.RECORDING || mode == Mode.TRANSCRIBING) {
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            val spacing = 6f * density
            val start = width / 2f - spacing * levels.lastIndex / 2f
            levels.forEachIndexed { i, level ->
                // Keep processing visible without suggesting the microphone is still live.
                val amplitude = if (mode == Mode.TRANSCRIBING) {
                    0.2f + 0.6f * (kotlin.math.sin(phase - i * 0.7f) + 1f) / 2f
                } else level
                val half = (2f + amplitude * 7f) * density
                canvas.drawLine(start + i * spacing, centerY - half, start + i * spacing, centerY + half, paint)
            }
        } else {
            val start = 14f * density
            val text = TextUtils.ellipsize(label, textPaint, (width - start - 14f * density).coerceAtLeast(0f),
                TextUtils.TruncateAt.END)
            val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(text.toString(), start, baseline, textPaint)
        }
    }
}
