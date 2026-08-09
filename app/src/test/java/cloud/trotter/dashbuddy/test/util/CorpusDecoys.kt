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
        // #993 dropoff_navigation/2026-08-02_18-00-01-999__…__2109db.json — `arriving_at_title`.
        "1425 Sample Ridge Dr, Apt 12, San Antonio, TX 78200, USA" to
            "hand-written street address; house number, street and ZIP are all invented, the " +
            "city/state are kept so the line still has the fielded shape",
    )

    /** True when [value] is an enumerated decoy (byte-exact). */
    fun isDecoy(value: String): Boolean = value in ALLOWED
}
