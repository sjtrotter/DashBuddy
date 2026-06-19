package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Closed, engine-owned vocabulary of validate assertions.
 *
 * Split out of [TransformRegistry] (audit #13): value transforms and
 * validate-assertions are two independently-dispatched vocabularies that share
 * no state, so each owns its own object. Assertions reason over the *parsed
 * field map* and return a [ValidateOutcome]; value transforms reason over a
 * single string value.
 *
 * All assertion names are compile-time validated: unknown names throw
 * [RuleCompileException] during rule loading, never at match time. Implementations
 * are pure functions over the parsed map — no Android, no UiNode, no IO.
 *
 * Matches ADR-0001 v2 specification.
 */
object ValidateRegistry {

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

    /**
     * Validate at compile time that a validate-assertion name is known.
     * Call during rule loading to fail fast on typos.
     */
    fun validateAssertionName(name: String) {
        if (name !in knownAssertions) throw RuleCompileException("Unknown validate assertion: '$name'")
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
