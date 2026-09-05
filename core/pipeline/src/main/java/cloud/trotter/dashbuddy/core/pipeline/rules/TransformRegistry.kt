package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Closed, engine-owned vocabulary of value transforms.
 *
 * All transform names are compile-time validated: unknown names throw
 * [RuleCompileException] during rule loading, never at match time. Implementations
 * are pure functions over string values — no Android, no UiNode, no IO.
 *
 * The sibling validate-assertion vocabulary (assertions over the parsed field
 * map) lives in [ValidateRegistry] — the two share no state and dispatch
 * independently (audit #13).
 *
 * Matches ADR-0001 v2 specification.
 */
object TransformRegistry {

    /** Compiled-regex cache for the `regex` transform (#362) — the spec is
     *  re-dispatched per value, so without this every value recompiled. */
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, BoundedRegex>()

    /**
     * Reference clock for time transforms (#343): the OBSERVATION's instant + zone,
     * not evaluation wall-clock — re-parsing a captured screen at a different hour
     * (or replaying it in tests) must yield the same millis.
     */
    data class TransformClock(val nowMillis: Long, val zoneId: ZoneId)

    // Scoped per classification via [withClock]. Classification is synchronous per
    // event, so a ThreadLocal is safe (pipelines classify concurrently on different
    // threads). Promote to an explicit parameter when the compiled-lambda signatures
    // are reworked (#239/#362).
    private val scopedClock = ThreadLocal<TransformClock?>()

    /**
     * Runs [block] with time transforms anchored to [nowMillis]/[zoneId].
     * Unscoped callers fall back to the system clock.
     */
    fun <T> withClock(
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        block: () -> T,
    ): T {
        scopedClock.set(TransformClock(nowMillis, zoneId))
        return try {
            block()
        } finally {
            scopedClock.remove()
        }
    }

    private fun currentClock(): TransformClock =
        scopedClock.get() ?: TransformClock(System.currentTimeMillis(), ZoneId.systemDefault())

    /**
     * Threshold for rolling a parsed wall-clock time forward to tomorrow.
     * Past by more than this → assume the deadline is tomorrow (e.g. late-night
     * offer for "6:00 AM" next morning). Past by less than this → treat as
     * past (e.g. dasher arrived a few minutes late for the pickup-by deadline,
     * which should render as "X min late" — not a near-24h countdown).
     */
    internal const val ROLLOVER_THRESHOLD_MS = 12L * 3600L * 1000L

    /**
     * Apply the rollover rule to a today-anchored target timestamp.
     * Pure function over millis — extracted so it can be unit-tested without
     * depending on the wall clock. See [ROLLOVER_THRESHOLD_MS].
     */
    internal fun applyRollover(
        targetMillis: Long,
        nowMillis: Long,
        thresholdMs: Long = ROLLOVER_THRESHOLD_MS,
    ): Long {
        val pastMillis = nowMillis - targetMillis
        return if (pastMillis > thresholdMs) targetMillis + 24L * 3600L * 1000L else targetMillis
    }

    // ========================================================================
    //  Plain transforms: (String?) -> Any?
    // ========================================================================

    fun apply(name: String, value: String?): Any? {
        if (value == null) return null
        return when (name) {
            "parseCurrency" -> parseCurrency(value)
            "parseGlyphCurrency" -> parseGlyphCurrency(value)
            "parseDistance" -> parseDistance(value)
            "parseItemCount" -> parseItemCount(value)
            "parseItemCountUnit" -> parseItemCountUnit(value)
            "parseDeadline" -> parseDeadlineMillis(value)
            "parseTime" -> parseTimeTextToMillis(value)
            "parseDuration" -> parseDuration(value)
            "parseHrMin" -> parseHrMin(value)
            "parseMinutes" -> parseMinutes(value)
            "parseLeadingInt" -> parseLeadingInt(value)
            "parsePercent" -> parsePercent(value)
            "sha256" -> sha256OrNull(value)
            "normalizeCustomerName" -> customerNameKey(value)
            "trim" -> value.trim()
            "lower" -> value.lowercase(Locale.ROOT)
            "upper" -> value.uppercase(Locale.ROOT)
            "toDouble" -> value.toDoubleOrNull()
            "toInt" -> value.toIntOrNull()
            "stripDeadlinePrefix" -> stripDeadlinePrefix(value)
            else -> throw RuleCompileException("Unknown plain transform: '$name'")
        }
    }

    /**
     * Parameterized transform: spec is a JsonObject with the transform name as key
     * and parameters as nested object.
     */
    fun apply(spec: JsonObject, value: String?): Any? {
        if (value == null) return null
        val key = spec.keys.singleOrNull()
            ?: throw RuleCompileException(
                "Transform spec must have exactly one key, got: ${spec.keys}",
            )
        val params = spec[key]!!

        return when (key) {
            "stripPrefix" -> {
                val prefix = params.jsonPrimitive.content
                if (value.startsWith(prefix, ignoreCase = true))
                    value.substring(prefix.length).trim()
                else value
            }

            "stripSuffix" -> {
                val suffix = params.jsonPrimitive.content
                if (value.endsWith(suffix, ignoreCase = true))
                    value.substring(0, value.length - suffix.length).trim()
                else value
            }

            "stripPrefixes" -> {
                val prefixes = params.jsonArray.map { it.jsonPrimitive.content }
                var result: String = value
                for (prefix in prefixes) {
                    if (result.startsWith(prefix, ignoreCase = true)) {
                        result = result.substring(prefix.length).trim()
                        break
                    }
                }
                result
            }

            "extractBefore" -> {
                val sep = params.jsonPrimitive.content
                val idx = value.indexOf(sep)
                if (idx >= 0) value.substring(0, idx).trim() else value
            }

            "extractAfter" -> {
                val sep = params.jsonPrimitive.content
                val idx = value.indexOf(sep)
                if (idx >= 0) value.substring(idx + sep.length).trim() else null
            }

            "replace" -> {
                val obj = params.jsonObject
                val pattern = obj["pattern"]!!.jsonPrimitive.content
                val replacement = obj["replacement"]?.jsonPrimitive?.content ?: ""
                value.replace(pattern, replacement, ignoreCase = true)
            }

            "split" -> {
                val obj = params.jsonObject
                val separator = obj["separator"]!!.jsonPrimitive.content
                val index = obj["index"]!!.jsonPrimitive.int
                value.split(separator).getOrNull(index)?.trim()
            }

            "regex" -> {
                val obj = params.jsonObject
                val pattern = obj["pattern"]!!.jsonPrimitive.content
                val group = obj["group"]?.jsonPrimitive?.intOrNull ?: 0
                val thenTransform = obj["then"]
                val regex = regexCache.getOrPut(pattern) { RuleCompiler.compileRegex(pattern) }
                val match = regex.find(value) ?: return null
                val extracted = match.groupValues.getOrNull(group) ?: return null
                if (thenTransform != null) {
                    applyAny(thenTransform, extracted)
                } else extracted
            }

            else -> throw RuleCompileException("Unknown parameterized transform: '$key'")
        }
    }

    /**
     * Chain: apply a sequence of transforms. Each transform's output becomes the
     * next transform's input (coerced to String via toString if non-null).
     */
    fun chain(specs: JsonArray, value: Any?): Any? {
        var current = value
        for (spec in specs) {
            val str = current?.toString()
            current = applyAny(spec, str)
        }
        return current
    }

    /**
     * Dispatch a transform spec that can be a plain string name, a parameterized
     * object, or an array (chain).
     */
    fun applyAny(spec: JsonElement, value: String?): Any? = when (spec) {
        is JsonPrimitive -> apply(spec.content, value)
        is JsonObject -> apply(spec, value)
        is JsonArray -> chain(spec, value)
    }

    // ========================================================================
    //  Compile-time validation
    // ========================================================================

    private val knownPlainTransforms = setOf(
        "parseCurrency", "parseGlyphCurrency",
        "parseDistance", "parseItemCount", "parseItemCountUnit", "parseDeadline",
        "parseTime", "parseDuration", "parseHrMin", "parseMinutes", "parseLeadingInt",
        "parsePercent", "sha256", "normalizeCustomerName", "trim", "lower", "upper",
        "toDouble", "toInt", "stripDeadlinePrefix",
    )

    private val knownParameterizedTransforms = setOf(
        "stripPrefix", "stripSuffix", "stripPrefixes",
        "extractBefore", "extractAfter",
        "replace", "split", "regex",
    )

    /**
     * Validate at compile time that a plain transform name is known.
     * Call during rule loading to fail fast on typos.
     */
    fun validateTransformName(name: String) {
        if (name !in knownPlainTransforms)
            throw RuleCompileException("Unknown transform: '$name'")
    }

    /**
     * Validate a transform spec at compile time: plain string, parameterized
     * object, or chain array. Recursively validates all nested transforms
     * (e.g. `regex.then`). Throws [RuleCompileException] on unknown names.
     */
    fun validateTransformSpec(spec: JsonElement) {
        when (spec) {
            is JsonPrimitive -> validateTransformName(spec.content)
            is JsonObject -> {
                val key = spec.keys.singleOrNull()
                    ?: throw RuleCompileException(
                        "Transform spec must have exactly one key, got: ${spec.keys}",
                    )
                if (key !in knownParameterizedTransforms)
                    throw RuleCompileException("Unknown parameterized transform: '$key'")
                validateTransformParams(key, spec[key]!!)
            }
            is JsonArray -> spec.forEach { validateTransformSpec(it) }
        }
    }

    /**
     * Required-parameter checks at COMPILE time (#362): a malformed spec like
     * `{"split":{"separator":","}}` (missing `index`) used to pass validation
     * and NPE at match time. Every parameterized transform's required shape is
     * asserted here, so match-time access can assume it.
     */
    private fun validateTransformParams(key: String, params: JsonElement) {
        fun fail(msg: String): Nothing =
            throw RuleCompileException("Transform '$key': $msg")

        when (key) {
            "stripPrefix", "stripSuffix", "extractBefore", "extractAfter" -> {
                if (params !is JsonPrimitive || !params.isString) fail("requires a string parameter")
            }
            "stripPrefixes" -> {
                if (params !is JsonArray || params.isEmpty()) fail("requires a non-empty array")
                if (params.any { it !is JsonPrimitive }) fail("array entries must be strings")
            }
            "replace" -> {
                val obj = params as? JsonObject ?: fail("requires an object with 'pattern'")
                if (obj["pattern"]?.jsonPrimitive?.isString != true) fail("missing required 'pattern'")
            }
            "split" -> {
                val obj = params as? JsonObject ?: fail("requires an object with 'separator' and 'index'")
                if (obj["separator"]?.jsonPrimitive?.isString != true) fail("missing required 'separator'")
                if (obj["index"]?.jsonPrimitive?.intOrNull == null) fail("missing required integer 'index'")
            }
            "regex" -> {
                val obj = params as? JsonObject ?: fail("requires an object with 'pattern'")
                val pattern = obj["pattern"]?.jsonPrimitive?.content
                    ?: fail("missing required 'pattern'")
                RuleCompiler.compileRegex(pattern)
                obj["then"]?.let { validateTransformSpec(it) }
            }
        }
    }

    // ========================================================================
    //  Transform implementations
    // ========================================================================

    /**
     * Parses currency strings: "$10.50", "+$4.00", "$7.75+ Total..."
     */
    private fun parseCurrency(text: String): Double? {
        val clean = text.replace("$", "").replace("+", "").replace(",", "").trim()
        return clean.split(" ").firstOrNull()?.toDoubleOrNull()
    }

    /**
     * Longest input [parseGlyphCurrency] will look at (#1029, bounded ingestion). A glyph wheel's
     * whole subtree text is a label plus a handful of digits — "This dash so far$16.70" is 22
     * chars. Anything materially longer means the rule is aimed at the wrong container, and a
     * fail-CLOSED null is the honest answer there; it also keeps the scan trivially bounded on the
     * classification thread.
     */
    internal const val MAX_GLYPH_CURRENCY_INPUT = 256

    /** The only characters a currency figure is built from. Everything else is label or spacer. */
    private const val GLYPH_CURRENCY_ALPHABET = "$0123456789.,"

    /**
     * Characters that change a figure's VALUE rather than spell it (#1052): ASCII hyphen-minus,
     * the true minus sign U+2212, and the accounting negative's opening paren. The keep-filter
     * would delete all three and read the magnitude as a positive; they reject the whole input
     * instead. See [parseGlyphCurrency].
     */
    private const val GLYPH_CURRENCY_SIGNS = "-−("

    /**
     * [GLYPH_CURRENCY_SIGNS] as CODE POINTS (#1052 round 2), derived from that one string rather
     * than listed twice. The scan below is code-point-wise, so membership has to be too — a sign
     * outside the BMP would otherwise be tested one surrogate half at a time and never match.
     */
    private val GLYPH_CURRENCY_SIGN_CODE_POINTS: Set<Int> = buildSet {
        var i = 0
        while (i < GLYPH_CURRENCY_SIGNS.length) {
            val cp = GLYPH_CURRENCY_SIGNS.codePointAt(i)
            add(cp)
            i += Character.charCount(cp)
        }
    }

    /**
     * A **settled** currency figure: one leading `$` (written `\x24` so neither the Kotlin string
     * template nor a regex anchor is in play) followed by [CurrencyShape.FIGURE_CORE] — the ONE
     * shape definition this and the rules' money scans now share, rather than two hand-written
     * patterns that were loose in different ways. Applied with [Regex.matches], which anchors the
     * WHOLE input by construction — deliberately strict; see [parseGlyphCurrency].
     */
    private val SETTLED_GLYPH_CURRENCY = Regex("\\x24" + CurrencyShape.FIGURE_CORE)

    /**
     * Reconstructs a currency figure from an **animated digit-wheel** render (#1029).
     *
     * DoorDash 8.93.7 removed every money view id from the receipt sheet and the on-dash earnings
     * pill and now renders the figure as per-glyph, id-less `TextView`s — `'$'`, `'1'`, `'6'`,
     * `'.'`, `'7'`, `'0'` — each in its own wrapper `View`, beside a label node ("This dash so
     * far", "This dash", "This week"). `read: allText` joins a subtree's text with the EMPTY
     * separator, so the container reads as one fused string: `"This dash so far$16.70"`.
     * [parseCurrency] is not merely useless on that shape, it is WRONG — it splits on space and
     * takes the first token, so a space-separated wheel reads `"$ 1 6 . 7 0"` → `$1.00`.
     *
     * Three steps, all fail-closed:
     *  0. **Reject what the keep-filter cannot read** (#1052) — any non-ASCII digit, and any of
     *     `-` / `−` / `(`. A keep-filter DELETES what it does not recognise, so without this a
     *     dropped Arabic-Indic digit or a stripped minus sign leaves a remainder that still
     *     full-matches, turning an unreadable figure into a confident wrong one. Scanned by CODE
     *     POINT, not by `Char`: a supplementary-plane digit (U+1D7DA `𝟚`, a mathematical
     *     two) is a surrogate PAIR, and `Character.isDigit` is false for either half on its own —
     *     so a char-wise scan waved it through and the keep-filter then deleted it, reading
     *     `"This dash$1𝟚6.70"` as 16.70.
     *  1. **Keep only the currency glyphs**, in order. This is the "keep the `$` / digit-run / `.`
     *     / `,` tokens, drop the label words and spacers" filter expressed at CHARACTER
     *     granularity — which it has to be, because the `allText` join has no separator to
     *     tokenize on, so a label fuses with the first glyph (`"far$16.70"`) and, on the pill,
     *     the label fuses onto the END (`"$3.10This dash"`). Letters carry no currency glyph, so
     *     for a purely alphabetic label the two spellings agree exactly.
     *  2. **Full-match the strict settled shape** ([SETTLED_GLYPH_CURRENCY]) or return null.
     *
     * Step 2 is what makes this safe on a wheel captured MID-SPIN. Roughly a fifth of fielded
     * reads are mid-animation, and they arrive malformed — the committed 8.93.7 collapsed receipt
     * reads `$70103.030`, the 07-17 corpus `$016.603` — every one of which fails the full match
     * and yields null rather than a fabricated figure. It cannot defend against a mid-spin read
     * that happens to be well-FORMED but wrong (`$470.00` for a $16.70 dash): that is the state
     * layer's settle gate (a read commits once it has stood unchallenged on its surface for the
     * settle window), not a string function's job.
     *
     * Also fails closed on a label that carries its own digits — `"1 out of 1$16.70"` folds to
     * `"11$16.70"`, which no longer full-matches — and on two figures in one container
     * (`"$8.70$1.00"`), so a mis-aimed rule reads null, never a fused number. Since #1029 round 3
     * the shape also rejects a LEADING-ZERO integer (`$016.70` — the fielded mid-spin `$016.603`
     * is one settled digit away from it) and a malformed thousands group (`$1234,567.00`, which
     * the old `\d{1,4}(?:,\d{3})?` accepted and folded to 1234567.0).
     */
    private fun parseGlyphCurrency(text: String): Double? {
        if (text.length > MAX_GLYPH_CURRENCY_INPUT) return null
        // #1052: REJECT what the keep-filter cannot read, rather than deleting it. Step 1 is a
        // KEEP filter, so a character it does not recognise vanishes and the remainder can still
        // full-match — which turns two whole classes of unreadable input into confident numbers:
        // a NON-ASCII digit (`"This dash$1٢6.70"` → 16.70, a digit silently dropped from the
        // middle of the figure) and a SIGN (`"-$16.70"` → 16.70, `"($16.70)"` → 16.70 — accounting
        // negatives read as positives). Neither can be handled by widening the alphabet: their
        // meaning is arithmetic, not glyphic. So a container carrying either is unreadable, and
        // unreadable is null (the transform's whole posture — see step 2).
        //
        // Round 2: by CODE POINT. `Character.isDigit(Char)` cannot see a supplementary-plane digit
        // at all — each surrogate half is false — so a char-wise scan passed U+1D7DA and the
        // keep-filter deleted it: `"This dash$1𝟚6.70"` → `"$16.70"` → 16.70, a digit
        // silently removed from the middle of the figure.
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in GLYPH_CURRENCY_SIGN_CODE_POINTS) return null
            if (Character.isDigit(cp) && cp !in '0'.code..'9'.code) return null
            i += Character.charCount(cp)
        }
        val glyphs = buildString(text.length) {
            for (c in text) if (c in GLYPH_CURRENCY_ALPHABET) append(c)
        }
        if (!SETTLED_GLYPH_CURRENCY.matches(glyphs)) return null
        return glyphs.removePrefix("$").replace(",", "").toDoubleOrNull()
    }

    /**
     * Parses distance: "5.5 mi", "Additional 2.6 mi", "500 ft" (converts ft to mi).
     *
     * Unit-anchored (#827): read the number bound to its OWN "mi" token with a
     * word boundary so "mi" can NEVER match inside "min". Uber fuses time and
     * distance in one node — e.g. "40 min (17.5 mi) total" — and the old
     * leading-number read returned the 40-*minute* value as 40.0 miles (the real
     * 17.5 mi discarded, then re-modeled ≈2.7× on every offer). Falls back to a
     * "ft" number (converted), then any leading number for legacy single-value
     * nodes ("5.5 mi", "500 ft") — byte-identical to the old behaviour there.
     */
    private fun parseDistance(text: String): Double? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*mi\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*ft\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()?.div(5280.0)
        }
        val num = Regex("(\\d+(?:\\.\\d+)?)").find(text)?.value?.toDoubleOrNull() ?: return null
        return if (text.contains("ft", true)) num / 5280.0 else num
    }

    /**
     * Parses whole minutes bound to their OWN "min" token (#827): "40 min
     * (17.5 mi) total" -> 40, "+ 19 min (+ 3.9 mi) total" -> 19. Unlike
     * [parseLeadingInt] (which reads the node's leading token and returns null on
     * the "+ 19 min" add-on shape because the leading token is "+"), this anchors
     * on the unit, so it is robust to any lead-in and can never pick up the miles
     * value. Returns null when no "min" token is present.
     */
    private fun parseMinutes(text: String): Int? {
        return Regex("(\\d+)\\s*min\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Parses item counts: "(2 items)", "(3 items * 4 units)".
     */
    private fun parseItemCount(text: String): Int? {
        return Regex("\\((\\d+)\\s*(?:item|order|unit)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Denomination of the FIRST count token [parseItemCount] reads (#823 Phase 1): "UNITS" when that
     * count is bound to "unit(s)" (a units-only offer like "(64 units)" or "(1 unit)"), else "ITEMS"
     * (an items figure — "(4 items)", or the leading "9 items" of "(9 items • 11 units)"). Anchored
     * on the SAME `(<number><word>` shape as [parseItemCount] so the two never disagree about which
     * token was read; returns null when no count token is present (leaves the field absent → the
     * factory's ITEMS default). "order" reads as ITEMS (an order count is not a unit multiplier).
     */
    private fun parseItemCountUnit(text: String): String? {
        val word = Regex("\\(\\d+\\s*(item|order|unit)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
        return if (word.startsWith("unit")) "UNITS" else "ITEMS"
    }

    /**
     * Extracts time from deadline strings like "Pick up by 17:39" and converts to epoch millis.
     */
    private fun parseDeadlineMillis(text: String): Long? {
        val timeRegex = Regex("(\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?)")
        val timeText = timeRegex.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return parseTimeTextToMillis(timeText)
    }

    /**
     * Parses "h:mm a" or "HH:mm" to epoch millis (today or tomorrow if past).
     */
    private fun parseTimeTextToMillis(timeText: String): Long? {
        val fmt12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val fmt24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        val localTime = try {
            LocalTime.parse(timeText, fmt12)
        } catch (_: Exception) {
            try {
                LocalTime.parse(timeText, fmt24)
            } catch (_: Exception) {
                return null
            }
        }

        // Anchor "today" to the scoped clock (#343) — the observation's instant and
        // zone — never the evaluation wall clock, so replaying a captured screen at a
        // different hour (or zone) yields the same millis.
        val clock = currentClock()
        val today = Instant.ofEpochMilli(clock.nowMillis).atZone(clock.zoneId).toLocalDate()
        val targetMillis = today.atTime(localTime).atZone(clock.zoneId).toInstant().toEpochMilli()
        // Roll forward only when the target is *significantly* in the past —
        // interpret as "this time tomorrow" (e.g. late-night offer for next
        // morning pickup). Past by less than the threshold stays as today's
        // timestamp so a blown deadline renders as "X min late" instead of
        // jumping ~24h ahead. See field log 2026-05-19 #2 for the bug shape
        // ("1434:38" ghost countdown caused by 37-second-past re-parse).
        return applyRollover(targetMillis, clock.nowMillis)
    }

    /**
     * Parses timer durations in "mm:ss" format to millis.
     * Also handles "N hr N min" format.
     */
    private fun parseDuration(text: String): Long? {
        // Try mm:ss first
        val colonParts = text.trim().split(":")
        if (colonParts.size == 2) {
            val mins = colonParts[0].trim().toLongOrNull()
            val secs = colonParts[1].trim().toLongOrNull()
            if (mins != null && secs != null) return mins * 60_000 + secs * 1000
        }
        // Fall back to hr/min pattern
        return parseHrMin(text)
    }

    /**
     * Parses "N hr N min" / "N hr" / "N min" to millis.
     */
    private fun parseHrMin(text: String): Long? {
        val pattern = Regex("(\\d+)\\s*(hr|min)")
        var totalMs = 0L
        var found = false
        for (match in pattern.findAll(text)) {
            found = true
            val value = match.groupValues[1].toLongOrNull() ?: 0L
            when (match.groupValues[2]) {
                "hr" -> totalMs += value * 3_600_000
                "min" -> totalMs += value * 60_000
            }
        }
        return if (found) totalMs else null
    }

    /**
     * Parses leading integer: "4 items" -> 4, "12" -> 12.
     */
    private fun parseLeadingInt(text: String): Int? {
        return text.trim().split(" ").firstOrNull()?.toIntOrNull()
    }

    /**
     * Parses percent: "85%" -> 85.0, "85" -> 85.0.
     */
    private fun parsePercent(text: String): Double? {
        return text.removeSuffix("%").trim().toDoubleOrNull()
    }

    /**
     * Strips known deadline prefixes: "Pick up by ", "Deliver by ", "Dash ends at ", "by ".
     */
    private fun stripDeadlinePrefix(text: String): String {
        val prefixes = listOf("Pick up by ", "Deliver by ", "Dash ends at ", "by ")
        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                return text.substring(prefix.length).trim()
            }
        }
        return text.trim()
    }
}
