status: active
issues: #438, #594, #991, #1000

- **🆕 NEW — offer lifecycle unchanged single-platform after the platform-owned-offers move (#438 B3 / PR).**
  Offers moved off the shared global screen slot onto each platform's own region (the concurrency
  fix), and the whole accept-stash mechanism was replaced by an owned accepted-pending-consumption
  survivor. Single-platform behavior must be **indistinguishable**. On a normal DoorDash dash watch the
  full offer surface end-to-end: (1) the **offer card** pops with correct $/hr, $/mi, and the expiry
  bar; (2) the **heads-up notification** posts once the eval lands, with working Accept/Decline; (3) the
  **spoken read** (TTS) fires; (4) accepting **mints the job with economics** (offer $ on the job/receipt)
  and the right pickup/dropoff placeholders for a stack; (5) declining/timing-out clears the card and
  logs the right outcome (Declined/Timed Out), and the #594 "Review offer→Accept after a committed
  decline" still stands as Declined. Any offer that fails to card/speak/notify, an accept that mints a
  **bare** job (no offer pay), or a wrong/absent outcome card is a B3 regression — capture it.
  - Confirmed: 1/2 (07-07 + 07-08 desk analysis: the machine half — (4) accepts mint jobs WITH
    economics (every delivery costed OFFER_FROZEN), (5) outcomes correct incl. a first-fielded
    OFFER_TIMEOUT, and the Sonic+Willie's two-pickup stack minted both pickup placeholders —
    clean on BOTH dashes. 2026-07-18: 25 offers across the day all resolved coherently — data half
    reinforced. Second confirmation should be dev-eyes on (1)–(3): card visuals, heads-up
    notification, TTS.
    Desk 08-09, the 08-01→08-08 window: the machine half stays clean at scale — 26 offers across
    five dashes, every one carded and evaluated, **zero** `OFFER_TIMEOUT`, outcomes coherent
    throughout. Two notes that bear on sub-check (3) and on (4)'s completeness rather than on B3
    itself: the **spoken read stopped firing entirely from 08-06 16:28 onward** (every `speak()`
    returned −1 — that is #991, a TTS-handler death, not an offer-lifecycle defect: the offers
    still carded and notified), and one accepted offer never linked to its job
    (`offer_records.linkedJobId` null on the 08-08 Zaxbys accept, because the job had no pickup
    phase at all — #1000). Stays 1/2; sub-checks (1)–(3) still need dev eyes, and (3) can't be
    judged at all until #991 is fixed.)
  Triage source: first-line blame 2026-07-06 (`093eac45a`, original README L1723). Recorded first confirmation: 2026-07-07 and 2026-07-08 desk analysis (machine half) (existing evidence only).
