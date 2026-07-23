package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #419 (2a) — sensitive-rule survival across a full ruleset REPLACEMENT.
 *
 * [JsonRuleInterpreter.load] swaps the whole ruleset (the CDN hot-reload path,
 * #192). A replacement bundle can't be trusted to include the dasher's
 * sensitive-screen rules, so a replacement that OMITS them for a platform that
 * still ships screen rules is rejected — the previous good bundle (its sensitive
 * block) stays in force. Coverage cannot be silently dropped by a re-load.
 */
class JsonRuleInterpreterReplaceSurvivalTest {

    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    // #416: load() now only accepts signature-verified bytes. Tests sign their own
    // bundles with a runtime keypair (no committed private key) and mint through the
    // real RulesetVerifier — exercising the whole gate.
    private val signingKey = TestRulesetSigning.keyPair()
    private fun load(json: String, source: String) =
        TestRulesetSigning.verified(json, source, signingKey)

    /** A single-child tree carrying [text] (exists/hasText searches the subtree). */
    private fun screen(text: String) = UiNode(children = listOf(UiNode(text = text)))

    private val bankingScreen = screen("Available Balance")

    private val goodBundle = """
        {
          "format_version": 2,
          "platform_id": "doordash.driver",
          "screens": [
            {
              "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
              "parse": { "as": "sensitive" },
              "require": { "exists": { "hasText": "Available Balance" } }
            },
            {
              "id": "doordash.screen.idle_map", "priority": 10,
              "require": { "exists": { "hasText": "MapView" } }
            }
          ]
        }
    """.trimIndent()

    private val replacementWithoutSensitive = """
        {
          "format_version": 2,
          "platform_id": "doordash.driver",
          "screens": [
            {
              "id": "doordash.screen.idle_map", "priority": 10,
              "require": { "exists": { "hasText": "MapView" } }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a replacement that omits sensitive rules is rejected and the previous block survives`() = runTest {
        interpreter.load(load(goodBundle, "good"))
        assertTrue("good bundle should be live", interpreter.isLoaded)
        assertEquals(
            "sensitive",
            interpreter.screenRuleset?.matchFirst(bankingScreen)?.shape,
        )

        // A sensitive-omitting replacement must NOT go live.
        interpreter.load(load(replacementWithoutSensitive, "evil-replacement"))

        // The previous bundle's sensitive block is still in force.
        assertEquals(
            "the banking screen must still classify sensitive after a rejected replacement",
            "sensitive",
            interpreter.screenRuleset?.matchFirst(bankingScreen)?.shape,
        )
    }

    @Test
    fun `a replacement that keeps sensitive rules loads normally`() = runTest {
        interpreter.load(load(goodBundle, "good"))
        // Same platform, still ships the sensitive rule → allowed to swap.
        interpreter.load(load(goodBundle, "good-again"))
        assertEquals(
            "sensitive",
            interpreter.screenRuleset?.matchFirst(bankingScreen)?.shape,
        )
    }
}
