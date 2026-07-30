package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #937 — the observed platform app's `versionName` reaches the capture envelope.
 *
 * Two halves: the classifier stamps every observation it produces (the one point that always
 * runs, even when the release `NoOpCaptureBus` skips capture entirely), and the envelope
 * serializes it — while an unstamped envelope stays byte-identical to a pre-#937 one, which is
 * what keeps the committed corpus and the parse golden untouched.
 */
class PlatformAppVersionStampTest {

    private val doordash = "com.doordash.driverapp"

    private val metadataProvider = mock<ReplayMetadataProvider> {
        on { current() } doReturn ReplayMetadata(engineVersion = 7)
    }

    private fun classifier(versions: PlatformAppVersions) =
        ObservationClassifier(mock<JsonRuleInterpreter>(), metadataProvider, versions)

    private fun screenEvent(pkg: String? = doordash) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = UiNode(text = "anything"),
        snapshot = TreeSnapshot(tree = UiNode(text = "anything"), packageName = pkg),
        packageName = pkg,
    )

    // ── The classifier stamps ───────────────────────────────────────────

    @Test
    fun `a classified screen carries the observed app version`() {
        val obs = classifier(PlatformAppVersions { "15.2.3" }).classify(screenEvent())

        assertEquals("15.2.3", obs.metadata.platformAppVersion)
        assertEquals("the base metadata is untouched", 7, obs.metadata.engineVersion)
    }

    @Test
    fun `an unresolvable package leaves the observation unstamped`() {
        val obs = classifier(PlatformAppVersions { null }).classify(screenEvent())

        assertNull(obs.metadata.platformAppVersion)
    }

    @Test
    fun `a frame with no package is never stamped`() {
        val obs = classifier(PlatformAppVersions { "15.2.3" }).classify(screenEvent(pkg = null))

        assertNull(obs.metadata.platformAppVersion)
    }

    @Test
    fun `a resolver that breaks its never-throws contract costs the stamp, not the frame`() {
        val obs = classifier(
            PlatformAppVersions { throw IllegalStateException("PackageManager is unhappy") },
        ).classify(screenEvent())

        // The frame still classified — an escaping throwable here would take the whole
        // sensing upstream down until supervision resubscribed (#430/#909).
        assertNull(obs.metadata.platformAppVersion)
    }

    // ── The envelope serializes it ──────────────────────────────────────

    private val captureBus: CaptureBus = mock { on { isEnabled } doReturn true }

    private val noRedaction = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = null
    }

    private fun capture(metadata: ReplayMetadata): String {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val bus = captureBus
        CaptureWriter(bus, PipelineStats(), noRedaction).captureScreen(
            Observation.Screen(
                timestamp = 1_000L,
                captureId = null,
                ruleId = "doordash.screen.offer",
                metadata = metadata,
                flow = null,
                modeHint = null,
                parsed = ParsedFields.None,
                target = "offer",
            ),
            screenEvent(),
        )
        val cap = argumentCaptor<String>()
        org.mockito.kotlin.verify(bus).offer(any(), any(), anyOrNull(), any(), cap.capture(), anyOrNull())
        return cap.lastValue
    }

    @Test
    fun `the envelope carries the version when the observation was stamped`() {
        val json = capture(ReplayMetadata(engineVersion = 7, platformAppVersion = "15.2.3"))

        assertTrue("expected the stamp in $json", json.contains("\"platformAppVersion\": \"15.2.3\""))
    }

    @Test
    fun `an unstamped envelope omits the key entirely - the corpus stays pre-937 shaped`() {
        val json = capture(ReplayMetadata(engineVersion = 7))

        assertFalse(
            "a null stamp must not even emit the key — committed fixtures carry none",
            json.contains("platformAppVersion"),
        )
    }
}
