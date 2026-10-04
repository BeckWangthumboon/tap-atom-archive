package dev.backbutton

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in checks on the dedicated fixture device. Synthetic transcription never uploads audio. */
@RunWith(AndroidJUnit4::class)
class ClientRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keyFile = File(context.filesDir, "fish-audio-key")
    private var automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for regression fixture state")
    }

    @Before fun dedicatedFixture() {
        assertEquals("Use an emulator or dedicated fixture device", "true",
            InstrumentationRegistry.getArguments().getString("nativeFixture"))
        assertFalse("Never run against a device with a real Fish key", keyFile.exists())
    }

    @Test fun grantsRemainAccurateDuringServiceSuppressionAndChannelBlocking() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertTrue("Enable text insertion on the fixture device first", AppPermissions.read(context).textInsertion)
            // Reproduce the temporary disconnect caused by ordinary UiAutomator inspection.
            automation = instrumentation.getUiAutomation(0)
            waitFor { !context.dictation.accessibilityConnected }
            onMain { assertTrue("The user's grant must survive a temporary disconnect", AppPermissions.read(context).textInsertion) }

            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(DictationService.CHANNEL,
                "Dictation ready", NotificationManager.IMPORTANCE_NONE))
            assertTrue("Runtime notification permission is granted", manager.areNotificationsEnabled())
            assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(DictationService.CHANNEL).importance)
            assertFalse("A blocked channel must not display Allowed", AppPermissions.read(context).notifications)
        } finally {
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            onMain { activity.finish() }
        }
    }

    @Test fun recordingErrorsCancelCaptureWithFeedbackAndIgnoreStaleErrors() {
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var session: DictationSession? = null
        var previous: MediaRecorder? = null
        try {
            onMain { session = DictationSession(context) }
            // Cover both hold-to-talk and tap-to-toggle session ownership.
            for (held in listOf(true, false)) {
                onMain {
                    val current = checkNotNull(session)
                    assertTrue(current.start(insert = { fail("Failed audio must not be delivered") },
                        heldBy = if (held) DictationSession.Input.BUTTON else null))
                    val active = current.audio.javaClass.getDeclaredField("recorder").apply { isAccessible = true }
                        .get(current.audio) as MediaRecorder
                    val failed = current.audio.javaClass.getDeclaredMethod("recordingFailed", MediaRecorder::class.java)
                        .apply { isAccessible = true }
                    previous?.let {
                        failed.invoke(current.audio, it)
                        assertTrue("An old recorder callback must not cancel new capture", current.audio.isRecording)
                    }
                    failed.invoke(current.audio, active)
                    previous = active
                    assertFalse(current.audio.isRecording)
                    assertFalse(current.audio.hasRecording)
                    assertNull(current.heldBy)
                    assertFalse(current.recordingInField)
                    assertEquals("Recording was interrupted. Please try again.", current.notice)
                    assertEquals(current.notice, current.statusMessage)
                    current.release(DictationSession.Input.BUTTON)
                    assertFalse("A stale release must not upload or restart capture", current.transcription.isTranscribing)
                    assertFalse(current.audio.isRecording)
                }
            }
        } finally {
            onMain { session?.interrupt("Fixture cleanup"); activity.finish() }
        }
    }

    @Test fun successfulTranscriptIsDeliveredWhenPersistenceFails() {
        val transcriptFile = File(context.filesDir, "latest-transcript.txt")
        val original = transcriptFile.takeIf { it.isFile }?.readBytes()
        var delivered: String? = null
        var failure: String? = null
        lateinit var session: TranscriptionSession
        try {
            assertTrue(!transcriptFile.exists() || transcriptFile.delete())
            assertTrue(transcriptFile.mkdir()) // Forces the atomic save/rename to fail.
            keyFile.writeText("fixture-key-never-used")
            onMain {
                session = TranscriptionSession(context) { _, _ -> "A usable synthetic transcript." }
                session.transcribe(onResult = { delivered = it }, onError = { failure = it })
            }
            waitFor { !session.isTranscribing }
            onMain {
                assertEquals("A usable synthetic transcript.", delivered)
                assertEquals(delivered, session.transcript)
                assertFalse(session.transcriptDismissed)
                assertNull(failure)
                assertNull(session.error)
                assertNotNull("Explain that the transcript is only available in memory", session.persistenceWarning)
                assertTrue(transcriptFile.isDirectory)
                assertFalse(File(context.filesDir, "latest-transcript.pending").exists())
                session.prepareRecording()
                assertEquals("A later capture must preserve the unsaved result", delivered, session.transcript)
            }
        } finally {
            keyFile.delete()
            transcriptFile.delete()
            original?.let { transcriptFile.writeBytes(it) }
        }
    }
}
