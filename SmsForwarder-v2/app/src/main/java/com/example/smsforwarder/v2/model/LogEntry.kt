package com.example.smsforwarder.v2.model

import java.util.UUID

enum class LogStatus { PENDING, SUCCESS, FAILED }

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String,
    val messagePreview: String,
    val ruleName: String,
    val destination: String,
    var status: LogStatus = LogStatus.PENDING,
    var errorMessage: String = "",
    val fullMessage: String,
    val ruleId: String
)
