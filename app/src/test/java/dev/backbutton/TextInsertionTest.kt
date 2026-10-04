package dev.backbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInsertionTest {
    @Test fun emptyFieldReceivesTranscript() {
        assertEquals(TextInsertion.Edit("Hello there.", 12), TextInsertion.plan("", 0, 0, "Hello there."))
    }

    @Test fun middleInsertionPreservesBothSides() {
        assertEquals(TextInsertion.Edit("Meet me tomorrow at noon.", 17), TextInsertion.plan("Meet me at noon.", 8, 8, "tomorrow"))
    }

    @Test fun selectionReplacementPreservesSurroundingText() {
        assertEquals(TextInsertion.Edit("Hello Beck!", 10), TextInsertion.plan("Hello world!", 6, 11, "Beck"))
        assertEquals(TextInsertion.Edit("Hello Beck!", 10), TextInsertion.plan("Hello world!", 11, 6, "Beck"))
    }

    @Test fun punctuationDoesNotGainAnExtraSpace() {
        assertEquals(TextInsertion.Edit("Hello, friend", 6), TextInsertion.plan("Hello friend", 5, 5, ","))
    }

    @Test fun emojiUsesAndroidUtf16SelectionOffsets() {
        assertEquals(TextInsertion.Edit("👋 hello", 8), TextInsertion.plan("👋 ", 3, 3, "hello"))
    }

    @Test fun invalidSelectionOrBlankSpeechCannotEraseText() {
        assertNull(TextInsertion.plan("Keep this", -1, 4, "hello"))
        assertNull(TextInsertion.plan("Keep this", 4, -1, "hello"))
        assertNull(TextInsertion.plan("Keep this", 0, 100, "hello"))
        assertNull(TextInsertion.plan("Keep this", 0, 9, "   "))
    }

    @Test fun typingOrMovingCursorInvalidatesPendingInsertion() {
        assertTrue(TextInsertion.unchanged("hello", 5, 5, "hello", 5, 5))
        assertFalse(TextInsertion.unchanged("hello", 5, 5, "hello!", 5, 5))
        assertFalse(TextInsertion.unchanged("hello", 5, 5, "hello", 0, 0))
        assertFalse(TextInsertion.unchanged("hello", 1, 3, "hello", 1, 4))
    }

    @Test fun inputConnectionReceivesOnlyReplacementWithNeededSpacing() {
        assertEquals("tomorrow ", TextInsertion.replacement("Meet me at noon.", 8, 8, "tomorrow"))
        assertEquals("Beck", TextInsertion.replacement("Hello world!", 6, 11, "Beck"))
        assertEquals("Beck", TextInsertion.replacement("Hello world!", 11, 6, "Beck"))
        assertEquals("", TextInsertion.replacement("", 0, 0, " ") ?: "")
    }
}
