package com.example.smsfrowarder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.LogStatus
import com.example.smsfrowarder.model.SimSlot
import com.example.smsfrowarder.model.TelegramDest
import com.example.smsfrowarder.model.WebhookDest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class RuleEditActivity : BaseActivity() {

    companion object {
        private const val EXTRA_RULE_ID = "rule_id"
        fun start(context: Context, launcher: ActivityResultLauncher<Intent>, ruleId: String?) {
            launcher.launch(Intent(context, RuleEditActivity::class.java).apply {
                ruleId?.let { putExtra(EXTRA_RULE_ID, it) }
            })
        }
    }

    private lateinit var repo: RulesRepository
    private var existingRule: ForwardRule? = null
    private var telegramVerified = false
    private var webhookVerified  = false

    // Views
    private lateinit var etName:         EditText
    private lateinit var spinnerSim:     Spinner
    private lateinit var etSender:       EditText
    // Telegram
    private lateinit var cbTelegram:     CheckBox
    private lateinit var layoutTelegram: View
    private lateinit var etBotToken:     EditText
    private lateinit var etChatIds:      EditText
    private lateinit var btnVerifyTg:    Button
    private lateinit var tvTgStatus:     TextView
    // Webhook
    private lateinit var cbWebhook:      CheckBox
    private lateinit var layoutWebhook:  View
    private lateinit var etWebhookUrl:   EditText
    private lateinit var etAuthHeader:   EditText
    private lateinit var etEncKey:       EditText
    private lateinit var btnVerifyWh:    Button
    private lateinit var tvWhStatus:     TextView

    private lateinit var btnSave:     Button
    private lateinit var btnTestRule: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_edit)
        repo = App.get(this).rulesRepository
        bindViews()
        setupSimSpinner()
        setupDestinationToggles()
        setupVerificationReset()

        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        if (ruleId != null) {
            title = getString(R.string.title_edit_rule)
            lifecycleScope.launch(Dispatchers.IO) {
                val rule = try { repo.getRules().find { it.id == ruleId } } catch (_: Exception) { null }
                withContext(Dispatchers.Main) {
                    existingRule = rule
                    rule?.let {
                        telegramVerified = it.telegramVerified
                        webhookVerified  = it.webhookVerified
                        populateForm(it)
                        updateStatusLabels()
                    }
                }
            }
        } else {
            title = getString(R.string.title_new_rule)
        }

        btnSave.setOnClickListener { save() }
        btnTestRule.setOnClickListener { testRule() }
        btnVerifyTg.setOnClickListener { verifyTelegram() }
        btnVerifyWh.setOnClickListener { verifyWebhook() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun bindViews() {
        etName         = findViewById(R.id.etRuleName)
        spinnerSim     = findViewById(R.id.spinnerSim)
        etSender       = findViewById(R.id.etSender)
        cbTelegram     = findViewById(R.id.cbTelegram)
        layoutTelegram = findViewById(R.id.layoutTelegram)
        etBotToken     = findViewById(R.id.etBotToken)
        etChatIds      = findViewById(R.id.etChatIds)
        btnVerifyTg    = findViewById(R.id.btnVerifyTelegram)
        tvTgStatus     = findViewById(R.id.tvTelegramStatus)
        cbWebhook      = findViewById(R.id.cbWebhook)
        layoutWebhook  = findViewById(R.id.layoutWebhook)
        etWebhookUrl   = findViewById(R.id.etWebhookUrl)
        etAuthHeader   = findViewById(R.id.etAuthHeader)
        etEncKey       = findViewById(R.id.etEncKey)
        btnVerifyWh    = findViewById(R.id.btnVerifyWebhook)
        tvWhStatus     = findViewById(R.id.tvWebhookStatus)
        btnSave        = findViewById(R.id.btnSave)
        btnTestRule    = findViewById(R.id.btnTestRule)
    }

    private fun setupSimSpinner() {
        spinnerSim.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            arrayOf(getString(R.string.sim_any), getString(R.string.sim_1), getString(R.string.sim_2))
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupDestinationToggles() {
        cbTelegram.setOnCheckedChangeListener { _, checked ->
            layoutTelegram.visibility = if (checked) View.VISIBLE else View.GONE
            updateStatusLabels()
        }
        cbWebhook.setOnCheckedChangeListener { _, checked ->
            layoutWebhook.visibility = if (checked) View.VISIBLE else View.GONE
            updateStatusLabels()
        }
        cbTelegram.isChecked = true
        cbWebhook.isChecked  = false
        updateStatusLabels()
    }

    /** Reset verified flag when user edits credential fields. */
    private fun setupVerificationReset() {
        fun watcherFor(reset: () -> Unit) = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { reset() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etBotToken.addTextChangedListener(watcherFor { if (telegramVerified) { telegramVerified = false; updateStatusLabels() } })
        etChatIds.addTextChangedListener(watcherFor  { if (telegramVerified) { telegramVerified = false; updateStatusLabels() } })
        etWebhookUrl.addTextChangedListener(watcherFor { if (webhookVerified) { webhookVerified = false; updateStatusLabels() } })
    }

    private fun updateStatusLabels() {
        tvTgStatus.text = if (telegramVerified) "✅ ${getString(R.string.verified)}" else "❌ ${getString(R.string.not_verified)}"
        tvWhStatus.text = if (webhookVerified)  "✅ ${getString(R.string.verified)}" else "❌ ${getString(R.string.not_verified)}"
    }

    // ── OTP ───────────────────────────────────────────────────────────────────

    private fun verifyTelegram() {
        val token   = etBotToken.text?.toString()?.trim() ?: ""
        val chatIds = etChatIds.text?.toString()?.trim() ?: ""
        if (token.isEmpty() || chatIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_token_chatid_required), Toast.LENGTH_SHORT).show()
            return
        }
        val otp  = Random.nextInt(100000, 999999).toString()
        val dest = TelegramDest(token, chatIds)
        val msg  = getString(R.string.otp_message, otp)

        btnVerifyTg.isEnabled = false
        Toast.makeText(this, getString(R.string.otp_sending), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val error = ForwardDispatcher(App.get(this@RuleEditActivity).logRepository).sendOtpTelegram(msg, dest)
            withContext(Dispatchers.Main) {
                btnVerifyTg.isEnabled = true
                if (error == null) showOtpDialog(otp, isTelegram = true)
                else Toast.makeText(this@RuleEditActivity, "${getString(R.string.otp_send_failed)}\n$error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun verifyWebhook() {
        val url = etWebhookUrl.text?.toString()?.trim() ?: ""
        if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            Toast.makeText(this, getString(R.string.error_url_required), Toast.LENGTH_SHORT).show()
            return
        }
        val key  = etEncKey.text?.toString()?.trim() ?: ""
        val dest = WebhookDest(url, etAuthHeader.text?.toString()?.trim() ?: "", key)
        val otp  = Random.nextInt(100000, 999999).toString()
        val msg  = getString(R.string.otp_message, otp)

        btnVerifyWh.isEnabled = false
        Toast.makeText(this, getString(R.string.otp_sending), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val error = ForwardDispatcher(App.get(this@RuleEditActivity).logRepository).sendOtpWebhook(msg, dest)
            withContext(Dispatchers.Main) {
                btnVerifyWh.isEnabled = true
                if (error == null) showOtpDialog(otp, isTelegram = false)
                else Toast.makeText(this@RuleEditActivity, "${getString(R.string.otp_send_failed)}\n$error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOtpDialog(expectedOtp: String, isTelegram: Boolean) {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.otp_enter_title))
            .setMessage(getString(R.string.otp_enter_msg))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val entered = input.text?.toString()?.trim() ?: ""
                if (entered == expectedOtp) {
                    if (isTelegram) telegramVerified = true else webhookVerified = true
                    updateStatusLabels()
                    Toast.makeText(this, getString(R.string.otp_verified_ok), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.otp_wrong), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    private fun testRule() {
        val telegram: TelegramDest? = if (cbTelegram.isChecked) {
            val token   = etBotToken.text?.toString()?.trim() ?: ""
            val chatIds = etChatIds.text?.toString()?.trim()  ?: ""
            if (token.isEmpty() || chatIds.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_token_chatid_required), Toast.LENGTH_SHORT).show()
                return
            }
            TelegramDest(token, chatIds)
        } else null

        val webhook: WebhookDest? = if (cbWebhook.isChecked) {
            val url = etWebhookUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
                Toast.makeText(this, getString(R.string.error_url_required), Toast.LENGTH_SHORT).show()
                return
            }
            WebhookDest(url, etAuthHeader.text?.toString()?.trim() ?: "", etEncKey.text?.toString()?.trim() ?: "")
        } else null

        if (telegram == null && webhook == null) {
            Toast.makeText(this, getString(R.string.error_no_destination), Toast.LENGTH_SHORT).show()
            return
        }

        val tempRule = ForwardRule(
            name = etName.text?.toString()?.trim()?.ifEmpty { "Test" } ?: "Test",
            telegram = telegram,
            webhook  = webhook
        )

        btnTestRule.isEnabled = false
        Toast.makeText(this, getString(R.string.test_running), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val logRepo    = App.get(this@RuleEditActivity).logRepository
            val dispatcher = ForwardDispatcher(logRepo)
            val testText   = "🔧 *TEST* — Rule \"${tempRule.name}\" test message."
            dispatcher.dispatch(testText, "TEST", listOf(tempRule))
            delay(3000)

            val results = logRepo.getAll()
                .filter { it.ruleId == tempRule.id }
                .sortedByDescending { it.timestamp }

            withContext(Dispatchers.Main) {
                btnTestRule.isEnabled = true
                val report = buildString {
                    results.forEach { e ->
                        val icon = when (e.status) {
                            LogStatus.SUCCESS -> "✅"; LogStatus.FAILED -> "❌"; LogStatus.PENDING -> "⏳"
                        }
                        appendLine("$icon ${e.destination}")
                        if (e.errorMessage.isNotEmpty()) appendLine("   ${e.errorMessage.take(120)}")
                    }
                    if (results.isEmpty()) appendLine(getString(R.string.test_no_response))
                }
                AlertDialog.Builder(this@RuleEditActivity)
                    .setTitle(getString(R.string.test_title))
                    .setMessage(report)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ── Populate ──────────────────────────────────────────────────────────────

    private fun populateForm(rule: ForwardRule) {
        etName.setText(rule.name)
        etSender.setText(rule.senderFilter)
        spinnerSim.setSelection(when (rule.simSlot) {
            SimSlot.ANY -> 0; SimSlot.SIM_1 -> 1; SimSlot.SIM_2 -> 2
        })
        rule.telegram?.let {
            cbTelegram.isChecked = true
            layoutTelegram.visibility = View.VISIBLE
            etBotToken.setText(it.botToken)
            etChatIds.setText(it.chatIds)
        } ?: run { cbTelegram.isChecked = false; layoutTelegram.visibility = View.GONE }
        rule.webhook?.let {
            cbWebhook.isChecked = true
            layoutWebhook.visibility = View.VISIBLE
            etWebhookUrl.setText(it.url)
            etAuthHeader.setText(it.authHeader)
            etEncKey.setText(it.encryptionKey)
        } ?: run { cbWebhook.isChecked = false; layoutWebhook.visibility = View.GONE }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun save() {
        val name   = etName.text?.toString()?.trim() ?: ""
        val sender = etSender.text?.toString()?.trim()?.ifEmpty { "*" } ?: "*"
        val sim    = when (spinnerSim.selectedItemPosition) {
            1 -> SimSlot.SIM_1; 2 -> SimSlot.SIM_2; else -> SimSlot.ANY
        }

        val telegram: TelegramDest? = if (cbTelegram.isChecked) {
            if (!telegramVerified) {
                Toast.makeText(this, getString(R.string.error_telegram_not_verified), Toast.LENGTH_LONG).show()
                return
            }
            TelegramDest(etBotToken.text?.toString()?.trim() ?: "", etChatIds.text?.toString()?.trim() ?: "")
        } else null

        val webhook: WebhookDest? = if (cbWebhook.isChecked) {
            if (!webhookVerified) {
                Toast.makeText(this, getString(R.string.error_webhook_not_verified), Toast.LENGTH_LONG).show()
                return
            }
            val key = etEncKey.text?.toString()?.trim() ?: ""
            if (key.isEmpty()) {
                // Warn about no encryption
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.enc_warning_title))
                    .setMessage(getString(R.string.enc_warning_msg))
                    .setPositiveButton(getString(R.string.enc_warning_confirm)) { _, _ ->
                        doSave(name, sim, sender, telegram, WebhookDest(
                            etWebhookUrl.text?.toString()?.trim() ?: "",
                            etAuthHeader.text?.toString()?.trim() ?: "",
                            ""
                        ))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                return
            }
            WebhookDest(etWebhookUrl.text?.toString()?.trim() ?: "", etAuthHeader.text?.toString()?.trim() ?: "", key)
        } else null

        if (telegram == null && webhook == null) {
            Toast.makeText(this, getString(R.string.error_no_destination), Toast.LENGTH_SHORT).show()
            return
        }

        doSave(name, sim, sender, telegram, webhook)
    }

    private fun doSave(name: String, sim: SimSlot, sender: String, telegram: TelegramDest?, webhook: WebhookDest?) {
        val rule = existingRule?.copy(
            name = name, simSlot = sim, senderFilter = sender,
            telegram = telegram, webhook = webhook,
            telegramVerified = telegramVerified, webhookVerified = webhookVerified
        ) ?: ForwardRule(
            name = name, simSlot = sim, senderFilter = sender,
            telegram = telegram, webhook = webhook,
            telegramVerified = telegramVerified, webhookVerified = webhookVerified
        )
        btnSave.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (existingRule != null) repo.updateRule(rule) else repo.addRule(rule)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnSave.isEnabled = true
                    Toast.makeText(this@RuleEditActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RuleEditActivity, getString(R.string.rule_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
