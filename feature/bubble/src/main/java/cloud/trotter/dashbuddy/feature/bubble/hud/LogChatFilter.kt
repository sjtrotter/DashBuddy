package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes

object LogChatFilter {
    // EffectMap.outcomeCardText
    private val outcomes = setOf("Offer Accepted", "Offer Declined", "Offer Timed Out!")
    // OfferEffects: action acknowledgements, already represented by the offer outcome.
    private val acknowledgements = setOf("Accepting…", "Declining…", "Decline already submitted…")
    // ModeEffects: the summary is represented by the dash's figures.
    private const val SESSION_ENDED = "Session Ended"
    private val tags = Regex("<[^>]*>")
    private val breaks = Regex("<br\\b[^>]*>|</(?:p|div|li|h[1-6]|blockquote|ul|ol)\\s*>", RegexOption.IGNORE_CASE)
    private val unresolvedEntity = Regex("&(?:#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val hash = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val entities = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    /** Match the displayed text, including names split across HTML spans or entity-encoded. */
    internal fun plainText(html: String): String = entities.replace(tags.replace(breaks.replace(html, "\n"), "")) { match ->
        val entity = match.groupValues[1]
        when (entity) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
    }.map { if (it.isWhitespace() || it == '\u00a0') ' ' else it }.joinToString("").trim()

    fun keep(msg: ChatMessage): Boolean {
        if (msg.persona != ChatPersona.Dispatcher && msg.persona != ChatPersona.System) return false
        val text = plainText(msg.text)
        if (!safeText(text)) return false
        if (msg.persona == ChatPersona.Dispatcher) {
            if (text.removeSuffix(" (offer replaced)") in outcomes || text in acknowledgements ||
                text.startsWith("Decline already submitted") ||
                text.startsWith(SESSION_ENDED)) return false
        }
        return true // Unknown dispatcher/system copy fails open after the privacy guard.
    }

    internal fun safeText(text: String): Boolean = !unresolvedEntity.containsMatchIn(text) &&
        !hash.containsMatchIn(text) && !PiiShapes.hasNameShape(text) &&
        PiiShapes.customerLeadIn(text) == null && !PiiShapes.containsMask(text) &&
        PiiShapes.VALUE_SHAPES.none { it.hits(text) }
}
