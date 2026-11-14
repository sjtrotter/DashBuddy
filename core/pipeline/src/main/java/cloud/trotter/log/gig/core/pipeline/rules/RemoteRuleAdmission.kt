package cloud.trotter.log.gig.core.pipeline.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** True when [this] rule has branches and every one of them has the sensitive (blocking) shape. */
internal val CompiledRule<*>.isSensitiveShape: Boolean
    get() = branches.isNotEmpty() && branches.all { it.shape == SENSITIVE_SHAPE }

internal const val SENSITIVE_SHAPE = "sensitive"

/**
 * The checks a rule from a remote source must pass, beyond compiling (#1568). Each is decided for
 * one rule; a refusal rejects the whole generation, so the live one stays.
 *
 * - `overrideable: false` is allowed only when every branch has the sensitive shape: a remote rule
 *   may join the front partition only to block a screen.
 * - Every customer field ([ParsedFieldsFactory.CUSTOMER_FIELDS_BY_SHAPE]) the rule parses ends its
 *   transform chain in `sha256`, and the rule declares a non-empty `redact`. This stops a customer
 *   field being parsed unhashed. It cannot stop a signed rule routing a customer's text into a field
 *   stored as-is, such as `storeName`: the rule chooses what each field means (ADR-0013 X2).
 */
internal object RemoteRuleAdmission {

    /** Null when [rule] may enter a generation, else why not (rule id and field names only). */
    fun refusal(raw: JsonObject, rule: CompiledRule<*>, context: RuleContext): String? {
        if (!rule.overrideable && !rule.isSensitiveShape) {
            return "rule '${rule.id}' is overrideable: false without the sensitive shape on every branch"
        }
        val customerFields = customerFieldSpecs(raw)
        val unhashed = customerFields.filterNot { (_, spec) -> hashesLast(spec) }.map { it.first }
        if (unhashed.isNotEmpty()) {
            return "rule '${rule.id}' parses customer field(s) ${unhashed.distinct()} without sha256 last"
        }
        if (customerFields.isNotEmpty() && !declaresRedact(rule, context)) {
            return "rule '${rule.id}' parses a customer field but declares no redact"
        }
        return null
    }

    private fun declaresRedact(rule: CompiledRule<*>, context: RuleContext): Boolean = when (context) {
        RuleContext.SCREEN -> !rule.redact.isEmpty()
        RuleContext.NOTIFICATION -> !rule.notifRedact.isEmpty()
        RuleContext.CLICK -> false
    }

    /**
     * Every (field name, spec) the rule parses that its shape marks as customer text, read from the
     * raw JSON the way the compiler resolves it: a branch's own `parse` block, else the rule's, and
     * the block's `as`, else the rule's. Nested `extract` blocks count under the same shape.
     */
    private fun customerFieldSpecs(raw: JsonObject): List<Pair<String, JsonElement>> {
        val ruleParse = raw["parse"] as? JsonObject
        val ruleShape = shapeOf(ruleParse)
        val blocks = (raw["branches"] as? JsonArray)
            ?.map { branch -> ((branch as? JsonObject)?.get("parse") as? JsonObject) ?: ruleParse }
            ?: listOf(ruleParse)
        return blocks.filterNotNull().flatMap { block ->
            val names = ParsedFieldsFactory.CUSTOMER_FIELDS_BY_SHAPE[shapeOf(block) ?: ruleShape]
                ?: return@flatMap emptyList()
            fieldSpecs(block["fields"] as? JsonObject, names)
        }
    }

    private fun shapeOf(parse: JsonObject?): String? =
        (parse?.get("as") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun fieldSpecs(fields: JsonObject?, names: Set<String>): List<Pair<String, JsonElement>> =
        fields.orEmpty().flatMap { (name, spec) ->
            val own = if (name in names) listOf(name to spec) else emptyList()
            own + fieldSpecs((spec as? JsonObject)?.get("extract") as? JsonObject, names)
        }

    /**
     * True when the value [spec] produces is a `sha256` output: a `coalesce` whose every arm is, or a
     * read whose `transform` ends in `sha256`. Constants and the collection, enum and presence forms
     * are never a hashed read.
     */
    private fun hashesLast(spec: JsonElement): Boolean {
        val obj = spec as? JsonObject ?: return false
        val arms = obj["coalesce"]
        if (arms != null) return arms is JsonArray && arms.isNotEmpty() && arms.all { hashesLast(it) }
        if (NON_READ_FORMS.any { it in obj }) return false
        val last = when (val transform = obj["transform"]) {
            is JsonArray -> transform.lastOrNull()
            else -> transform
        }
        return last is JsonPrimitive && last.isString && last.content == "sha256"
    }

    private val NON_READ_FORMS = setOf("literal", "conditionalEnum", "presence", "each", "findAll")
}
