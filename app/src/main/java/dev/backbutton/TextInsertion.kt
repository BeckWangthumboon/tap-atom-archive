package dev.backbutton

/** Android selection offsets refer to UTF-16, matching Kotlin String indices. */
object TextInsertion {
    data class Edit(val text: String, val cursor: Int)

    fun plan(original: String, start: Int, end: Int, spoken: String): Edit? {
        if (start !in 0..original.length || end !in 0..original.length || spoken.isBlank()) return null
        val before = original.substring(0, minOf(start, end))
        val after = original.substring(maxOf(start, end))
        val words = spoken.trim()
        val prefix = if (before.lastOrNull()?.isLetterOrDigit() == true && words.first().isLetterOrDigit()) " " else ""
        val suffix = if (after.firstOrNull()?.isLetterOrDigit() == true && words.last().isLetterOrDigit()) " " else ""
        val inserted = prefix + words + suffix
        return Edit(before + inserted + after, before.length + inserted.length)
    }

    fun unchanged(original: String, start: Int, end: Int, current: String, currentStart: Int, currentEnd: Int): Boolean =
        original == current && start == currentStart && end == currentEnd

    fun replacement(original: String, start: Int, end: Int, spoken: String): String? {
        val edit = plan(original, start, end, spoken) ?: return null
        return edit.text.substring(minOf(start, end), edit.cursor)
    }
}
