package dev.backbutton

import android.Manifest
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in device check against the local browser fixture, without audio uploads or a Fish key.
 * Requires Chrome, accessibility access, and the fixture server/reverse described in README.
 * Reflection exercises the private Fish-result insertion path without adding a production test hook.
 */
@RunWith(AndroidJUnit4::class)
class ChromeInsertionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for the browser editor/accessibility connection")
    }

    private fun target(service: DictationAccessibilityService): Any? =
        service.javaClass.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(service)

    private fun insert(service: DictationAccessibilityService, target: Any, text: String) {
        service.javaClass.getDeclaredMethod("insert", target.javaClass, String::class.java)
            .apply { isAccessible = true }.invoke(service, target, text)
    }

    private fun text(service: DictationAccessibilityService): String? =
        service.inputMethod?.currentInputConnection?.getSurroundingText(2048, 2048, 0)?.text?.toString()

    private fun tap(cssY: Int) {
        // Fixture positions are CSS pixels; Chrome's viewport uses Android density on this page.
        val density = context.resources.displayMetrics.density
        val status = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: 0
        shell("input tap ${(100 * density).toInt()} ${status + ((56 + cssY) * density).toInt()}")
    }

    @Test fun insertionPreservesTextAndRejectsChangedTargets() {
        assertEquals("Pass -e browserFixture true to run this opt-in local fixture test",
            "true", InstrumentationRegistry.getArguments().getString("browserFixture"))
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Instrumentation force-stops the target process. Rebind only this already-enabled
        // service on the dedicated test device; never enable a service the user has not enabled.
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue("Enable Back Button dictation in Accessibility first", enabled.split(':').any {
            android.content.ComponentName.unflattenFromString(it)?.className == DictationAccessibilityService::class.java.name
        })
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabled.split(':').filterNot { android.content.ComponentName.unflattenFromString(it)?.className == DictationAccessibilityService::class.java.name }.joinToString(":"))
            SystemClock.sleep(200)
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled)
        } finally { automation.dropShellPermissionIdentity() }
        waitFor { DictationAccessibilityService.current != null }
        instrumentation.runOnMainSync { DictationService.enable(activity) }
        waitFor { context.dictation.crossAppEnabled }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:8765/insertion.html"))
            .setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1500)
        tap(124)
        waitFor { DictationAccessibilityService.current?.let { text(it) == "Meet me at noon." } == true }
        val service = DictationAccessibilityService.current!!
        try {
            instrumentation.runOnMainSync { service.inputMethod!!.currentInputConnection!!.setSelection(11, 15) }
            waitFor { service.inputMethod?.currentInputConnection?.getSurroundingText(2048, 2048, 0)?.let {
                it.selectionStart == 11 && it.selectionEnd == 15
            } == true }
            instrumentation.runOnMainSync { insert(service, target(service)!!, "tomorrow") }
            waitFor { text(service) == "Meet me at tomorrow." }

            lateinit var oldTarget: Any
            instrumentation.runOnMainSync { oldTarget = target(service)!! }
            instrumentation.runOnMainSync { service.inputMethod!!.currentInputConnection!!.setSelection(0, 0) }
            waitFor { service.inputMethod?.currentInputConnection?.getSurroundingText(2048, 2048, 0)?.selectionStart == 0 }
            instrumentation.runOnMainSync { insert(service, oldTarget, "must not appear") }
            instrumentation.runOnMainSync { assertEquals("Meet me at tomorrow.", text(service)) }

            instrumentation.runOnMainSync { oldTarget = target(service)!! }
            tap(224)
            waitFor { text(service) == "Other field" }
            instrumentation.runOnMainSync { insert(service, oldTarget, "must not appear") }
            instrumentation.runOnMainSync { assertEquals("Other field", text(service)) }

            instrumentation.runOnMainSync { service.toggle() }
            waitFor { context.dictation.audio.isRecording }
            SystemClock.sleep(1500)
            tap(124)
            waitFor { !context.dictation.audio.isRecording && text(service) == "Meet me at tomorrow." }
            instrumentation.runOnMainSync {
                assertTrue("Interrupted recording should retain a finalized clip", context.dictation.audio.hasRecording)
                assertFalse("Changing fields must not upload", context.dictation.transcription.isTranscribing)
                assertFalse(context.dictation.recordingInField)
            }

            tap(324)
            waitFor { service.inputMethod?.currentInputEditorInfo?.inputType?.let {
                it and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            } == true }
            instrumentation.runOnMainSync { assertNull("Password editors must not be captured", target(service)) }
        } finally {
            instrumentation.runOnMainSync { DictationService.disable(context) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
