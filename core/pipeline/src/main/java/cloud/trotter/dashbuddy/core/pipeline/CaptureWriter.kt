package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.pipeline.PipelineRegistry
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
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
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
        // #435 item 5: a disabled bus (release NoOpCaptureBus) discards every offer,
        // so skip the full tree→DTO→JSON→reparse→pretty-print envelope build here —
        // it would only be thrown away. The returned obs is unchanged (captureId stays
        // null, exactly as when the bus deduped/skipped), so downstream is identical.
        // Structural skip: the bus DECLARES it is off; not a per-call-site guess.
        if (!captureBus.isEnabled) return obs
        // Fail-closed backstop (#432): the sensitive gate upstream only sees
        // screens a RULE recognized as sensitive. An UNRECOGNIZED sensitive
        // screen classifies UNKNOWN and would be captured verbatim for triage
        // — scan its text and drop the capture on any sensitive marker. The
        // observation still flows (it dies at the UNKNOWN gate downstream);
        // only the disk write is suppressed.
        if (obs.target == UNKNOWN_TARGET &&
            droppedOnSensitiveMarker(SensitiveTextMarkers.findMarker(event.tree), "UNKNOWN screen", ruleId = null)
        ) return obs
        // #1148 (review rounds 1-2): the window TITLE is app-controlled text (`Activity.setTitle` /
        // `Dialog.setTitle` → `AccessibilityWindowInfo.title`) that sits OUTSIDE the tree, so no
        // tree scrub or rule `redact` selector ever sees it, and a customer name or street line
        // passes both marker scans. It has no recognition or replay value today, so it is NEVER
        // persisted (the envelope field stays, written null); the hashed form arrives with the
        // census skeleton (#1145). One fail-closed signal is kept: an UNKNOWN frame whose title
        // carries a sensitive marker (a title-only banking dialog) is dropped like a tree hit. A
        // RECOGNIZED frame is not dropped on its title — the rule vetted the tree, and dropping
        // every frame of a surface on a constant title would erase it from the corpus.
        if (obs.target == UNKNOWN_TARGET &&
            droppedOnSensitiveMarker(
                event.snapshot.windowContext?.windowTitle?.let(SensitiveTextMarkers::findMarker),
                "UNKNOWN screen's window title",
                ruleId = null,
            )
        ) return obs
        val platform = Platform.fromPackage(event.packageName).wire
        val winCtx = event.snapshot.windowContext?.let { wc ->
            WindowContextDto(
                windowId = wc.windowId,
                windowType = wc.windowType,
                windowTitle = null, // #1148: never persisted in plaintext — hashed form is #1145
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
        // frames have no ruleId, so this consults nothing (redactedTree == event.tree).
        val redact = obs.ruleId?.let { redactionSource.redactFor(it) }
        val redactedTree = redact?.apply(event.tree) ?: event.tree
        // Customer-PII text-marker backstop over the envelope-bound tree, for BOTH
        // frame classes (one marker SSOT, cross-platform DATA — principle 8):
        //  - RECOGNIZED (#624 defense-in-depth): the #598 sha256→redact compile gate
        //    only fires for a rule that HASHES PII, so a recognized rule that ships
        //    raw customer text with NO redact block (or a future CDN rule that omits
        //    one) stays silent. Scrub the offending node so a forgotten redact still
        //    ships a scrubbed capture.
        //  - UNKNOWN (#806): a customer-bearing surface no rule recognized (the
        //    "Deliver to "/"Pickup for " task-detail views) would otherwise persist
        //    name/address/gate-code verbatim. SensitiveTextMarkers above (dasher-
        //    banking) correctly ignores customer content, so this scan is the ONLY
        //    customer control on the UNKNOWN screen path. Fail toward privacy:
        //    scrubbing a benign marker-shaped string is the accepted cost. #910 adds
        //    the node-ID scan beside it (see scrubUnknownTree) for the split-node
        //    shape a prefix scan structurally cannot own.
        // The VET V1 already-redacted skip keeps a rule's OWN redact output from
        // re-tripping. FrameGate's UNKNOWN suppressor already dedups UNKNOWN frames
        // upstream, so the WARN below is at most one per admitted frame — no storm.
        val payloadTree = scrubCustomerPii(redactedTree, obs.target, obs.ruleId, kind = "screen")
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
            source = PipelineRegistry.SCREEN_PIPELINE_ID,
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

    /**
     * @param screenTarget the classification of the screen the tap landed on
     *   (serialized into the envelope as click context, #438 item 2).
     * @param screenRuleId the RULE that produced that classification, or null when
     *   the screen was UNKNOWN / unattributable. Drives the #910 screen-redact
     *   inheritance below; fail-OPEN by construction (a null id, a rule with no
     *   `redact`, leaves the node untouched — exactly as before #910).
     */
    fun captureClick(
        obs: Observation.Click,
        event: PipelineEvent.Click,
        screenTarget: String?,
        screenRuleId: String?,
    ): Observation.Click {
        // #435 item 5: skip the envelope build for a disabled bus (see captureScreen).
        if (!captureBus.isEnabled) return obs
        // Same fail-closed backstop as captureScreen (#432), added with #597, and since #1147
        // review Z1 on EVERY click (recognized included): a click envelope carries the raw tapped
        // node, and the old premise "a rule-matched click is an app button whose labels carry no
        // PII" no longer holds — a Compose button's click-action label / hint / tooltip is arbitrary
        // app text, and a click can be classified during the #1104 stale-screen window on a
        // DasherDirect sheet ("Transfer $45.66 to bank" in the action label). Envelope-only: the
        // observation still flows; only the disk write is suppressed. (A recognized SCREEN is still
        // not dropped on this scan — its rule vetted the whole tree; a click rule vets one label.)
        val clickKind = if (obs.target == UNKNOWN_TARGET) "UNKNOWN click" else "recognized click"
        if (droppedOnSensitiveMarker(SensitiveTextMarkers.findMarker(event.node), clickKind, obs.ruleId)) return obs
        // #910: the click envelope INHERITS the redact of the rule that recognized the
        // screen the tap landed on. A tapped node is serialized in ISOLATION — the
        // fielded leak was a `customer_name` row on a recognized `pickup_post_arrival_multi`
        // list, where the screen envelope masks correctly and the click envelope shipped
        // the same node raw, because captureClick consulted no rule at all. The screen's
        // rule is the one authority on which of ITS nodes are PII, and the tapped node is
        // one of them, so applying that same compiled block here is the smallest correct
        // control (no second enumeration to drift, principle 5). Applies to recognized AND
        // UNKNOWN clicks: a tap can be UNKNOWN while its screen is recognized (exactly the
        // fielded shape). Fail-OPEN: no screen rule / no redact block leaves the node
        // untouched. Sibling-anchored predicates that need a node the subtree doesn't carry
        // simply don't match (fail toward keeping) — the full-frame screen envelope is the
        // primary control for those; the id/text entries that name the tapped node itself
        // are what carry here.
        val screenRedact = screenRuleId?.let { redactionSource.redactFor(it) }
        val redactedNode = screenRedact?.apply(event.node) ?: event.node
        // #806 customer-PII backstop for the click node, on EVERY click (#1147 review X2),
        // mirroring the screen path: a Compose button's click-action label, hint or tooltip is
        // arbitrary app text (fielded shape: `accept_button` text "Accept" with a customer name in
        // its action label), so a RECOGNIZED click runs the TEXT-marker scrub too — byte-identical
        // unless a marker hits; the `ID_MARKERS` node-id scan and the #919 text-input scan stay UNKNOWN-only. The dedup
        // hash below is still on the ORIGINAL node (envelope-only).
        val payloadNode = scrubCustomerPii(
            redactedNode, obs.target, obs.ruleId, kind = "click node",
            // #919 (fable review): a click rule vets one label; with NO screen rule there is no authority
            // over the tapped node's text, so a text input on such a click is masked too.
            screenAuthority = screenRuleId != null,
        )
        val platform = Platform.fromPackage(event.packageName).wire
        val capture = EnvelopeBuilder.build(
            pipelineId = AccessibilityPipeline.CLICK_PIPELINE_ID,
            schema = ClickContextSchema,
            platform = platform,
            ruleId = obs.ruleId,
            classificationName = obs.target,
            payload = ClickCapturePayload(node = payloadNode, screenTarget = screenTarget),
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

    // The UNKNOWN-envelope customer scrub itself is [scrubUnknownTree] below (three scans since #919, plus the #1116 address-block backstop).
    /**
     * #1147 review Z1/Z3 — THE dasher-sensitive drop decision shared by the screen tree, the screen's
     * window title and every click: on a [marker] hit, count it, WARN (tag `Pipeline`, the marker's
     * log-safe id + the rule id only — #862, never the value or the marker verbatim) and return true
     * so the caller suppresses the envelope. The observation itself always flows.
     */
    private fun droppedOnSensitiveMarker(marker: String?, what: String, ruleId: String?): Boolean {
        if (marker == null) return false
        stats.onScrubbedUnknownCapture()
        Timber.tag("Pipeline").w(
            "Capture scrubbed: %s hit sensitive marker id '%s' (ruleId=%s)",
            what, MarkerLogId.of(marker), ruleId ?: "-",
        )
        return true
    }

    /**
     * #1147 review Z3 — THE customer-PII text-marker backstop over an envelope-bound tree, shared by
     * the screen and click paths (one marker SSOT, cross-platform DATA — principle 8):
     *  - UNKNOWN → [scrubUnknownTree] (text marker AND the #910 node-id scan AND the #919 text-input scan AND the #1116 address block);
     *  - recognized, no marker → returned unchanged (byte-identical envelope);
     *  - recognized, marker hit (#624 defense-in-depth: a rule that ships raw customer text with
     *    no redact, or — since #1147 — a click label / hint / tooltip) → count, WARN (tag
     *    `Pipeline`, marker log-safe id + rule id only, #862) and scrub the offending field.
     * The VET V1 already-redacted skip keeps a rule's OWN redact output from re-tripping.
     */
    private fun scrubCustomerPii(
        tree: UiNode,
        target: String?,
        ruleId: String?,
        kind: String,
        screenAuthority: Boolean = true,
    ): UiNode {
        val marker = CustomerTextMarkers.firstUnredactedMarker(tree)
        if (target == UNKNOWN_TARGET) return scrubUnknownTree(tree, marker, kind)
        // #919 (Astra r2/r3 P1): the two recognized-path scrubs COMPOSE, INPUT FIRST — ownership is judged on
        // the ORIGINAL tree (a marker scrub that masks an input's `text` would otherwise erase the user-text
        // evidence the input scan keys on, leaving a draft in another field raw), then the marker scrub.
        val inputScrubbed = if (screenAuthority) tree else scrubUnvettedInputs(tree, ruleId, kind)
        return scrubRecognizedMarker(inputScrubbed, marker, ruleId, kind)
    }

    private fun scrubRecognizedMarker(tree: UiNode, marker: String?, ruleId: String?, kind: String): UiNode {
        return when {
            marker == null -> tree
            else -> {
                stats.onRedactBackstopScrub()
                Timber.tag("Pipeline").w(
                    "Capture backstop: recognized %s carried un-redacted customer marker " +
                        "id '%s' (ruleId=%s) — scrubbing node from envelope",
                    kind, MarkerLogId.of(marker), ruleId,
                )
                CustomerTextMarkers.scrub(tree)
            }
        }
    }

    /**
     * #919 (fable review): a RECOGNIZED click with no screen rule (`screenRuleId == null`) — the click rule vetted
     * one label, nothing vetted the tapped node's text — masks every text-input subtree (and only those);
     * byte-identical when the tap carries no input. Counted with the recognized-path backstop scrubs.
     */
    private fun scrubUnvettedInputs(tree: UiNode, ruleId: String?, kind: String): UiNode {
        val inputNode = CustomerTextMarkers.firstUnredactedInputNode(tree) ?: return tree
        stats.onRedactBackstopScrub()
        Timber.tag("Pipeline").w(
            "Capture backstop: recognized %s with no screen rule carried a text input (input=%s ruleId=%s) — " +
                "masking the input from envelope",
            kind, inputNode, ruleId,
        )
        return CustomerTextMarkers.scrubInputs(tree)
    }

    private fun scrubUnknownTree(
        tree: UiNode,
        textMarker: String?,
        kind: String = "screen",
    ): UiNode {
        val idMarker = CustomerTextMarkers.firstUnredactedIdMarker(tree)
        val inputNode = CustomerTextMarkers.firstUnredactedInputNode(tree) // #919
        // #1116: the rule-independent address-block backstop (street → City, ST ZIP, plus the block's
        // quoted notes and short codes), selected on THIS tree before the clean-frame early return.
        val addressBlock = UnknownAddressBackstop.select(tree)
        // #1116: the gated `Return <name> to <store>` task line (name masked, store kept).
        val returnName = CustomerTextMarkers.hasUnredactedReturnName(tree)
        if (textMarker == null && idMarker == null && inputNode == null && addressBlock.isEmpty() && !returnName) return tree
        stats.onUnknownCustomerScrub()
        // Principle 7: a text marker is named by its log-safe id (#862) — the marker
        // constants are themselves scanned by the shareable-log sink, so naming one
        // verbatim would self-scrub the WARN. A node-ID marker is NOT a marker
        // constant: it is a view-id token ("user_name"), carries no PII and matches
        // no sensitive marker, so it logs verbatim and stays decodable.
        Timber.tag("Pipeline").w(
            "Capture backstop: UNKNOWN %s carried customer PII (textMarker=%s nodeId=%s input=%s addressBlock=%d returnName=%s) — " +
                "scrubbing node from envelope",
            kind,
            textMarker?.let { MarkerLogId.of(it) } ?: "-",
            idMarker ?: "-",
            inputNode ?: "-",
            addressBlock.count,
            returnName,
        )
        return CustomerTextMarkers.scrubUnknown(tree, addressBlock)
    }

    fun captureNotification(
        obs: Observation.Notification,
        raw: RawNotificationData,
    ): Observation.Notification {
        // #435 item 5: skip the envelope build for a disabled bus (see captureScreen).
        if (!captureBus.isEnabled) return obs
        // Same fail-closed backstop as captureScreen (#432) for UNKNOWN
        // notification bodies. Scans the flat text fields AND actionLabels
        // (#666 item 2c — a push action button label is serialized into the
        // envelope same as the text fields and was previously excluded from
        // this scan, so an UNKNOWN notification with a sensitive marker ONLY in
        // an action label would have shipped uncaught).
        if (obs.target == UNKNOWN_TARGET) {
            val marker = SensitiveTextMarkers.findMarker(raw.toFullString())
                ?: raw.actionLabels.firstNotNullOfOrNull { SensitiveTextMarkers.findMarker(it) }
            if (marker != null) {
                stats.onScrubbedUnknownCapture()
                // #862: marker named by log-safe id, not verbatim (see captureScreen).
                Timber.tag("Pipeline").w(
                    "Capture scrubbed: UNKNOWN notification hit sensitive marker id '%s'",
                    MarkerLogId.of(marker),
                )
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
        // DIFFERENT hash). UNKNOWN notifications have no ruleId so this rule-declared
        // redact is a no-op for them; their customer-PII control is the marker
        // backstop below (#806) plus the SensitiveTextMarkers drop above.
        val originalContentHash = raw.contentHash
        val notifRedact = obs.ruleId?.let { redactionSource.notifRedactFor(it) }
        val redactedRaw = notifRedact?.apply(raw) ?: raw
        // Customer-PII notification backstop over the envelope-bound raw, for BOTH
        // frame classes (one marker SSOT — the scan covers all 5 flat text fields via
        // RawNotificationData.textFields() AND actionLabels, #666):
        //  - RECOGNIZED (#632 defense-in-depth): the notif analogue of the #624 screen
        //    backstop. The #598 sha256→redact compile gate only fires for a rule that
        //    HASHES PII; a recognized notification rule that ships raw customer text
        //    with NO redact block (or a future CDN rule that omits one) stays silent.
        //    Scrub the offending whole field so a forgotten redact still ships clean.
        //  - UNKNOWN (#806): an unrecognized customer-bearing push (a "Message from
        //    <name>"/"Meet at door for <name>" body an OTA rule doesn't cover yet)
        //    would otherwise persist the customer name verbatim. SensitiveTextMarkers
        //    above (dasher-banking) ignores customer content, so this scan is the only
        //    customer control on the UNKNOWN notification path. Fail toward privacy.
        // The contentHash is still originalContentHash (the dedup identity, #620 VET
        // V7) either way. UNKNOWN notifs have no ruleId so redactedRaw == raw.
        val payloadRaw = run {
            val marker = CustomerTextMarkers.firstUnredactedMarkerInNotif(redactedRaw)
            when {
                marker == null -> redactedRaw
                obs.target == UNKNOWN_TARGET -> {
                    stats.onUnknownCustomerScrub()
                    // Principle 7: log the MARKER ID only (#862) — NEVER the leaked value,
                    // and never the marker verbatim (that self-scrubs at the sink).
                    Timber.tag("Pipeline").w(
                        "Capture backstop: UNKNOWN notification carried customer marker id '%s' — " +
                            "scrubbing field from envelope",
                        MarkerLogId.of(marker),
                    )
                    CustomerTextMarkers.scrubNotif(redactedRaw)
                }
                else -> {
                    stats.onNotifRedactBackstopScrub()
                    // Principle 7: log the MARKER ID + rule id only (#862) — NEVER the leaked
                    // value, and never the marker verbatim (that self-scrubs at the sink).
                    Timber.tag("Pipeline").w(
                        "Capture backstop: recognized notification carried un-redacted customer " +
                            "marker id '%s' (ruleId=%s) — scrubbing field from envelope",
                        MarkerLogId.of(marker), obs.ruleId,
                    )
                    CustomerTextMarkers.scrubNotif(redactedRaw)
                }
            }
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
