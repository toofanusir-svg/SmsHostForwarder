package com.example.smsfrowarder

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smsfrowarder.model.SmsData
import com.example.smsfrowarder.model.SimSlot
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shown when an SMS matches rules.
 * 10-second countdown with Cancel button.
 * On timeout: dispatches the message.
 * On cancel: stops the service (user must manually restart).
 */
class SendConfirmActivity : AppCompatActivity() {

    private var timer: CountDownTimer? = null
    private var pendingFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_confirm)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val tvCountdown = findViewById<TextView>(R.id.tvCountdown)
        val tvPreview    = findViewById<TextView>(R.id.tvPreview)
        val btnCancel    = findViewById<Button>(R.id.btnCancel)

        pendingFile = File(filesDir, "pending_dispatch.json")

        // Show preview of the pending message
        val pending = readPending()
        tvPreview.text = pending?.let {
            "From: ${it.smsData.sender}\n${it.smsData.body.take(60)}"
        } ?: "No pending message"

        timer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                tvCountdown.text = "$seconds"
            }

            override fun onFinish() {
                tvCountdown.text = "0"
                dispatchPending()
                finish()
            }
        }.start()

        btnCancel.setOnClickListener {
            timer?.cancel()
            // Stop the service — user must manually restart
            SmsService.stop(this)
            pendingFile?.delete()
            finish()
        }
    }

    private fun dispatchPending() {
        val pending = readPending() ?: run { finish(); return }
        pendingFile?.delete()

        val app = App.get(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val rules = app.rulesRepository.getRules()
                .filter { it.id in pending.ruleIds && it.enabled }

            if (rules.isNotEmpty()) {
                val dispatcher = ForwardDispatcher(app.logRepository)
                dispatcher.dispatch(pending.smsData, rules)
            }
        }
    }

    private fun readPending(): PendingData? {
        val file = pendingFile ?: return null
        if (!file.exists()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            val smsData = SmsData(
                sender = json.get("sender")?.asString ?: "",
                body = json.get("body")?.asString ?: "",
                simSlot = try { SimSlot.valueOf(json.get("simSlot")?.asString ?: "ANY") }
                    catch (_: Exception) { null },
                receiveTime = json.get("receiveTime")?.asLong ?: System.currentTimeMillis()
            )
            val ruleIds = json.get("ruleIds")?.asString?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            PendingData(smsData, ruleIds)
        } catch (_: Exception) { null }
    }

    private data class PendingData(val smsData: SmsData, val ruleIds: List<String>)

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
