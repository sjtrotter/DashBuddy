package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.clickDedupHash
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipeline
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
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
    private val redactionSource: ScreenRedactionSource,
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
        // #598: rule-declared capture redaction. A recognized screen persists its
        // full tree for corpus building, but a rule that RECOGNIZES customer PII
        // must MASK it in the serialized envelope. The redacted copy is
        // envelope-only — recognition, parse, the state machine, and the dedup
        // contentHash all ran / run on the ORIGINAL tree (event.tree). UNKNOWN
        // frames have no ruleId and are governed by the SensitiveTextMarkers
        // backstop above instead.
        val redact = obs.ruleId?.let { redactionSource.redactFor(it) }
        val redactedTree = redact?.apply(event.tree) ?: event.tree
        // #624 recognized-frame customer-PII backstop (defense-in-depth): the
        // #598 sha256→redact compile gate only fires for a rule that HASHES PII.
        // A recognized rule that ships raw customer text with NO redact block (or
        // a future downloaded rule that simply omits one) stays silent. Scan the
        // envelope-bound tree for a customer-PII marker whose node was NOT already
        // redacted and scrub it, so a rule that FORGOT to redact still ships a
        // scrubbed capture. UNKNOWN frames are handled by SensitiveTextMarkers above.
        val payloadTree = if (obs.target != UNKNOWN_TARGET) {
            val marker = CustomerTextMarkers.firstUnredactedMarker(redactedTree)
            if (marker != null) {
                stats.onRedactBackstopScrub()
                // Principle 7: log the MARKER + rule id only — NEVER the leaked value.
                Timber.w(
                    "Capture backstop: recognized frame carried un-redacted customer marker '%s' " +
                        "(ruleId=%s) — scrubbing node from envelope",
                    marker, obs.ruleId,
                )
                CustomerTextMarkers.scrub(redactedTree)
            } else {
                redactedTree
            }
        } else {
            redactedTree
        }
        val capture = EnvelopeBuilder.build(
            pipelineId = AccessibilityPipeline.SCREEN_PIPELINE_ID,
            schema = UiNodeSchema,
            platform = platform,
            ruleId = obs.ruleId,
            classificationName = obs.target,
            payload = payloadTree,
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
        // Same fail-closed backstop as captureScreen (#432), added with #597:
        // an UNKNOWN click envelope carries the raw tapped node, and a tap on
        // an unruled sensitive surface must not persist its text. (Rule-matched
        // clicks are app-vocabulary buttons — Accept/Decline/confirm — whose
        // labels carry no PII.)
        if (obs.target == UNKNOWN_TARGET) {
            val marker = SensitiveTextMarkers.findMarker(event.node)
            if (marker != null) {
                stats.onScrubbedUnknownCapture()
                Timber.w("Capture scrubbed: UNKNOWN click hit sensitive marker '%s'", marker)
                return obs
            }
        }
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
            // #597: rule-matched clicks are NEVER deduped at the bus. The bus's
            // seen-set lives as long as the a11y process (days), and a repeat tap
            // on the same button hashes identically — dedup here decayed click
            // forensics to zero within a week. Rule-matched clicks are
            // human-bounded (one envelope per physical tap), so every one
            // persists; the envelope keeps its contentHash for replay. (UNKNOWN
            // clicks are separately bounded by FrameGate's UnknownSuppressor
            // before this method is reached.)
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
        // #620: rule-declared notification-envelope redaction. A recognized
        // notification (customer chat, order-ready) carries customer PII in its
        // flat fields; a rule's `redact` block masks the customer name/body in the
        // serialized envelope only. The masked copy is envelope-only — recognition
        // and parse ran on the ORIGINAL raw, and its contentHash is the dedup
        // identity, captured BEFORE masking (a masked .copy() recomputes a
        // DIFFERENT hash). UNKNOWN notifications have no ruleId and are governed by
        // the SensitiveTextMarkers backstop above instead.
        val originalContentHash = raw.contentHash
        val notifRedact = obs.ruleId?.let { redactionSource.notifRedactFor(it) }
        val redactedRaw = notifRedact?.apply(raw) ?: raw
        // #632 recognized-notification customer-PII backstop (defense-in-depth):
        // the notif analogue of the #624 screen backstop. The #598 sha256→redact
        // compile gate only fires for a rule that HASHES PII; a recognized
        // notification rule that ships raw customer text with NO redact block (or a
        // future downloaded rule that omits one) stays silent. Scan the masked flat
        // fields for a customer-PII marker whose field was NOT already redacted and
        // scrub the whole field, so a rule that FORGOT to redact still ships a
        // scrubbed envelope. The contentHash is still originalContentHash (the dedup
        // identity, #620 VET V7). UNKNOWN notifications are handled by
        // SensitiveTextMarkers above; here they have no ruleId so nothing changes.
        val payloadRaw = if (obs.target != UNKNOWN_TARGET) {
            val marker = CustomerTextMarkers.firstUnredactedMarkerInNotif(redactedRaw)
            if (marker != null) {
                stats.onNotifRedactBackstopScrub()
                // Principle 7: log the MARKER + rule id only — NEVER the leaked value.
                Timber.w(
                    "Capture backstop: recognized notification carried un-redacted customer " +
                        "marker '%s' (ruleId=%s) — scrubbing field from envelope",
                    marker, obs.ruleId,
                )
                CustomerTextMarkers.scrubNotif(redactedRaw)
            } else {
                redactedRaw
            }
        } else {
            redactedRaw
        }
        val capture = EnvelopeBuilder.build(
            pipelineId = NotificationPipeline.PIPELINE_ID,
            schema = RawNotificationSchema,
            platform = platform,
            ruleId = obs.ruleId,
            classificationName = obs.target,
            payload = payloadRaw,
            contentHash = originalContentHash,
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
