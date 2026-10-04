package dev.backbutton

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** A passive indicator: no focus, clicks, dragging, or recording gestures. */
class DictationStatusView(context: Context) : View(context) {
    enum class Mode { RECORDING, PROCESSING, RECOVERY, ERROR }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        paint.color = if (mode == Mode.ERROR) Color.rgb(157, 49, 49) else Color.rgb(34, 34, 34)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        val centerY = height / 2f
        if (mode == Mode.RECORDING || mode == Mode.PROCESSING) {
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            val spacing = 6f * density
            val start = width / 2f - spacing * levels.lastIndex / 2f
            levels.forEachIndexed { i, level ->
                // Keep processing visible without suggesting the microphone is still live.
                val amplitude = if (mode == Mode.PROCESSING) {
                    0.2f + 0.6f * (kotlin.math.sin(phase - i * 0.7f) + 1f) / 2f
                } else level
                val half = (2f + amplitude * 7f) * density
                canvas.drawLine(start + i * spacing, centerY - half, start + i * spacing, centerY + half, paint)
            }
        } else {
            // Shape and accessibility feedback make the error distinguishable without reading text.
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(width / 2f, centerY - 6f * density, width / 2f, centerY + density, paint)
            canvas.drawCircle(width / 2f, centerY + 6f * density, 1.4f * density, paint)
        }
    }
}
