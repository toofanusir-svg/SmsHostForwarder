package com.example.smsfrowarder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    /**
     * Single repository instance for the whole process.
     * RulesRepository now uses plain file I/O — no KeyStore, no main-thread risk.
     * Lazy initialisation is safe from any thread.
     */
    val rulesRepository: RulesRepository by lazy {
        RulesRepository.create(this)
    }

    override fun onCreate() {
        super.onCreate()
        applyTheme(AppSettings(this).theme)
    }

    companion object {
        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(
                if (theme == AppSettings.THEME_DARK) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        fun get(context: android.content.Context): App =
            context.applicationContext as App
    }
}
