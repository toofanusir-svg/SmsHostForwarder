package com.example.smsdecoder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DecodeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decode)

        val prefs      = getSharedPreferences("decoder_prefs", MODE_PRIVATE)
        val etKey      = findViewById<EditText>(R.id.etDecodeKey)
        val etInput    = findViewById<EditText>(R.id.etEncodedInput)
        val tvResult   = findViewById<TextView>(R.id.tvDecodeResult)
        val tvStatus   = findViewById<TextView>(R.id.tvStatus)
        val btnDecode  = findViewById<Button>(R.id.btnDecode)
        val btnDismiss = findViewById<Button>(R.id.btnDismiss)
        val btnSaveKey = findViewById<Button>(R.id.btnSaveKey)

        // Restore saved key
        etKey.setText(prefs.getString("enc_key", ""))

        // Handle Share intent or direct text extra — works for all share modes
        val sharedText: String? = when {
            intent?.action == Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            intent?.action == Intent.ACTION_VIEW ->
                intent?.data?.toString()
            else ->
                intent?.getStringExtra("encoded_text")
        }
        sharedText?.let {
            etInput.setText(it.trim())
            tvStatus.text       = getString(R.string.status_input_received)
            tvStatus.visibility = View.VISIBLE
        }

        btnSaveKey.setOnClickListener {
            val key = etKey.text?.toString()?.trim() ?: ""
            prefs.edit().putString("enc_key", key).apply()
            Toast.makeText(this, getString(R.string.key_saved), Toast.LENGTH_SHORT).show()
        }

        btnDecode.setOnClickListener {
            val key   = etKey.text?.toString()?.trim() ?: ""
            val input = etInput.text?.toString()?.trim() ?: ""

            if (key.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_no_key), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (input.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_no_input), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val (success, result) = try {
                true to DecoderCrypto.extractAndDecrypt(input, key)
            } catch (e: Exception) {
                false to getString(R.string.decode_error, e.message ?: "Unknown error")
            }

            tvResult.text             = result
            tvResult.visibility       = View.VISIBLE
            tvStatus.visibility       = View.GONE
            btnDismiss.visibility     = View.VISIBLE

            // Visual feedback: green background on success, red on failure
            tvResult.setBackgroundColor(
                if (success)
                    android.graphics.Color.parseColor("#1A4CAF50")   // soft green
                else
                    android.graphics.Color.parseColor("#1AF44336")   // soft red
            )
        }

        btnDismiss.setOnClickListener {
            tvResult.visibility   = View.GONE
            tvStatus.visibility   = View.GONE
            btnDismiss.visibility = View.GONE
        }
    }
}
