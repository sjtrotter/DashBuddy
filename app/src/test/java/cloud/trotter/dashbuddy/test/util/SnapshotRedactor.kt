package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.model.notification.NotifTextField
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Strips customer PII from snapshot/capture JSON before it becomes a committed
 * fixture, preserving everything the recognition rules key on (resource-ids,
 * class names, stable UI anchor text).
 *
 * It parses the JSON only to *decide* which text values are PII (id-aware +
 * pattern-aware), then does a raw string replacement of exactly those values —
 * so every other byte (formatting, structure, key order) is preserved and the
 * git diff shows only what was scrubbed. Works on a bare UiNode tree, a wrapped
 * snapshot, or a capture envelope (window / click / notification).
 *
 * Recognition matches on structure + stable anchors, never on customer values,
 * so aggressive scrubbing is safe; [GoldenSnapshotRegressionTest] is the
 * guardrail that catches a redaction removing an anchor a rule needs.
 */
object SnapshotRedactor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val MASK = "[redacted]"

    // ---------------------------------------------------------------------------------------
    // The PII SHAPE vocabulary lives in `:domain` since #1145 (`PiiShapes`, ADR-0011 §2) so the
    // census chrome-likely filter and this commit-path scrubber share ONE owner. Every constant
    // below is a DELEGATE — never a copy — pinned by `PiiShapesParityTest` (identity + bytes).
    // The full rationale for each shape (receipts, trade-offs) moved with it; read it there.
    // ---------------------------------------------------------------------------------------

    /**
     * Resource-id suffixes whose text value is always customer PII → fully masked. #1160 review AL3
     * (deliberate): DERIVED from the runtime `CustomerTextMarkers.ID_MARKER_TABLE` — ONE list, matched by
     * `endsWith` (ignoring case), a widening toward privacy over the old exact-last-segment rule.
     */
    internal val PII_ID_SUFFIXES: Set<String> = CustomerTextMarkers.ID_MARKER_SUFFIXES

    /** Unconditional customer lead-ins (see [PiiShapes.NAME_PREFIXES]). Use [customerLeadIn]. */
    internal val NAME_PREFIXES: List<String> = PiiShapes.NAME_PREFIXES

    /** Chrome-ambiguous, tail-gated lead-ins (#1064; see [PiiShapes.GATED_NAME_PREFIXES]). */
    internal val GATED_NAME_PREFIXES: Map<String, (String) -> Boolean> = PiiShapes.GATED_NAME_PREFIXES

    /** The ONE owner of "does this value open with a customer lead-in?" — [PiiShapes.customerLeadIn]. */
    internal fun customerLeadIn(text: String): String? = PiiShapes.customerLeadIn(text)

    internal val PHONE: Regex = PiiShapes.PHONE
    internal val EMAIL: Regex = PiiShapes.EMAIL
    internal val STREET: Regex = PiiShapes.STREET
    internal val CITY_STATE_ZIP: Regex = PiiShapes.CITY_STATE_ZIP
    internal val FULL_ADDRESS: Regex = PiiShapes.FULL_ADDRESS
    internal val BARE_STREET: Regex = PiiShapes.BARE_STREET

    /**
     * The id-less first-name + last-initial shape, whole-value anchored — byte-SSOT with every
     * rule-side redact carrying it (`CaptureRedactionCorpusTest` FIX 1c). Derived in
     * [PiiShapes.FIRST_LAST_INITIAL_PATTERN] from the one [PiiShapes.FIRST_LAST_INITIAL_BODY].
     */
    const val FIRST_LAST_INITIAL_PATTERN = PiiShapes.FIRST_LAST_INITIAL_PATTERN
    internal val FIRST_LAST_INITIAL: Regex = PiiShapes.FIRST_LAST_INITIAL
    internal val APT: Regex = PiiShapes.APT
    internal val PIN: Regex = PiiShapes.PIN
    internal val QUOTED_NOTE: Regex = PiiShapes.QUOTED_NOTE
    internal val CARD: Regex = PiiShapes.CARD

    fun redact(jsonText: String): String {
        val root = try { json.parseToJsonElement(jsonText) } catch (e: Exception) { return jsonText }
        val repl = LinkedHashMap<String, String>() // escaped-original -> escaped-redacted
        collect(root, repl)
        var out = jsonText
        for ((orig, red) in repl) if (orig != red) out = out.replace(orig, red)
        return out
    }

    private fun collect(e: JsonElement, repl: MutableMap<String, String>) {
        when (e) {
            is JsonObject -> {
                if (e.containsKey("packageName") || e.containsKey("channelId")) collectNotif(e, repl)
                else collectNode(e, repl)
                e.values.forEach { collect(it, repl) }
            }
            is JsonArray -> e.forEach { collect(it, repl) }
            else -> {}
        }
    }

    private fun collectNode(o: JsonObject, repl: MutableMap<String, String>) {
        val piiById = CustomerTextMarkers.idMarkerFor(o["id"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content) != null
        // #835: iterate the production UiNodeTextField wire-name SSOT instead of
        // hand-listing text/desc/state, so a string field added to UiNodeDto is
        // scrubbed on the commit path automatically.
        for (field in UiNodeTextField.entries) {
            val v = o[field.wire] as? JsonPrimitive ?: continue
            if (!v.isString) continue
            record(v.content, scrub(v.content, piiById), repl)
        }
    }

    private fun collectNotif(o: JsonObject, repl: MutableMap<String, String>) {
        val isChat = (o["channelId"]?.jsonPrimitive?.content?.contains("inapp-chat") == true) ||
            (o["title"]?.jsonPrimitive?.content?.startsWith("Message from") == true)
        // #666: iterate the production RawNotificationData.textFields() wire-name
        // SSOT instead of hand-listing title/text/bigText/tickerText/subText.
        for (field in NotifTextField.entries) {
            val k = field.wire
            val v = o[k] as? JsonPrimitive ?: continue
            if (!v.isString) continue
            val red = if (isChat && field != NotifTextField.TITLE) MASK else scrub(v.content, false)
            record(v.content, red, repl)
        }
    }

    private fun record(orig: String, red: String, repl: MutableMap<String, String>) {
        if (orig == red || orig.isBlank()) return
        repl[esc(orig)] = esc(red)
    }

    /** The exact quoted/escaped form a string takes inside the JSON file. */
    private fun esc(s: String): String = Json.encodeToString(String.serializer(), s)

    private fun scrub(text: String, piiById: Boolean): String {
        if (text.isBlank()) return text
        if (piiById) return MASK
        customerLeadIn(text)?.let { return text.substring(0, it.length) + MASK }
        // A whole-value bare street line ("7610 Flecthers") with no recognized suffix — mask outright
        // before the token-level passes, so a residual unsuffixed street can't leak.
        if (BARE_STREET.matches(text.trim())) return "[address]"
        // A whole-value id-less first-name + last-initial customer name ("Brandon C") — the
        // multi-order drop-off confirm card residual (#501). Matched on RAW text (the pattern is
        // whitespace-tolerant) so this agrees byte-for-byte with the runtime redact's
        // containsMatchIn; whole-value anchored, so it fires only on a node that IS just the name.
        if (FIRST_LAST_INITIAL.matches(text)) return MASK
        var t = text
        t = EMAIL.replace(t, "[email]")
        t = PHONE.replace(t, "[phone]")
        t = CARD.replace(t, "[card]")
        t = QUOTED_NOTE.replace(t, "\"[note]\"")
        t = APT.replace(t) { it.value.substringBefore(it.groupValues[1]) + it.groupValues[1] + " " + MASK }
        t = PIN.replace(t) { it.groupValues[1] + " " + MASK }
        t = FULL_ADDRESS.replace(t, "[address]")
        t = STREET.replace(t, "[address]")
        t = CITY_STATE_ZIP.replace(t, "[address]")
        return t
    }
}
