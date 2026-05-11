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
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
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
            "sha256" -> generateSha256(value)
            "trim" -> value.trim()
            "lower" -> value.lowercase()
            "upper" -> value.uppercase()
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
        val key = spec.keys.firstOrNull()
            ?: throw RuleCompileException("Empty transform spec")
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
                val regex = RuleCompiler.compileRegex(pattern)
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

    fun validate(name: String, args: JsonObject, parsed: Map<String, Any?>): ValidateOutcome {
        return when (name) {
            "sumApproxEquals" -> validateSumApproxEquals(args, parsed)
            "fieldsLe" -> validateFieldsLe(args, parsed)
            "fieldsGe" -> validateFieldsGe(args, parsed)
            "fieldNotNull" -> validateFieldNotNull(args, parsed)
            "fieldEquals" -> validateFieldEquals(args, parsed)
            else -> throw RuleCompileException("Unknown validate assertion: '$name'")
        }
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
                val key = spec.keys.firstOrNull()
                    ?: throw RuleCompileException("Empty transform spec")
                if (key !in knownParameterizedTransforms)
                    throw RuleCompileException("Unknown parameterized transform: '$key'")
                // Validate nested 'then' in regex transforms
                if (key == "regex") {
                    val regexObj = spec[key]?.jsonObject
                    val pattern = regexObj?.get("pattern")?.jsonPrimitive?.content
                    if (pattern != null) RuleCompiler.compileRegex(pattern)
                    regexObj?.get("then")?.let { validateTransformSpec(it) }
                }
            }
            is JsonArray -> spec.forEach { validateTransformSpec(it) }
        }
    }

    fun validateAssertionName(name: String) {
        val known = setOf(
            "sumApproxEquals", "fieldsLe", "fieldsGe", "fieldNotNull", "fieldEquals",
        )
        if (name !in known) throw RuleCompileException("Unknown validate assertion: '$name'")
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

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, localTime.hour)
            set(Calendar.MINUTE, localTime.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis < now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
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

    /** SHA-256 hash. Returns input on failure. */
    private fun generateSha256(input: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            bytes.fold("") { str, it -> str + "%02x".format(it) }
        } catch (_: Exception) {
            input
        }
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
