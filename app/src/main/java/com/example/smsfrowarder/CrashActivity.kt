package com.example.smsfrowarder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown after an uncaught crash. Displays the full stack trace so the user
 * can copy it and send it for debugging.
 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACE = "trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        val trace = intent.getStringExtra(EXTRA_TRACE)
            ?: runCatching {
                openFileInput("last_crash.txt").bufferedReader().readText()
            }.getOrElse { "No crash info available." }

        findViewById<TextView>(R.id.tvCrashTrace).text = trace

        findViewById<Button>(R.id.btnCopyCrash).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRestartApp).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
        }
    }
}
