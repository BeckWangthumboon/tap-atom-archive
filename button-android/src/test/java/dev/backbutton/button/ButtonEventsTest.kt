package dev.backbutton.button

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonEventsTest {
    private fun packet(pressed: Boolean, sequence: Long): ByteArray = byteArrayOf(
        1, if (pressed) 1 else 0,
        sequence.toByte(), (sequence shr 8).toByte(), (sequence shr 16).toByte(), (sequence shr 24).toByte(),
    )

    @Test fun heldButtonOnConnectDoesNotEmitAPress() {
        val events = ButtonEvents()
        assertTrue(events.baseline(packet(true, 7)))
        assertEquals(ButtonEvents.Action.IGNORE, events.accept(packet(true, 7)))
        assertEquals(ButtonEvents.Action.RELEASE, events.accept(packet(false, 8)))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 9)))
    }

    @Test fun duplicatesAndLatePacketsDoNotEmitExtraEdges() {
        val events = ButtonEvents()
        events.baseline(packet(false, 0))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 1)))
        assertEquals(ButtonEvents.Action.IGNORE, events.accept(packet(true, 1)))
        assertEquals(ButtonEvents.Action.RELEASE, events.accept(packet(false, 2)))
        assertEquals(ButtonEvents.Action.IGNORE, events.accept(packet(true, 1)))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 3)))
    }

    @Test fun missedEdgesRequireSafeStopThenAFreshPress() {
        val events = ButtonEvents()
        events.baseline(packet(false, 0))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 1)))
        assertEquals(ButtonEvents.Action.GAP, events.accept(packet(true, 3)))
        assertEquals(ButtonEvents.Action.RELEASE, events.accept(packet(false, 4)))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 5)))
    }

    @Test fun reconnectAndRebootEstablishNewBaseline() {
        val events = ButtonEvents()
        events.baseline(packet(true, 99))
        events.reset()
        assertEquals(ButtonEvents.Action.IGNORE, events.accept(packet(false, 0)))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 1)))
    }

    @Test fun sequenceWrapPreservesValidPresses() {
        val events = ButtonEvents()
        events.baseline(packet(false, 0xffffffffL))
        assertEquals(ButtonEvents.Action.PRESS, events.accept(packet(true, 0)))
        assertEquals(ButtonEvents.Action.RELEASE, events.accept(packet(false, 1)))
    }

    @Test fun malformedPacketsDoNotBecomePresses() {
        val events = ButtonEvents()
        assertFalse(events.baseline(byteArrayOf(1)))
        assertEquals(ButtonEvents.Action.INVALID, events.accept(byteArrayOf(1, 1)))
        assertEquals(ButtonEvents.Action.INVALID, events.accept(byteArrayOf(2, 1, 0, 0, 0, 0)))
        assertEquals(ButtonEvents.Action.INVALID, events.accept(byteArrayOf(1, 2, 0, 0, 0, 0)))
    }
}
