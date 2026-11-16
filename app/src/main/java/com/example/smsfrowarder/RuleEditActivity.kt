package com.example.smsfrowarder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import com.example.smsfrowarder.model.Destination
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.SimSlot

/**
 * Add or edit a single [ForwardRule].
 * Pass [EXTRA_RULE_ID] to edit an existing rule; omit it to create a new one.
 */
class RuleEditActivity : BaseActivity() {

    companion object {
        private const val EXTRA_RULE_ID = "rule_id"

        fun start(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            ruleId: String?
        ) {
            val intent = Intent(context, RuleEditActivity::class.java).apply {
                ruleId?.let { putExtra(EXTRA_RULE_ID, it) }
            }
            launcher.launch(intent)
        }
    }

    private lateinit var rulesRepository: RulesRepository
    private var existingRule: ForwardRule? = null

    // Views
    private lateinit var etName:        EditText
    private lateinit var spinnerSim:    Spinner
    private lateinit var etSender:      EditText
    private lateinit var rgDestType:    RadioGroup
    private lateinit var rbTelegram:    RadioButton
    private lateinit var rbWebhook:     RadioButton
    private lateinit var layoutTelegram: View
    private lateinit var etBotToken:    EditText
    private lateinit var etChatIds:     EditText
    private lateinit var layoutWebhook: View
    private lateinit var etWebhookUrl:  EditText
    private lateinit var etAuthHeader:  EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_edit)

        rulesRepository = RulesRepository.create(this)

        bindViews()
        setupSimSpinner()
        setupDestinationToggle()

        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        if (ruleId != null) {
            existingRule = rulesRepository.getRules().find { it.id == ruleId }
            existingRule?.let { populateForm(it) }
            title = getString(R.string.title_edit_rule)
        } else {
            title = getString(R.string.title_new_rule)
        }

        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
    }

    // -------------------------------------------------------------------------

    private fun bindViews() {
        etName         = findViewById(R.id.etRuleName)
        spinnerSim     = findViewById(R.id.spinnerSim)
        etSender       = findViewById(R.id.etSender)
        rgDestType     = findViewById(R.id.rgDestinationType)
        rbTelegram     = findViewById(R.id.rbTelegram)
        rbWebhook      = findViewById(R.id.rbWebhook)
        layoutTelegram = findViewById(R.id.layoutTelegram)
        etBotToken     = findViewById(R.id.etBotToken)
        etChatIds      = findViewById(R.id.etChatIds)
        layoutWebhook  = findViewById(R.id.layoutWebhook)
        etWebhookUrl   = findViewById(R.id.etWebhookUrl)
        etAuthHeader   = findViewById(R.id.etAuthHeader)
    }

    private fun setupSimSpinner() {
        val options = arrayOf(
            getString(R.string.sim_any),
            getString(R.string.sim_1),
            getString(R.string.sim_2)
        )
        spinnerSim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupDestinationToggle() {
        rgDestType.setOnCheckedChangeListener { _, checkedId ->
            layoutTelegram.visibility = if (checkedId == R.id.rbTelegram) View.VISIBLE else View.GONE
            layoutWebhook.visibility  = if (checkedId == R.id.rbWebhook)  View.VISIBLE else View.GONE
        }
        // Default: Telegram
        rbTelegram.isChecked    = true
        layoutTelegram.visibility = View.VISIBLE
        layoutWebhook.visibility  = View.GONE
    }

    private fun populateForm(rule: ForwardRule) {
        etName.setText(rule.name)
        etSender.setText(rule.senderFilter)
        spinnerSim.setSelection(when (rule.simSlot) {
            SimSlot.ANY   -> 0
            SimSlot.SIM_1 -> 1
            SimSlot.SIM_2 -> 2
        })
        when (val dest = rule.destination) {
            is Destination.Telegram -> {
                rbTelegram.isChecked = true
                etBotToken.setText(dest.botToken)
                etChatIds.setText(dest.chatIds)
                layoutTelegram.visibility = View.VISIBLE
                layoutWebhook.visibility  = View.GONE
            }
            is Destination.Webhook -> {
                rbWebhook.isChecked = true
                etWebhookUrl.setText(dest.url)
                etAuthHeader.setText(dest.authHeader)
                layoutTelegram.visibility = View.GONE
                layoutWebhook.visibility  = View.VISIBLE
            }
        }
    }

    private fun save() {
        val name   = etName.text.toString().trim()
        val sender = etSender.text.toString().trim().ifEmpty { "*" }
        val sim    = when (spinnerSim.selectedItemPosition) {
            1    -> SimSlot.SIM_1
            2    -> SimSlot.SIM_2
            else -> SimSlot.ANY
        }

        val destination: Destination = if (rbTelegram.isChecked) {
            val token   = etBotToken.text.toString().trim()
            val chatIds = etChatIds.text.toString().trim()
            if (token.isEmpty() || chatIds.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_token_chatid_required), Toast.LENGTH_SHORT).show()
                return
            }
            Destination.Telegram(token, chatIds)
        } else {
            val url = etWebhookUrl.text.toString().trim()
            if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
                Toast.makeText(this, getString(R.string.error_url_required), Toast.LENGTH_SHORT).show()
                return
            }
            Destination.Webhook(url, etAuthHeader.text.toString().trim())
        }

        val rule = existingRule?.copy(
            name         = name,
            simSlot      = sim,
            senderFilter = sender,
            destination  = destination
        ) ?: ForwardRule(
            name         = name,
            simSlot      = sim,
            senderFilter = sender,
            destination  = destination
        )

        if (existingRule != null) rulesRepository.updateRule(rule)
        else rulesRepository.addRule(rule)

        Toast.makeText(this, getString(R.string.rule_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
