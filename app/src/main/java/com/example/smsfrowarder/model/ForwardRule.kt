package com.example.smsfrowarder.model

import java.util.UUID

/**
 * One forwarding rule.
 *
 * @param id           Unique identifier (UUID).
 * @param name         Human-readable label shown in the list.
 * @param simSlot      Which SIM triggers this rule: SIM_1, SIM_2, or ANY.
 * @param senderFilter "*" means any sender; otherwise an exact phone number to match.
 * @param destination  Where to forward the message.
 * @param enabled      Whether this rule is active.
 */
data class ForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val simSlot: SimSlot = SimSlot.ANY,
    val senderFilter: String = "*",
    val destination: Destination,
    val enabled: Boolean = true
)

enum class SimSlot { SIM_1, SIM_2, ANY }

/**
 * Where a matched message gets forwarded.
 * Sealed so the compiler enforces exhaustive handling everywhere.
 */
sealed class Destination {

    /** Send via Telegram Bot API. chatIds is comma-separated. */
    data class Telegram(
        val botToken: String,
        val chatIds: String          // "123456,789012"
    ) : Destination()

    /** POST the message as JSON to a custom URL. */
    data class Webhook(
        val url: String,
        val authHeader: String = "" // optional Authorization header value
    ) : Destination()
}
