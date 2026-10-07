package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * SCOPE (#910): this pin covers the TEXT-marker scan on the RECOGNIZED path only —
 * that is the contract, and it is unchanged. #910's sibling
 * [CustomerTextMarkers.firstUnredactedIdMarker] is deliberately NOT run here: it is
 * an UNKNOWN-envelope-only control, and the `user_name` id it scans legitimately
 * carries MERCHANT text on recognized pickup frames ("Pickup from <store>", which
 * `pickup_pre_arrival` parses as `storeName`), so running it over this recognized
 * corpus would report false positives for values the rules keep raw BY DESIGN. Its
 * own coverage lives in `CustomerTextMarkersTest` + `CaptureScrubTest` (UNKNOWN
 * screen/click envelopes). Nothing here is weakened — no assertion was removed or
 * narrowed for #910.
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

    /**
     * #1116 — a SEPARATE, recursive UNKNOWN simulation of the address-block backstop
     * ([UnknownAddressBackstop]) over every committed tree: intent folders, the nested session frames
     * and the committed negative UNKNOWN corpus (`UNKNOWN/negative/`). The test above is untouched.
     *
     * Production runs the detector on UNKNOWN frames only, so a hit on a RECOGNIZED fixture is not a
     * production false positive — it would only cost triage text if that frame ever fell UNKNOWN. Hits
     * are therefore audited, not exempted: every node the detector selects is listed in [EXPECTED_HITS]
     * by file and preorder index, and any change in that set fails (a new hit, or a listed one gone).
     *
     * The corpus is already redacted, so it cannot prove recall on its own: each tree also gets a
     * synthetic address block injected beside its content, which must mask while every original node
     * stays byte-identical to the uninjected scrub. Failure messages carry paths and indices, never text.
     */
    @Test
    fun `the address-block backstop hits only the audited nodes and masks an injected block in every tree`() {
        val base = File("src/test/resources/snapshots")
        val hits = sortedSetOf<String>()
        var scanned = 0
        base.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .filter { committedForAddressScan(it.relativeTo(base).invariantSeparatorsPath) }
            .sortedBy { it.path }
            .forEach { file ->
                val node = try {
                    TestResourceLoader.loadNode(file)
                } catch (_: Exception) {
                    return@forEach
                }
                val path = file.relativeTo(base).invariantSeparatorsPath
                val selection = UnknownAddressBackstop.select(node)
                var index = 0
                fun walk(n: UiNode) {
                    if (index in selection) hits += "$path#$index"
                    index++
                    n.children.forEach { walk(it) }
                }
                walk(node)

                val injected = node.copy(children = node.children + injectedBlock())
                val scrubbedOriginal = CustomerTextMarkers.scrubUnknown(node, selection)
                val scrubbedInjected = CustomerTextMarkers.scrubUnknown(injected, UnknownAddressBackstop.select(injected))
                val block = UiNodeSchema.serialize(scrubbedInjected.children.last())
                for (secret in INJECTED_SECRETS) {
                    assertFalse("$path: an injected block value survived", block.contains(secret))
                }
                assertTrue("$path: the injected header is chrome", block.contains(INJECTED_HEADER))
                assertEquals(
                    "$path: injecting a block changed an original node",
                    UiNodeSchema.serialize(scrubbedOriginal),
                    UiNodeSchema.serialize(scrubbedInjected.copy(children = scrubbedInjected.children.dropLast(1))),
                )
                scanned++
            }
        assertTrue("expected a non-empty corpus", scanned > 500)
        val added = hits - EXPECTED_HITS.toSet()
        val gone = EXPECTED_HITS.toSet() - hits
        assertTrue(
            "address-block selections changed — audit each node (file#preorder index)\n" +
                "new:\n${added.joinToString("\n")}\ngone:\n${gone.joinToString("\n")}",
            added.isEmpty() && gone.isEmpty(),
        )
    }

    private fun injectedBlock() = UiNode(
        className = "android.view.View",
        children = listOf(
            UiNode(text = INJECTED_HEADER),
            UiNode(children = listOf(UiNode(text = "4321 Injected Hollow Dr"), UiNode(text = "Sampleton, TX 78111"))),
            UiNode(text = "\"Injected note by the side gate\""),
            UiNode(children = listOf(UiNode(text = "8642"))),
        ),
    )

    private fun committedForAddressScan(relative: String): Boolean {
        val top = relative.substringBefore('/')
        return when (top) {
            "INBOX", "SENSITIVE" -> false
            "UNKNOWN" -> relative.startsWith("UNKNOWN/negative/")
            else -> true
        }
    }

    companion object {
        private const val INJECTED_HEADER = "Injected details"
        private val INJECTED_SECRETS = listOf("Injected Hollow", "78111", "side gate", "8642")

        /** Audited #1116 selections over the committed corpus (file#preorder index) — see the test KDoc. */
        // Every hit is a real address block on a RECOGNIZED fixture (production never runs the detector
        // there; the rule's redact owns those frames). Audited 2026-10-06: each group is the block's street
        // line, its City, ST ZIP line and, where present, the block's quoted note and short code — no chrome.
        private val EXPECTED_HITS = listOf(
            // street, city
            "dropoff_handoff/20260128_180855_435_DROPOFF_DETAILS_PRE_ARRIVAL.json#29",
            "dropoff_handoff/20260128_180855_435_DROPOFF_DETAILS_PRE_ARRIVAL.json#30",
            // street, city, quoted note (x3 captures of one screen)
            "dropoff_pre_arrival/20260128_183203_985_DROPOFF_DETAILS_PRE_ARRIVAL.json#29",
            "dropoff_pre_arrival/20260128_183203_985_DROPOFF_DETAILS_PRE_ARRIVAL.json#30",
            "dropoff_pre_arrival/20260128_183203_985_DROPOFF_DETAILS_PRE_ARRIVAL.json#42",
            "dropoff_pre_arrival/20260128_184326_798_DROPOFF_DETAILS_PRE_ARRIVAL.json#29",
            "dropoff_pre_arrival/20260128_184326_798_DROPOFF_DETAILS_PRE_ARRIVAL.json#30",
            "dropoff_pre_arrival/20260128_184326_798_DROPOFF_DETAILS_PRE_ARRIVAL.json#42",
            "dropoff_pre_arrival/20260128_184327_028_DROPOFF_DETAILS_PRE_ARRIVAL.json#29",
            "dropoff_pre_arrival/20260128_184327_028_DROPOFF_DETAILS_PRE_ARRIVAL.json#30",
            "dropoff_pre_arrival/20260128_184327_028_DROPOFF_DETAILS_PRE_ARRIVAL.json#42",
            // street, city (decoy values)
            "dropoff_workflow_sheet/2026-08-30_16-30-48-851__doordash__accessibility.window__UNKNOWN__1c7706.json#29",
            "dropoff_workflow_sheet/2026-08-30_16-30-48-851__doordash__accessibility.window__UNKNOWN__1c7706.json#30",
            "dropoff_workflow_sheet/2026-08-30_16-30-48-867__doordash__accessibility.window__UNKNOWN__52eccf.json#18",
            "dropoff_workflow_sheet/2026-08-30_16-30-48-867__doordash__accessibility.window__UNKNOWN__52eccf.json#19",
            // #985's sheet: street (unit included), city, quoted note, short code (decoy values)
            "timeline_task_detail/2026-07-30_19-40-07-170__doordash__accessibility.window__UNKNOWN__f7dd84.json#17",
            "timeline_task_detail/2026-07-30_19-40-07-170__doordash__accessibility.window__UNKNOWN__f7dd84.json#18",
            "timeline_task_detail/2026-07-30_19-40-07-170__doordash__accessibility.window__UNKNOWN__f7dd84.json#27",
            "timeline_task_detail/2026-07-30_19-40-07-170__doordash__accessibility.window__UNKNOWN__f7dd84.json#30",
        )

        /** Top-level snapshot dirs the recognized-frame backstop does not own:
         *  INBOX/UNKNOWN are unrecognized; SENSITIVE is the dasher-block path. */
        private val SKIP = setOf("INBOX", "UNKNOWN", "SENSITIVE")
    }
}
