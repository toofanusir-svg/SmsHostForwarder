package com.example.smsfrowarder.model

import java.util.UUID

data class ForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val simSlot: SimSlot = SimSlot.ANY,
    val senderFilter: String = "*",
    val telegram: TelegramDest? = null,
    val webhook: WebhookDest? = null,
    val enabled: Boolean = true,
    val telegramVerified: Boolean = false,
    val webhookVerified: Boolean = false
)

enum class SimSlot { SIM_1, SIM_2, ANY }

data class TelegramDest(
    val botToken: String,
    val chatIds: String
)

data class WebhookDest(
    val url: String,
    val authHeader: String = "",
    val encryptionKey: String = ""
)
