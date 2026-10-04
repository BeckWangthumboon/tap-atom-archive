package dev.backbutton

import android.Manifest
import android.app.UiAutomation
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import java.io.File
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
        fail("Timed out waiting for browser/recording state: enabled=${context.dictation.crossAppEnabled}, " +
            "notice=${context.dictation.notice}, audio=${context.dictation.audio.message}")
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

    private fun controlPosition(service: DictationAccessibilityService): Pair<Float, Float> {
        var result: Pair<Float, Float>? = null
        instrumentation.runOnMainSync {
            val params = service.javaClass.getDeclaredField("params").apply { isAccessible = true }.get(service)
                as WindowManager.LayoutParams
            result = Pair(params.x + params.width / 2f, params.y + params.height / 2f)
        }
        return result!!
    }

    private fun waitForControl(service: DictationAccessibilityService) {
        waitFor { service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) != null }
        // WindowManager layout and rendering follow the service state on a later frame.
        SystemClock.sleep(250)
    }

    private fun touch(action: Int, position: Pair<Float, Float>, downAt: Long) {
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, position.first, position.second, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun waitForIdle(service: DictationAccessibilityService) {
        waitFor { service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) == null }
    }

    private fun holdChecks(service: DictationAccessibilityService) {
        waitForIdle(service)
        // Exercise the exact PRESS/RELEASE path used by the Bluetooth button.
        instrumentation.runOnMainSync { context.dictation.press(DictationSession.Input.BUTTON) }
        waitFor { context.dictation.heldBy == DictationSession.Input.BUTTON && context.dictation.audio.isRecording }
        waitForControl(service)
        instrumentation.runOnMainSync {
            val params = service.javaClass.getDeclaredField("params").apply { isAccessible = true }.get(service)
                as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            val pill = service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) as android.view.View
            assertFalse(pill.isClickable)
            assertFalse(pill.isFocusable)
        }
        SystemClock.sleep(1500)
        instrumentation.runOnMainSync { context.dictation.release(DictationSession.Input.BUTTON) }
        waitFor { !context.dictation.audio.isRecording }
        instrumentation.runOnMainSync {
            assertNull(context.dictation.heldBy)
            assertEquals("Fish Audio key is not configured yet.", context.dictation.transcription.error)
        }
        waitForControl(service) // Brief failure feedback, then the idle surface disappears.
        waitFor {
            (service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) as? android.view.View)
                ?.contentDescription?.toString()?.startsWith("Dictation error.") == true
        }
        SystemClock.sleep(100) // Capture the error frame after the drawing update.
        capture("status-error.png")
        waitForIdle(service)

        // Short physical-button clicks still latch recording until the next click.
        instrumentation.runOnMainSync {
            context.dictation.press(DictationSession.Input.BUTTON)
            context.dictation.release(DictationSession.Input.BUTTON)
        }
        waitFor { context.dictation.audio.isRecording }
        waitForControl(service)
        val position = controlPosition(service)
        val downAt = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, position, downAt)
        touch(MotionEvent.ACTION_UP, position, downAt)
        SystemClock.sleep(300)
        instrumentation.runOnMainSync { assertTrue("Touching status must not stop recording", context.dictation.audio.isRecording) }
        SystemClock.sleep(1200)
        instrumentation.runOnMainSync {
            context.dictation.press(DictationSession.Input.BUTTON)
            context.dictation.release(DictationSession.Input.BUTTON)
        }
        waitFor { !context.dictation.audio.isRecording }
        waitForIdle(service)

        // Cancelling a physical hold keeps the clip and never uploads it.
        instrumentation.runOnMainSync { context.dictation.press(DictationSession.Input.BUTTON) }
        waitFor { context.dictation.heldBy == DictationSession.Input.BUTTON }
        SystemClock.sleep(1500)
        instrumentation.runOnMainSync { context.dictation.cancelPress(DictationSession.Input.BUTTON) }
        waitFor { !context.dictation.audio.isRecording }
        instrumentation.runOnMainSync {
            assertTrue(context.dictation.audio.hasRecording)
            assertNull(context.dictation.transcription.error)
        }
        waitForIdle(service)
    }

    private fun nativeChecks(service: DictationAccessibilityService) {
        fun open(field: String) {
            context.startActivity(Intent().setComponent(ComponentName("dev.backbutton.test", NativeEditorActivity::class.java.name))
                .putExtra("field", field).putExtra("reset", field == "message").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        open("message")
        waitFor { service.inputMethod?.currentInputEditorInfo?.packageName == "dev.backbutton.test" && text(service) == "Meet me at noon." }
        waitForIdle(service) // Idle editors have no on-screen recording control.
        instrumentation.runOnMainSync { service.inputMethod!!.currentInputConnection!!.setSelection(8, 8) }
        waitFor { service.inputMethod?.currentInputConnection?.getSurroundingText(2048, 2048, 0)?.selectionStart == 8 }
        lateinit var original: Any
        instrumentation.runOnMainSync {
            original = target(service)!!
            insert(service, original, "tomorrow")
        }
        waitFor { text(service) == "Meet me tomorrow at noon." }
        waitForIdle(service) // Successful insertion has no confirmation flash.

        open("other")
        waitFor { text(service) == "Other field" }
        waitForIdle(service)
        instrumentation.runOnMainSync {
            insert(service, original, "must not appear")
            assertEquals("Other field", text(service))
        }
        waitForControl(service)
        instrumentation.runOnMainSync {
            val params = service.javaClass.getDeclaredField("params").apply { isAccessible = true }.get(service)
                as WindowManager.LayoutParams
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals("must not appear", context.dictation.recoveryTranscript)
        }
        SystemClock.sleep(3800)
        waitForControl(service) // Recovery must not vanish with the error timeout.
        capture("status-recovery.png")
        val position = controlPosition(service)
        val density = context.resources.displayMetrics.density
        val downAt = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, Pair(position.first - 24 * density, position.second), downAt)
        touch(MotionEvent.ACTION_UP, Pair(position.first - 24 * density, position.second), downAt)
        waitForIdle(service)
        instrumentation.runOnMainSync {
            assertNull(context.dictation.recoveryTranscript)
            assertEquals("A saved transcript for the recovery check.", context.dictation.transcription.transcript)
            assertEquals("dev.backbutton.test", service.inputMethod?.currentInputEditorInfo?.packageName)
        }
        instrumentation.runOnMainSync {
            context.dictation.showRecovery("A clipboard recovery fixture.", "Fixture insertion could not finish.")
        }
        waitForControl(service)
        val copyPosition = controlPosition(service)
        val copyDownAt = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, Pair(copyPosition.first + 24 * density, copyPosition.second), copyDownAt)
        touch(MotionEvent.ACTION_UP, Pair(copyPosition.first + 24 * density, copyPosition.second), copyDownAt)
        waitForIdle(service)
        // Read the clipboard with tap foreground, matching normal Android clipboard access.
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(500)
        instrumentation.runOnMainSync {
            assertNull(context.dictation.recoveryTranscript)
            assertEquals("A clipboard recovery fixture.", context.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString())
        }
        for (field in listOf("password", "pin")) {
            open(field)
            waitFor { service.inputMethod?.currentInputEditorInfo?.fieldId == if (field == "password") 3 else 4 }
            instrumentation.runOnMainSync { assertNull("$field fields must not be captured", target(service)) }
            waitFor { service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) == null }
        }
    }

    private fun capture(name: String) {
        val bitmap = automation.takeScreenshot()
        assertNotNull(bitmap)
        File(context.filesDir, name).outputStream().use {
            bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap!!.recycle()
    }

    @Test fun recordAnywhereKeepsCaptureAcrossInputChangesAndOffersRecovery() {
        assertEquals("Use the dedicated native fixture device", "true",
            InstrumentationRegistry.getArguments().getString("nativeFixture"))
        assertFalse("This fixture must never upload audio", File(context.filesDir, "fish-audio-key").exists())
        File(context.filesDir, "latest-transcript.txt").writeText("A saved transcript for the recovery check.")
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue(enabled.contains("DictationAccessibilityService"))
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabled.split(':').filterNot { it.contains("DictationAccessibilityService") }.joinToString(":"))
            SystemClock.sleep(200)
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled)
        } finally { automation.dropShellPermissionIdentity() }
        waitFor { DictationAccessibilityService.current != null }
        instrumentation.runOnMainSync { DictationService.enable(activity) }
        waitFor { context.dictation.crossAppEnabled }
        val service = DictationAccessibilityService.current!!
        fun home() = context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun open(field: String) = context.startActivity(Intent().setComponent(
            ComponentName("dev.backbutton.test", NativeEditorActivity::class.java.name))
            .putExtra("field", field).putExtra("reset", field == "message").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        @Suppress("UNCHECKED_CAST")
        fun completion() = context.dictation.javaClass.getDeclaredField("delivery").apply { isAccessible = true }
            .get(context.dictation) as (String) -> Unit
        try {
            home()
            waitFor { target(service) == null &&
                service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service) == null }
            waitForIdle(service)
            instrumentation.runOnMainSync { context.dictation.press(DictationSession.Input.BUTTON) }
            waitFor { context.dictation.audio.isRecording && context.dictation.heldBy == DictationSession.Input.BUTTON }
            waitForControl(service)
            lateinit var noFieldCompletion: (String) -> Unit
            instrumentation.runOnMainSync { noFieldCompletion = completion() }
            capture("anywhere-no-keyboard.png")
            open("message")
            waitFor { text(service) == "Meet me at noon." }
            open("other")
            waitFor { text(service) == "Other field" }
            SystemClock.sleep(1200)
            instrumentation.runOnMainSync {
                assertTrue("Input restarts must not stop capture", context.dictation.audio.isRecording)
                assertEquals(DictationSession.Input.BUTTON, context.dictation.heldBy)
            }
            home()
            waitFor { target(service) == null }
            instrumentation.runOnMainSync {
                assertTrue("App switches must not stop capture", context.dictation.audio.isRecording)
                context.dictation.release(DictationSession.Input.BUTTON)
                assertTrue(context.dictation.audio.hasRecording)
                assertFalse(context.dictation.audio.isRecording)
                // Inject a successful provider completion through the actual captured callback.
                // The fixture has no API key and performs no network request.
                noFieldCompletion("A standalone dictation fixture.")
            }
            waitFor { service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) is TranscriptRecoveryView &&
                service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service) == null }
            waitForControl(service)
            instrumentation.runOnMainSync { assertEquals("A standalone dictation fixture.", context.dictation.recoveryTranscript) }
            capture("anywhere-recovery-no-keyboard.png")
            instrumentation.runOnMainSync { context.dictation.dismissRecovery() }
            waitForIdle(service)

            open("message")
            waitFor { text(service) == "Meet me at noon." && target(service) != null }
            instrumentation.runOnMainSync { context.dictation.press(DictationSession.Input.BUTTON); context.dictation.release(DictationSession.Input.BUTTON) }
            waitFor { context.dictation.audio.isRecording }
            lateinit var changedFieldCompletion: (String) -> Unit
            instrumentation.runOnMainSync { changedFieldCompletion = completion() }
            open("other")
            waitFor { text(service) == "Other field" }
            SystemClock.sleep(1200)
            instrumentation.runOnMainSync {
                assertTrue(context.dictation.audio.isRecording)
                context.dictation.press(DictationSession.Input.BUTTON)
                context.dictation.release(DictationSession.Input.BUTTON)
                assertFalse(context.dictation.audio.isRecording)
                changedFieldCompletion("Must be copied, not inserted.")
                assertEquals("Other field", text(service))
                assertEquals("Must be copied, not inserted.", context.dictation.recoveryTranscript)
                context.dictation.dismissRecovery()
            }
            waitForIdle(service)
            nativeChecks(service) // Verify direct insertion, × persistence, Copy, and protected fields.
            instrumentation.runOnMainSync {
                context.dictation.press(DictationSession.Input.BUTTON)
                context.dictation.release(DictationSession.Input.BUTTON)
                assertFalse("PIN fields must still block capture", context.dictation.audio.isRecording)
            }
            open("message")
            waitFor { target(service) != null }
            instrumentation.runOnMainSync { context.dictation.press(DictationSession.Input.BUTTON); context.dictation.release(DictationSession.Input.BUTTON) }
            waitFor { context.dictation.audio.isRecording }
            open("password")
            waitFor { !context.dictation.audio.isRecording }
            instrumentation.runOnMainSync { assertFalse(context.dictation.transcription.isTranscribing) }
        } catch (failure: Throwable) {
            capture("anywhere-failure.png")
            throw failure
        } finally {
            instrumentation.runOnMainSync { context.dictation.interrupt("Fixture cleanup"); DictationService.disable(context); activity.finish() }
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @Test fun nativeStatusIsPassiveAnchoredAndHiddenWhileIdle() {
        assertEquals("Pass -e nativeFixture true on an emulator or dedicated test device",
            "true", InstrumentationRegistry.getArguments().getString("nativeFixture"))
        val keyFile = File(context.filesDir, "fish-audio-key")
        assertFalse("Never run this test on a device with a Fish key", keyFile.exists())
        // Recovery survives dismissal and a failed/new recording.
        val saved = File(context.filesDir, "latest-transcript.txt")
        saved.writeText("A saved transcript for the recovery check.")
        instrumentation.runOnMainSync {
            val transcription = TranscriptionSession(context)
            assertTrue(transcription.saveApiKey("fixture-key-never-used"))
            assertTrue(transcription.hasApiKey)
            keyFile.delete()
            transcription.refreshKeyStatus()
            assertFalse(transcription.hasApiKey)
            transcription.dismissTranscript()
            val restored = TranscriptionSession(context)
            assertTrue(restored.transcriptDismissed)
            assertEquals("A saved transcript for the recovery check.", restored.transcript)
            restored.revealTranscript()
            restored.prepareRecording()
            assertFalse(restored.transcriptDismissed)
            assertEquals("A saved transcript for the recovery check.", saved.readText())
        }
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue("Enable the service on the dedicated fixture device first", enabled.contains("DictationAccessibilityService"))
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabled.split(':').filterNot { it.contains("DictationAccessibilityService") }.joinToString(":"))
            SystemClock.sleep(200)
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled)
        } finally { automation.dropShellPermissionIdentity() }
        waitFor { DictationAccessibilityService.current != null }
        instrumentation.runOnMainSync { DictationService.enable(activity) }
        waitFor { context.dictation.crossAppEnabled }
        val service = DictationAccessibilityService.current!!
        try {
            // This experimental build allows recording even from settings.
            instrumentation.runOnMainSync {
                context.dictation.press(DictationSession.Input.BUTTON)
                context.dictation.release(DictationSession.Input.BUTTON)
                assertTrue(context.dictation.audio.isRecording)
                context.dictation.interrupt("Fixture setup recording cancelled without uploading.")
            }
            waitForIdle(service)
            for ((rotation, name) in listOf(UiAutomation.ROTATION_FREEZE_0 to "status-portrait.png",
                UiAutomation.ROTATION_FREEZE_90 to "status-landscape.png")) {
                assertTrue(automation.setRotation(rotation))
                context.startActivity(Intent().setComponent(ComponentName("dev.backbutton.test", NativeEditorActivity::class.java.name))
                    .putExtra("field", "message").putExtra("reset", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                // Rotation can dismiss the IME after an early showSoftInput request.
                // Reopen it with a real tap after the new native window has settled.
                SystemClock.sleep(1000)
                val field = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Meet me at noon.")
                    .first { it.className == "android.widget.EditText" }
                val fieldBounds = android.graphics.Rect()
                field.getBoundsInScreen(fieldBounds)
                shell("input tap " + fieldBounds.centerX() + " " + fieldBounds.centerY())
                waitForIdle(service)
                waitFor { service.inputMethod?.currentInputEditorInfo?.packageName == "dev.backbutton.test" &&
                    target(service) != null &&
                    service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service) != null }
                SystemClock.sleep(1000) // Let the keyboard animation/input restart settle before the physical click.
                instrumentation.runOnMainSync {
                    assertSame("Service changed across rotation", service, DictationAccessibilityService.current)
                    assertNotNull("Editor stopped across rotation", target(service))
                    assertNotNull("Keyboard disappeared across rotation",
                        service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service))
                    context.dictation.press(DictationSession.Input.BUTTON)
                    context.dictation.release(DictationSession.Input.BUTTON)
                    assertTrue("Physical click did not start capture: " + context.dictation.notice, context.dictation.audio.isRecording)
                }
                waitFor { context.dictation.audio.isRecording }
                var roomAboveKeyboard = false
                instrumentation.runOnMainSync {
                    val keyboard = service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service)
                        as android.graphics.Rect
                    val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
                    val safe = metrics.windowInsets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    val density = context.resources.displayMetrics.density
                    roomAboveKeyboard = keyboard.top - (126 * density).toInt() >= metrics.bounds.top + safe.top + (8 * density).toInt()
                }
                if (!roomAboveKeyboard) {
                    waitForControl(service) // Tight landscape layouts use the top-edge fallback.
                    capture(name)
                    instrumentation.runOnMainSync { context.dictation.interrupt("Fixture recording cancelled without uploading.") }
                    waitFor { !context.dictation.audio.isRecording }
                    waitForIdle(service)
                    continue
                }
                waitForControl(service)
                instrumentation.runOnMainSync {
                    val params = service.javaClass.getDeclaredField("params").apply { isAccessible = true }.get(service)
                        as WindowManager.LayoutParams
                    val keyboard = service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service)
                        as android.graphics.Rect
                    val density = context.resources.displayMetrics.density
                    assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                    assertEquals(keyboard.top - (96 * density).toInt() - params.height, params.y)
                    assertEquals(keyboard.exactCenterX(), params.x + params.width / 2f, density)
                }
                SystemClock.sleep(200) // The overlay must be drawn, not just attached.
                instrumentation.runOnMainSync {
                    assertTrue(context.dictation.audio.isRecording)
                    val view = service.javaClass.getDeclaredField("pill").apply { isAccessible = true }.get(service) as android.view.View
                    val params = service.javaClass.getDeclaredField("params").apply { isAccessible = true }.get(service)
                        as WindowManager.LayoutParams
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    assertEquals(params.x, location[0])
                    assertEquals(params.y, location[1])
                }
                capture(name)
                instrumentation.runOnMainSync { context.dictation.interrupt("Fixture recording cancelled without uploading.") }
                waitFor { !context.dictation.audio.isRecording }
                waitForIdle(service)
            }
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            context.startActivity(Intent().setComponent(ComponentName("dev.backbutton.test", NativeEditorActivity::class.java.name))
                .putExtra("field", "message").putExtra("reset", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            SystemClock.sleep(1000)
            val field = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Meet me at noon.")
                .first { it.className == "android.widget.EditText" }
            val fieldBounds = android.graphics.Rect()
            field.getBoundsInScreen(fieldBounds)
            shell("input tap " + fieldBounds.centerX() + " " + fieldBounds.centerY())
            waitFor { text(service) == "Meet me at noon." && target(service) != null &&
                service.javaClass.getDeclaredMethod("keyboardBounds").apply { isAccessible = true }.invoke(service) != null }
            SystemClock.sleep(1000)
            holdChecks(service)
            nativeChecks(service)
            instrumentation.runOnMainSync {
                assertEquals("A saved transcript for the recovery check.", context.dictation.transcription.transcript)
            }
        } catch (failure: Throwable) {
            capture("status-failure.png")
            throw failure
        } finally {
            instrumentation.runOnMainSync { context.dictation.interrupt("Fixture cleanup"); DictationService.disable(context); activity.finish() }
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @Test fun insertionPreservesTextAndRejectsChangedTargets() {
        assertEquals("Pass -e browserFixture true to run this opt-in local fixture test",
            "true", InstrumentationRegistry.getArguments().getString("browserFixture"))
        assertFalse("Use a dedicated device without a Fish key; this test must never upload audio",
            File(context.filesDir, "fish-audio-key").exists())
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Instrumentation force-stops the target process. Rebind only this already-enabled
        // service on the dedicated test device; never enable a service the user has not enabled.
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue("Enable tap dictation in Accessibility first", enabled.split(':').any {
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

            instrumentation.runOnMainSync { context.dictation.dismissRecovery() }

            waitForIdle(service)
            instrumentation.runOnMainSync { service.toggle() }
            waitFor { context.dictation.audio.isRecording }
            SystemClock.sleep(1500)
            tap(124)
            waitFor { text(service) == "Meet me at tomorrow." }
            instrumentation.runOnMainSync {
                assertTrue("Changing ordinary fields must keep capture running", context.dictation.audio.isRecording)
                context.dictation.interrupt("Fixture cancellation without uploading.")
                assertTrue("Interrupted recording should retain a finalized clip", context.dictation.audio.hasRecording)
                assertFalse("Cancelling must not upload", context.dictation.transcription.isTranscribing)
                assertFalse(context.dictation.recordingInField)
            }

            tap(324)
            waitFor { service.inputMethod?.currentInputEditorInfo?.inputType?.let {
                it and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            } == true }
            instrumentation.runOnMainSync { assertNull("Password editors must not be captured", target(service)) }

            tap(124)
            waitFor { text(service) == "Meet me at tomorrow." }
            waitForIdle(service)
            holdChecks(service)
            nativeChecks(service)
        } finally {
            instrumentation.runOnMainSync { DictationService.disable(context) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
