package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import android.util.Log
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import timber.log.Timber

/**
 * #633 — cross-FILE rule-id collision hardening. [acceptNonCollidingFiles] skips a
 * LATER file that re-declares an id an EARLIER file already claimed, matching the
 * malformed-file-skip policy. This is the across-files complement to the within-file
 * dup-id reject (#624, [JsonRuleInterpreterDupIdTest]).
 *
 * The shadow it prevents: [Ruleset.byId] is last-wins, so merging a later file that
 * re-uses an id would make the capture-redaction lookup (`redactFor`) resolve to the
 * WRONG rule's `redact` block while priority-ordered [Ruleset.matchFirst] still
 * recognizes with the earlier rule — a customer-PII node could ship raw. Keeping only
 * the earlier file makes byId and matchFirst agree.
 *
 * loadSingle touches neither the Context nor the grant store, so both are mocked.
 */
class JsonRuleInterpreterCrossFileCollisionTest {

    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    private class CapturingTree : Timber.Tree() {
        data class Entry(val priority: Int, val message: String)
        val entries = mutableListOf<Entry>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(priority, message)
        }
    }

    private val logs = CapturingTree()

    @Before fun plant() = Timber.plant(logs)
    @After fun uproot() = Timber.uprootAll()

    /** A file re-declaring [sharedId], with a distinctive redact [keepPrefix] marker. */
    private fun fileDeclaring(platform: String, sharedId: String, keepPrefix: String, ownId: String) = """
        {
          "format_version": 2,
          "platform_id": "$platform",
          "screens": [
            {
              "id": "$sharedId",
              "priority": 9101,
              "require": { "exists": { "hasText": "Deliver to" } },
              "redact": [ { "find": { "hasTextStartsWith": "Deliver to " }, "keepPrefix": ["$keepPrefix"] } ]
            },
            { "id": "$ownId", "priority": 9102, "require": { "exists": { "hasText": "X" } } }
          ]
        }
    """.trimIndent()

    private fun disjointFile(platform: String, id: String) = """
        {
          "format_version": 2,
          "platform_id": "$platform",
          "screens": [
            { "id": "$id", "priority": 9301, "require": { "exists": { "hasText": "C" } } }
          ]
        }
    """.trimIndent()

    @Test
    fun `later file colliding on a rule id is skipped, earlier file and other platform survive`() {
        val shared = "doordash.screen.shared"
        val alpha = interpreter.loadSingle(
            fileDeclaring("doordash.driver", shared, "ALPHA ", "doordash.screen.alpha_only"), "alpha.json",
        )!!
        // Later file re-declares `shared` with a DIFFERENT redact (the would-be shadow).
        val beta = interpreter.loadSingle(
            fileDeclaring("doordash.driver", shared, "BETA ", "doordash.screen.beta_only"), "beta.json",
        )!!
        // A disjoint, different platform that must keep loading.
        val gamma = interpreter.loadSingle(disjointFile("uber.driver", "uber.screen.gamma_only"), "gamma.json")!!

        val accepted = acceptNonCollidingFiles(
            listOf("alpha.json" to alpha, "beta.json" to beta, "gamma.json" to gamma),
        )

        // The LATER colliding file (beta) is dropped whole; earlier + disjoint survive.
        assertEquals(listOf("alpha.json", "gamma.json"), accepted.map { it.first })

        // An ERROR names the colliding id + both files, and leaks no rule text / PII.
        val err = logs.entries.singleOrNull { it.priority == Log.ERROR }
        assertNotNull("a cross-file collision must log exactly one ERROR", err)
        assertTrue(err!!.message.contains(shared))
        assertTrue(err.message.contains("beta.json"))
        assertTrue(err.message.contains("alpha.json"))

        // byId/redact consistency: build the SCREEN ruleset exactly as loadDefaults does
        // and confirm the shared id resolves to the EARLIER (alpha) rule's redact — this
        // is precisely what redactFor(shared) reads. The would-be shadow (beta) is gone.
        val screens = Ruleset(accepted.flatMap { it.second.screens })
        val survivor = screens.ruleById(shared)
        assertNotNull(survivor)
        assertEquals(listOf("ALPHA "), survivor!!.redact.entries.single().keepPrefix)
        // beta contributed nothing at all — not even its own unique rule.
        assertNull(screens.ruleById("doordash.screen.beta_only"))
        // the other platform still loaded.
        assertNotNull(screens.ruleById("uber.screen.gamma_only"))
    }

    /** A file with a single screen rule at [priority] with a unique id, for the given platform. */
    private fun fileWithPriority(platform: String, id: String, priority: Int) = """
        {
          "format_version": 2,
          "platform_id": "$platform",
          "screens": [
            { "id": "$id", "priority": $priority, "require": { "exists": { "hasText": "P" } } }
          ]
        }
    """.trimIndent()

    @Test
    fun `later file colliding on a same-platform section priority is skipped`() {
        // #438 item 3: two DIFFERENT files for the SAME platform declare the SAME
        // screen priority with DISTINCT ids. compileRules' per-file check can't see
        // it (different files); the post-merge gate rejects the later file.
        val alpha = interpreter.loadSingle(
            fileWithPriority("doordash.driver", "doordash.screen.a", 7777), "alpha.json",
        )!!
        val beta = interpreter.loadSingle(
            fileWithPriority("doordash.driver", "doordash.screen.b", 7777), "beta.json",
        )!!

        val accepted = acceptNonCollidingFiles(listOf("alpha.json" to alpha, "beta.json" to beta))

        // Later colliding file dropped whole; earlier survives.
        assertEquals(listOf("alpha.json"), accepted.map { it.first })
        val err = logs.entries.singleOrNull { it.priority == Log.ERROR }
        assertNotNull("a priority collision must log exactly one ERROR", err)
        assertTrue(err!!.message.contains("7777"))
        assertTrue(err.message.contains("beta.json"))
        assertTrue(err.message.contains("alpha.json"))

        val screens = Ruleset(accepted.flatMap { it.second.screens })
        assertNotNull(screens.ruleById("doordash.screen.a"))
        assertNull(screens.ruleById("doordash.screen.b"))
    }

    @Test
    fun `distinct platforms may reuse the same priority freely`() {
        // #438 item 3: a DoorDash screen and an Uber screen at the SAME priority is
        // NOT a collision — distinct platforms never interleave in matchFirst.
        val dd = interpreter.loadSingle(
            fileWithPriority("doordash.driver", "doordash.screen.a", 5555), "dd.json",
        )!!
        val uber = interpreter.loadSingle(
            fileWithPriority("uber.driver", "uber.screen.a", 5555), "uber.json",
        )!!

        val accepted = acceptNonCollidingFiles(listOf("dd.json" to dd, "uber.json" to uber))

        assertEquals(listOf("dd.json", "uber.json"), accepted.map { it.first })
        assertTrue(
            "cross-platform priority reuse must not log an ERROR",
            logs.entries.none { it.priority == Log.ERROR },
        )
        val screens = Ruleset(accepted.flatMap { it.second.screens })
        assertNotNull(screens.ruleById("doordash.screen.a"))
        assertNotNull(screens.ruleById("uber.screen.a"))
    }

    @Test
    fun `two files with disjoint ids both load fully`() {
        val one = interpreter.loadSingle(disjointFile("doordash.driver", "doordash.screen.one"), "one.json")!!
        val two = interpreter.loadSingle(disjointFile("uber.driver", "uber.screen.two"), "two.json")!!

        val accepted = acceptNonCollidingFiles(listOf("one.json" to one, "two.json" to two))

        assertEquals(listOf("one.json", "two.json"), accepted.map { it.first })
        assertTrue("disjoint ids must not log an ERROR", logs.entries.none { it.priority == Log.ERROR })

        val screens: Ruleset<UiNode> = Ruleset(accepted.flatMap { it.second.screens })
        assertNotNull(screens.ruleById("doordash.screen.one"))
        assertNotNull(screens.ruleById("uber.screen.two"))
    }
}
