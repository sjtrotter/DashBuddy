package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #1568: the batch loader. A generation is the bundled files plus any remote files; remote content
 * is admitted one rule at a time and rejected whole on any failure, and the bundled sensitive rules
 * can be neither removed nor pre-empted by it.
 */
class RuleGenerationTest {

    private val signingKey = TestRulesetSigning.keyPair()
    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    private fun file(vararg screens: String, platform: String = "doordash.driver") = """
        { "format_version": 2, "platform_id": "$platform", "screens": [ ${screens.joinToString(",")} ] }
    """.trimIndent()

    private fun rule(
        id: String,
        priority: Int,
        text: String,
        shape: String? = null,
        overrideable: Boolean = true,
        extra: String = "",
    ) = """
        { "id": "$id", "priority": $priority, "overrideable": $overrideable,
          ${shape?.let { "\"parse\": { \"as\": \"$it\" }," } ?: ""}
          "require": { "exists": { "hasText": "$text" } }$extra }
    """.trimIndent()

    private val known = rule("doordash.screen.sensitive.known", 0, "Available Balance", "sensitive", overrideable = false)
    private val catchall = rule("doordash.screen.sensitive.catchall", 999, "Routing Number", "sensitive")
    private val bundledIdle = rule("doordash.screen.idle_map", 10, "Dash Now")
    private val bundledFile = RuleFile.Bundled("rules/doordash.json", file(known, catchall, bundledIdle))

    private fun remote(json: String) = RuleFile.Remote(TestRulesetSigning.verified(json, "test:rules", signingKey))

    private fun assemble(files: Array<out RuleFile>) =
        RuleGenerationAssembler.assemble(files.filterIsInstance<RuleFile.Bundled>(), files.filterIsInstance<RuleFile.Remote>())

    private fun prepared(vararg files: RuleFile): RuleGeneration {
        val result = assemble(files)
        if (result !is GenerationResult.Prepared) fail("expected a prepared generation, got $result")
        return (result as GenerationResult.Prepared).generation
    }

    private fun rejected(vararg files: RuleFile): String {
        val result = assemble(files)
        if (result !is GenerationResult.Rejected) fail("expected a rejection, got $result")
        return (result as GenerationResult.Rejected).reason
    }

    private fun screen(text: String) = UiNode(children = listOf(UiNode(text = text)))
    private fun RuleGeneration.winner(text: String) = screens.matchFirst(screen(text), "doordash")?.ruleId

    // ── Compile errors ─────────────────────────────────────────────────

    @Test
    fun `a generation with one compile-isolated rule is rejected and the last good one stays live`() = runTest {
        interpreter.loadBundled(listOf(bundledFile))
        interpreter.load(TestRulesetSigning.verified(file(known, bundledIdle), "test:rules", signingKey))
        val live = interpreter.generation
        assertNotNull(live)

        val typo = """{ "id": "doordash.screen.typo", "priority": 20, "overridable": true,
            "require": { "exists": { "hasText": "x" } } }"""
        interpreter.load(TestRulesetSigning.verified(file(known, bundledIdle, typo), "test:rules", signingKey))

        assertSame("the rejected generation never went live", live, interpreter.generation)
        assertTrue(rejected(remote(file(known, bundledIdle, typo))).contains("doordash.screen.typo"))
    }

    @Test
    fun `a bundled generation with one compile-isolated rule is rejected and nothing goes live`() = runTest {
        val typo = """{ "id": "doordash.screen.typo", "priority": 20, "overridable": true,
            "require": { "exists": { "hasText": "x" } } }"""
        val broken = RuleFile.Bundled("rules/doordash.json", file(known, bundledIdle, typo))
        assertTrue(rejected(broken).contains("doordash.screen.typo"))

        interpreter.loadBundled(listOf(broken))
        assertFalse("the readiness gate stays closed", interpreter.isLoaded)
        assertTrue("no floor, so no remote generation either", interpreter.prepareGeneration(listOf(remote(file(known)))) is GenerationResult.Rejected)
    }

    @Test
    fun `a bundled file that fails to compile rejects the bundled generation`() {
        val other = RuleFile.Bundled("rules/uber.json", "{ not json")
        assertTrue(rejected(bundledFile, other).contains("rules/uber.json"))
    }

    @Test
    fun `a bundled rule that collides with an earlier file's rule is dropped, not its file`() {
        val sameId = rule("doordash.screen.idle_map", 11, "Dash Now")
        val ownBlock = rule("doordash.screen.sensitive.second", 1, "Bank Account", "sensitive", overrideable = false)
        val later = RuleFile.Bundled("rules/doordash_more.json", file(sameId, ownBlock))
        val generation = prepared(bundledFile, later)
        assertEquals(10, generation.screens.ruleById("doordash.screen.idle_map")?.priority)
        assertEquals("doordash.screen.sensitive.second", generation.winner("Bank Account"))
    }

    // ── The bundled floor ──────────────────────────────────────────────

    @Test
    fun `remote rules are always prepared on the live bundled floor`() = runTest {
        interpreter.loadBundled(listOf(bundledFile))
        val hollowKnown = rule("doordash.screen.sensitive.known", 0, "never rendered", "sensitive", overrideable = false)
        val result = interpreter.prepareGeneration(listOf(remote(file(hollowKnown, rule("doordash.screen.other", 11, "Other")))))
        val generation = (result as GenerationResult.Prepared).generation

        assertEquals("doordash.screen.sensitive.known", generation.winner("Available Balance"))
        assertEquals("doordash.screen.sensitive.catchall", generation.winner("Routing Number"))
    }

    @Test
    fun `a remote preparation before the bundled floor exists is rejected`() = runTest {
        assertTrue(interpreter.prepareGeneration(listOf(remote(file(known)))) is GenerationResult.Rejected)
        interpreter.load(TestRulesetSigning.verified(file(known), "test:rules", signingKey))
        assertNull(interpreter.generation)
    }

    // ── Admission ──────────────────────────────────────────────────────

    @Test
    fun `a remote rule with a protected id is dropped and the bundled rule stays`() {
        val hollowKnown = rule("doordash.screen.sensitive.known", 0, "never rendered", "sensitive", overrideable = false)
        val hijackedCatchall = rule("doordash.screen.sensitive.catchall", 998, "Routing Number")
        val generation = prepared(bundledFile, remote(file(hollowKnown, hijackedCatchall, bundledIdle)))

        assertEquals("doordash.screen.sensitive.known", generation.winner("Available Balance"))
        assertEquals("sensitive", generation.screens.matchFirst(screen("Routing Number"), "doordash")?.shape)
        assertEquals(999, generation.screens.ruleById("doordash.screen.sensitive.catchall")?.priority)
    }

    @Test
    fun `protection is decided by shape, not by the id`() {
        val oddlyNamedBlock = rule("doordash.screen.wallet", 50, "Wallet", "sensitive")
        val notABlock = rule("doordash.screen.sensitive.tips", 60, "Tips")
        val base = RuleFile.Bundled("rules/doordash.json", file(known, oddlyNamedBlock, notABlock))
        val remoteFile = remote(file(rule("doordash.screen.wallet", 50, "Wallet"), rule("doordash.screen.sensitive.tips", 60, "Tips", "idle")))
        val generation = prepared(base, remoteFile)
        assertEquals("sensitive", generation.screens.ruleById("doordash.screen.wallet")?.branches?.single()?.shape)
        assertEquals("idle", generation.screens.ruleById("doordash.screen.sensitive.tips")?.branches?.single()?.shape)
    }

    @Test
    fun `priority below zero is refused for every source`() {
        val negative = rule("doordash.screen.early", -1, "Dash Now")
        assertTrue(rejected(bundledFile, remote(file(known, negative))).contains("doordash.screen.early"))

        val bundledNegative = RuleFile.Bundled("rules/doordash.json", file(known, negative, bundledIdle))
        assertTrue("a bundled one rejects the bundled generation", rejected(bundledNegative).contains("doordash.screen.early"))

        val sensitiveNegative = rule("doordash.screen.sensitive.early", -1, "x", "sensitive", overrideable = false)
        try {
            RuleCompiler.compileRules<UiNode>(Json.parseToJsonElement("[$sensitiveNegative]").jsonArray, RuleContext.SCREEN)
            fail("a sensitive rule with a negative priority must reject its file")
        } catch (e: RuleCompileException) {
            assertTrue(e.message!!.contains("priority"))
        }
    }

    @Test
    fun `remote overrideable false without the sensitive shape on every branch is refused`() {
        val mixed = """{ "id": "doordash.screen.pinned", "priority": 5, "overrideable": false, "branches": [
            { "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Bank" } } },
            { "parse": { "as": "idle" }, "require": { "exists": { "hasText": "Dash Now" } } } ] }"""
        assertTrue(rejected(bundledFile, remote(file(known, mixed))).contains("doordash.screen.pinned"))
    }

    @Test
    fun `a remote block joins after the bundled guard and ahead of every overrideable rule`() {
        fun guard(text: String) = rule("doordash.screen.sensitive.known", 5, text, "sensitive", overrideable = false)
        val remoteBlock = rule("doordash.screen.sensitive.extra", 3, "Available Balance", "sensitive", overrideable = false)
        val greedy = rule("doordash.screen.everything", 2, "Available Balance")
        val remoteFile = remote(file(remoteBlock, greedy))

        val generation = prepared(RuleFile.Bundled("rules/doordash.json", file(guard("Available Balance"), catchall)), remoteFile)
        assertEquals("the bundled guard wins at any priority", "doordash.screen.sensitive.known", generation.winner("Available Balance"))

        val guardMisses = prepared(RuleFile.Bundled("rules/doordash.json", file(guard("zzz"), catchall)), remoteFile)
        assertEquals("then the remote block, ahead of a lower number", "doordash.screen.sensitive.extra", guardMisses.winner("Available Balance"))
    }

    @Test
    fun `a remote generation that omits a bundled catch-all keeps the bundled catch-all in force`() {
        val remoteIdle = rule("doordash.screen.idle_map", 10, "Dash Now", "idle")
        val generation = prepared(bundledFile, remote(file(remoteIdle)))

        assertEquals("doordash.screen.sensitive.catchall", generation.winner("Routing Number"))
        assertEquals("doordash.screen.sensitive.known", generation.winner("Available Balance"))
        assertEquals("the remote rule replaced the bundled one", "idle", generation.screens.ruleById("doordash.screen.idle_map")?.branches?.single()?.shape)
    }

    @Test
    fun `a remote rule that collides with a kept bundled rule is dropped, not its file`() {
        val atCatchallPriority = rule("doordash.screen.late", 999, "Late")
        val remoteIdle = rule("doordash.screen.idle_map", 10, "Dash Now")
        val generation = prepared(bundledFile, remote(file(atCatchallPriority, remoteIdle)))
        assertNull(generation.screens.ruleById("doordash.screen.late"))
        assertNotNull(generation.screens.ruleById("doordash.screen.idle_map"))
    }

    @Test
    fun `platforms the remote files do not carry keep their bundled rules`() {
        val uberKnown = rule("uber.screen.sensitive.known", 0, "Wallet", "sensitive", overrideable = false)
        val uberIdle = rule("uber.screen.idle", 10, "Go online")
        val uberFile = RuleFile.Bundled("rules/uber.json", file(uberKnown, uberIdle, platform = "uber.driver"))
        val generation = prepared(bundledFile, uberFile, remote(file(rule("doordash.screen.other", 11, "Other"))))

        assertNotNull(generation.screens.ruleById("uber.screen.idle"))
        assertNull("the remote platform's bundled overrideable rules are replaced", generation.screens.ruleById("doordash.screen.idle_map"))
    }

    @Test
    fun `a remote file for no known platform is refused`() {
        val reason = rejected(remote(file(rule("acme.screen.sensitive.known", 0, "x", "sensitive", overrideable = false), platform = "acme.driver")))
        assertTrue(reason, reason.contains("platform"))
    }

    @Test
    fun `a remote platform without a sensitive rule is refused`() {
        val reason = rejected(remote(file(rule("uber.screen.idle", 10, "Go online"), platform = "uber.driver")))
        assertTrue(reason, reason.contains("uber"))
    }

    @Test
    fun `remote capabilities carry the remote source`() {
        val offer = """{ "id": "doordash.screen.offer_card", "priority": 30, "enables": ["accept_offer"],
            "bind": { "acceptButton": { "find": { "hasText": "Accept" } } },
            "require": { "exists": { "hasText": "Accept" } } }"""
        val result = RuleGenerationAssembler.assemble(listOf(bundledFile), listOf(remote(file(offer))))
        val capabilities = (result as GenerationResult.Prepared).capabilities
        assertEquals(listOf("test:rules"), capabilities.map { it.source }.distinct())
    }

    // ── Customer fields ────────────────────────────────────────────────

    private fun taskRule(nameSpec: String, redact: Boolean = true) = """
        { "id": "doordash.screen.dropoff", "priority": 40,
          "require": { "exists": { "hasIdSuffix": "customer_name" } },
          ${if (redact) "\"redact\": [ { \"find\": { \"hasIdSuffix\": \"customer_name\" } } ]," else ""}
          "parse": { "as": "task", "fields": { "phase": "DROPOFF", "customerNameHash": $nameSpec } } }
    """.trimIndent()

    private val hashed = """{ "find": { "hasIdSuffix": "customer_name" }, "read": "text",
        "transform": ["trim", "normalizeCustomerName", "sha256"] }"""

    @Test
    fun `a remote rule that parses a customer field without sha256 is refused`() {
        val raw = """{ "find": { "hasIdSuffix": "customer_name" }, "read": "text", "transform": ["trim"] }"""
        val reason = rejected(bundledFile, remote(file(known, taskRule(raw))))
        assertTrue(reason, reason.contains("customerNameHash"))

        val oneArmRaw = """{ "coalesce": [ $hashed, { "find": { "hasIdSuffix": "user_name" }, "read": "text" } ] }"""
        assertTrue(rejected(bundledFile, remote(file(known, taskRule(oneArmRaw)))).contains("customerNameHash"))
    }

    @Test
    fun `a remote rule that parses a customer field without a redact is refused`() {
        rejected(bundledFile, remote(file(known, taskRule(hashed, redact = false))))

        val compiled = RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement("[${taskRule(hashed)}]").jsonArray, RuleContext.SCREEN,
        ).single()
        val raw = Json.parseToJsonElement(taskRule(hashed)).jsonObject
        val reason = RemoteRuleAdmission.refusal(raw, compiled.copy(redact = CompiledRedact.EMPTY), RuleContext.SCREEN)
        assertTrue("$reason", reason!!.contains("redact"))
    }

    @Test
    fun `a nested customer field is checked too`() {
        val timeline = """{ "id": "doordash.screen.timeline", "priority": 41,
            "require": { "exists": { "hasText": "Timeline" } },
            "redact": [ { "find": { "hasTextStartsWith": "Deliver to " } } ],
            "parse": { "as": "timeline", "fields": { "tasks": {
              "each": { "hasTextStartsWith": "Deliver to " },
              "extract": { "nameHash": { "find": { "hasTextStartsWith": "Deliver to " }, "read": "text" } } } } } }"""
        assertTrue(rejected(bundledFile, remote(file(known, timeline))).contains("nameHash"))
    }

    @Test
    fun `a remote rule that hashes its customer fields and redacts them is admitted`() {
        val generation = prepared(bundledFile, remote(file(known, taskRule(hashed))))
        assertNotNull(generation.screens.ruleById("doordash.screen.dropoff"))
    }

    @Test
    fun `a customer field inside a coalesce arm's extract is checked too`() {
        val timeline = """{ "id": "doordash.screen.timeline", "priority": 41,
            "require": { "exists": { "hasText": "Timeline" } },
            "parse": { "as": "timeline", "fields": { "tasks": { "coalesce": [ {
              "each": { "hasTextStartsWith": "Deliver to " },
              "extract": { "nameHash": { "find": { "hasTextStartsWith": "Deliver to " }, "read": "text" } } } ] } } } }"""
        val reason = rejected(bundledFile, remote(file(known, timeline)))
        assertTrue(reason, reason.contains("nameHash"))
    }

    @Test
    fun `a customer field naming a raw selector beside a hashed one is refused`() {
        val address = """{ "find": { "hasIdSuffix": "address_line_1" }, "read": "text",
            "coalesce": [ { "find": { "hasIdSuffix": "address_line_1" }, "read": "text", "transform": "sha256" } ] }"""
        val rule = """{ "id": "doordash.screen.dropoff_address", "priority": 42,
            "require": { "exists": { "hasIdSuffix": "address_line_1" } },
            "redact": [ { "find": { "hasIdSuffix": "address_line_1" } } ],
            "parse": { "as": "task", "fields": { "phase": "DROPOFF", "customerAddressHash": $address } } }"""
        val reason = rejected(bundledFile, remote(file(known, rule)))
        assertTrue(reason, reason.contains("customerAddressHash"))
    }

    private fun notificationFile(addressSpec: String) = """
        { "format_version": 2, "platform_id": "doordash.driver", "notifications": [
          { "id": "doordash.notification.address_push", "priority": 50, "intent": "address_push",
            "require": { "anyFieldContains": "Deliver" },
            "redact": { "text": {} },
            "parse": { "as": "task", "fields": { "customerAddressHash": $addressSpec } } } ] }
    """.trimIndent()

    @Test
    fun `a notification customer field is judged on the notification compiler's semantics`() {
        val ignoredCoalesce = """{ "from": "text", "coalesce": [ { "find": { "hasIdSuffix": "unused" }, "transform": "sha256" } ] }"""
        val reason = rejected(bundledFile, remote(notificationFile(ignoredCoalesce)))
        assertTrue(reason, reason.contains("customerAddressHash"))

        val generation = prepared(bundledFile, remote(notificationFile("""{ "from": "text", "transform": "sha256" }""")))
        assertNotNull(generation.notifications.ruleById("doordash.notification.address_push"))
    }

    // ── Stale prepared generations ─────────────────────────────────────

    private val extraBlock = rule("doordash.screen.sensitive.second", 1, "Bank Account", "sensitive", overrideable = false)
    private val newerFloor = RuleFile.Bundled("rules/doordash.json", file(known, catchall, bundledIdle, extraBlock))

    @Test
    fun `a generation prepared on an older bundled floor is refused at activation`() = runTest {
        interpreter.loadBundled(listOf(bundledFile))
        val onOldFloor = interpreter.prepareGeneration(listOf(remote(file(rule("doordash.screen.other", 11, "Other")))))
        interpreter.loadBundled(listOf(newerFloor))
        val live = interpreter.generation

        assertFalse(interpreter.activate(onOldFloor as GenerationResult.Prepared))
        assertSame(live, interpreter.generation)
        assertEquals("doordash.screen.sensitive.second", interpreter.generation?.winner("Bank Account"))
    }

    @Test
    fun `an older prepared generation cannot replace a newer live one`() = runTest {
        interpreter.loadBundled(listOf(bundledFile))
        val a = interpreter.prepareGeneration(listOf(remote(file(rule("doordash.screen.a", 11, "A")))))
        val b = interpreter.prepareGeneration(listOf(remote(file(rule("doordash.screen.b", 12, "B")))))

        assertTrue(interpreter.activate(b as GenerationResult.Prepared))
        assertFalse(interpreter.activate(a as GenerationResult.Prepared))
        assertNotNull(interpreter.generation?.screens?.ruleById("doordash.screen.b"))
        assertNull(interpreter.generation?.screens?.ruleById("doordash.screen.a"))
    }

    @Test
    fun `another interpreter's prepared generation is refused`() = runTest {
        interpreter.loadBundled(listOf(bundledFile))
        val other = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())
        other.loadBundled(listOf(bundledFile))
        val foreign = other.prepareGeneration(listOf(remote(file(rule("doordash.screen.a", 11, "A")))))
        assertFalse(interpreter.activate(foreign as GenerationResult.Prepared))
    }
}
