package com.example.smsfrowarder.model

import java.util.UUID

enum class LogStatus { PENDING, SUCCESS, FAILED }

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String,
    val messagePreview: String,     // first 80 chars of SMS body
    val ruleName: String,
    val destination: String,        // human label: "Telegram" or webhook URL
    var status: LogStatus = LogStatus.PENDING,
    var errorMessage: String = "",
    // stored so the retry button can re-dispatch
    val fullMessage: String,        // full text that was sent
    val ruleId: String
)
