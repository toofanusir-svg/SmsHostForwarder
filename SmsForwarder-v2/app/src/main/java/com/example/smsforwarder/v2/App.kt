package com.example.smsforwarder.v2

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    lateinit var rulesRepository: RulesRepository
        private set
    lateinit var logRepository: LogRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        rulesRepository = RulesRepository.create(this)
        logRepository   = LogRepository.create(this)
        applyTheme(AppSettings(this).theme)
    }

    companion object {
        fun get(context: android.content.Context): App =
            context.applicationContext as App

        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(
                if (theme == AppSettings.THEME_DARK) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}
