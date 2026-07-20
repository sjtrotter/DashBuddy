package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #624 (VET V2) corpus-validate for the recognized-frame customer-marker backstop.
 *
 * It mirrors the production capture flow — recognize the frame, apply that rule's
 * `redact`, THEN run [CustomerTextMarkers.firstUnredactedMarker] — over EVERY
 * committed, already-redacted snapshot (intent folders AND the nested session
 * replay fixtures under `sessions`, #620 review F6), and asserts ZERO hits. The
 * corpus is clean by construction, so ANY hit is a false positive: this is what
 * pins the marker set (why "Heading to " — a STORE-name prefix on pickup-nav
 * frames — is deliberately excluded, VET V2) and proves the #624 rule/data fixes
 * (dropoff_reminder, pickup_verify_items) leave the corpus scrub-free.
 *
 * This test walks the tree itself (rather than [TestResourceLoader.loadSnapshots],
 * which is non-recursive) so the session frames are covered too; the shared loader
 * stays non-recursive because GoldenSnapshotRegressionTest iterates the session
 * dir as an intent folder and would break if it recursed.
 */
class CaptureBackstopCorpusTest {

    private val screenRuleset = TestRulesetFactory.screenRuleset

    @Test
    fun `the customer-marker backstop finds zero leaks across the redacted corpus`() {
        val base = File("src/test/resources/snapshots")
        assertTrue("snapshot corpus dir must exist", base.isDirectory)

        var scanned = 0
        base.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .filter { it.relativeTo(base).invariantSeparatorsPath.substringBefore('/') !in SKIP }
            .sortedBy { it.path }
            .forEach { file ->
                // Some session envelopes are click/notification captures whose payload
                // isn't a UiNode tree — they decode to an empty node (or throw) and
                // classify UNKNOWN, so they're harmlessly skipped.
                val node = try {
                    TestResourceLoader.loadNode(file)
                } catch (_: Exception) {
                    return@forEach
                }
                // This test validates the RECOGNIZED path only (rule → its redact →
                // backstop scan). UNKNOWN frames are also scrubbed by this backstop now
                // (#806, on the CaptureWriter UNKNOWN screen/notif/click paths), but the
                // committed corpus here is all recognized, redacted fixtures — an
                // unmatched frame has no rule redact to mirror, so it's skipped.
                val match = screenRuleset.matchFirst(node) ?: return@forEach
                // Mirror captureScreen: recognized rule's redact first, then backstop scan.
                val redacted = screenRuleset.ruleById(match.ruleId)
                    ?.redact?.takeUnless { it.isEmpty() }
                    ?.apply(node)
                    ?: node
                val marker = CustomerTextMarkers.firstUnredactedMarker(redacted)
                assertNull(
                    "${file.path} leaked an un-redacted customer marker '$marker' " +
                        "(rule ${match.ruleId}) — a false positive on a clean corpus; fix the rule's " +
                        "redact or the marker set",
                    marker,
                )
                scanned++
            }

        assertTrue("expected a non-empty corpus", scanned > 0)
    }

    companion object {
        /** Top-level snapshot dirs the recognized-frame backstop does not own:
         *  INBOX/UNKNOWN are unrecognized; SENSITIVE is the dasher-block path. */
        private val SKIP = setOf("INBOX", "UNKNOWN", "SENSITIVE")
    }
}
