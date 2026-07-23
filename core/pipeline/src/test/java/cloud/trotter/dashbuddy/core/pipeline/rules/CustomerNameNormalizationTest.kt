package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #733 — the customer-name canonical key ([customerNameKey]), the mask↔hash invariant it preserves
 * ([CompiledRedact.mask] with a `normalize` flag), and the compile-time lint that forces every
 * `customerNameHash` + `sha256` chain to normalize.
 */
class CustomerNameNormalizationTest {

    // =====================================================================
    // customerNameKey — the canonical form
    // =====================================================================

    @Test
    fun `name forms of one customer collapse to a single key`() {
        val key = "brandy s"
        listOf("Brandy S", "Brandy S.", "Brandy Smith", "brandy  smith ", "BRANDY SMITH").forEach {
            assertEquals("'$it' should canonicalize to '$key'", key, customerNameKey(it))
        }
    }

    @Test
    fun `a single-token name has no second initial`() {
        assertEquals("cher", customerNameKey("Cher"))
        assertEquals("madonna", customerNameKey("  Madonna  "))
    }

    @Test
    fun `distinct customers stay distinct`() {
        assertNotEquals(customerNameKey("Brandy Smith"), customerNameKey("Brandy Jones"))
        assertNotEquals(customerNameKey("Alice Wong"), customerNameKey("Bob Wong"))
    }

    @Test
    fun `blank or punctuation-only input is null (fail-closed, hash never runs on empty)`() {
        assertNull(customerNameKey(""))
        assertNull(customerNameKey("   "))
        assertNull(customerNameKey(".,-"))
    }

    @Test
    fun `an already-masked token stays a single distinct token`() {
        assertEquals("redactedab12", customerNameKey("[redacted:ab12]"))
        assertNotEquals(
            "two masked customers must not collapse together",
            customerNameKey("[redacted:ab12]"), customerNameKey("[redacted:cd34]"),
        )
    }

    // =====================================================================
    // mask ↔ hash invariant (#623 preserved through normalization)
    // =====================================================================

    private fun hashPrefix(token: String) = sha256OrNull(token)!!.take(4)

    @Test
    fun `a normalized name mask hex equals the first 4 hex of the persisted canonical-key hash`() {
        val masked = CompiledRedact.mask("Brandy Smith", emptyList(), RedactNormalize.CUSTOMER_NAME)
        // The parse persists sha256(normalizeCustomerName("Brandy Smith")) = sha256("brandy s").
        assertEquals("[redacted:${hashPrefix("brandy s")}]", masked)
    }

    @Test
    fun `two surface forms of the same customer produce the same normalized mask`() {
        val short = CompiledRedact.mask("Brandy S", emptyList(), RedactNormalize.CUSTOMER_NAME)
        val full = CompiledRedact.mask("Brandy Smith", emptyList(), RedactNormalize.CUSTOMER_NAME)
        val prefixed = CompiledRedact.mask("Deliver to Brandy Smith", listOf("Deliver to "), RedactNormalize.CUSTOMER_NAME)
        assertEquals(short, full)
        assertEquals("Deliver to $full", prefixed)
    }

    @Test
    fun `an address entry (no normalize) is unaffected — masks the whole trimmed token`() {
        val masked = CompiledRedact.mask("123 Main St", emptyList(), normalize = null)
        assertEquals("[redacted:${hashPrefix("123 Main St")}]", masked)
        // And it does NOT equal the normalized form (normalization would degrade address distinctness).
        assertNotEquals(masked, CompiledRedact.mask("123 Main St", emptyList(), RedactNormalize.CUSTOMER_NAME))
    }

    @Test
    fun `fail-closed — a blank normalized token masks to plain redacted, never raw`() {
        val masked = CompiledRedact.mask("   ", emptyList(), RedactNormalize.CUSTOMER_NAME)
        assertEquals(CompiledRedact.REDACTED, masked)
        assertTrue(masked!!.startsWith("[redacted"))
    }

    @Test
    fun `795 plainMask masks a small-space secret to the plain constant, no distinctness hash`() {
        // The default (hashed) mask leaks a 4-digit PIN: 10 000 values map into 65 536
        // 4-hex buckets, mostly injectively, so the suffix is brute-force reversible.
        val hashed = CompiledRedact.mask("9315", emptyList(), normalize = null, plainMask = false)
        assertEquals("[redacted:${hashPrefix("9315")}]", hashed)
        // plainMask drops the suffix entirely — nothing left to reverse.
        val plain = CompiledRedact.mask("9315", emptyList(), normalize = null, plainMask = true)
        assertEquals(CompiledRedact.REDACTED, plain)
        assertFalse(Regex("""[0-9a-f]{4}]""").containsMatchIn(plain!!))
    }

    @Test
    fun `795 plainMask still honors keepPrefix, dropping only the hash`() {
        val masked = CompiledRedact.mask("PIN 9315", listOf("PIN "), normalize = null, plainMask = true)
        assertEquals("PIN ${CompiledRedact.REDACTED}", masked)
    }

    // =====================================================================
    // compile-time lint
    // =====================================================================

    private fun compile(rule: String) =
        RuleCompiler.compileRules<UiNode>(Json.parseToJsonElement(rule).jsonArray, RuleContext.SCREEN)

    @Test(expected = RuleCompileException::class)
    fun `a customerNameHash sha256 chain without normalizeCustomerName fails compile (#733)`() {
        compile(
            """
            [{
              "id": "test.namehash.unnormalized",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "redact": [ { "find": { "hasIdSuffix": "customer_name" }, "normalize": "customerName" } ],
              "require": { "exists": { "hasText": "x" } },
              "parse": {
                "as": "task",
                "fields": {
                  "phase": "DROPOFF",
                  "customerNameHash": {
                    "find": { "hasIdSuffix": "customer_name" }, "read": "text",
                    "transform": [ "trim", "sha256" ]
                  }
                }
              }
            }]
            """.trimIndent(),
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `795 a redact entry combining plainMask and normalize fails compile`() {
        // Both shape the distinctness hash; a plain mask has none, so the combination is a
        // contradiction — reject loud rather than silently ignore one.
        compile(
            """
            [{
              "id": "test.redact.plain-and-normalize",
              "priority": 9999,
              "redact": [ { "find": { "hasIdSuffix": "x" }, "plainMask": true, "normalize": "customerName" } ],
              "require": { "exists": { "hasText": "x" } }
            }]
            """.trimIndent(),
        )
    }

    @Test
    fun `795 a redact entry with plainMask alone compiles`() {
        val rules = compile(
            """
            [{
              "id": "test.redact.plain",
              "priority": 9999,
              "redact": [ { "find": { "hasTextMatchesRegex": "^\\d{1,6}$" }, "plainMask": true } ],
              "require": { "exists": { "hasText": "x" } }
            }]
            """.trimIndent(),
        )
        assertTrue(rules.single().redact.entries.single().plainMask)
    }

    @Test
    fun `a customerNameHash chain that normalizes before sha256 compiles`() {
        val compiled = compile(
            """
            [{
              "id": "test.namehash.normalized",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "redact": [ { "find": { "hasIdSuffix": "customer_name" }, "normalize": "customerName" } ],
              "require": { "exists": { "hasText": "x" } },
              "parse": {
                "as": "task",
                "fields": {
                  "phase": "DROPOFF",
                  "customerNameHash": {
                    "find": { "hasIdSuffix": "customer_name" }, "read": "text",
                    "transform": [ "trim", "normalizeCustomerName", "sha256" ]
                  }
                }
              }
            }]
            """.trimIndent(),
        )
        assertEquals(1, compiled.size)
    }

    @Test(expected = RuleCompileException::class)
    fun `a coalesce branch that hashes without normalizing fails compile (per-chain lint)`() {
        compile(
            """
            [{
              "id": "test.namehash.coalesce.partial",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "redact": [ { "find": { "hasIdSuffix": "customer_name" }, "normalize": "customerName" } ],
              "require": { "exists": { "hasText": "x" } },
              "parse": {
                "as": "task",
                "fields": {
                  "phase": "DROPOFF",
                  "customerNameHash": {
                    "coalesce": [
                      { "find": { "hasTextStartsWith": "Deliver to" }, "read": "text",
                        "transform": [ "trim", "normalizeCustomerName", "sha256" ] },
                      { "find": { "hasTextContaining": "Delivery for" }, "read": "text",
                        "transform": [ "trim", "sha256" ] }
                    ]
                  }
                }
              }
            }]
            """.trimIndent(),
        )
    }

    // =====================================================================
    // cross-surface hash contract — the three DoorDash dropoff-surface forms
    // of one customer converge on ONE customerNameHash (the untested contract,
    // now pinned): pickup card "Brandy S", nav sheet "Heading to Brandy S.",
    // arrival card "Brandy Smith" → identical hash.
    // =====================================================================

    private fun chain(spec: String, value: String): Any? =
        TransformRegistry.chain(Json.parseToJsonElement(spec).jsonArray, value)

    @Test
    fun `the three dropoff-surface transform chains converge on one customerNameHash (#733)`() {
        // pickup_arrival:166 — [trim, normalizeCustomerName, sha256] on the customer_name node.
        val pickup = chain("""[ "trim", "normalizeCustomerName", "sha256" ]""", "Brandy S")
        // dropoff_navigation:146 — [stripPrefixes(Deliver to/Heading to), trim, normalizeCustomerName, sha256].
        val nav = chain(
            """[ { "stripPrefixes": [ "Deliver to ", "Heading to " ] }, "trim", "normalizeCustomerName", "sha256" ]""",
            "Heading to Brandy S.",
        )
        // dropoff_pre_arrival:567 branch 2 — [trim, normalizeCustomerName, sha256] on the arrival card user_name.
        val arrival = chain("""[ "trim", "normalizeCustomerName", "sha256" ]""", "Brandy Smith")

        assertEquals("pickup card and nav sheet hash identically", pickup, nav)
        assertEquals("pickup card and arrival card hash identically", pickup, arrival)
        // A different customer diverges.
        val other = chain("""[ "trim", "normalizeCustomerName", "sha256" ]""", "Brandy Jones")
        assertNotEquals(pickup, other)
    }

    @Test(expected = RuleCompileException::class)
    fun `a redact entry with an unknown normalize value fails compile`() {
        compile(
            """
            [{
              "id": "test.redact.badnormalize",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "redact": [ { "find": { "hasIdSuffix": "customer_name" }, "normalize": "everything" } ],
              "require": { "exists": { "hasText": "x" } }
            }]
            """.trimIndent(),
        )
    }

    // =====================================================================
    // #745 — positional + bidirectional lint (normalize immediately before sha256;
    // normalize nowhere else). Four cases: wrong order, normalize-without-sha256,
    // nested-branch evasion, happy path.
    // =====================================================================

    private fun nameHashRule(transform: String): String = """
        [{
          "id": "test.namehash.positional",
          "priority": 9999,
          "state": { "flow": "task:dropoff:navigation" },
          "redact": [ { "find": { "hasIdSuffix": "customer_name" }, "normalize": "customerName" } ],
          "require": { "exists": { "hasText": "x" } },
          "parse": {
            "as": "task",
            "fields": {
              "phase": "DROPOFF",
              "customerNameHash": {
                "find": { "hasIdSuffix": "customer_name" }, "read": "text",
                "transform": $transform
              }
            }
          }
        }]
    """.trimIndent()

    @Test(expected = RuleCompileException::class)
    fun `#745 wrong order — normalize not immediately before sha256 fails`() {
        // normalize present, but a `trim` sits between it and sha256 → not immediately before.
        compile(nameHashRule("""[ "normalizeCustomerName", "trim", "sha256" ]"""))
    }

    @Test(expected = RuleCompileException::class)
    fun `#745 normalize without sha256 anywhere fails (plaintext-canonical leak)`() {
        // A store-name field canonicalizing a customer key with no hash after it would persist the
        // canonical name in the clear. The bidirectional guard rejects it on ANY field.
        compile(
            """
            [{
              "id": "test.storename.bareNormalize",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "require": { "exists": { "hasText": "x" } },
              "parse": {
                "as": "task",
                "fields": {
                  "phase": "DROPOFF",
                  "storeName": {
                    "find": { "hasIdSuffix": "store" }, "read": "text",
                    "transform": [ "trim", "normalizeCustomerName" ]
                  }
                }
              }
            }]
            """.trimIndent(),
        )
    }

    @Test(expected = RuleCompileException::class)
    fun `#745 nested-branch evasion — one coalesce branch hashes unnormalized`() {
        // One coalesce branch normalizes; the sibling hashes bare. The per-chain lint must catch the
        // bare branch even though a sibling is compliant.
        compile(
            """
            [{
              "id": "test.namehash.coalesce.evasion",
              "priority": 9999,
              "state": { "flow": "task:dropoff:navigation" },
              "redact": [ { "find": { "hasIdSuffix": "customer_name" }, "normalize": "customerName" } ],
              "require": { "exists": { "hasText": "x" } },
              "parse": {
                "as": "task",
                "fields": {
                  "phase": "DROPOFF",
                  "customerNameHash": {
                    "coalesce": [
                      { "find": { "hasTextStartsWith": "Deliver to" }, "read": "text",
                        "transform": [ "trim", "normalizeCustomerName", "sha256" ] },
                      { "find": { "hasTextContaining": "Delivery for" }, "read": "text",
                        "transform": [ "trim", "sha256" ] }
                    ]
                  }
                }
              }
            }]
            """.trimIndent(),
        )
    }

    @Test
    fun `#745 happy path — normalize immediately before sha256 compiles`() {
        val compiled = compile(nameHashRule("""[ "trim", "normalizeCustomerName", "sha256" ]"""))
        assertEquals(1, compiled.size)
    }
}
