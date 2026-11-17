package com.example.smsfrowarder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.SimSlot
import com.example.smsfrowarder.model.TelegramDest
import com.example.smsfrowarder.model.WebhookDest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuleEditActivity : BaseActivity() {

    companion object {
        private const val EXTRA_RULE_ID = "rule_id"
        fun start(context: Context, launcher: ActivityResultLauncher<Intent>, ruleId: String?) {
            launcher.launch(Intent(context, RuleEditActivity::class.java).apply {
                ruleId?.let { putExtra(EXTRA_RULE_ID, it) }
            })
        }
    }

    private lateinit var repo:         RulesRepository
    private var existingRule: ForwardRule? = null

    // Views
    private lateinit var etName:         EditText
    private lateinit var spinnerSim:     Spinner
    private lateinit var etSender:       EditText

    // Telegram
    private lateinit var cbTelegram:     CheckBox
    private lateinit var layoutTelegram: View
    private lateinit var etBotToken:     EditText
    private lateinit var etChatIds:      EditText

    // Webhook
    private lateinit var cbWebhook:      CheckBox
    private lateinit var layoutWebhook:  View
    private lateinit var etWebhookUrl:   EditText
    private lateinit var etAuthHeader:   EditText

    private lateinit var btnSave:        Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_edit)
        repo = App.get(this).rulesRepository
        bindViews()
        setupSimSpinner()
        setupDestinationToggles()

        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        if (ruleId != null) {
            title = getString(R.string.title_edit_rule)
            lifecycleScope.launch(Dispatchers.IO) {
                val rule = try { repo.getRules().find { it.id == ruleId } } catch (_: Exception) { null }
                withContext(Dispatchers.Main) { existingRule = rule; rule?.let { populateForm(it) } }
            }
        } else {
            title = getString(R.string.title_new_rule)
        }

        btnSave.setOnClickListener { save() }
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
        cbWebhook      = findViewById(R.id.cbWebhook)
        layoutWebhook  = findViewById(R.id.layoutWebhook)
        etWebhookUrl   = findViewById(R.id.etWebhookUrl)
        etAuthHeader   = findViewById(R.id.etAuthHeader)
        btnSave        = findViewById(R.id.btnSave)
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
        }
        cbWebhook.setOnCheckedChangeListener { _, checked ->
            layoutWebhook.visibility = if (checked) View.VISIBLE else View.GONE
        }
        // Default: Telegram checked
        cbTelegram.isChecked      = true
        layoutTelegram.visibility = View.VISIBLE
        cbWebhook.isChecked       = false
        layoutWebhook.visibility  = View.GONE
    }

    private fun populateForm(rule: ForwardRule) {
        etName.setText(rule.name)
        etSender.setText(rule.senderFilter)
        spinnerSim.setSelection(when (rule.simSlot) {
            SimSlot.ANY -> 0; SimSlot.SIM_1 -> 1; SimSlot.SIM_2 -> 2
        })
        rule.telegram?.let {
            cbTelegram.isChecked      = true
            layoutTelegram.visibility = View.VISIBLE
            etBotToken.setText(it.botToken)
            etChatIds.setText(it.chatIds)
        } ?: run {
            cbTelegram.isChecked      = false
            layoutTelegram.visibility = View.GONE
        }
        rule.webhook?.let {
            cbWebhook.isChecked      = true
            layoutWebhook.visibility = View.VISIBLE
            etWebhookUrl.setText(it.url)
            etAuthHeader.setText(it.authHeader)
        } ?: run {
            cbWebhook.isChecked      = false
            layoutWebhook.visibility = View.GONE
        }
    }

    private fun save() {
        val name   = etName.text?.toString()?.trim() ?: ""
        val sender = etSender.text?.toString()?.trim()?.ifEmpty { "*" } ?: "*"
        val sim    = when (spinnerSim.selectedItemPosition) {
            1 -> SimSlot.SIM_1; 2 -> SimSlot.SIM_2; else -> SimSlot.ANY
        }

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
            WebhookDest(url, etAuthHeader.text?.toString()?.trim() ?: "")
        } else null

        if (telegram == null && webhook == null) {
            Toast.makeText(this, getString(R.string.error_no_destination), Toast.LENGTH_SHORT).show()
            return
        }

        val rule = existingRule?.copy(name = name, simSlot = sim, senderFilter = sender, telegram = telegram, webhook = webhook)
            ?: ForwardRule(name = name, simSlot = sim, senderFilter = sender, telegram = telegram, webhook = webhook)

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
