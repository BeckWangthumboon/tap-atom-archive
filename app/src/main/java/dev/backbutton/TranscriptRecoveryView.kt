package dev.backbutton

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.ImageButton
import android.widget.LinearLayout

/** Small visible icons with full-sized touch targets; never takes keyboard focus. */
class TranscriptRecoveryView @JvmOverloads constructor(
    context: Context, copy: () -> Unit = {}, dismiss: () -> Unit = {},
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 34, 34) }

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        isFocusable = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        addAction(R.drawable.ic_close, "Dismiss saved transcript", dismiss)
        addAction(R.drawable.ic_copy, "Copy saved transcript", copy)
    }

    private fun addAction(icon: Int, description: String, action: () -> Unit) {
        addView(ImageButton(context).apply {
            setImageResource(icon)
            setBackgroundColor(Color.TRANSPARENT)
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)
            contentDescription = description
            setOnClickListener { action() }
        }, LayoutParams((48 * density).toInt(), (48 * density).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfHeight = 15f * density
        canvas.drawRoundRect(0f, height / 2f - halfHeight, width.toFloat(), height / 2f + halfHeight,
            halfHeight, halfHeight, paint)
    }
}
