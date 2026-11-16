package com.example.smsfrowarder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application entry point.
 * Applies the saved theme once at process start so every Activity
 * inherits the correct night-mode without a flicker.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme(AppSettings(this).theme)
    }

    companion object {
        fun applyTheme(theme: String) {
            val mode = when (theme) {
                AppSettings.THEME_DARK  -> AppCompatDelegate.MODE_NIGHT_YES
                else                    -> AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
