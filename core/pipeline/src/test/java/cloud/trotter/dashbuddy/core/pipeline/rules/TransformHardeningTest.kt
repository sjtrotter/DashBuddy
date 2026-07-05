package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #362 — the defect classes that survived #293's RuleCompiler pass, in
 * TransformRegistry and the parse-expression compilers: malformed specs must
 * fail at COMPILE time (rule load), never at match time; case transforms and
 * hashes must be locale-independent; the privacy hash must fail closed.
 */
class TransformHardeningTest {

    private fun spec(json: String) = Json.parseToJsonElement(json)

    private fun assertCompileFails(json: String, contains: String? = null) {
        try {
            TransformRegistry.validateTransformSpec(spec(json))
            fail("expected RuleCompileException for: $json")
        } catch (e: RuleCompileException) {
            contains?.let {
                if (e.message?.contains(it) != true) {
                    fail("expected message containing '$it', got: ${e.message}")
                }
            }
        }
    }

    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // ── Item 1 + 5: required params + single-key specs at compile time ──

    @Test
    fun `split without index fails at compile time`() =
        assertCompileFails("""{"split":{"separator":","}}""", contains = "index")

    @Test
    fun `split without separator fails at compile time`() =
        assertCompileFails("""{"split":{"index":1}}""", contains = "separator")

    @Test
    fun `replace without pattern fails at compile time`() =
        assertCompileFails("""{"replace":{"replacement":"x"}}""", contains = "pattern")

    @Test
    fun `regex without pattern fails at compile time`() =
        assertCompileFails("""{"regex":{"group":1}}""", contains = "pattern")

    @Test
    fun `stripPrefix with non-string param fails at compile time`() =
        assertCompileFails("""{"stripPrefix":{"x":1}}""")

    @Test
    fun `multi-key spec fails at compile time`() =
        assertCompileFails("""{"stripPrefix":"a","stripSuffix":"b"}""", contains = "exactly one key")

    @Test
    fun `nested then inside regex is validated too`() =
        assertCompileFails("""{"regex":{"pattern":"x","then":{"split":{"separator":","}}}}""", contains = "index")

    @Test
    fun `valid specs still pass validation and apply`() {
        TransformRegistry.validateTransformSpec(spec("""{"split":{"separator":",","index":1}}"""))
        TransformRegistry.validateTransformSpec(spec("""{"regex":{"pattern":"(\\d+)","group":1}}"""))
        assertEquals("b", TransformRegistry.applyAny(spec("""{"split":{"separator":",","index":1}}"""), "a,b,c"))
        assertEquals("42", TransformRegistry.applyAny(spec("""{"regex":{"pattern":"(\\d+)","group":1}}"""), "x42y"))
    }

    // ── Item 4: locale-independent case transforms ──

    @Test
    fun `lower and upper are Locale-ROOT under a Turkish default locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        // Bare lowercase() in tr-TR maps I → dotless ı, breaking field matching.
        assertEquals("title", TransformRegistry.apply("lower", "TITLE"))
        assertEquals("TITLE", TransformRegistry.apply("upper", "title"))
    }

    // ── Item 7: fail-closed, locale-stable sha256 ──

    @Test
    fun `sha256 transform produces the known vector and never echoes input`() {
        val out = TransformRegistry.apply("sha256", "abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            out,
        )
        assertNotEquals("abc", out)
    }

    @Test
    fun `sha256OrNull hex digits stay ASCII under non-ASCII-numeral locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256OrNull("abc"),
        )
    }

    // ── Item 6: onFail typos are caught at compile (not coerced at match) ──
    //
    // #362 made a typo'd onFail a compile-time RuleCompileException instead of a
    // silent match-time coercion. #293 item 4 isolates a non-sensitive AUTHORING
    // malformation to a per-rule SKIP (WARN'd) rather than failing the whole
    // file — the typo is still caught at compile (the rule never becomes a live
    // matcher), which is the property #362 defends. The whole-file-reject arm
    // (sensitive rules + fail-closed controls) is covered in RuleCompilerHardeningTest.

    @Test
    fun `unknown onFail is caught at compile and isolates the rule (item 4 skip)`() {
        val ruleJson = """[{
            "id": "test.screen.onfail_typo",
            "priority": 10,
            "require": { "exists": { "hasText": "x" } },
            "parse": {
                "as": "future_shape",
                "fields": {
                    "f": { "find": { "hasText": "y" }, "read": "text" }
                }
            },
            "validate": [
                { "assert": "fieldNotNull", "field": "f", "onFail": "drop" }
            ]
        }]"""
        val compiled = RuleCompiler.compileRules<cloud.trotter.dashbuddy.domain.model.accessibility.UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )
        assertTrue("the malformed rule must be skipped, not compiled to a live matcher", compiled.isEmpty())
    }

    // ── Item 3: unknown navigation caught at compile, not at first match ──

    @Test
    fun `unknown navigate verb is caught at compile and isolates the rule (item 4 skip)`() {
        val ruleJson = """[{
            "id": "test.screen.bad_nav",
            "priority": 10,
            "require": { "exists": { "hasText": "x" } },
            "parse": {
                "as": "future_shape",
                "fields": {
                    "f": { "find": { "hasText": "y" }, "navigate": "uncle", "read": "text" }
                }
            }
        }]"""
        val compiled = RuleCompiler.compileRules<cloud.trotter.dashbuddy.domain.model.accessibility.UiNode>(
            Json.parseToJsonElement(ruleJson).jsonArray, RuleContext.SCREEN,
        )
        assertTrue("the malformed rule must be skipped, not compiled to a live matcher", compiled.isEmpty())
    }

    // ── Item 1 regression: validated specs can't NPE at match time ──

    @Test
    fun `match-time apply on a validated split spec never NPEs`() {
        val s = spec("""{"split":{"separator":",","index":5}}""")
        TransformRegistry.validateTransformSpec(s)
        assertNull(TransformRegistry.applyAny(s, "a,b")) // out-of-range index → null, not crash
    }
}
