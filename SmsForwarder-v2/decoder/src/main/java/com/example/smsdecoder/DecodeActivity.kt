package com.example.smsdecoder

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class DecodeActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var etKey: TextInputEditText
    private lateinit var etInput: TextInputEditText
    private lateinit var tvResult: TextView
    private lateinit var tvStatus: TextView
    private lateinit var layoutBottom: LinearLayout
    private lateinit var swAutoDecode: SwitchMaterial

    private var suppressWatch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decode)

        prefs     = getSharedPreferences("decoder_prefs", MODE_PRIVATE)
        etKey     = findViewById(R.id.etDecodeKey)
        etInput   = findViewById(R.id.etEncodedInput)
        tvResult  = findViewById(R.id.tvDecodeResult)
        tvStatus  = findViewById(R.id.tvStatus)
        layoutBottom = findViewById(R.id.layoutBottomBtns)
        swAutoDecode = findViewById(R.id.swAutoDecode)

        // Restore settings
        etKey.setText(prefs.getString("enc_key", ""))
        swAutoDecode.isChecked = prefs.getBoolean("auto_decode", false)

        // Persist auto-decode toggle
        swAutoDecode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_decode", checked).apply()
        }

        // Save key
        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = etKey.text?.toString()?.trim() ?: ""
            if (key.isEmpty()) { toast(getString(R.string.error_no_key)); return@setOnClickListener }
            prefs.edit().putString("enc_key", key).apply()
            toast(getString(R.string.key_saved))
        }

        // Paste
        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.getText()?.toString() ?: ""
            if (text.isNotEmpty()) {
                suppressWatch = true
                etInput.setText(text)
                suppressWatch = false
                tryAutoDecode()
            }
        }

        // Clear
        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            etInput.text?.clear()
            tvResult.text = ""
            tvResult.visibility = View.GONE
            tvStatus.visibility = View.GONE
            layoutBottom.visibility = View.GONE
        }

        // Decode
        findViewById<Button>(R.id.btnDecode).setOnClickListener { decode() }

        // Copy result
        findViewById<Button>(R.id.btnCopyResult).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("decoded", tvResult.text)
            cm.setPrimaryClip(clip)
            toast(getString(R.string.copied))
        }

        // Dismiss
        findViewById<Button>(R.id.btnDismiss).setOnClickListener {
            tvResult.visibility     = View.GONE
            tvStatus.visibility     = View.GONE
            layoutBottom.visibility = View.GONE
        }

        // Auto-decode on input change
        etInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatch) tryAutoDecode()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Handle incoming shared text
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val text: String? = when {
            i?.action == Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            i?.action == Intent.ACTION_VIEW -> i?.data?.toString()
            else -> i?.getStringExtra("encoded_text")
        }
        text?.trim()?.let {
            suppressWatch = true
            etInput.setText(it)
            suppressWatch = false
            showStatus(getString(R.string.status_input_received))
            tryAutoDecode()
        }
    }

    private fun tryAutoDecode() {
        if (swAutoDecode.isChecked) decode(silent = true)
    }

    private fun decode(silent: Boolean = false) {
        val key   = etKey.text?.toString()?.trim() ?: ""
        val input = etInput.text?.toString()?.trim() ?: ""
        if (key.isEmpty()) {
            if (!silent) toast(getString(R.string.error_no_key)); return
        }
        if (input.isEmpty()) {
            if (!silent) toast(getString(R.string.error_no_input)); return
        }
        try {
            val result = DecoderCrypto.extractAndDecrypt(input, key)
            tvResult.text            = result
            tvResult.visibility      = View.VISIBLE
            tvResult.setBackgroundColor(android.graphics.Color.parseColor("#1A4CAF50"))
            layoutBottom.visibility  = View.VISIBLE
            showStatus(getString(R.string.decode_success))
        } catch (e: Exception) {
            if (!silent) {
                tvResult.text           = getString(R.string.decode_error, e.message ?: "")
                tvResult.visibility     = View.VISIBLE
                tvResult.setBackgroundColor(android.graphics.Color.parseColor("#1AF44336"))
                layoutBottom.visibility = View.GONE
                showStatus(getString(R.string.decode_failed))
            }
        }
    }

    private fun showStatus(msg: String) {
        tvStatus.text       = msg
        tvStatus.visibility = View.VISIBLE
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
