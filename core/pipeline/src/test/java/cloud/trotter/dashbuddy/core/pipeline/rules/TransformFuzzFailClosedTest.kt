package cloud.trotter.dashbuddy.core.pipeline.rules

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.fail
import org.junit.Test

/**
 * #590 fill-in — [TransformRegistry] is FAIL-CLOSED under fuzzed transform specs.
 *
 * The engine contract (ADR-0001 v2) is: every transform spec is validated at COMPILE
 * time ([TransformRegistry.validateTransformSpec], typed [RuleCompileException] on any
 * malformed shape), so match-time [TransformRegistry.applyAny] can assume a
 * well-formed spec and MUST never throw a raw (non-[RuleCompileException]) [Throwable]
 * — a raw NPE/ClassCast escaping `apply` on the classification thread is a fail-OPEN
 * crash.
 *
 * This fuzzes arbitrary/malformed specs — unknown ops, wrong arg types, 0/2-key
 * objects, absurd `split` indices, empty `replace` patterns — and asserts:
 *  1. `validateTransformSpec` only ever throws the typed [RuleCompileException]
 *     (never a raw Throwable);
 *  2. a spec that PASSES validation then applies without a raw throw (RuleCompileException
 *     is tolerated — it's still typed/fail-closed — but a raw Throwable is a defect);
 *  3. a validated string output never exceeds the input by more than a DOCUMENTED
 *     factor (the only amplifier is `replace` with an empty pattern, which inserts the
 *     replacement between every char — bounded by the rule-authored, compile-bounded
 *     replacement length).
 *
 * A failing property prints its seed; pin it with `PropTestConfig(seed = ...)`.
 */
class TransformFuzzFailClosedTest {

    private val input = "Deliver to 123-45-6789 at 4111 1111 1111 1111 by 8:52 PM"

    // Documented amplification factor (assertion 3). `replace` with an empty pattern is
    // the only growth path: output <= (input.length + 1) * replacementLen. The generator
    // caps replacement length at 6, so the worst-case bound is generous headroom.
    private val maxReplacementLen = 6
    private fun lengthBound(): Int = (input.length + 1) * maxReplacementLen + 64

    // --- Arb of transform specs, half well-formed and half deliberately broken --------

    private val plainName = Arb.element(
        // known plain transforms
        "trim", "lower", "upper", "sha256", "parseCurrency", "parseDistance",
        // unknown / typo'd names (must compile-reject)
        "explode", "eval", "toBytes", "", "  ", "REPLACE",
    )

    private val shortStr = Arb.string(0, maxReplacementLen)

    private fun paramSpec(rs: io.kotest.property.RandomSource): JsonElement {
        val key = Arb.element(
            "stripPrefix", "stripSuffix", "extractBefore", "extractAfter",
            "stripPrefixes", "replace", "split", "regex",
            "unknownOp", // must reject
        ).sample(rs).value
        val param: JsonElement = when (Arb.int(0, 6).sample(rs).value) {
            0 -> JsonPrimitive(shortStr.sample(rs).value) // string param
            1 -> JsonPrimitive(Arb.int(-5, Int.MAX_VALUE).sample(rs).value) // absurd int
            2 -> buildJsonObject { // replace-ish (maybe missing keys)
                if (Arb.int(0, 1).sample(rs).value == 0) put("pattern", JsonPrimitive(shortStr.sample(rs).value))
                put("replacement", JsonPrimitive(shortStr.sample(rs).value))
            }
            3 -> buildJsonObject { // split-ish (maybe missing index)
                put("separator", JsonPrimitive(shortStr.sample(rs).value))
                if (Arb.int(0, 1).sample(rs).value == 0) put("index", JsonPrimitive(Arb.int(-3, 999999).sample(rs).value))
            }
            4 -> buildJsonObject { // regex-ish
                put("pattern", JsonPrimitive(Arb.element("\\d+", "(a+)+", "[", "").sample(rs).value))
                put("group", JsonPrimitive(Arb.int(-1, 9).sample(rs).value))
            }
            5 -> buildJsonArray { } // wrong type for a string param
            else -> JsonPrimitive(true) // wrong type entirely
        }
        return buildJsonObject { put(key, param) }
    }

    private val specArb: Arb<JsonElement> = arbitrary { rs ->
        when (Arb.int(0, 4).sample(rs).value) {
            0 -> JsonPrimitive(plainName.sample(rs).value)
            1, 2 -> paramSpec(rs)
            3 -> buildJsonArray { // chain of 1-3 specs
                repeat(Arb.int(1, 3).sample(rs).value) {
                    add(if (Arb.int(0, 1).sample(rs).value == 0) JsonPrimitive(plainName.sample(rs).value) else paramSpec(rs))
                }
            }
            else -> buildJsonObject { // pathological 0-key or 2-key object
                if (Arb.int(0, 1).sample(rs).value == 0) {
                    put("split", JsonPrimitive(1)); put("trim", JsonPrimitive(2))
                }
            }
        }
    }

    @Test
    fun `property - validate is typed-reject-or-ok and a validated spec never raw-throws at apply`() = runTest {
        checkAll(400, specArb) { spec ->
            val validated = try {
                TransformRegistry.validateTransformSpec(spec)
                true
            } catch (e: RuleCompileException) {
                false // the acceptable typed-reject arm
            } catch (t: Throwable) {
                fail("validateTransformSpec leaked a raw ${t.javaClass.name}: ${t.message} for spec=$spec")
                false
            }

            if (validated) {
                val out = try {
                    TransformRegistry.applyAny(spec, input)
                } catch (e: RuleCompileException) {
                    null // still typed/fail-closed — tolerated
                } catch (t: Throwable) {
                    fail("applyAny leaked a raw ${t.javaClass.name}: ${t.message} for a VALIDATED spec=$spec")
                    null
                }
                if (out is String) {
                    assertOutputBounded(out, spec)
                }
            }
        }
    }

    private fun assertOutputBounded(out: String, spec: JsonElement) {
        if (out.length > lengthBound()) {
            fail("validated transform output length ${out.length} exceeded documented bound ${lengthBound()} for spec=$spec")
        }
    }

    @Test
    fun `applyAny on an unvalidated malformed spec still only throws the typed RuleCompileException`() {
        // The runtime contract when apply IS reached on a broken spec (e.g. a 2-key
        // object, an unknown op): a typed reject, never a raw crash. (Missing required
        // sub-keys are a compile-time reject, so this asserts the shapes apply() itself
        // guards — single-key + known-op dispatch.)
        val broken = listOf(
            buildJsonObject { put("nope", JsonPrimitive("x")) },
            buildJsonObject { put("a", JsonPrimitive(1)); put("b", JsonPrimitive(2)) },
            JsonPrimitive("definitelyNotATransform"),
        )
        for (spec in broken) {
            try {
                TransformRegistry.applyAny(spec, input)
                // succeeding is fine (no-op)
            } catch (e: RuleCompileException) {
                // typed reject — acceptable
            } catch (t: Throwable) {
                fail("applyAny leaked a raw ${t.javaClass.name} for broken spec=$spec")
            }
        }
    }
}
