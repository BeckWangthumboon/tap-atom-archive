package dev.backbutton

import org.junit.Assert.*
import org.junit.Test

class StatusPlacementTest {
    @Test fun portraitTracksKeyboardHeight() {
        val screen = OverlayBounds(0, 0, 1080, 2340)
        assertEquals(OverlayPosition(405, 1434),
            StatusPlacement.aboveKeyboard(screen, OverlayBounds(0, 1540, 1080, 2340), 270, 90, 16, 24))
        assertEquals(OverlayPosition(405, 1194),
            StatusPlacement.aboveKeyboard(screen, OverlayBounds(0, 1300, 1080, 2340), 270, 90, 16, 24))
    }

    @Test fun landscapeUsesNewScreenAndKeyboardBounds() {
        assertEquals(OverlayPosition(1035, 514),
            StatusPlacement.aboveKeyboard(OverlayBounds(0, 0, 2340, 1080),
                OverlayBounds(0, 620, 2340, 1080), 270, 90, 16, 24))
    }

    @Test fun floatingKeyboardCentersOnKeyboardAndClampsToScreen() {
        assertEquals(OverlayPosition(674, 1194),
            StatusPlacement.aboveKeyboard(OverlayBounds(0, 0, 1080, 2340),
                OverlayBounds(860, 1300, 1080, 2000), 382, 90, 16, 24))
    }

    @Test fun splitScreenHonorsNonzeroOrigin() {
        assertEquals(OverlayPosition(855, 1414),
            StatusPlacement.aboveKeyboard(OverlayBounds(600, 500, 1380, 2000),
                OverlayBounds(600, 1520, 1380, 2000), 270, 90, 16, 24))
    }

    @Test fun hidesWhenThereIsNoRoomInsteadOfCoveringKeyboard() {
        assertNull(StatusPlacement.aboveKeyboard(OverlayBounds(0, 0, 2340, 1080),
            OverlayBounds(0, 100, 2340, 1080), 270, 90, 16, 24))
        assertNull(StatusPlacement.aboveKeyboard(OverlayBounds(0, 0, 280, 1080),
            OverlayBounds(0, 620, 280, 1080), 270, 90, 16, 24))
    }

    @Test fun movesAboveGrowingComposerWithClearance() {
        val screen = OverlayBounds(0, 0, 400, 900)
        val keyboard = OverlayBounds(0, 600, 400, 900)
        assertEquals(OverlayPosition(152, 474),
            StatusPlacement.aboveKeyboard(screen, keyboard, 96, 30, 96, 8,
                composer = OverlayBounds(20, 540, 380, 600), composerGap = 12))
        assertEquals(OverlayPosition(152, 398),
            StatusPlacement.aboveKeyboard(screen, keyboard, 96, 30, 96, 8,
                composer = OverlayBounds(20, 440, 380, 600), composerGap = 12))
    }

    @Test fun preservesPositionWhenComposerDoesNotOverlap() {
        assertEquals(OverlayPosition(152, 474),
            StatusPlacement.aboveKeyboard(OverlayBounds(0, 0, 400, 900),
                OverlayBounds(0, 600, 400, 900), 96, 30, 96, 8,
                composer = OverlayBounds(0, 440, 140, 600), composerGap = 12))
    }

    @Test fun fallsBackWhenThereIsNoRoomAboveComposer() {
        assertEquals(OverlayPosition(152, 474),
            StatusPlacement.aboveKeyboard(OverlayBounds(0, 0, 400, 900),
                OverlayBounds(0, 600, 400, 900), 96, 30, 96, 8,
                composer = OverlayBounds(20, 20, 380, 600), composerGap = 12))
    }
}
