status: active
section: §4 1/2 items older than 6 weeks / state machine
issues: #830, #839, #826, #827, #762, #251, #786
confirmed: 1/2

- [active] **🆕 NEW — #830 / PR #839 — presentation-scoped offer identity (+ the #826 accept chain).** The
  ticking Uber card no longer mints replacement offers: a re-render with the same store/order shape
  ENRICHES the pending offer in place (keeps its presentation epoch and click latches; heads-up
  updates; TTS speaks once).
  **What to watch (Uber dash):** each physical offer is spoken ONCE (no triple reads), no rapid
  "offer replaced" bubble storms, and — the big one — accepting an offer and driving it should now
  produce a costed job (this + #827 unblocks the #762-D2 accept inference that 07-21 proved
  unreachable). **Desk:** `offer_records` shows ~one row per physical offer (07-21 showed 17 rows
  for far fewer offers, every lifetime 3–10 s); Uber `OFFER_TIMEOUT` with description "Replaced by
  new offer" ≈ 0; an accepted trip has `OFFER_ACCEPTED` + non-null economics instead of the
  "Unknown Store uncosted corpse". Churn *rate* post-#827 is also worth noting in the log
  (pay/miles still tick — enrichment should absorb it silently).
  - Confirmed: 1/2 (desk 07-26, post-install window: PASS on the core invariant — ZERO
    same-presentationKey replaces across 130 Uber offers (every "Replaced by new offer" was a
    genuine store change), replace rate 12%→8.5%, and the 07-21 triple-read signature is gone
    (repeated utterances were distinct presentations with identical numbers, i.e. Trip Radar
    re-offering the same trip — sounds like a repeat but isn't churn). The ACCEPT-CHAIN half is
    untested: zero Uber accepts occurred (see #251/#786/#826 — the decline/board problem). Second
    confirmation needs an Uber dash with a real accept.)
  Triage source: first-line blame 2026-07-23 (`f94718bc3`, original README L1316). Recorded first confirmation: desk 2026-07-26 (existing evidence only).
