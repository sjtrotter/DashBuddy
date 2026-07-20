package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData

/**
 * App-owned, rules-independent backstop for the CUSTOMER-PII redaction pledge
 * (#624/#632) — the recognized-frame analogue of [SensitiveTextMarkers], shared
 * by BOTH the screen ([firstUnredactedMarker]/[scrub], a UiNode tree) and the
 * notification ([firstUnredactedMarkerInNotif]/[scrubNotif], flat fields) capture
 * paths off one marker SSOT.
 *
 * The two SSOTs differ in both target and action:
 * - [SensitiveTextMarkers] guards the UNKNOWN path against the DASHER's OWN
 *   sensitive screens (banking/identity) by DROPPING the whole capture.
 * - This SSOT guards CUSTOMER PII by SCRUBBING only the offending node/field to
 *   `[redacted]` (the capture still ships, minus the leaked PII), on THREE paths:
 *   RECOGNIZED frames whose rule SHOULD have declared a `redact` block but didn't
 *   (#624/#632), and — since #806 — UNKNOWN SCREEN frames, a customer-bearing
 *   surface no rule recognized (the "Deliver to "/"Pickup for " task-detail views)
 *   that would otherwise persist name/address/gate-code verbatim, since
 *   [SensitiveTextMarkers] (dasher-banking) correctly ignores customer content.
 *   The UNKNOWN-screen scan is fail-toward-privacy: a benign marker-shaped string
 *   is scrubbed too (losing triage-useful text is the accepted cost).
 *
 * Why it's needed: the #598 `sha256`→`redact` compile gate only fires for a rule
 * that HASHES PII in its parse. A rule that ships raw customer text WITHOUT
 * hashing it (the live `dropoff_reminder` screen leak / the `trip_*_dropoff`
 * notification leak #631 found+fixed) stays silent — and once rules are
 * downloaded over a CDN (#192/#416/#419) a rule that simply omits redaction would
 * leak. This backstop closes that class on both sensor paths.
 *
 * Markers are customer-PII LABEL PREFIXES: text that starts with one carries a
 * customer name/address immediately after. The set spans BOTH platforms'
 * vocabulary (DoorDash screens/pushes AND Uber pushes) so the backstop is not
 * DoorDash-only — cross-platform marker DATA in one SSOT, not per-platform Kotlin
 * (#585 platform-coupling catalog: recognition vocabulary is data). Deliberate
 * exclusions (VET V2): `"Heading to "` and `"Your delivery from "` prefix STORE
 * names (merchants are not PII), and Uber's `"Going to "` is excluded for the
 * same reason — it prefixes a STORE on `trip_en_route_pickup` (`^Going to (?!\d)`)
 * and only an address on `trip_en_route_dropoff` (`^Going to \d`); a plain prefix
 * scan can't tell them apart, so that dropoff title relies on the rule-declared
 * `redact` as the primary control (store-FP risk, the "Heading to " precedent —
 * NOT because the title lacks a lead-in). DoorDash's `order_ready` is a true
 * no-marker residual: the customer name sits at the START (`"<name>'s order is
 * ready…"`), so no prefix precedes it. So is `pickup_arrival`'s `customer_name`
 * node (#526 D6a): the "Order for " label is a SEPARATE sibling node
 * (`customer_name_label`), so the `customer_name` node's text is the bare name with
 * no in-node prefix for this scan to catch — the rule-declared `redact` on that node
 * is the ONLY capture control there (and the #548 guard pins the parse to it). The
 * `CaptureBackstopCorpusTest` pins the set to ZERO false positives on the committed
 * (already-redacted) corpus.
 */
object CustomerTextMarkers {

    /** Customer-PII label prefixes. Case-insensitive `startsWith` match. */
    val MARKERS: List<String> = listOf(
        // DoorDash screen + notification vocabulary.
        "Deliver to ",
        "Pickup for ", // DoorDash pickup / "Current task" task-detail views -> customer name (#806).
        "Order for ",
        "Verify items for ",
        "Delivery for ",
        "Message from ", // DoorDash in-app chat push title -> customer name.
        // Uber notification vocabulary (#632) — the keepPrefix lead-ins the
        // uber.json5 trip_at_dropoff redact declares.
        "Leave the order at ", // Uber trip_at_dropoff title -> dropoff ADDRESS.
        "Meet at door for ", // Uber trip_at_dropoff title -> customer name.
    )

    /** Substring that classifies a node's text as already-redacted (VET V1). */
    private const val REDACTED_MARK = "[redacted"

    /**
     * The first marker [text] carries UN-redacted, or null when clean. A node
     * whose text contains "[redacted" anywhere is treated as already-redacted and
     * skipped (VET V1) — otherwise a rule's OWN redact output ("Deliver to door
     * of [redacted:…]") would trip the "Deliver to " marker and re-scrub.
     */
    fun unredactedMarker(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (text.contains(REDACTED_MARK)) return null
        return MARKERS.firstOrNull { text.startsWith(it, ignoreCase = true) }
    }

    /**
     * The first un-redacted customer marker anywhere in [tree] (text or
     * contentDescription of any node), or null when clean. Cheap scan run on
     * every recognized capture; the [scrub] copy is built only on a hit.
     */
    fun firstUnredactedMarker(tree: UiNode): String? =
        tree.allText.firstNotNullOfOrNull { unredactedMarker(it) }

    /**
     * Return a copy of [tree] with every node whose `text`/`contentDescription`
     * carries an un-redacted customer marker scrubbed to [CompiledRedact.REDACTED].
     * Call only after [firstUnredactedMarker] returned non-null, so the tree copy
     * never happens on the clean path.
     */
    fun scrub(tree: UiNode): UiNode = tree.copy(
        text = if (unredactedMarker(tree.text) != null) CompiledRedact.REDACTED else tree.text,
        contentDescription =
            if (unredactedMarker(tree.contentDescription) != null) {
                CompiledRedact.REDACTED
            } else {
                tree.contentDescription
            },
        children = tree.children.map { scrub(it) },
    )

    // --- Notification path (#632) --------------------------------------------
    // Notification captures are FLAT fields, not a UiNode tree — so a hit scrubs
    // the WHOLE offending field (there are no children to isolate). Same
    // already-redacted skip (VET V1) and same marker SSOT as the screen path.
    // Text fields iterate the #666 RawNotificationData.textFields() SSOT rather
    // than hand-listing title/text/bigText/tickerText/subText.

    /**
     * The first un-redacted customer marker across [raw]'s flat text fields
     * (title/text/bigText/tickerText/subText, via [RawNotificationData.textFields])
     * OR its `actionLabels` (#666 item 2 — a push action button label is
     * serialized into the envelope same as the text fields and was previously
     * excluded from this scan), or null when clean. Cheap scan run on every
     * recognized notification capture; the [scrubNotif] copy is built only on
     * a hit.
     */
    fun firstUnredactedMarkerInNotif(raw: RawNotificationData): String? =
        raw.textFields().firstNotNullOfOrNull { (_, value) -> unredactedMarker(value) }
            ?: raw.actionLabels.firstNotNullOfOrNull { unredactedMarker(it) }

    /**
     * A copy of [raw] with every flat text field AND every action label carrying
     * an un-redacted customer marker scrubbed WHOLE to [CompiledRedact.REDACTED].
     * Call only after [firstUnredactedMarkerInNotif] returned non-null.
     * `actionLabels` is intentionally scrubbed here (not folded into
     * [RawNotificationData.textFields]) since it isn't part of
     * [RawNotificationData.toFullString]/`contentHash` — the dedup identity must
     * stay stable regardless of action-label scrubbing.
     */
    fun scrubNotif(raw: RawNotificationData): RawNotificationData = raw
        .withTextFields(raw.textFields().associate { (field, value) -> field to scrubField(value) })
        .copy(actionLabels = raw.actionLabels.map { scrubActionLabel(it) })

    private fun scrubField(field: String?): String? =
        if (unredactedMarker(field) != null) CompiledRedact.REDACTED else field

    private fun scrubActionLabel(label: String): String =
        if (unredactedMarker(label) != null) CompiledRedact.REDACTED else label
}
