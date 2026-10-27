status: active
issues: #594

- **🔧 FIX SHIPPED — a committed decline can't be un-declined by a later Accept (#594). CONFIRM ON DASH.**
  Field 06-30 16:59 (BK $6.25, seq 226/227): declined the offer, confirmed the decline on the sheet
  (committed server-side), then hit DoorDash's "Review offer" → Accept ~1.2 s later — the app logged
  **OFFER_ACCEPTED** and flashed both "Offer Declined" and "Offer Accepted", while DoorDash's own dash
  summary showed the decline stood (3 accepts, $45.75, no $6.25; no job/pickup ever formed). Now a
  confirm-sheet decline **latches** the outcome: any later Accept still records **OFFER_DECLINED** and
  the bubble shows a **"Decline already submitted — Accept won't take"** card instead of "Offer
  Accepted". **Confirm on dash: 0/2 —** decline an offer, confirm it, then tap **Review offer →
  Accept**: the db must record **OFFER_DECLINED** (no OFFER_ACCEPTED, no PICKUP_* / job), and the
  bubble shows the "Decline already submitted" card — not "Offer Accepted". **Watch the control
  case:** a normal accept with NO decline first must **still record ACCEPTED** and form the job.
  Broken = an OFFER_ACCEPTED after a confirmed decline, or a normal accept that fails to record
  ACCEPTED. Tag #594.
  Triage source: first-line blame 2026-07-02 (`eabf4472e`, original README L2247).
