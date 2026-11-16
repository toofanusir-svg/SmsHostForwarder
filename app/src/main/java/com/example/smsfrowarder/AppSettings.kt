package com.example.smsfrowarder

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the user's language and theme preference.
 * Plain SharedPreferences is enough here — no secrets in this file.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        const val LANG_EN = "en"
        const val LANG_FA = "fa"

        const val THEME_LIGHT = "light"
        const val THEME_DARK  = "dark"

        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME    = "theme"
    }

    /** Current language code. Default: "en". */
    var language: String
        get()      = prefs.getString(KEY_LANGUAGE, LANG_EN) ?: LANG_EN
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** Current theme. Default: "light". */
    var theme: String
        get()      = prefs.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()
}
