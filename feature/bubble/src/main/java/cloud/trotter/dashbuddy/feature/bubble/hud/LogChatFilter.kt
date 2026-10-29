package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes

object LogChatFilter {
    // EffectMap.outcomeCardText
    private val outcomes = setOf("Offer accepted", "Offer declined", "Offer expired")
    // OfferEffects: exact-match acknowledgements, already represented by the offer outcome.
    private val acknowledgements = setOf("Accepting…", "Declining…")
    // Prefix-match copy from OfferEffects and ModeEffects, represented by outcomes and shift figures.
    private val acknowledgementPrefixes = listOf("Already declined", "Shift ended")
    private val tags = Regex("<[^>]*>")
    private val breaks = Regex("<br\\b[^>]*>|</(?:p|div|li|h[1-6]|blockquote|ul|ol)\\s*>", RegexOption.IGNORE_CASE)
    private val unresolvedEntity = Regex("&(?:#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val hash = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val entities = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    private val whitespace = Regex("\\s+")

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
    }.map { if (it.isWhitespace() || it == '\u00a0') ' ' else it }.joinToString("").replace(whitespace, " ").trim()

    fun keep(msg: ChatMessage): Boolean = kept(msg) != null

    /** The sole text owner: privacy checks and the row consume this same normalized value. */
    fun kept(msg: ChatMessage): String? {
        if (msg.persona != ChatPersona.Dispatcher && msg.persona != ChatPersona.System) return null
        val text = plainText(msg.text)
        if (!safeText(text)) return null
        if (msg.persona == ChatPersona.Dispatcher) {
            if (text.removeSuffix(", replaced by a new offer") in outcomes || text in acknowledgements ||
                acknowledgementPrefixes.any { text.startsWith(it) }) return null
        }
        return text // Unknown dispatcher/system copy fails open after the privacy guard.
    }

    internal fun safeText(text: String): Boolean = !unresolvedEntity.containsMatchIn(text) &&
        !hash.containsMatchIn(text) && !PiiShapes.hasNameShape(text) &&
        PiiShapes.customerLeadIn(text) == null && !PiiShapes.containsMask(text) &&
        PiiShapes.VALUE_SHAPES.none { it.hits(text) }
}
