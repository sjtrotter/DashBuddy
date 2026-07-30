package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * #862 — the capture layer's OWN scrub/backstop WARNs must survive the shareable-log sink.
 *
 * The sink ([cloud.trotter.dashbuddy.core.data.log.LogRepository]) scans every INFO+ line with
 * [SensitiveTextMarkers.findMarker] and redacts the WHOLE line on a hit. A WARN that named the
 * marker VERBATIM therefore redacted itself out of the exported bug report — the export lost
 * exactly the event it exists to record ("the privacy layer fired, here's where"), keeping only
 * `[scrubbed:Transfer out]`.
 *
 * This test drives every scrub/backstop WARN site in [CaptureWriter] through the real production
 * path and asserts each emitted message is scan-clean under the exact function the sink is bound
 * to. It FAILS on the pre-#862 code (the message carried the marker verbatim). The sink itself is
 * untouched — no allowlist — so a genuine third-party string carrying a marker is still scrubbed
 * (pinned in `:app` by `ScrubDiagnosticSurvivesSinkTest` / `LogRepositoryTest`).
 */
class CaptureScrubDiagnosticLogTest {

    private class Recorder : Timber.Tree() {
        val messages = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }

    /** Enabled bus that persists nothing — we only care about the WARN the writer emits. */
    private object SinkBus : CaptureBus {
        override fun offer(
            captureId: String,
            source: String,
            classification: String?,
            platform: String,
            envelopeJson: String,
            contentHash: Int?,
        ): String = captureId
    }

    private val recorder = Recorder()
    private val writer = CaptureWriter(SinkBus, PipelineStats(), NoRedaction)

    @Before fun plant() = Timber.plant(recorder)

    @After fun uproot() = Timber.uproot(recorder)

    private fun screenEvent(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
        packageName = "com.doordash.driverapp",
    )

    private fun screenObs(ruleId: String? = null, target: String = UNKNOWN_TARGET) = Observation.Screen(
        timestamp = 1_000L, captureId = null, ruleId = ruleId, metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.None, target = target,
    )

    private fun clickObs() = Observation.Click(
        timestamp = 1_000L, captureId = null, ruleId = null, metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.None, target = UNKNOWN_TARGET,
    )

    private fun notifObs(ruleId: String? = null, target: String = UNKNOWN_TARGET) = Observation.Notification(
        timestamp = 1_000L, captureId = null, ruleId = ruleId, metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.None, target = target,
    )

    private fun rawNotif(
        title: String? = null,
        text: String? = null,
        actionLabels: List<String> = emptyList(),
    ) = RawNotificationData(
        title = title, text = text, tickerText = null, bigText = null,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        actionLabels = actionLabels,
    )

    /** Fire every scrub/backstop WARN site: 3 sensitive drops + 4 customer scrubs. */
    private fun driveEveryScrubSite() {
        // --- SensitiveTextMarkers drops (the live #862 bug) ---
        writer.captureScreen(
            screenObs(),
            screenEvent(UiNode(children = listOf(UiNode(text = "Available Balance: \$152.10")))),
        )
        writer.captureClick(
            clickObs(),
            PipelineEvent.Click(
                timestamp = 1_000L,
                node = UiNode(text = "Transfer out", isClickable = true),
                packageName = "com.doordash.driverapp",
            ),
            screenTarget = null,
            screenRuleId = null,
        )
        writer.captureNotification(notifObs(), rawNotif(title = "Promo", actionLabels = listOf("Cash out")))

        // --- CustomerTextMarkers backstop scrubs (same shape; pre-emptively id-ified) ---
        writer.captureScreen(screenObs(), screenEvent(UiNode(text = "Deliver to Jane D.")))
        writer.captureScreen(
            screenObs(ruleId = "doordash.screen.test", target = "dropoff_reminder"),
            screenEvent(UiNode(text = "Deliver to Jane D.")),
        )
        writer.captureClick(
            clickObs(),
            PipelineEvent.Click(
                timestamp = 1_000L,
                node = UiNode(text = "Deliver to Jane D.", isClickable = true),
                packageName = "com.doordash.driverapp",
            ),
            screenTarget = null,
            screenRuleId = null,
        )
        writer.captureNotification(notifObs(), rawNotif(title = "Message from Jane"))
        writer.captureNotification(
            notifObs(ruleId = "doordash.notif.test", target = "chat_message"),
            rawNotif(title = "Message from Jane"),
        )
    }

    private fun diagnostics(): List<String> = recorder.messages
        .filter { it.startsWith("Capture scrubbed:") || it.startsWith("Capture backstop:") }

    @Test
    fun `every scrub-backstop WARN survives the shareable sink scan untouched`() {
        driveEveryScrubSite()

        val lines = diagnostics()
        // Sanity: all 8 sites actually fired (a vacuous pass would be worthless).
        assertEquals("expected one diagnostic per driven site: $lines", 8, lines.size)

        for (line in lines) {
            assertNull("diagnostic self-scrubs at the sink: $line", SensitiveTextMarkers.findMarker(line))
        }
    }

    @Test
    fun `no diagnostic names a marker verbatim, from either SSOT`() {
        driveEveryScrubSite()

        val markers = SensitiveTextMarkers.KEYWORDS + CustomerTextMarkers.MARKERS
        for (line in diagnostics()) {
            val leaked = markers.firstOrNull { line.contains(it, ignoreCase = true) }
            assertNull("diagnostic names marker '$leaked' verbatim: $line", leaked)
        }
    }

    @Test
    fun `diagnostics keep their grep-stable prefix and carry the decodable marker id`() {
        driveEveryScrubSite()

        val lines = diagnostics()
        assertEquals(3, lines.count { it.startsWith("Capture scrubbed: ") })
        assertEquals(5, lines.count { it.startsWith("Capture backstop: ") })

        // The id is present and decodes (per MarkerLogIdTest, uniquely) back to the marker.
        assertTrue(
            "click drop must name the 'Transfer out' marker by id: $lines",
            lines.any { it.contains("UNKNOWN click") && it.contains("'${MarkerLogId.of("Transfer out")}'") },
        )
        assertTrue(
            "customer backstop must name the 'Deliver to ' marker by id: $lines",
            lines.any { it.contains("customer marker id '${MarkerLogId.of("Deliver to ")}'") },
        )
    }

    @Test
    fun `a genuine third-party line carrying the same marker is still caught by the scan`() {
        // Fail-closed control: the id transform must not have blunted the scanner itself.
        assertEquals("Transfer out", SensitiveTextMarkers.findMarker("some third-party Transfer out screen"))
    }

    /** No third-party text may reach these lines — only our constants, ids, and rule ids. */
    @Test
    fun `no diagnostic carries scanned third-party text`() {
        driveEveryScrubSite()

        for (line in diagnostics()) {
            assertTrue("diagnostic leaked the scanned value: $line", !line.contains("Jane"))
            assertTrue("diagnostic leaked the scanned value: $line", !line.contains("152.10"))
        }
    }
}
