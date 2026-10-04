package dev.backbutton

import android.text.InputType

/** Any app can expose an editor; input type, rather than an app allowlist, excludes secrets. */
object EditorEligibility {
    fun accepts(packageName: String?, ownPackage: String, inputType: Int): Boolean {
        if (packageName.isNullOrBlank() || packageName == ownPackage) return false
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (kind == InputType.TYPE_NULL) return false
        return when (kind) {
            InputType.TYPE_CLASS_TEXT -> variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> true
        }
    }
}
