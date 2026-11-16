package com.example.smsfrowarder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    // Repository created once, eagerly, in onCreate — no lazy, no race condition.
    lateinit var rulesRepository: RulesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // Plain file I/O — safe on main thread (< 1 ms on first run)
        rulesRepository = RulesRepository.create(this)
        applyTheme(AppSettings(this).theme)
    }

    companion object {
        fun get(app: android.content.Context): App =
            app.applicationContext as App

        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(
                if (theme == AppSettings.THEME_DARK) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}
