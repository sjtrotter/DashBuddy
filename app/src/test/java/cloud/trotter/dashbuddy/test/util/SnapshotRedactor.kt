package cloud.trotter.dashbuddy.test.util

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

    /** Resource-id suffixes whose text value is always customer PII → fully masked. */
    private val PII_ID_SUFFIXES = setOf(
        "user_name", "customer_name", "address_line_1", "address_line_2",
        "address_subpremise_line", "primaryManeuverText",
        "message_self_message", "message_other_message", "message_input",
        "chat_input_text_field", "bottom_sheet_address_line_1", "bottom_sheet_address_line_2",
        "tvTitle", "tvLastMessage",
        "bottom_sheet_instructions", "step_description", "instructions_list", "instruction_text",
    )

    /** Text starting with one of these keeps the anchor prefix; the rest (a name/store) is masked. */
    private val NAME_PREFIXES = listOf(
        "Pickup for ", "Pickup from ", "Deliver to ", "Delivery for ", "Order for ",
        "Message from ", "Heading to ", "Pick up at ",
    )

    private val PHONE = Regex("""\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b""")
    private val EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
    private val STREET = Regex(
        """\b\d{1,6}\s+([A-Za-z0-9.'\-]+\s+){0,4}""" +
            """(Rd|Road|St|Street|Ave|Avenue|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|""" +
            """Hwy|Highway|Pkwy|Parkway|Pl|Place|Cir|Circle|Trl|Trail|Loop|Ter|Terrace|Pike|""" +
            """I-\d+|FM\s*\d+|US-?\d+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val APT = Regex("""(?i)\b(apt|suite|ste|unit|bldg|building|gate code|gate)\b[:#\s]*[A-Za-z0-9\-]+""")

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
        val piiById = (o["id"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content ?: "")
            .substringAfterLast('/') in PII_ID_SUFFIXES
        for (k in listOf("text", "desc", "state")) {
            val v = o[k] as? JsonPrimitive ?: continue
            if (!v.isString) continue
            record(v.content, scrub(v.content, piiById), repl)
        }
    }

    private fun collectNotif(o: JsonObject, repl: MutableMap<String, String>) {
        val isChat = (o["channelId"]?.jsonPrimitive?.content?.contains("inapp-chat") == true) ||
            (o["title"]?.jsonPrimitive?.content?.startsWith("Message from") == true)
        for (k in listOf("title", "text", "bigText", "tickerText", "subText")) {
            val v = o[k] as? JsonPrimitive ?: continue
            if (!v.isString) continue
            val red = if (isChat && k != "title") MASK else scrub(v.content, false)
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
        for (p in NAME_PREFIXES) {
            if (text.startsWith(p, ignoreCase = true) && text.length > p.length) return text.substring(0, p.length) + MASK
        }
        var t = text
        t = EMAIL.replace(t, "[email]")
        t = PHONE.replace(t, "[phone]")
        t = APT.replace(t) { it.value.substringBefore(it.groupValues[1]) + it.groupValues[1] + " " + MASK }
        t = STREET.replace(t, "[address]")
        return t
    }
}
