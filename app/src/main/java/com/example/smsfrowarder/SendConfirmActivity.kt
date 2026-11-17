package com.example.smsfrowarder

import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shown for 10 seconds when an SMS matches a rule.
 * On timeout: dispatches the message.
 * On cancel: stops the service.
 *
 * All data arrives via Intent extras — no temp files needed.
 */
class SendConfirmActivity : BaseActivity() {

    companion object {
        const val EXTRA_TEXT     = "confirm_text"
        const val EXTRA_SENDER   = "confirm_sender"
        const val EXTRA_RULE_IDS = "confirm_rule_ids"   // comma-separated
        const val EXTRA_PREVIEW  = "confirm_preview"
    }

    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_confirm)

        // Allow showing over lock screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val text     = intent.getStringExtra(EXTRA_TEXT)     ?: return finish()
        val sender   = intent.getStringExtra(EXTRA_SENDER)   ?: ""
        val ruleIds  = intent.getStringExtra(EXTRA_RULE_IDS) ?: return finish()
        val preview  = intent.getStringExtra(EXTRA_PREVIEW)  ?: text.take(60)

        val tvCountdown = findViewById<TextView>(R.id.tvCountdown)
        val tvPreview   = findViewById<TextView>(R.id.tvPreview)
        val tvFrom      = findViewById<TextView>(R.id.tvFrom)
        val btnCancel   = findViewById<Button>(R.id.btnCancel)

        tvFrom.text    = getString(R.string.send_confirm_from, sender)
        tvPreview.text = preview

        timer = object : CountDownTimer(10_000L, 1_000L) {
            override fun onTick(ms: Long) {
                tvCountdown.text = (ms / 1000).toString()
            }
            override fun onFinish() {
                tvCountdown.text = "0"
                sendNow(text, sender, ruleIds)
            }
        }.start()

        btnCancel.setOnClickListener {
            timer?.cancel()
            SmsService.stop(this)
            finish()
        }
    }

    private fun sendNow(text: String, sender: String, ruleIdsStr: String) {
        val ruleIdSet = ruleIdsStr.split(",").map { it.trim() }.toSet()
        val app = App.get(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val rules = app.rulesRepository.getRules()
                .filter { it.id in ruleIdSet && it.enabled }
            if (rules.isNotEmpty()) {
                ForwardDispatcher(app.logRepository).dispatch(text, sender, rules)
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
