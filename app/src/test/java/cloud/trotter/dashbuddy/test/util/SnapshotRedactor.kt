package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.model.notification.NotifTextField
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
    internal val PII_ID_SUFFIXES = setOf(
        "user_name", "customer_name", "address_line_1", "address_line_2",
        "address_subpremise_line", "primaryManeuverText",
        // #886: the rest of the embedded Google-Nav maneuver cluster, which the runtime redact
        // on `dropoff_navigation` / `navigation_generic` declares customer PII. Kept in sync with
        // that block deliberately — a number-LESS destination street ("Canyon Golf Road ") carries
        // no digits, so STREET / FULL_ADDRESS / CITY_STATE_ZIP / BARE_STREET all structurally miss
        // it and a future committed nav fixture would ship the customer's street raw on the
        // commit path. Ids, not shapes, are the only handle here.
        "subManeuverText", "secondaryManeuverText", "roadNameView",
        // #993: DoorDash's OWN nav arrival banner. `arriving_at_title` restates the destination,
        // which on every dropoff-phase surface is the customer's full street address (the fielded
        // 08-02 frame carried "<street>, Apt <n>, <City>, <ST> <zip>, USA"). The runtime redact now
        // declares it on every dropoff rule + the two nav rules; without the id here the COMMIT
        // path stayed open, because the value is one fused line whose city/ST/ZIP tail the address
        // shapes would mask while leaving the house number + street standing.
        "arriving_at_title",
        "message_self_message", "message_other_message", "message_input",
        "chat_input_text_field", "bottom_sheet_address_line_1", "bottom_sheet_address_line_2",
        "tvTitle", "tvLastMessage",
        "bottom_sheet_instructions", "step_description", "instructions_list", "instruction_text",
        // #549: the dropoff-arrival card's free instruction text carries the customer's gate code +
        // note ("Leave at my door: Gate code 883423# …") — never parsed by any rule; masked here so
        // the committed corpus fixture can't leak it.
        "dasher_instruction_content_collapsed",
        // GoPuff (DoorDash Drive) batch screens (#501): the per-order customer name on the
        // bin-scan/pickup-steps screens.
        "order_cx_name",
    )

    /**
     * Text starting with one of these keeps the anchor prefix; the rest (a name/store) is masked
     * **unconditionally** — every one of them is a lead-in whose tail is customer data on every
     * surface that renders it.
     *
     * This is the COMMIT-path twin of the rules' `keepPrefix` enumerations, NOT of
     * `CustomerTextMarkers.MARKERS` — the runtime backstop must stay free of chrome-ambiguous
     * prefixes (a false positive there scrubs a live envelope), whereas an over-scrub here
     * normally only costs a little triage text in a committed fixture. **That asymmetry has a
     * floor (#1064):** an over-scrub that eats a rule's own recognition ANCHOR does not cost
     * triage text, it costs the fixture — the frame stops classifying and the golden guard
     * rejects it. `"Return "` was the receipt: added unconditionally by #994 for the timeline's
     * `"Return <FirstName L> to <store>"` line, it also masked DoorDash's own `"Return to dash"`
     * button — a `hasText` anchor on four rules — so the 09-05 intake's two `on_dash_map`
     * fixtures re-classified as `side_nav_drawer` and were set aside. It lives in
     * [GATED_NAME_PREFIXES] now, not here.
     *
     * Use [customerLeadIn], never this list directly: it is only half the enumeration.
     */
    internal val NAME_PREFIXES = listOf(
        "Pickup for ", "Pickup from ", "Deliver to ", "Delivery for ", "Order for ",
        "Message from ", "Heading to ", "Pick up at ",
    )

    /**
     * Chrome-AMBIGUOUS lead-ins: the prefix alone does not prove the tail is a customer, so each
     * carries a predicate over the tail and fires only when that predicate holds (#1064).
     *
     * `"Return "` is the only member. DoorDash renders it both as the timeline's return-order task
     * line (`"Return <FirstName L> to <store>"`, #994 — the customer's name, raw) and as its own
     * `"Return to dash"` navigation button (platform chrome, and a recognition anchor). The
     * discriminator is the conjugation's own `" to "` separator: the segment ahead of it is a
     * customer name on the task line (`"Riley P"`) and is not one on the button (`"to dash"` has
     * no separator, so the whole tail is tested and fails the name shape). The shape test is
     * [FIRST_LAST_INITIAL] itself — the byte-SSOT the rule side shares — never a second copy.
     *
     * The intake gate is deliberately belt-and-braces, not the primary control: on the RECOGNIZED
     * path the `doordash.screen.timeline` rule's own `redact` masks this line at the edge (#806
     * doctrine), and the runtime `CustomerTextMarkers` set still REJECTS a bare `"Return "` for
     * exactly the ambiguity above. What this gate covers is the frame that arrives UNKNOWN — a
     * layout change drops the timeline out of recognition and the raw name would reach the
     * commit path with no rule redact behind it.
     */
    internal val GATED_NAME_PREFIXES: Map<String, (String) -> Boolean> = mapOf(
        "Return " to { tail -> FIRST_LAST_INITIAL.matches(tail.substringBefore(" to ")) },
    )

    /**
     * The ONE owner of "does this value open with a customer lead-in whose tail is raw PII?" —
     * returns the prefix to keep, or null. Shared by [scrub] and the committed-corpus PII guard
     * (`CaptureRedactionCorpusTest` FIX 4) so the scrubber and the gate that polices its output
     * can never disagree about what a lead-in is (#1064).
     */
    internal fun customerLeadIn(text: String): String? {
        for (p in NAME_PREFIXES) {
            if (text.startsWith(p, ignoreCase = true) && text.length > p.length) return p
        }
        for ((p, tailIsCustomer) in GATED_NAME_PREFIXES) {
            if (text.startsWith(p, ignoreCase = true) && text.length > p.length &&
                tailIsCustomer(text.substring(p.length))
            ) {
                return p
            }
        }
        return null
    }

    private val PHONE = Regex("""\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b""")
    private val EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
    private val STREET = Regex(
        """\b\d{1,6}\s+([A-Za-z0-9.'\-]+\s+){0,4}""" +
            """(Rd|Road|St|Street|Ave|Avenue|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|""" +
            """Hwy|Highway|Pkwy|Parkway|Pl|Place|Cir|Circle|Trl|Trail|Loop|Ter|Terrace|Pike|""" +
            """Path|Walk|Pass|Run|Row|Bend|Bnd|Cove|Cv|Crossing|Xing|Square|Sq|Plaza|Plz|""" +
            """Point|Pt|Alley|Aly|Trace|Trce|Manor|Mnr|Grove|Grv|Ridge|Rdg|Creek|Crk|Hill|Hills|""" +
            """I-\d+|FM\s*\d+|US-?\d+)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "City, ST 78254" (incl. ZIP+4) — the city/state/zip line of a dropoff address, often id-less. */
    private val CITY_STATE_ZIP = Regex("""\b[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+){0,3},\s*[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""")
    /**
     * A full single-line address "7610 Fletchers, San Antonio, TX 78254" — house number + a
     * (possibly suffix-less) street, then city, ST zip. Masked whole so an unsuffixed street can't
     * survive ahead of the city/state/zip. Anchored to the ZIP so it can't run away.
     */
    private val FULL_ADDRESS = Regex("""\b\d{1,6}\s+[^,]+,\s*[A-Z][A-Za-z .'-]+,\s*[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""")
    /**
     * A bare street line with no recognized suffix, e.g. "7610 Flecthers" — `<housenum> <Word(s)>`
     * with the whole string being just that. Anchored to the full value so it can't eat "29 items"
     * or "$15.15 Guaranteed"; requires a capitalized street word, not a unit/count.
     */
    private val BARE_STREET = Regex("""^\d{2,6}\s+[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z0-9.'-]+){0,3}$""")
    /**
     * The canonical **id-less first-name + last-initial** customer-name shape, e.g. "Brandon C" /
     * "Gilberto U." / "José R" / "O'Brien M" / "Mary-Jo K" / "McKenna B" — the line on the
     * multi-order drop-off confirm card (#501), whose name node ships **no viewId** so it escapes
     * [PII_ID_SUFFIXES] and carries **no** "Deliver to "/"Message from " marker so the
     * [NAME_PREFIXES] pass and the runtime CustomerTextMarkers backstop both miss it (the
     * documented split-node residual).
     *
     * **SSOT (#362 class):** [FIRST_LAST_INITIAL_PATTERN] is byte-identical to the rule-side
     * `hasTextMatchesRegex` on EVERY rule that carries this shape — across the doordash
     * pickup/dropoff/camera surfaces and the uber trip surfaces; the parity test below owns the
     * live count, which is why no number is written here (it went stale twice) — and both sides
     * compile `IGNORE_CASE`,
     * so the committed corpus is scrubbed the SAME way the runtime redacts the live envelope. The
     * **authoritative enumeration is the parity tests**, not this KDoc: `CaptureRedactionCorpusTest`
     * asserts every such rule's redact regex is byte-equal to this constant, so a new site is
     * covered the moment it is added and this comment cannot rot into a wrong site list.
     *
     * Whole-value anchored (`^…$`, whitespace-tolerant via `\s{0,8}` so a trailing space still
     * matches — the runtime `containsMatchIn` on raw text and this `matches` then agree on
     * "Brandon C "), so it can't eat a multi-word merchant anchor ("SPROUTS FARMERS MARKET #118"),
     * a warehouse zone code ("SAT_San-Antonio_187"), or a count ("2 items"). Character classes are
     * `\p{L}` (accented/Unicode letters — José, Muñoz; interior capitals — McKenna) plus `'` and
     * `-` (O'Brien, D'Angelo, Mary-Jo); the last token is a single letter with an optional trailing
     * period. Every quantifier is bounded ({0,20}/{0,3}/{0,8}/{1,4}) so it passes RegexSafety's
     * ReDoS guard when the identical string is compiled on the rule side.
     *
     * **INTERIOR whitespace is `\s{1,4}`, not a literal space (#885).** The 07-27 pull fielded
     * `dropoff_multi_order_confirm` rendering the name with a DOUBLE space ("Firstname  L.") — the
     * layout, not the data — and the single-space separator this pattern used to carry did not
     * match, so the rule's redact entry existed but never fired and a raw customer name reached
     * the recognized envelope. Leading/trailing tolerance was never the gap; the SEPARATOR was.
     * `\s` covers the ASCII whitespace family (space/tab/newline), so a multi-space or tabbed
     * render now masks identically; a non-ASCII separator (NBSP) is NOT covered by `\s` and stays
     * the documented residual — the `CustomerTextMarkers` backstop and this scrubber's other
     * passes remain downstream of it.
     *
     * **Accepted over-mask trade-off (privacy-first):** because it is case-insensitive and
     * shape-only, a two-token UI label that happens to end in a single letter — "Vitamin C",
     * "Terminal B", "Plan B" — would also be masked. That is deliberate: a rare over-mask of a
     * non-PII label is caught by the snapshot diff review + the [GoldenSnapshotRegressionTest]
     * anchor guard (a masked anchor a rule needs turns the test red), whereas leaking a real
     * customer name is the dangerous, silent failure. The `hasNoId` conjunct on the rule side and
     * the whole-value anchoring here keep the surface small.
     */
    const val FIRST_LAST_INITIAL_PATTERN =
        "^\\s{0,8}[\\p{L}][\\p{L}'-]{0,20}(\\s{1,4}[\\p{L}][\\p{L}'-]{0,20}){0,3}\\s{1,4}[A-Z]\\.?\\s{0,8}$"
    private val FIRST_LAST_INITIAL = Regex(FIRST_LAST_INITIAL_PATTERN, RegexOption.IGNORE_CASE)
    private val APT = Regex("""(?i)\b(apt|suite|ste|unit|bldg|building|gate code|gate)\b[:#\s]*[A-Za-z0-9\-]+""")
    /**
     * A residence-entry PIN, e.g. "pin 4821" / "PIN: 4821" / "pin:4821" / "Pin4821"
     * (#803). Kept SEPARATE from [APT]'s alternation (never folded in) because "pin"
     * needs a **digit-adjacency** guard the APT shape lacks — `[:#\s]*[A-Za-z0-9-]+`
     * would mask "PIN pad" / "pin it". Word-bounded lead-in (`\bpin`, so
     * "shopping"/"opinion" don't match), a `[\s:#]` separator class (covers the colon
     * variants), and **no trailing `\b`** so the fused "Pin4821" shape is caught too —
     * "pinch"/"opinion" stay clean because the char after "pin" is constrained to
     * `[\s:#]` or a digit. Requires ≥3 trailing digits; the "pin" lead-in is kept, the
     * digits become [MASK]. Byte-aligned with the rule-side `\bpin[\s:#]*\d{3}` and the
     * [SnapshotSecurityScanner] shape (three-layer parity, #362 class).
     */
    private val PIN = Regex("""(?i)\b(pin)[\s:#]*\d{3,}""")
    /** A quoted free-text customer note, e.g. "Corner House, please leave at door." — customer-entered, mask whole. */
    private val QUOTED_NOTE = Regex(""""[^"]{6,}"""")

    /** Masked payout/debit card on cashout screens, e.g. "Visa ••••6222" or "Debit card ....1234". */
    private val CARD = Regex(
        """(?i)\b(visa|mastercard|amex|american express|discover|debit card)\b""" +
            """[\s ]*[•·•*xX.]{2,}[\s ]*\d{2,4}""",
    )

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
