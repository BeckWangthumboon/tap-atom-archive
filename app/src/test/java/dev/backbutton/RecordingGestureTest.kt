package dev.backbutton

import org.junit.Assert.*
import org.junit.Test
import dev.backbutton.RecordingGesture.Action.*

class RecordingGestureTest {
    @Test fun shortPressTogglesOnlyOnceOnRelease() {
        val gesture = RecordingGesture()
        assertTrue(gesture.down(recording = false, enabled = true))
        assertEquals(TAP, gesture.release())
        assertEquals(NONE, gesture.release())
        assertEquals(NONE, gesture.hold())
    }

    @Test fun holdStartsOnceAndReleaseFinishesOnceWithoutATap() {
        val gesture = RecordingGesture()
        gesture.down(false, true)
        assertEquals(START_HOLD, gesture.hold())
        assertEquals(NONE, gesture.hold())
        assertEquals(FINISH_HOLD, gesture.release())
        assertEquals(NONE, gesture.release())
    }

    @Test fun pressingWhileAlreadyRecordingRemainsAStopTap() {
        val gesture = RecordingGesture()
        assertFalse(gesture.down(recording = true, enabled = true))
        assertEquals(NONE, gesture.hold())
        assertEquals(TAP, gesture.release())
    }

    @Test fun busyPressCannotStartWhenProcessingFinishesBeforeRelease() {
        val gesture = RecordingGesture()
        assertFalse(gesture.down(recording = false, enabled = false))
        assertEquals(NONE, gesture.hold())
        assertEquals(NONE, gesture.release())
        assertTrue(gesture.down(false, true))
    }

    @Test fun draggingBeforeTheThresholdDoesNotRecordOrToggle() {
        val gesture = RecordingGesture()
        gesture.down(false, true)
        assertEquals(NONE, gesture.cancel())
        assertEquals(NONE, gesture.hold())
        assertEquals(NONE, gesture.release())
    }

    @Test fun cancellingAHoldNeverFinishesOrTogglesOnRelease() {
        val gesture = RecordingGesture()
        gesture.down(false, true)
        gesture.hold()
        assertEquals(CANCEL_HOLD, gesture.cancel())
        assertEquals(NONE, gesture.cancel())
        assertEquals(NONE, gesture.release())
    }

    @Test fun failedHoldDoesNotRetryAsATap() {
        val gesture = RecordingGesture()
        gesture.down(false, true)
        gesture.hold()
        gesture.rejectHold()
        assertEquals(NONE, gesture.release())
    }

    @Test fun interruptionAndReconnectRequireAFreshPress() {
        val gesture = RecordingGesture()
        assertEquals(NONE, gesture.release()) // A baseline received while already held.
        gesture.down(false, true)
        gesture.hold()
        gesture.reset()
        assertEquals(NONE, gesture.release())
        assertTrue(gesture.down(false, true))
        assertEquals(TAP, gesture.release())
    }

    @Test fun duplicateDownDoesNotRestartTheGesture() {
        val gesture = RecordingGesture()
        assertTrue(gesture.down(false, true))
        assertFalse(gesture.down(false, true))
        assertEquals(START_HOLD, gesture.hold())
        assertFalse(gesture.down(false, true))
        assertEquals(FINISH_HOLD, gesture.release())
    }
}
