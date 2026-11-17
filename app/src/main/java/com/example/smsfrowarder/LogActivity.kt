package com.example.smsfrowarder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smsfrowarder.model.LogEntry
import com.example.smsfrowarder.model.LogStatus
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LogActivity : BaseActivity() {

    private lateinit var logRepo:   LogRepository
    private lateinit var rulesRepo: RulesRepository
    private lateinit var adapter:   LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val app = App.get(this)
        logRepo   = app.logRepository
        rulesRepo = app.rulesRepository

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbarLog))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = LogAdapter(
            onRetry  = { entry -> retryEntry(entry) },
            onDetail = { entry -> showDetail(entry) }
        )
        val rv = findViewById<RecyclerView>(R.id.rvLog)
        rv.layoutManager = LinearLayoutManager(this).apply { reverseLayout = true; stackFromEnd = true }
        rv.adapter = adapter

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.log_clear_title))
                .setMessage(getString(R.string.log_clear_msg))
                .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        logRepo.clearAll()
                        refreshList()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refreshList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = logRepo.getAll().sortedByDescending { it.timestamp }
            withContext(Dispatchers.Main) { adapter.submitList(entries) }
        }
    }

    private fun retryEntry(entry: LogEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            val rules = rulesRepo.getRules()
            // mark pending before retry
            logRepo.updateStatus(entry.id, LogStatus.PENDING, "")
            withContext(Dispatchers.Main) { refreshList() }
            ForwardDispatcher(logRepo).retry(entry, rules)
            refreshList()
        }
    }

    private fun showDetail(entry: LogEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.ruleName)
            .setMessage(
                "To: ${entry.destination}\n" +
                "From: ${entry.sender}\n" +
                "Time: ${formatTime(entry.timestamp)}\n" +
                "Status: ${entry.status.name}\n\n" +
                (if (entry.errorMessage.isNotEmpty()) "Error: ${entry.errorMessage}\n\n" else "") +
                "Message:\n${entry.messagePreview}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    // =========================================================================
    private inner class LogAdapter(
        private val onRetry:  (LogEntry) -> Unit,
        private val onDetail: (LogEntry) -> Unit
    ) : RecyclerView.Adapter<LogAdapter.VH>() {

        private var items: List<LogEntry> = emptyList()

        fun submitList(list: List<LogEntry>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvStatus:  TextView = view.findViewById(R.id.tvLogStatus)
            private val tvSender:  TextView = view.findViewById(R.id.tvLogSender)
            private val tvDest:    TextView = view.findViewById(R.id.tvLogDest)
            private val tvTime:    TextView = view.findViewById(R.id.tvLogTime)
            private val btnRetry:  Button   = view.findViewById(R.id.btnLogRetry)

            fun bind(entry: LogEntry) {
                tvStatus.text = when (entry.status) {
                    LogStatus.SUCCESS -> "✅"
                    LogStatus.FAILED  -> "❌"
                    LogStatus.PENDING -> "⏳"
                }
                tvSender.text = entry.sender.ifEmpty { "SYSTEM" }
                tvDest.text   = entry.destination
                tvTime.text   = formatTime(entry.timestamp)

                btnRetry.visibility = if (entry.status == LogStatus.FAILED) View.VISIBLE else View.GONE
                btnRetry.setOnClickListener { onRetry(entry) }
                itemView.setOnClickListener { onDetail(entry) }
            }
        }
    }
}
