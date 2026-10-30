status: active
issues: #859, #830

- **🆕 NEW — #859 (H4 + placeholder filenames) — one offer screenshot per presentation, and no
  `{storeName}` filenames.** The Uber offer screenshot now dedupes on the presentation (#830's
  `presentationKey`) instead of the churning quote hash, and a rule filename template whose field
  parsed null saves as `Offer` instead of the literal token.
  **What to watch (Uber dash, evidence capture ON):** Pictures/gig.log holds roughly **one
  `Offer - <store>.png` per offer you actually saw** — no runs of 3–6 near-identical shots of the
  same card seconds apart; a *later* offer from the SAME store still gets its own shot (this is the
  regression to watch for — a missing capture for a repeat store means the dedupe went too far).
  **Desk (next pull):** `ls` the pulled `screenshots/` — (a) zero filenames containing `{`, (b)
  offer-screenshot count ≈ offer count for that platform (was 140/112 on 07-25), (c) cross-check a
  repeat-store hour in `offer_records` against the file list to confirm repeats were captured.
  - Issue: #859. Confirmed: 0/2 (desk 07-30, the 07-29 dash — SUPPORTING evidence only, the item
    is Uber-gated and Uber never went online: DoorDash side 12 `Offer - *.png` for 12
    OFFER_RECEIVED, exactly 1:1, repeat-store offers each captured (7 H-E-B offers, 7 shots),
    zero `{` filenames. The churn-dedupe target case and the null-field `{storeName}` fallback
    both remain unexercised.
    Desk 07-31, the 07-30 dash: still Uber-gated and Uber never went online, so still supporting
    evidence only — zero `{` filenames, 6 OFFER_RECEIVED with 7 offer screenshots (one physical
    presentation, offer seq 1338/Target $11.45, produced two identical `Offer - 11.45.png` shots
    5.1 s apart). Not a data-integrity issue (not the churn-dedupe target class), but a minor
    off-by-one worth noting — the 07-29 pull was exactly 12/12. Still needs an Uber dash.
    Desk 09-09, the 09-08 dash: still Uber-gated — DoorDash side 17 `Offer - <pay>.png` for 17
    `OFFER_RECEIVED`, exactly 1:1, plus 2 `DashSummary` / 1 `Delivery` / 1 `DeliveryBreakdown`, zero `{`
    filenames, zero placeholders.)
  Triage source: first-line blame 2026-07-26 (`cc377c2ee`, original README L1263).
