package com.example.smsfrowarder.model

/** Raw SMS data for structured webhook payload. */
data class SmsData(
    val sender: String,
    val body: String,
    val simSlot: SimSlot?,
    val receiveTime: Long
)
