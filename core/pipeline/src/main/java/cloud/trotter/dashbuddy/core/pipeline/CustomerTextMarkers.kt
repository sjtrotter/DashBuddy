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
 *   `[redacted]` (the capture still ships, minus the leaked PII), on both frame
 *   classes: RECOGNIZED frames whose rule SHOULD have declared a `redact` block
 *   but didn't (#624/#632), and — since #806 — UNKNOWN screen, notification, AND
 *   click envelopes, customer-bearing surfaces no rule recognized (the "Deliver
 *   to "/"Pickup for " task-detail views) that would otherwise persist
 *   name/address/gate-code verbatim, since [SensitiveTextMarkers]
 *   (dasher-banking) correctly ignores customer content.
 *   The UNKNOWN-path scan is fail-toward-privacy: a benign marker-shaped string
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
 *
 * Two further candidates were VETTED AND REJECTED on 2026-08-09 (#994/#995) — CHROME-ambiguous
 * rather than store-ambiguous, but the same category as "Heading to " and found the same way
 * (add the marker, watch `CaptureBackstopCorpusTest` go red on a clean, PII-free corpus):
 * - `"Return "` — the DoorDash timeline renders a RETURN order's task line as
 *   `"Return <FirstName L> to <store>"` (#994), but the platform's own chrome renders the
 *   `on_dash_map` button `"Return to dash"` (6 committed fixtures trip it), and a bare
 *   case-insensitive `startsWith` cannot separate the customer line from the button.
 * - `"Focus on "` — the receipt-scan camera renders `"Focus on <FirstName L>"` (#995), but the
 *   shopping-accuracy tip on `performance_rate_detail` opens `"Focus on accuracy by remembering
 *   drinks and desserts…"`, so the same prefix also precedes app copy.
 * Both leaks are owned instead by the rule-declared `redact` — the primary control per the #806
 * doctrine — on `doordash.screen.timeline` and the new `doordash.screen.pickup_receipt_scan`.
 * The residual is the usual one: an UNKNOWN sibling of either surface (the still-unmodelled
 * return flow) is not covered by THIS scan until a rule recognizes it.
 *
 * ## The node-ID half ([ID_MARKERS], #910)
 *
 * That split-node shape is not an exception, it is the DOMINANT rendering: the 07-28
 * pull shipped a customer name AND a full street address on an UNKNOWN window whose
 * lead-in node read exactly `"Delivery for"` — no trailing space, no name — while the
 * name lived in a bare sibling. A prefix scan can never own that: the marker is in one
 * node and the PII is in another. So the UNKNOWN paths carry a SECOND, structural scan
 * keyed on the node's own view id ([unredactedIdMarker] / [firstUnredactedIdMarker] /
 * [scrubUnknown]) — an enumerated list of ids whose VALUE is customer PII by
 * construction, matched with the same `endsWith` suffix semantics the rules'
 * `hasIdSuffix` predicate uses (the `com.<vendor>:id/` prefix varies by package).
 * Like [MARKERS], the ids are cross-platform recognition DATA in one SSOT, not
 * per-platform Kotlin (principle 8).
 *
 * Scope is deliberately UNKNOWN-only (screen + click envelopes). On a RECOGNIZED frame
 * the rule's declared `redact` is the primary control and its decisions are deliberate
 * — #886 keeps `pickup_navigation`'s address raw because it is a MERCHANT address — so
 * an id scan there could fight the ruleset instead of backing it up. The text-marker
 * backstop above still covers the recognized path.
 *
 * Accepted cost — the `user_name` id is REUSED for non-customer values: it renders
 * the DASHER's own name in some DoorDash chrome, and on a pickup card it holds the
 * MERCHANT ("Pickup from <store>", which `pickup_pre_arrival` parses as `storeName`).
 * Scrubbing those on an UNKNOWN frame loses a little triage text and leaks nothing —
 * fail toward privacy, exactly as the text scan does. It costs nothing on the
 * RECOGNIZED path, which this scan deliberately does not touch, so no rule's
 * merchant-keeps-raw decision is affected.
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
        // #962, fielded 2026-07-30: the on-time rating's PER-ORDER timeline
        // drill-down (Ratings -> On-time rate -> an order) renders the delivery leg
        // as "Delivery to <customer first name>" beside "Pickup at <store>". The
        // list above already carried "Deliver to " and "Delivery for " but NOT this
        // third conjugation, so capture …UNKNOWN__2134b3 persisted a customer's
        // given name verbatim. Data-only addition to the SSOT; zero hits across the
        // whole committed corpus, so no recognized frame's kept text moves.
        "Delivery to ",
        "Message from ", // DoorDash in-app chat push title -> customer name.
        // Uber notification vocabulary (#632) — the keepPrefix lead-ins the
        // uber.json5 trip_at_dropoff redact declares.
        "Leave the order at ", // Uber trip_at_dropoff title -> dropoff ADDRESS.
        "Meet at door for ", // Uber trip_at_dropoff title -> customer name.
    )

    /**
     * View-id SUFFIXES whose node value IS customer PII, independent of any text
     * prefix (#910). Matched case-insensitively with `endsWith`, the same semantics
     * as the rules' `hasIdSuffix` predicate, because the `com.<vendor>:id/` prefix
     * varies by package.
     *
     * Scanned on the UNKNOWN screen/click envelope paths ONLY — see the class KDoc
     * for why the recognized path keeps the rule's `redact` as its primary control.
     * Every entry is a node whose ONLY content is a customer name or a customer
     * address line, so scrubbing it can never take app vocabulary with it: the
     * label siblings (`user_name_label`, `customer_name_label`) are deliberately
     * absent, so a replayed UNKNOWN frame keeps its shape for triage.
     */
    val ID_MARKERS: List<String> = listOf(
        // DoorDash multi-order pickup rows / pickup arrival card -> customer name.
        "customer_name",
        // DoorDash drop-off + pickup contact blocks -> customer name (the node the
        // "Delivery for" label sibling names; #910 V5).
        "user_name",
        // DoorDash address block -> street line and city/ST/ZIP line (#910 V1/V5).
        "address_line_1",
        "address_line_2",
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
     * The first un-redacted customer marker anywhere in [tree] — across EVERY
     * serialized string field of every node ([UiNode.scrubbableStrings], #835:
     * text, contentDescription AND stateDescription) — or null when clean. Cheap
     * short-circuiting scan run on every recognized capture; the [scrub] copy is
     * built only on a hit.
     *
     * Walks the tree itself rather than the memoized [UiNode.allText] because
     * that list is the RECOGNITION SSOT and deliberately excludes
     * `stateDescription` — reading it here would leave the field this scan is
     * supposed to cover invisible.
     */
    fun firstUnredactedMarker(tree: UiNode): String? =
        tree.scrubbableStrings().firstNotNullOfOrNull { (_, value) -> unredactedMarker(value) }
            ?: tree.children.firstNotNullOfOrNull { firstUnredactedMarker(it) }

    /**
     * Return a copy of [tree] with every node's marker-carrying string field
     * scrubbed to [CompiledRedact.REDACTED] — per field, across the
     * [UiNode.scrubbableStrings] SSOT (#835). Call only after
     * [firstUnredactedMarker] returned non-null, so the tree copy never happens
     * on the clean path.
     */
    fun scrub(tree: UiNode): UiNode = tree
        .mapScrubbableStrings { if (unredactedMarker(it) != null) CompiledRedact.REDACTED else it }
        .copy(children = tree.children.map { scrub(it) })

    // --- Node-id path, UNKNOWN envelopes only (#910) --------------------------

    /**
     * The [ID_MARKERS] suffix [node]'s own view id carries while the node still
     * holds UN-redacted text/description, or null. A node whose every value is
     * already masked (a rule's own `[redacted:…]` output, or an empty node) returns
     * null — same already-redacted skip as [unredactedMarker], so this never
     * re-scrubs a mask.
     */
    fun unredactedIdMarker(node: UiNode): String? {
        val id = node.viewIdResourceName
        if (id.isNullOrEmpty()) return null
        val marker = ID_MARKERS.firstOrNull { id.endsWith(it, ignoreCase = true) } ?: return null
        // #835: every serialized string field counts as "still carrying raw" — a
        // customer-PII node whose only remaining value is its `stateDescription`
        // must still be scrubbed.
        val carriesRaw = node.scrubbableStrings()
            .any { (_, value) -> !value.isNullOrEmpty() && !value.contains(REDACTED_MARK) }
        return if (carriesRaw) marker else null
    }

    /**
     * The first [ID_MARKERS] hit anywhere in [tree], or null when clean. Cheap
     * structural scan (no text comparison); the [scrubUnknown] copy is built only
     * on a hit.
     */
    fun firstUnredactedIdMarker(tree: UiNode): String? =
        unredactedIdMarker(tree) ?: tree.children.firstNotNullOfOrNull { firstUnredactedIdMarker(it) }

    /**
     * The UNKNOWN-envelope scrub: a copy of [tree] with every node scrubbed to
     * [CompiledRedact.REDACTED] that carries EITHER an un-redacted text marker
     * ([unredactedMarker]) OR a customer-PII view id ([unredactedIdMarker]). One
     * traversal for both scans, so an UNKNOWN frame is never rebuilt twice. Call
     * only after one of the two scans returned non-null.
     */
    fun scrubUnknown(tree: UiNode): UiNode {
        val byId = unredactedIdMarker(tree) != null
        // An id hit scrubs the node WHOLE (every field of the
        // [UiNode.scrubbableStrings] SSOT, #835); otherwise each field is judged
        // on its own text marker.
        return tree
            .mapScrubbableStrings {
                if (byId || unredactedMarker(it) != null) CompiledRedact.REDACTED else it
            }
            .copy(children = tree.children.map { scrubUnknown(it) })
    }

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
