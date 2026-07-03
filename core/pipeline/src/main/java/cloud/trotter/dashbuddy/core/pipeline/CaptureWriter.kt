package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.clickDedupHash
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipeline
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.domain.capture.WindowContextDto
import cloud.trotter.dashbuddy.domain.capture.schema.ClickCapturePayload
import cloud.trotter.dashbuddy.domain.capture.schema.ClickContextSchema
import cloud.trotter.dashbuddy.domain.capture.schema.RawNotificationSchema
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.Platform
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single envelope→bus capture stage (#361). Both sensor pipelines used to
 * inline their own envelope assembly (83 lines in the accessibility
 * orchestrator, a parallel copy in the notification chain); the serialization
 * mechanics now live here, and the pipelines stay orchestration-only.
 *
 * Each method returns the observation with its `captureId` stamped (null when
 * the bus deduped or skipped the write).
 */
@Singleton
class CaptureWriter @Inject constructor(
    private val captureBus: CaptureBus,
    private val stats: PipelineStats,
) {

    fun captureScreen(
        obs: Observation.Screen,
        event: PipelineEvent.Screen,
    ): Observation.Screen {
        // Fail-closed backstop (#432): the sensitive gate upstream only sees
        // screens a RULE recognized as sensitive. An UNRECOGNIZED sensitive
        // screen classifies UNKNOWN and would be captured verbatim for triage
        // — scan its text and drop the capture on any sensitive marker. The
        // observation still flows (it dies at the UNKNOWN gate downstream);
        // only the disk write is suppressed.
        if (obs.target == UNKNOWN_TARGET) {
            val marker = SensitiveTextMarkers.findMarker(event.tree)
            if (marker != null) {
                stats.onScrubbedUnknownCapture()
                Timber.w("Capture scrubbed: UNKNOWN screen hit sensitive marker '%s'", marker)
                return obs
            }
        }
        val platform = Platform.fromPackage(event.packageName).wire
        val winCtx = event.snapshot.windowContext?.let { wc ->
            WindowContextDto(
                windowId = wc.windowId,
                windowType = wc.windowType,
                windowTitle = wc.windowTitle,
                windowLayer = wc.windowLayer,
                isActive = wc.isActive,
                isFocused = wc.isFocused,
                totalWindowCount = wc.totalWindowCount,
            )
        }
        val capture = EnvelopeBuilder.build(
            pipelineId = AccessibilityPipeline.SCREEN_PIPELINE_ID,
            schema = UiNodeSchema,
            platform = platform,
            ruleId = obs.ruleId,
            classificationName = obs.target,
            payload = event.tree,
            contentHash = event.tree.stableHash,
            metadata = obs.metadata,
            windowContext = winCtx,
        )
        val captureId = captureBus.offer(
            captureId = capture.captureId,
            source = AccessibilityPipeline.SCREEN_PIPELINE_ID,
            classification = obs.target,
            platform = platform,
            envelopeJson = capture.envelopeJson,
            contentHash = capture.contentHash,
        )
        Timber.d(
            "Captured screen: target=%s  ruleId=%s  captured=%s",
            obs.target, obs.ruleId, captureId != null,
        )
        return obs.copy(captureId = captureId)
    }

    fun captureClick(
        obs: Observation.Click,
        event: PipelineEvent.Click,
        screenTarget: String?,
    ): Observation.Click {
        val platform = Platform.fromPackage(event.packageName).wire
        val capture = EnvelopeBuilder.build(
            pipelineId = AccessibilityPipeline.CLICK_PIPELINE_ID,
            schema = ClickContextSchema,
            platform = platform,
            ruleId = obs.ruleId,
            classificationName = obs.target,
            payload = ClickCapturePayload(node = event.node, screenTarget = screenTarget),
            contentHash = clickDedupHash(event.node, screenTarget),
            metadata = obs.metadata,
        )
        val captureId = captureBus.offer(
            captureId = capture.captureId,
            source = AccessibilityPipeline.CLICK_PIPELINE_ID,
            classification = obs.target,
            platform = platform,
            envelopeJson = capture.envelopeJson,
            // #597: clicks are NEVER deduped at the bus. The bus's seen-set lives as
            // long as the a11y process (days), and a repeat tap on the same button
            // hashes identically — dedup here decayed click forensics to zero within
            // a week. Clicks are human-bounded (one envelope per physical tap), so
            // every one persists; the envelope keeps its contentHash for replay.
            contentHash = null,
        )
        Timber.d(
            "Captured click: target=%s  ruleId=%s  captured=%s",
            obs.target, obs.ruleId, captureId != null,
        )
        return obs.copy(captureId = captureId)
    }

    fun captureNotification(
        obs: Observation.Notification,
        raw: RawNotificationData,
    ): Observation.Notification {
        // Same fail-closed backstop as captureScreen (#432) for UNKNOWN
        // notification bodies.
        if (obs.target == UNKNOWN_TARGET) {
            val marker = SensitiveTextMarkers.findMarker(raw.toFullString())
            if (marker != null) {
                stats.onScrubbedUnknownCapture()
                Timber.w("Capture scrubbed: UNKNOWN notification hit sensitive marker '%s'", marker)
                return obs
            }
        }
        val platform = Platform.fromPackage(raw.packageName).wire
        val capture = EnvelopeBuilder.build(
            pipelineId = NotificationPipeline.PIPELINE_ID,
            schema = RawNotificationSchema,
            platform = platform,
            ruleId = obs.ruleId,
            classificationName = obs.target,
            payload = raw,
            contentHash = raw.contentHash,
            metadata = obs.metadata,
        )
        val captureId = captureBus.offer(
            captureId = capture.captureId,
            source = NotificationPipeline.PIPELINE_ID,
            classification = obs.target,
            platform = platform,
            envelopeJson = capture.envelopeJson,
            contentHash = capture.contentHash,
        )
        return obs.copy(captureId = captureId)
    }
}
