package com.example.smsfrowarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smsfrowarder.model.Destination
import com.example.smsfrowarder.model.ForwardRule
import com.example.smsfrowarder.model.SimSlot
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    private lateinit var evaluator: RuleEvaluator
    private lateinit var adapter:   RulesAdapter

    // Repository lives in App — already initialised on IO thread
    private val repo get() = App.get(this).rulesRepository

    private val editRuleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        evaluator = RuleEvaluator(this)

        adapter = RulesAdapter(
            onEdit   = { rule -> RuleEditActivity.start(this, editRuleLauncher, rule.id) },
            onDelete = { rule -> confirmDelete(rule) },
            onToggle = { rule, enabled ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.setEnabled(rule.id, enabled)
                    refreshList()
                }
            }
        )

        val recycler = findViewById<RecyclerView>(R.id.rulesRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            RuleEditActivity.start(this, editRuleLauncher, null)
        }
        findViewById<View>(R.id.btnStartService).setOnClickListener {
            SmsService.start(this)
        }
        findViewById<View>(R.id.btnStopService).setOnClickListener {
            SmsService.stop(this)
        }

        requestPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        warnIfMultiSimUnavailable()
    }

    // -------------------------------------------------------------------------
    // List refresh — always on IO then post to main
    // -------------------------------------------------------------------------

    private fun refreshList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rules = repo.getRules()
            withContext(Dispatchers.Main) {
                adapter.submitList(rules)
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun confirmDelete(rule: ForwardRule) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(getString(R.string.confirm_delete_msg, rule.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.deleteRule(rule.id)
                    refreshList()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun warnIfMultiSimUnavailable() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hasSimRule = repo.getRules().any {
                it.simSlot == SimSlot.SIM_1 || it.simSlot == SimSlot.SIM_2
            }
            if (hasSimRule && !evaluator.isMultiSimDetectionAvailable()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.warn_multisim), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            Toast.makeText(this, getString(R.string.warn_permissions), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                add(Manifest.permission.FOREGROUND_SERVICE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
    }

    // =========================================================================
    // RecyclerView adapter (inner — has access to getString via outer Activity)
    // =========================================================================

    private inner class RulesAdapter(
        private val onEdit:   (ForwardRule) -> Unit,
        private val onDelete: (ForwardRule) -> Unit,
        private val onToggle: (ForwardRule, Boolean) -> Unit
    ) : RecyclerView.Adapter<RulesAdapter.VH>() {

        private var items: List<ForwardRule> = emptyList()

        fun submitList(list: List<ForwardRule>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvName:    TextView       = view.findViewById(R.id.tvRuleName)
            private val tvSummary: TextView       = view.findViewById(R.id.tvRuleSummary)
            private val swEnabled: SwitchMaterial = view.findViewById(R.id.swEnabled)

            fun bind(rule: ForwardRule) {
                tvName.text    = rule.name.ifBlank { getString(R.string.label_unnamed_rule) }
                tvSummary.text = buildSummary(rule)

                swEnabled.setOnCheckedChangeListener(null)
                swEnabled.isChecked = rule.enabled
                swEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }

                itemView.setOnClickListener { onEdit(rule) }
                itemView.setOnLongClickListener { onDelete(rule); true }
            }

            private fun buildSummary(rule: ForwardRule): String {
                val sim = when (rule.simSlot) {
                    SimSlot.SIM_1 -> getString(R.string.sim_1)
                    SimSlot.SIM_2 -> getString(R.string.sim_2)
                    SimSlot.ANY   -> getString(R.string.sim_any)
                }
                val sender = if (rule.senderFilter.isBlank() || rule.senderFilter == "*")
                    getString(R.string.label_any_sender) else rule.senderFilter
                val dest = when (val d = rule.destination) {
                    is Destination.Telegram -> "Telegram (${d.chatIds})"
                    is Destination.Webhook  -> "Webhook (${d.url.take(30)}…)"
                }
                return "$sim · $sender → $dest"
            }
        }
    }
}
