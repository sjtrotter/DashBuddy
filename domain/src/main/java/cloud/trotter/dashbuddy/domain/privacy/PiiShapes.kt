package cloud.trotter.dashbuddy.domain.privacy

/**
 * The customer-PII SHAPE vocabulary — promoted out of the test-only `SnapshotRedactor` (#1145,
 * ADR-0011 §2) so the census chrome-likely filter and the corpus intake share ONE owner and can never
 * drift apart. `SnapshotRedactor` delegates every constant here; `PiiShapesParityTest` pins the bytes
 * (pattern text AND options) and the identity of each delegate.
 *
 * Two consumers, two uses of the same patterns:
 * - the COMMIT path (`SnapshotRedactor.redact`, test-only) REPLACES matches with masks;
 * - the census (`SkeletonBuilder`, `:core:pipeline`) only asks "does it match?" and withholds the
 *   hash. Each pattern keeps the match mode the redactor uses ([MatchMode], [VALUE_SHAPES]).
 *
 * Every pattern here is app-authored and bounded (fixed-count repetitions, no nested unbounded
 * quantifiers). The census runs them only on values already capped at 40 characters. They are Kotlin
 * [Regex] (the JDK/ICU engine), NOT rule regexes: rule-authored patterns go through `RegexSafety` onto
 * RE2J (#1053) and none of these may move into the rule package. `IcuRegexGuardTest` sees only the
 * `Regex("literal")` sites here — the `const`-built and `+`-concatenated patterns are invisible to it — so
 * `PiiShapesIcuGuardTest` applies the same bare-`}` rule to every COMPILED pattern this object exposes.
 *
 * Licence: this file is part of the app (PolyForm Shield), deliberately OUTSIDE the Apache-2.0
 * census contract package.
 */
object PiiShapes {

    /** How a shape is applied — the mode `SnapshotRedactor` uses today, pinned by the parity test. */
    enum class MatchMode {
        /** The WHOLE (trimmed) value must match (`Regex.matches`). */
        WHOLE_VALUE,

        /** Any occurrence inside the value (`Regex.containsMatchIn`). */
        SUBSTRING,
    }

    /** One promoted value shape with its [mode]. */
    class PiiShape(val name: String, val regex: Regex, val mode: MatchMode) {
        /** True when [value] carries this shape in [mode]. */
        fun hits(value: String): Boolean = when (mode) {
            MatchMode.WHOLE_VALUE -> regex.matches(value)
            MatchMode.SUBSTRING -> regex.containsMatchIn(value)
        }
    }

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
    val NAME_PREFIXES = listOf(
        "Pickup for ", "Pickup from ", "Deliver to ", "Delivery for ", "Order for ",
        "Message from ", "Heading to ", "Pick up at ",
    )


    /**
     * The ONE owner of "does this value open with a customer lead-in whose tail is raw PII?" —
     * returns the prefix to keep, or null. Shared by `SnapshotRedactor.scrub` and the committed-corpus PII guard
     * (`CaptureRedactionCorpusTest` FIX 4) so the scrubber and the gate that polices its output
     * can never disagree about what a lead-in is (#1064).
     */
    fun customerLeadIn(text: String): String? {
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

    val PHONE = Regex("""\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b""")
    val EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
    val STREET = Regex(
        """\b\d{1,6}\s+([A-Za-z0-9.'\-]+\s+){0,4}""" +
            """(Rd|Road|St|Street|Ave|Avenue|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|""" +
            """Hwy|Highway|Pkwy|Parkway|Pl|Place|Cir|Circle|Trl|Trail|Loop|Ter|Terrace|Pike|""" +
            """Path|Walk|Pass|Run|Row|Bend|Bnd|Cove|Cv|Crossing|Xing|Square|Sq|Plaza|Plz|""" +
            """Point|Pt|Alley|Aly|Trace|Trce|Manor|Mnr|Grove|Grv|Ridge|Rdg|Creek|Crk|Hill|Hills|""" +
            """I-\d+|FM\s*\d+|US-?\d+)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "City, ST 78254" (incl. ZIP+4) — the city/state/zip line of a dropoff address, often id-less. */
    val CITY_STATE_ZIP = Regex("""\b[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+){0,3},\s*[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""")
    /**
     * A full single-line address "7610 Fletchers, San Antonio, TX 78254" — house number + a
     * (possibly suffix-less) street, then city, ST zip. Masked whole so an unsuffixed street can't
     * survive ahead of the city/state/zip. Anchored to the ZIP so it can't run away.
     */
    val FULL_ADDRESS = Regex("""\b\d{1,6}\s+[^,]+,\s*[A-Z][A-Za-z .'-]+,\s*[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""")
    /**
     * A bare street line with no recognized suffix, e.g. "7610 Flecthers" — `<housenum> <Word(s)>`
     * with the whole string being just that. Anchored to the full value so it can't eat "29 items"
     * or "$15.15 Guaranteed"; requires a capitalized street word, not a unit/count.
     */
    val BARE_STREET = Regex("""^\d{2,6}\s+[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z0-9.'-]+){0,3}$""")
    /**
     * The name tokens and the separator before the initial — the part of [FIRST_LAST_INITIAL_BODY]
     * both variants share verbatim. Only the INITIAL differs between them (see
     * [FIRST_LAST_INITIAL_EMBEDDED]).
     */
    const val FIRST_LAST_INITIAL_TOKENS =
        "[\\p{L}][\\p{L}'-]{0,20}(\\s{1,4}[\\p{L}][\\p{L}'-]{0,20}){0,3}\\s{1,4}"

    /**
     * The canonical **id-less first-name + last-initial** customer-name shape, e.g. "Brandon C" /
     * "Gilberto U." / "José R" / "O'Brien M" / "Mary-Jo K" / "McKenna B" — the line on the
     * multi-order drop-off confirm card (#501), whose name node ships **no viewId** so it escapes
     * the intake id list (`CustomerTextMarkers.ID_MARKER_SUFFIXES`) and carries **no** "Deliver to "/"Message from " marker so the
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
     * period. Every construct here is RE2-compatible (`\p{L}`, bounded quantifiers, char classes,
     * no lookaround) so the identical string compiles on the rule side, where it runs on RE2J
     * (#1053). This app-side copy stays a Kotlin [Regex]: it is app-authored, not rule-authored.
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
     * non-PII label is caught by the snapshot diff review + the `GoldenSnapshotRegressionTest`
     * anchor guard (a masked anchor a rule needs turns the test red), whereas leaking a real
     * customer name is the dangerous, silent failure. The `hasNoId` conjunct on the rule side and
     * the whole-value anchoring here keep the surface small.
     */
    const val FIRST_LAST_INITIAL_BODY = FIRST_LAST_INITIAL_TOKENS + "[A-Z]\\.?"

    /**
     * The ANCHORED whole-value variant — byte-identical to the pre-#1145 constant and to every rule-side
     * `hasTextMatchesRegex` that carries the shape (the redact side, unchanged).
     */
    const val FIRST_LAST_INITIAL_PATTERN = "^\\s{0,8}" + FIRST_LAST_INITIAL_BODY + "\\s{0,8}$"

    /**
     * The BOUNDARY-DELIMITED substring variant (ADR-0011 §2 step 7) that the census runs with
     * `containsMatchIn`. Searching [FIRST_LAST_INITIAL_PATTERN] would still reject
     * `Jane S is waiting at the door` — its anchors survive `containsMatchIn` — so the census needs
     * its own delimiters: no letter immediately before or after the shape.
     *
     * The INITIAL is CASE-SENSITIVE (`(?-i:[A-Z])`) even though the regex compiles `IGNORE_CASE`
     * (#1160 review AA3): case-insensitively the single-letter "initial" matched the English words
     * "a"/"i", so every `<word> a <word>` chrome phrase ("Take a photo", "Report a problem") was
     * withheld — a systematic recall hole. A lowercase WHOLE-VALUE name ("jordan t") is still caught,
     * by the anchored [FIRST_LAST_INITIAL] in [hasNameShape]. Residual over-withholding: a capital
     * "I" mid-sentence ("Can I help") still reads as an initial.
     */
    const val FIRST_LAST_INITIAL_EMBEDDED =
        "(?<![\\p{L}])" + FIRST_LAST_INITIAL_TOKENS + "(?-i:[A-Z])\\.?" + "(?![\\p{L}])"

    val FIRST_LAST_INITIAL = Regex(FIRST_LAST_INITIAL_PATTERN, RegexOption.IGNORE_CASE)

    /**
     * The FULLY case-sensitive id-path variant (#1160 reviews SS3, XX4, ZZ4): an UPPERCASE-led first token —
     * Capitalized ("Riley S") OR all-caps ("RILEY S", a SCREAMING_SNAKE test tag `chip_RILEY_S`) — and an
     * uppercase initial, compiled WITHOUT `IGNORE_CASE`. A lowercase-led `tab B` / `option A` stays chrome.
     * Accepted recall cost (ZZ4, fail closed): all-caps constants of the same shape (`TAB_B`, `SECTION_C`,
     * `PRIMARY_BUTTON_A`) are withheld too — ADR-0011 residual risk 10.
     */
    const val FIRST_LAST_INITIAL_ID_PATH =
        "(?<![\\p{L}])(?=\\p{Lu})" + FIRST_LAST_INITIAL_TOKENS + "\\p{Lu}\\.?" + "(?![\\p{L}])"

    /** [FIRST_LAST_INITIAL_ID_PATH], case-sensitive — the id-path name matcher. */
    val FIRST_LAST_INITIAL_ID_PATH_REGEX = Regex(FIRST_LAST_INITIAL_ID_PATH)

    /** [FIRST_LAST_INITIAL_EMBEDDED], compiled `IGNORE_CASE` (the initial stays case-sensitive). */
    val FIRST_LAST_INITIAL_EMBEDDED_REGEX = Regex(FIRST_LAST_INITIAL_EMBEDDED, RegexOption.IGNORE_CASE)

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
    // #1160 review AH6: DECLARED AFTER [FIRST_LAST_INITIAL] on purpose — object properties initialize in
    // declaration order, so every regex this map's predicates read is built before the map exists (the
    // #909 `<clinit>` class). Keep this block below the name-shape regexes.
    val GATED_NAME_PREFIXES: Map<String, (String) -> Boolean> = mapOf(
        "Return " to { tail -> FIRST_LAST_INITIAL.matches(tail.substringBefore(" to ")) },
        // #1127: the "Can't hand order to customer" page's `Contact <First L>` step title. Gated on the
        // name shape so the chrome rows ('Contact support', the 8.98.5 'Contact Customer') never match;
        // this is what lets intake (SnapshotRedactor) and the FIX 4 corpus guard see the slot at all.
        "Contact " to { tail -> FIRST_LAST_INITIAL.matches(tail) },
    )

    /**
     * The census step-7 predicate (ADR-0011 §2): the WHOLE value is the anchored redact-side name
     * shape (so everything the commit path masks as a name is withheld), OR the boundary-delimited
     * embedded shape occurs anywhere in it.
     */
    fun hasNameShape(value: String): Boolean =
        FIRST_LAST_INITIAL.matches(value) || FIRST_LAST_INITIAL_EMBEDDED_REGEX.containsMatchIn(value)

    val APT = Regex("""(?i)\b(apt|suite|ste|unit|bldg|building|gate code|gate)\b[:#\s]*[A-Za-z0-9\-]+""")
    /**
     * A residence-entry PIN, e.g. "pin 4821" / "PIN: 4821" / "pin:4821" / "Pin4821"
     * (#803). Kept SEPARATE from [APT]'s alternation (never folded in) because "pin"
     * needs a **digit-adjacency** guard the APT shape lacks — `[:#\s]*[A-Za-z0-9-]+`
     * would mask "PIN pad" / "pin it". Word-bounded lead-in (`\bpin`, so
     * "shopping"/"opinion" don't match), a `[\s:#]` separator class (covers the colon
     * variants), and **no trailing `\b`** so the fused "Pin4821" shape is caught too —
     * "pinch"/"opinion" stay clean because the char after "pin" is constrained to
     * `[\s:#]` or a digit. Requires ≥3 trailing digits; the "pin" lead-in is kept, the
     * digits become `[redacted]` on the commit path. Byte-aligned with the rule-side `\bpin[\s:#]*\d{3}` and the
     * `SnapshotSecurityScanner` shape (three-layer parity, #362 class).
     */
    val PIN = Regex("""(?i)\b(pin)[\s:#]*\d{3,}""")
    /** A quoted free-text customer note, e.g. "Corner House, please leave at door." — customer-entered, mask whole. */
    val QUOTED_NOTE = Regex(""""[^"]{6,}"""")

    // --- #1116: CONTEXTUAL address-block predicates -------------------------------------------
    // Line-level helpers for the UNKNOWN-only address-block backstop (`UnknownAddressBackstop`,
    // `:core:pipeline`). They are deliberately NOT [VALUE_SHAPES] members and must never become
    // unconditional census filters: `"000"` or a lone digit is a customer code ONLY inside an address
    // block, and chrome everywhere else. They add no pattern — the street/city tests reuse [STREET],
    // [BARE_STREET] and [CITY_STATE_ZIP] byte-for-byte; the cheap first/last-character checks only
    // skip the regex on values that cannot match.

    /**
     * A street line: [STREET] matching at offset zero of the trimmed value (the whole field is then the
     * address, trailing unit included), or the whole trimmed value a [BARE_STREET]. Digit-led only.
     */
    fun isStreetLine(value: String): Boolean {
        val t = value.trim()
        if (t.isEmpty() || t[0] !in '0'..'9') return false
        return STREET.matchAt(t, 0) != null || BARE_STREET.matches(t)
    }

    /** A whole-value `City, ST 12345` line ([CITY_STATE_ZIP], ZIP+4 included). */
    fun isCityStateZipLine(value: String): Boolean {
        val t = value.trim()
        if (t.length < 9 || t.last() !in '0'..'9' || ',' !in t) return false
        return CITY_STATE_ZIP.matches(t)
    }

    /**
     * A quote-led value — a customer-entered note. Only the opening quote is required (`"` or `“`):
     * no closing quote, no minimum length, so `"000"` and `"4417"` qualify where [QUOTED_NOTE] (six
     * characters inside quotes) does not.
     */
    fun isQuoteLeading(value: String): Boolean {
        val t = value.trim()
        return t.startsWith('"') || t.startsWith('\u201C')
    }

    /** The whole trimmed value is one to six ASCII digits — a gate/door/locker code in an address block. */
    fun isShortCode(value: String): Boolean {
        val t = value.trim()
        return t.length in 1..6 && t.all { it in '0'..'9' }
    }

    /** Masked payout/debit card on cashout screens, e.g. "Visa ••••6222" or "Debit card ....1234". */
    val CARD = Regex(
        """(?i)\b(visa|mastercard|amex|american express|discover|debit card)\b""" +
            """[\s ]*[•·•*xX.]{2,}[\s ]*\d{2,4}""",
    )

    /**
     * Every promoted VALUE shape (ADR-0011 §2 step 8) with the match mode `SnapshotRedactor` applies:
     * [BARE_STREET] whole-value (the redactor tests it with `matches` on the trimmed value), every
     * other one substring (the redactor `replace`s occurrences). The name shapes are step 7's
     * ([FIRST_LAST_INITIAL_EMBEDDED_REGEX]) and are not repeated here. This is the ONE list — no
     * census-specific subset is hand-maintained.
     */
    val VALUE_SHAPES: List<PiiShape> = listOf(
        PiiShape("EMAIL", EMAIL, MatchMode.SUBSTRING),
        PiiShape("PHONE", PHONE, MatchMode.SUBSTRING),
        PiiShape("CARD", CARD, MatchMode.SUBSTRING),
        PiiShape("QUOTED_NOTE", QUOTED_NOTE, MatchMode.SUBSTRING),
        PiiShape("APT", APT, MatchMode.SUBSTRING),
        PiiShape("PIN", PIN, MatchMode.SUBSTRING),
        PiiShape("FULL_ADDRESS", FULL_ADDRESS, MatchMode.SUBSTRING),
        PiiShape("STREET", STREET, MatchMode.SUBSTRING),
        PiiShape("CITY_STATE_ZIP", CITY_STATE_ZIP, MatchMode.SUBSTRING),
        PiiShape("BARE_STREET", BARE_STREET, MatchMode.WHOLE_VALUE),
    )

    /**
     * Every mask literal the redact side and the corpus intake emit, matched as a SUBSTRING (ADR-0011 §2
     * step 5): the runtime `CompiledRedact` masks (`[redacted]`, `[redacted:<4hex>]` — both start with
     * `[redacted`), the commit-path `SnapshotRedactor` masks (`[address]`, `[email]`, `[phone]`,
     * `[card]`, `"[note]"`), the shareable-log scrub (`[scrubbed:<marker>]`) and the hand mask the
     * committed corpus carries (`[name]`). A mask must never be hashed — `[address]` would otherwise
     * strip to `words:1`. `[icon]` is NOT here: it is DoorDash's own rendered chrome, not a mask.
     */
    val MASK_LITERALS: List<String> = listOf(
        MaskTokens.REDACTED_PREFIX,
        "[address]",
        "[email]",
        "[phone]",
        "[card]",
        "[note]",
        "[name]",
        "[scrubbed:",
    )

    /** True when [value] contains any [MASK_LITERALS] entry anywhere (case-insensitive). */
    fun containsMask(value: String): Boolean = MASK_LITERALS.any { value.contains(it, ignoreCase = true) }
}
