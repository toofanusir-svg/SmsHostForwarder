package com.example.smsfrowarder

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup

/**
 * Language + theme settings screen.
 * Changes take effect immediately: theme is applied via AppCompatDelegate,
 * language requires recreating all activities (we restart MainActivity).
 */
class SettingsActivity : BaseActivity() {

    private lateinit var settings: AppSettings

    private lateinit var rgLanguage: RadioGroup
    private lateinit var rbEn:       RadioButton
    private lateinit var rbFa:       RadioButton

    private lateinit var rgTheme:    RadioGroup
    private lateinit var rbLight:    RadioButton
    private lateinit var rbDark:     RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        rgLanguage = findViewById(R.id.rgLanguage)
        rbEn       = findViewById(R.id.rbLangEn)
        rbFa       = findViewById(R.id.rbLangFa)
        rgTheme    = findViewById(R.id.rgTheme)
        rbLight    = findViewById(R.id.rbThemeLight)
        rbDark     = findViewById(R.id.rbThemeDark)

        // Reflect current settings
        if (settings.language == AppSettings.LANG_FA) rbFa.isChecked = true
        else rbEn.isChecked = true

        if (settings.theme == AppSettings.THEME_DARK) rbDark.isChecked = true
        else rbLight.isChecked = true

        findViewById<Button>(R.id.btnApplySettings).setOnClickListener { applyAndRestart() }
    }

    private fun applyAndRestart() {
        val newLang  = if (rbFa.isChecked) AppSettings.LANG_FA else AppSettings.LANG_EN
        val newTheme = if (rbDark.isChecked) AppSettings.THEME_DARK else AppSettings.THEME_LIGHT

        val langChanged  = newLang  != settings.language
        val themeChanged = newTheme != settings.theme

        settings.language = newLang
        settings.theme    = newTheme

        if (themeChanged) App.applyTheme(newTheme)

        if (langChanged || themeChanged) {
            // Restart from the root so all activities pick up the new locale/theme
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } else {
            finish()
        }
    }
}
