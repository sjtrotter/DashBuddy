package cloud.trotter.dashbuddy.test.util

/**
 * The byte-exact set of **hand-sanitized pseudonyms** deliberately committed into the snapshot
 * corpus in PII-SHAPED form (#992/#993/#994/#995, PR #1010).
 *
 * ## Why this exists
 *
 * The corpus guards ([CaptureRedactionCorpusTest]'s committed-corpus PII guard, FIX 4) exist so a
 * future intake of a REAL field pull cannot commit a real customer name or street address green.
 * Those guards are shape- and id-based, so they cannot tell a real name from a realistic fake —
 * and a redaction fixture is only worth committing if it still carries the RAW shape the runtime
 * redact has to fire on. A fixture whose name is already `[redacted:…]` proves nothing about
 * whether the rule masks anything.
 *
 * Two resolutions were available. **(a) An explicit byte-exact decoy allowlist** — this file:
 * the fakes are enumerated, anything *else* PII-shaped in those folders still fails.
 * **(b) Convert the fixtures to device-emitted masked shape** and feed the redaction tests
 * separately-constructed raw trees. (a) was chosen: it keeps the committed fixtures able to prove
 * the production redacts fire on a real tree shape (the #871/#885 "teeth" property — a synthetic
 * tree only proves the predicate, not that the predicate matches what DoorDash actually renders),
 * and it makes the exemption an ENUMERATION rather than a loosening. The guard's strength is
 * unchanged for every value not listed here: a real name/address in a future intake fails.
 *
 * ## The rules for adding an entry
 *
 * 1. It must be a value **you** wrote, never a value a device emitted.
 * 2. It must be byte-exact — no prefix/substring matching, no regex. A real name that merely
 *    *resembles* a decoy still fails the guard.
 * 3. It must name the file it lives in and the issue that put it there, so a reviewer can check
 *    the provenance without trusting this comment.
 * 4. Deleting a fixture means deleting its entries. [CaptureRedactionCorpusTest] pins that every
 *    entry is still reachable, so a stale exemption cannot linger and silently widen the hole.
 */
object CorpusDecoys {

    /**
     * `value` → why it is a decoy. Matched with `==`, never `contains`.
     *
     * The three identities are deliberately reused across files exactly as the real capture
     * reused them (the wait-survey customer and the receipt-scan customer are the same physical
     * job, 08-07 19:46:17 → 19:46:54), so the cross-surface one-customer-one-hex assertions are
     * testing a real relationship rather than an artefact of the sanitizing.
     */
    val ALLOWED: Map<String, String> = mapOf(
        // #992 pickup_wait_survey/2026-08-07_19-46-17-026__…__7e4609.json — `customer_name`.
        // #995 pickup_receipt_scan/2026-08-07_19-46-54-647__…__1cb4be.json and …__92d1cb.json —
        //      the bare name node beside the scan target (same customer as the wait survey).
        "Jordan T" to "hand-written pseudonym for the 08-07 pickup/receipt-scan customer",
        // #995 pickup_receipt_scan/* — the id-less instruction line.
        "Focus on Jordan T" to "hand-written pseudonym inside the receipt-scan instruction line",
        // #994 timeline/2026-08-01_12-53-16-000__…__697a26.json — the return-order task line.
        "Return Riley P to H-E-B" to
            "hand-written pseudonym in the return conjugation; the store is the real merchant " +
            "(merchant names are driver-owned, not PII, and are kept across the corpus)",
        // #985 timeline_task_detail/2026-07-30_19-40-07-170__…__f7dd84.json — the DROPOFF-task
        //      render's id-less task line. The device capture shipped this node already scrubbed
        //      to a plain `[redacted]` (the #806 prefix backstop fires on "Deliver to "), which
        //      would have proved nothing about the new rule's redact, so the fixture carries a
        //      hand-written pseudonym in the raw shape DoorDash renders.
        "Deliver to Avery K" to
            "hand-written pseudonym on the Timeline order-detail sheet's dropoff task line",
        // #985 timeline_task_detail/2026-07-28_20-13-06-104__…__c9b8d9.json — the PICKUP-task
        //      render of the SAME sheet. Same physical pseudonym as the dropoff line on purpose:
        //      one customer's two task rows, so the cross-surface one-customer-one-hex assertion
        //      is testing a real relationship rather than an artefact of the sanitizing.
        "Pickup for Avery K" to
            "hand-written pseudonym on the Timeline order-detail sheet's pickup task line",
        // #993 dropoff_navigation/2026-08-02_18-00-01-999__…__2109db.json — `arriving_at_title`.
        "1425 Sample Ridge Dr, Apt 12, San Antonio, TX 78200, USA" to
            "hand-written street address; house number, street and ZIP are all invented, the " +
            "city/state are kept so the line still has the fielded shape",
        // #1039 (PR #1042 round 2) brought `dropoff_pre_arrival` into the FIX 4 guard, which
        // surfaced the pseudonyms its two 2026-06-12 fixtures have carried since they were
        // committed by PR #462/#460 (`dropoff_pre_arrival_711__335996.json` and
        // `dropoff_pre_arrival_alcohol__f076f1.json`, both the same card). They were sanitized by
        // hand at commit time — the fielded customer on that card was "Adam C" per the #462
        // commit message, and the committed value is "Sample C" — and they are PII-ID-borne
        // (`user_name` / `address_line_1` / `address_line_2`), which is what the guard flags.
        // Kept raw, per this file's whole premise: these fixtures are what prove the
        // `dropoff_pre_arrival` id-anchored redacts fire on the tree DoorDash actually renders.
        "Sample C" to "hand-written pseudonym for the 06-12 dropoff arrival card's customer",
        "Sample Complex" to
            "hand-written venue/complex name in the same card's `address_line_1`",
        "100 Sample St, San Antonio, TX 78000, USA" to
            "hand-written street address in the same card's `address_line_2`; house number, " +
            "street and ZIP are all invented, city/state kept so the line keeps its fielded shape",
        // #1058 — `address_subpremise_line` and `dasher_instruction_content_collapsed` joined
        // `CustomerTextMarkers.ID_MARKERS`, which is the id SSOT the FIX 4 guard reuses, so the
        // two 2026-06-12 `dropoff_pre_arrival` fixtures' long-standing hand-sanitized values
        // became visible to it. Both were sanitized by hand at commit time (PR #462/#460): the
        // unit number is a row of zeros, and the instruction body was truncated at its label.
        // They are kept in raw shape for the same reason as everything else in this file — they
        // are what proves the id-anchored redacts fire on the tree DoorDash actually renders.
        "Apt/Suite: 0000" to
            "hand-written unit number on the 06-12 dropoff arrival cards and on the 08-28 " +
            "alcohol-variant fixture (#1058); a row of zeros, never a fielded value",
        "Hand it to me: " to
            "hand-truncated delivery-instruction body on the two 06-12 dropoff arrival cards — " +
            "the customer's text was removed at commit time, the label kept",
        // #1058 dropoff_pre_arrival/2026-08-28_16-58-20-471__…__172c76.json — the ALCOHOL
        // variant's instruction body. The fielded value was a customer-written note carrying a
        // door code; this is an invented note of similar length in the same shape, so the
        // fixture still proves the id-anchored redact masks the whole node.
        "Hand it to me: Gate code 00000, then take the first left and park by building B. " +
            "Please call when you reach the gate and I will walk down to meet you at the " +
            "mailboxes by the pool, thank you so much for helping." to
            "hand-written delivery instruction on the 08-28 alcohol arrival card",
        // #1058 dropoff_workflow_sheet/2026-08-30_16-30-48-8{51,67}__…json — the id-LESS 8.93.7
        // sheet. EVERY customer value on it is invented: the whole point of the fixture is that
        // the address block, the unit row, the note and the bare code carry no view ids at all,
        // so the rule's shape-anchored redact is the only control and it can only be proven
        // against values in the fielded shape.
        "1200 Sample Loop" to "hand-written street line on the 08-30 dropoff workflow sheet",
        "Sampleton, TX 00000" to
            "hand-written city/ST/ZIP line on the same sheet; the city and ZIP are invented, the " +
            "two-letter-state shape is kept so the sibling-anchored entries still match",
        "Apt 0000" to "hand-written unit value in the same sheet's label-split subpremise row",
        "\"Gate code 0000 at the front entrance, which is the second gate on the right side of " +
            "the complex. Once inside take the first left and follow the road around past the " +
            "pool to building B, unit 0000 is upstairs on the far end. Please do not leave the " +
            "order by the office. If the gate will not open just call me and I will come down " +
            "to meet you.\"" to
            "hand-written quoted customer note on the same sheet — the fielded one carried a " +
            "door code, so the decoy does too, in the same quoted shape and similar length",
        "Sample D" to
            "hand-written pseudonym for the bare first-name + last-initial node the second " +
            "08-30 envelope renders",
    )

    /** True when [value] is an enumerated decoy (byte-exact). */
    fun isDecoy(value: String): Boolean = value in ALLOWED
}
