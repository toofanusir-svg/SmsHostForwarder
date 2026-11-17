package com.example.smsfrowarder.model

import java.util.UUID

/**
 * One forwarding rule.
 *
 * Both [telegram] and [webhook] are optional.
 * If both are set, the message is sent to both.
 * At least one must be non-null for the rule to be valid.
 */
data class ForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val simSlot: SimSlot = SimSlot.ANY,
    val senderFilter: String = "*",
    val telegram: TelegramDest? = null,
    val webhook: WebhookDest? = null,
    val enabled: Boolean = true
)

enum class SimSlot { SIM_1, SIM_2, ANY }

data class TelegramDest(
    val botToken: String,
    val chatIds: String     // comma-separated
)

data class WebhookDest(
    val url: String,
    val authHeader: String = ""
)
