package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #416 — end-to-end: the remote-replace path ([JsonRuleInterpreter.load]) is
 * unreachable without signature verification, and a rejected bundle keeps the
 * last-good ruleset live.
 *
 * The gate is STRUCTURAL: `load()` only accepts a [VerifiedRulesetBytes], which the
 * [RulesetVerifier] mints solely after a valid signature. A tampered / wrong-key /
 * garbage bundle produces `null` at verify time, so `load()` is simply never called
 * — the previous good bundle stays in force with no explicit "keep last good" branch
 * needed. This test drives that contract the way a real CDN caller would:
 * `verifier.verify(...)?.let { interpreter.load(it) }`.
 */
class JsonRuleInterpreterVerifiedLoadTest {

    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())

    private val source = "cdn:matchers/doordash"
    private val signingKey = TestRulesetSigning.keyPair()
    private val verifier = RulesetVerifier.fromConfig(
        listOf(RuleSourceKey(source, TestRulesetSigning.publicKeyBase64Der(signingKey))),
    )

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

    /** The real CDN-shaped call site: verify, and only load on success. */
    private suspend fun tryLoad(json: String, signatureBase64: String) {
        verifier.verify(source, json.toByteArray(Charsets.UTF_8), signatureBase64)
            ?.let { interpreter.load(it) }
    }

    @Test
    fun `a validly-signed remote bundle compiles and goes live`() = runTest {
        val sig = TestRulesetSigning.signBase64(goodBundle.toByteArray(Charsets.UTF_8), signingKey.private)
        tryLoad(goodBundle, sig)

        assertTrue("a verified bundle must go live", interpreter.isLoaded)
        assertEquals("sensitive", interpreter.screenRuleset?.matchFirst(bankingScreen)?.shape)
    }

    @Test
    fun `a tampered remote bundle never compiles and the last-good ruleset is retained`() = runTest {
        // First a good load establishes last-good.
        val sig = TestRulesetSigning.signBase64(goodBundle.toByteArray(Charsets.UTF_8), signingKey.private)
        tryLoad(goodBundle, sig)
        val liveRuleset = interpreter.screenRuleset

        // A single-byte flip of the payload, keeping the original (now-stale) signature.
        val tampered = goodBundle.replaceFirst("Available Balance", "Available BalancX")
        tryLoad(tampered, sig)

        assertTrue("previous good bundle must remain live", interpreter.isLoaded)
        assertEquals("live ruleset must be unchanged by a rejected tampered bundle", liveRuleset, interpreter.screenRuleset)
        // And the sensitive rule (which the tampered bundle mangled) is still enforced.
        assertEquals("sensitive", interpreter.screenRuleset?.matchFirst(bankingScreen)?.shape)
    }

    @Test
    fun `verification of a tampered bundle returns null (never reaches load)`() {
        val sig = TestRulesetSigning.signBase64(goodBundle.toByteArray(Charsets.UTF_8), signingKey.private)
        val tampered = (goodBundle + " ").toByteArray(Charsets.UTF_8)
        assertNull("a byte-changed bundle must not verify", verifier.verify(source, tampered, sig))
    }
}
