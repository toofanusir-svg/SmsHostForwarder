package com.example.smsfrowarder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * - Applies the saved theme before any Activity starts.
 * - Pre-warms [RulesRepository] on an IO thread so the KeyStore is never
 *   touched on the main thread (avoids the crash on Start / Add Rule).
 */
class App : Application() {

    /** IO scope tied to the process lifetime. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lazily-initialised on IO thread; always access via [rulesRepository]. */
    @Volatile private var _rulesRepository: RulesRepository? = null

    /**
     * Returns the repository. Blocks the calling thread for at most a few ms
     * the very first time (KeyStore init). After that it is instant.
     * Never call from main thread during the first open — use the coroutine
     * helper [withRulesRepository] instead.
     */
    val rulesRepository: RulesRepository
        get() = _rulesRepository ?: synchronized(this) {
            _rulesRepository ?: RulesRepository.create(this).also { _rulesRepository = it }
        }

    override fun onCreate() {
        super.onCreate()
        applyTheme(AppSettings(this).theme)

        // Pre-warm on IO so the first Activity never waits on KeyStore
        appScope.launch {
            _rulesRepository = RulesRepository.create(this@App)
        }
    }

    companion object {
        fun applyTheme(theme: String) {
            val mode = when (theme) {
                AppSettings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else                   -> AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        fun get(context: android.content.Context): App =
            context.applicationContext as App
    }
}
