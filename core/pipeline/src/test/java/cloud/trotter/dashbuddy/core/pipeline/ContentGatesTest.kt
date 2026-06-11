package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #399 — the shared content gate must drop SENSITIVE and NOISE observations
 * for BOTH sensor pipelines (the notification chain previously had no
 * sensitive gate at all; a future rule emitting the shape would have reached
 * the capture bus and the state machine).
 */
class ContentGatesTest {

    private fun notification(parsed: ParsedFields, target: String) = Observation.Notification(
        timestamp = 1_000L, captureId = null, ruleId = "r",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = parsed, target = target,
    )

    private fun screen(parsed: ParsedFields, target: String) = Observation.Screen(
        timestamp = 1_000L, captureId = null, ruleId = "r",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = parsed, target = target,
    )

    @Test
    fun `sensitive observations are dropped — notification and screen alike`() {
        assertFalse(passesContentGates(notification(ParsedFields.SensitiveFields(), "sensitive.banking")))
        assertFalse(passesContentGates(screen(ParsedFields.SensitiveFields(), "sensitive.banking")))
    }

    @Test
    fun `noise observations are dropped`() {
        assertFalse(passesContentGates(notification(ParsedFields.NoiseFields(), "noise.ongoing")))
    }

    @Test
    fun `ordinary observations pass — including UNKNOWN (captured for triage downstream)`() {
        assertTrue(passesContentGates(notification(ParsedFields.None, "UNKNOWN")))
        assertTrue(passesContentGates(screen(ParsedFields.None, "main_map_idle")))
    }
}
