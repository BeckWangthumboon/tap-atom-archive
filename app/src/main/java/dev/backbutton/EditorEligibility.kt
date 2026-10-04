package dev.backbutton

import android.text.InputType

/** Any app can expose an editor; input type, rather than an app allowlist, excludes secrets. */
object EditorEligibility {
    fun isSensitive(inputType: Int): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return kind == InputType.TYPE_CLASS_TEXT && variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ) || kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    fun accepts(packageName: String?, ownPackage: String, inputType: Int): Boolean {
        if (packageName.isNullOrBlank() || packageName == ownPackage) return false
        val kind = inputType and InputType.TYPE_MASK_CLASS
        if (kind == InputType.TYPE_NULL) return false
        return !isSensitive(inputType)
    }
}
