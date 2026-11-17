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
import com.example.smsfrowarder.model.SimSlot
import com.example.smsfrowarder.model.TelegramDest
import com.example.smsfrowarder.model.WebhookDest
import kotlinx.coroutines.Dispatchers
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

    // Verification state — not persisted until Save
    private var telegramVerified = false
    private var webhookVerified  = false

    // Views
    private lateinit var etName: EditText
    private lateinit var spinnerSim: Spinner
    private lateinit var etSender: EditText

    // Telegram
    private lateinit var cbTelegram: CheckBox
    private lateinit var layoutTelegram: View
    private lateinit var etBotToken: EditText
    private lateinit var etChatIds: EditText
    private lateinit var btnVerifyTelegram: Button
    private lateinit var tvTelegramStatus: TextView

    // Webhook
    private lateinit var cbWebhook: CheckBox
    private lateinit var layoutWebhook: View
    private lateinit var etWebhookUrl: EditText
    private lateinit var etAuthHeader: EditText
    private lateinit var etEncKey: EditText
    private lateinit var btnVerifyWebhook: Button
    private lateinit var tvWebhookStatus: TextView

    private lateinit var btnSave: Button

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
        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        btnVerifyTelegram.setOnClickListener { verifyTelegram() }
        btnVerifyWebhook.setOnClickListener { verifyWebhook() }
    }

    private fun bindViews() {
        etName             = findViewById(R.id.etRuleName)
        spinnerSim         = findViewById(R.id.spinnerSim)
        etSender           = findViewById(R.id.etSender)
        cbTelegram         = findViewById(R.id.cbTelegram)
        layoutTelegram     = findViewById(R.id.layoutTelegram)
        etBotToken         = findViewById(R.id.etBotToken)
        etChatIds          = findViewById(R.id.etChatIds)
        btnVerifyTelegram  = findViewById(R.id.btnVerifyTelegram)
        tvTelegramStatus   = findViewById(R.id.tvTelegramStatus)
        cbWebhook          = findViewById(R.id.cbWebhook)
        layoutWebhook       = findViewById(R.id.layoutWebhook)
        etWebhookUrl       = findViewById(R.id.etWebhookUrl)
        etAuthHeader       = findViewById(R.id.etAuthHeader)
        etEncKey           = findViewById(R.id.etEncKey)
        btnVerifyWebhook   = findViewById(R.id.btnVerifyWebhook)
        tvWebhookStatus    = findViewById(R.id.tvWebhookStatus)
        btnSave            = findViewById(R.id.btnSave)
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
    }

    /** Reset verified flag when user edits token/URL fields. */
    private fun setupVerificationReset() {
        val resetTelegram = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (telegramVerified) { telegramVerified = false; updateStatusLabels() } }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etBotToken.addTextChangedListener(resetTelegram)
        etChatIds.addTextChangedListener(resetTelegram)

        val resetWebhook = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (webhookVerified) { webhookVerified = false; updateStatusLabels() } }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etWebhookUrl.addTextChangedListener(resetWebhook)
        etEncKey.addTextChangedListener(resetWebhook)
    }

    private fun updateStatusLabels() {
        tvTelegramStatus.text = if (telegramVerified) "✅ ${getString(R.string.verified)}" else "❌ ${getString(R.string.not_verified)}"
        tvWebhookStatus.text  = if (webhookVerified) "✅ ${getString(R.string.verified)}" else "❌ ${getString(R.string.not_verified)}"
    }

    // ── OTP Verification ──────────────────────────────────────────────────────

    private fun verifyTelegram() {
        val token   = etBotToken.text?.toString()?.trim() ?: ""
        val chatIds = etChatIds.text?.toString()?.trim() ?: ""
        if (token.isEmpty() || chatIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_token_chatid_required), Toast.LENGTH_SHORT).show()
            return
        }

        val otp = Random.nextInt(100000, 999999).toString()
        val dest = TelegramDest(token, chatIds)
        val msg = getString(R.string.otp_message, otp)

        btnVerifyTelegram.isEnabled = false
        Toast.makeText(this, getString(R.string.otp_sending), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val dispatcher = ForwardDispatcher(App.get(this@RuleEditActivity).logRepository)
            val ok = dispatcher.sendOtpTelegram(msg, dest)

            withContext(Dispatchers.Main) {
                btnVerifyTelegram.isEnabled = true
                if (ok) showOtpDialog(otp, isTelegram = true)
                else Toast.makeText(this@RuleEditActivity, getString(R.string.otp_send_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun verifyWebhook() {
        val url = etWebhookUrl.text?.toString()?.trim() ?: ""
        if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            Toast.makeText(this, getString(R.string.error_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        val key = etEncKey.text?.toString()?.trim() ?: ""
        val dest = WebhookDest(url, etAuthHeader.text?.toString()?.trim() ?: "", key)
        val otp = Random.nextInt(100000, 999999).toString()
        val msg = getString(R.string.otp_message, otp)

        btnVerifyWebhook.isEnabled = false
        Toast.makeText(this, getString(R.string.otp_sending), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val dispatcher = ForwardDispatcher(App.get(this@RuleEditActivity).logRepository)
            val ok = dispatcher.sendOtpWebhook(msg, dest)

            withContext(Dispatchers.Main) {
                btnVerifyWebhook.isEnabled = true
                if (ok) showOtpDialog(otp, isTelegram = false)
                else Toast.makeText(this@RuleEditActivity, getString(R.string.otp_send_failed), Toast.LENGTH_LONG).show()
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

    // ── Populate form ─────────────────────────────────────────────────────────

    private fun populateForm(rule: ForwardRule) {
        etName.setText(rule.name)
        etSender.setText(rule.senderFilter)
        spinnerSim.setSelection(when (rule.simSlot) {
            SimSlot.ANY -> 0; SimSlot.SIM_1 -> 1; SimSlot.SIM_2 -> 2
        })
        rule.telegram?.let {
            cbTelegram.isChecked = true
            etBotToken.setText(it.botToken)
            etChatIds.setText(it.chatIds)
        } ?: run { cbTelegram.isChecked = false }
        rule.webhook?.let {
            cbWebhook.isChecked = true
            etWebhookUrl.setText(it.url)
            etAuthHeader.setText(it.authHeader)
            etEncKey.setText(it.encryptionKey)
        } ?: run { cbWebhook.isChecked = false }
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
                // Warn about unencrypted sending
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

    private fun doSave(
        name: String, sim: SimSlot, sender: String,
        telegram: TelegramDest?, webhook: WebhookDest?
    ) {
        val rule = (existingRule?.copy(
            name = name, simSlot = sim, senderFilter = sender,
            telegram = telegram, webhook = webhook,
            telegramVerified = telegramVerified, webhookVerified = webhookVerified
        ) ?: ForwardRule(
            name = name, simSlot = sim, senderFilter = sender,
            telegram = telegram, webhook = webhook,
            telegramVerified = telegramVerified, webhookVerified = webhookVerified
        ))

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
