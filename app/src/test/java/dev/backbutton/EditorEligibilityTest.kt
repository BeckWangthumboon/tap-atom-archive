package dev.backbutton

import android.text.InputType.*
import org.junit.Assert.*
import org.junit.Test

class EditorEligibilityTest {
    private fun accepts(type: Int, app: String? = "any.notes.app") =
        EditorEligibility.accepts(app, "dev.backbutton", type)

    @Test fun ordinaryFieldsWorkRegardlessOfAppName() {
        for (app in listOf("any.notes.app", "com.android.settings", "com.whatsapp", "dev.backbutton.test")) {
            assertTrue(accepts(TYPE_CLASS_TEXT, app))
            assertTrue(accepts(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_MULTI_LINE, app))
        }
    }

    @Test fun passwordVariationsStayExcludedWithAdditionalFlags() {
        for (variation in listOf(TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            assertFalse(accepts(TYPE_CLASS_TEXT or variation))
            assertFalse(accepts(TYPE_CLASS_TEXT or variation or TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        }
    }

    @Test fun numericPinsAreExcludedButOrdinaryNumbersAreAllowed() {
        assertFalse(accepts(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(accepts(TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD or TYPE_NUMBER_FLAG_SIGNED))
        assertTrue(accepts(TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_DECIMAL))
    }

    @Test fun emailAndOtherKeyboardEditorsAreAllowed() {
        assertTrue(accepts(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(accepts(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI))
        assertTrue(accepts(TYPE_CLASS_PHONE))
    }

    @Test fun missingEditorIdentityAndOurSetupAppAreExcluded() {
        assertFalse(accepts(TYPE_CLASS_TEXT, null))
        assertFalse(accepts(TYPE_CLASS_TEXT, ""))
        assertFalse(accepts(TYPE_CLASS_TEXT, "dev.backbutton"))
        assertFalse(accepts(TYPE_NULL))
    }
}
