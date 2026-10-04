package dev.backbutton

/** Screen coordinates, independent of orientation and Android UI classes. */
data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class OverlayPosition(val x: Int, val y: Int)

object StatusPlacement {
    fun aboveKeyboard(
        screen: OverlayBounds,
        keyboard: OverlayBounds,
        width: Int,
        height: Int,
        gap: Int,
        margin: Int,
        composer: OverlayBounds? = null,
        composerGap: Int = margin,
    ): OverlayPosition? {
        val left = maxOf(screen.left, keyboard.left)
        val right = minOf(screen.right, keyboard.right)
        if (right <= left || width <= 0 || height <= 0 ||
            screen.right - screen.left < width + margin * 2) return null
        var y = keyboard.top - gap - height
        // Full-screen landscape editors can leave no space above the keyboard.
        if (y < screen.top + margin || y + height > screen.bottom - margin) return null
        val x = (left + (right - left - width) / 2)
            .coerceIn(screen.left + margin, screen.right - width - margin)
        if (composer != null && composer.right > x && composer.left < x + width &&
            composer.bottom > y && composer.top < y + height + composerGap) {
            val aboveComposer = composer.top - composerGap - height
            // Keep the established position when the editor leaves no safe room above it.
            if (aboveComposer >= screen.top + margin) y = minOf(y, aboveComposer)
        }
        return OverlayPosition(x, y)
    }
}
