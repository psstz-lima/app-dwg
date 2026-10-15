package com.appdwg.app

import android.content.Context

/**
 * Whether a language's resources are actually in this install.
 * Play used to deliver only the device language. A missing pack resolves [R.string.lang_probe]
 * to the default value, so that option stays disabled.
 */
internal object LanguagePacks {
    fun installed(resolved: String?, expected: String): Boolean = resolved == expected

    fun ready(context: Context, choice: LanguageChoice): Boolean {
        val tag = choice.tag ?: return true
        val expected = if (tag.startsWith("en")) "en" else "pt"
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(tag))
        val resolved = context.createConfigurationContext(config).getString(R.string.lang_probe)
        return installed(resolved, expected)
    }
}
