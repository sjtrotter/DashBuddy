package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
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
 * Closed, engine-owned vocabulary of value transforms and validate assertions.
 *
 * All verbs are compile-time validated: unknown names throw [RuleCompileException]
 * during rule loading, never at match time. Implementations are pure functions
 * over string values — no Android, no UiNode, no IO.
 *
 * Matches ADR-0001 v2 specification.
 */
object TransformRegistry {

    /** Compiled-regex cache for the `regex` transform (#362) — the spec is
     *  re-dispatched per value, so without this every value recompiled. */
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

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
            "parseDistance" -> parseDistance(value)
            "parseItemCount" -> parseItemCount(value)
            "parseDeadline" -> parseDeadlineMillis(value)
            "parseTime" -> parseTimeTextToMillis(value)
            "parseDuration" -> parseDuration(value)
            "parseHrMin" -> parseHrMin(value)
            "parseLeadingInt" -> parseLeadingInt(value)
            "parsePercent" -> parsePercent(value)
            "sha256" -> sha256OrNull(value)
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
    //  Validate assertions
    // ========================================================================

    // ONE owner for the assertion vocabulary (#404): the dispatch map drives
    // both validate() and the compile-time name check — adding an assertion
    // is exactly one entry here plus its implementation.
    private val knownAssertions: Map<String, (JsonObject, Map<String, Any?>) -> ValidateOutcome> = mapOf(
        "sumApproxEquals" to ::validateSumApproxEquals,
        "fieldsLe" to ::validateFieldsLe,
        "fieldsGe" to ::validateFieldsGe,
        "fieldNotNull" to ::validateFieldNotNull,
        "fieldEquals" to ::validateFieldEquals,
    )

    fun validate(name: String, args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        val assertion = knownAssertions[name]
            ?: throw RuleCompileException("Unknown validate assertion: '$name'")
        return assertion(args, parsed)
    }

    // ========================================================================
    //  Compile-time validation
    // ========================================================================

    private val knownPlainTransforms = setOf(
        "parseCurrency", "parseDistance", "parseItemCount", "parseDeadline",
        "parseTime", "parseDuration", "parseHrMin", "parseLeadingInt",
        "parsePercent", "sha256", "trim", "lower", "upper", "toDouble", "toInt",
        "stripDeadlinePrefix",
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

    fun validateAssertionName(name: String) {
        if (name !in knownAssertions) throw RuleCompileException("Unknown validate assertion: '$name'")
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
     * Parses distance: "5.5 mi", "Additional 2.6 mi", "500 ft" (converts ft to mi).
     */
    private fun parseDistance(text: String): Double? {
        val num = Regex("(\\d+(?:\\.\\d+)?)").find(text)?.value?.toDoubleOrNull() ?: return null
        return if (text.contains("ft", true)) num / 5280.0 else num
    }

    /**
     * Parses item counts: "(2 items)", "(3 items * 4 units)".
     */
    private fun parseItemCount(text: String): Int? {
        return Regex("\\((\\d+)\\s*(?:item|order|unit)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
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

    // ========================================================================
    //  Validate assertion implementations
    // ========================================================================

    /**
     * Assert that the sum of named fields approximately equals a target field.
     * ```json
     * { "assert": "sumApproxEquals", "fields": ["appPay","customerTips"],
     *   "target": "totalPay", "tolerance": 0.02 }
     * ```
     */
    private fun validateSumApproxEquals(args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        val fields = args["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        val target = args["target"]!!.jsonPrimitive.content
        val tolerance = args["tolerance"]?.jsonPrimitive?.doubleOrNull ?: 0.02

        val sum = fields.sumOf { (parsed[it] as? Number)?.toDouble() ?: return ValidateOutcome.Pass }
        val targetVal = (parsed[target] as? Number)?.toDouble() ?: return ValidateOutcome.Pass

        return if (kotlin.math.abs(sum - targetVal) <= tolerance) ValidateOutcome.Pass
        else ValidateOutcome.Skip
    }

    /**
     * Assert that field A <= field B.
     * ```json
     * { "assert": "fieldsLe", "field": "totalEarnings", "le": "weeklyEarnings", "tolerance": 0.02 }
     * ```
     */
    private fun validateFieldsLe(args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        val fieldName = args["field"]!!.jsonPrimitive.content
        val leName = args["le"]!!.jsonPrimitive.content
        val tolerance = args["tolerance"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        val fieldVal = (parsed[fieldName] as? Number)?.toDouble() ?: return ValidateOutcome.Pass
        val leVal = (parsed[leName] as? Number)?.toDouble() ?: return ValidateOutcome.Pass

        return if (fieldVal <= leVal + tolerance) ValidateOutcome.Pass
        else ValidateOutcome.DropParsed
    }

    /**
     * Assert that field A >= field B.
     */
    private fun validateFieldsGe(args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        val fieldName = args["field"]!!.jsonPrimitive.content
        val geName = args["ge"]!!.jsonPrimitive.content
        val tolerance = args["tolerance"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        val fieldVal = (parsed[fieldName] as? Number)?.toDouble() ?: return ValidateOutcome.Pass
        val geVal = (parsed[geName] as? Number)?.toDouble() ?: return ValidateOutcome.Pass

        return if (fieldVal >= geVal - tolerance) ValidateOutcome.Pass
        else ValidateOutcome.DropParsed
    }

    /**
     * Assert that a field is non-null.
     */
    private fun validateFieldNotNull(args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        val fieldName = args["field"]!!.jsonPrimitive.content
        return if (parsed[fieldName] != null) ValidateOutcome.Pass
        else ValidateOutcome.Skip
    }

    /**
     * Assert that a field equals a specific value.
     */
    private fun validateFieldEquals(args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        val fieldName = args["field"]!!.jsonPrimitive.content
        val expected = args["value"]

        val actual = parsed[fieldName]
        val matches = when {
            expected is JsonPrimitive && expected.isString ->
                actual?.toString() == expected.content
            expected is JsonPrimitive && expected.doubleOrNull != null ->
                (actual as? Number)?.toDouble() == expected.double
            expected is JsonPrimitive && expected.content == "true" ->
                actual == true
            expected is JsonPrimitive && expected.content == "false" ->
                actual == false
            else -> actual?.toString() == expected?.toString()
        }

        return if (matches) ValidateOutcome.Pass else ValidateOutcome.Skip
    }
}

/**
 * Outcome of a validate assertion.
 *
 * - [Pass] — assertion holds, continue with parsed fields.
 * - [Skip] — assertion fails, fall through to next branch.
 * - [DropParsed] — assertion fails, keep the match but reset parsed to None.
 */
sealed class ValidateOutcome {
    data object Pass : ValidateOutcome()
    data object Skip : ValidateOutcome()
    data object DropParsed : ValidateOutcome()
}
