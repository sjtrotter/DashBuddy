package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.util.Locale

/**
 * #1059 — the dasher's OWN identity/payment surfaces are BLOCKED at the matcher layer,
 * never recognized.
 *
 * Three surfaces reached UNKNOWN capture on the shipped ruleset (2026-08-27/28 pull):
 * the embedded **Persona selfie / ID-verification** flow, the **"Your Red Card" wallet**
 * screen, and the **passport** variant of the ID-scan camera (whose two pre-existing
 * anchors are both driver's-licence specific). None of them leaked customer PII — the
 * Pledge blocks a *document-image capture* surface as a CLASS regardless of content, and
 * the dasher's payment-card management screen under the payment-screens-blocked doctrine.
 *
 * [GoldenSnapshotRegressionTest] already guards the whole `SENSITIVE/` folder, but its bar
 * is deliberately weak — "a sensitive rule OR the toxic scanner". That admits a fixture the
 * *rules* miss entirely and only the backstop catches, which is exactly the state these
 * three surfaces were in before this issue. This test pins the stronger property for them:
 *
 *  1. the **rule** claims the frame, and it is `doordash.screen.sensitive.known` — the
 *     priority-0, `overrideable: false` partition, so no later/lower-priority rule from any
 *     source can pre-empt it (#419);
 *  2. the frame therefore classifies with [ParsedFields.SensitiveFields], which is the exact
 *     predicate `passesContentGates` (`:core:pipeline`, `internal`) keys on — so it is dropped
 *     by the shared content gate BEFORE `CaptureWriter` ever sees it (`AccessibilityPipeline`
 *     orders the sensitive gate ahead of the dedup+capture stage; `ContentGatesTest` pins the
 *     gate's own behaviour on that shape);
 *  3. the rules-INDEPENDENT [SensitiveTextMarkers] backstop independently trips on every frame
 *     of these surfaces that renders text at all — the belt for a ruleset that fails to load
 *     or ships without these branches (#432).
 *
 * Plus the over-match direction, which is what makes the marker additions safe: the four new
 * keywords appear nowhere in the committed non-sensitive corpus. The Red Card screen's own
 * headline ("Your Red Card") is deliberately NOT a marker, because ordinary shopping-order
 * pickup instructions legitimately say "pay with your Red Card".
 */
class SensitiveSurfaceBlockTest {

    private val screenRuleset: Ruleset<UiNode> by lazy { TestRulesetFactory.screenRuleset }

    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> { on { screenRuleset } doReturn TestRulesetFactory.screenRuleset },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )

    private fun fixture(filename: String): UiNode {
        val file = File("src/test/resources/$FOLDER/$filename")
        assertTrue("missing #1059 fixture $FOLDER/$filename", file.isFile)
        return TestResourceLoader.loadNode(file)
    }

    private fun classify(tree: UiNode): Observation.Screen {
        val pkg = requireNotNull(Platform.DoorDash.packageName)
        return classifier.classify(
            PipelineEvent.Screen(
                timestamp = 1_000L,
                tree = tree,
                snapshot = TreeSnapshot(tree, packageName = pkg),
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `the dasher's identity and payment surfaces are claimed by the priority-0 sensitive rule`() {
        for ((filename, intent) in EXPECTED_INTENTS) {
            val match = screenRuleset.matchFirst(fixture(filename), platformWire = Platform.DoorDash.wire)
            assertNotNull("$filename no longer matches ANY rule — it would fall to UNKNOWN capture", match)
            assertEquals(
                "$filename must be claimed by the priority-0, non-overrideable sensitive rule",
                SENSITIVE_RULE_ID,
                match!!.ruleId,
            )
            assertEquals("$filename matched the wrong sensitive branch", intent, match.intent)
        }
    }

    @Test
    fun `each frame classifies sensitive, so the content gate drops it before any capture`() {
        for ((filename, _) in EXPECTED_INTENTS) {
            val obs = classify(fixture(filename))
            assertTrue(
                "$filename classified ${obs.parsed::class.simpleName} — only SensitiveFields is " +
                    "dropped by passesContentGates, so anything else reaches CaptureWriter",
                obs.parsed is ParsedFields.SensitiveFields,
            )
        }
    }

    @Test
    fun `the rules-independent marker backstop also trips on every text-bearing frame`() {
        for (filename in TEXT_BEARING) {
            assertNotNull(
                "$filename has no SensitiveTextMarkers hit — with the ruleset unloaded or " +
                    "missing these branches (#432) it would reach disk verbatim",
                SensitiveTextMarkers.findMarker(fixture(filename)),
            )
        }
        // The Compose-hosted Persona variant renders NO text at all (a single
        // `personaComposeView` container), so a text backstop structurally cannot cover it —
        // the locale-immune view-id anchor is its only defence, which is the #938/#924 point.
    }

    @Test
    fun `the new markers appear nowhere in the committed non-sensitive corpus`() {
        val base = File("src/test/resources/snapshots")
        val dirs = base.listFiles { f -> f.isDirectory && f.name !in SKIP_FOLDERS }?.sortedBy { it.name }.orEmpty()
        var scanned = 0
        for (dir in dirs) {
            for ((filename, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                scanned++
                val text = node.allScrubbableText().joinToString(" ").lowercase(Locale.ROOT)
                for (marker in NEW_MARKERS) {
                    assertTrue(
                        "snapshots/${dir.name}/$filename carries the #1059 marker '$marker' — it is " +
                            "a legitimate delivery surface, so the marker would drop real captures " +
                            "and redact real INFO log lines. Pick a phrase unique to the dasher's " +
                            "own surface (the #738 uniqueness discipline).",
                        !text.contains(marker.lowercase(Locale.ROOT)),
                    )
                }
            }
        }
        assertTrue("scanned no corpus files — the folder filter is wrong", scanned > 0)
    }

    private companion object {
        const val FOLDER = "snapshots/SENSITIVE"
        const val SENSITIVE_RULE_ID = "doordash.screen.sensitive.known"

        val SKIP_FOLDERS = setOf("SENSITIVE", "INBOX", "UNKNOWN")

        const val PERSONA_RETRY = "2026-08-27_15-44-42-388__doordash__persona_selfie_retry__41b24f.json"
        const val PERSONA_COMPOSE = "2026-08-27_17-40-19-020__doordash__persona_compose_view__65228e.json"
        const val RED_CARD = "2026-08-27_17-44-28-120__doordash__red_card_wallet__50d631.json"
        const val ID_SCAN_PASSPORT = "2026-08-28_16-58-51-783__doordash__id_scan_passport__407c00.json"

        /** Fixture → the `sensitive.known` branch that must claim it. */
        val EXPECTED_INTENTS = listOf(
            PERSONA_RETRY to "sensitive.selfie_verification",
            PERSONA_COMPOSE to "sensitive.selfie_verification",
            RED_CARD to "sensitive.red_card",
            ID_SCAN_PASSPORT to "sensitive.id_verification",
        )

        /** Every #1059 fixture that renders text (see the note in the backstop test). */
        val TEXT_BEARING = listOf(PERSONA_RETRY, RED_CARD, ID_SCAN_PASSPORT)

        /** The keywords #1059 added to [SensitiveTextMarkers.KEYWORDS]. */
        val NEW_MARKERS = listOf(
            "verifying your selfie",
            "Activate a physical card",
            "Request a physical card",
            "Align the character strip",
        )
    }
}
