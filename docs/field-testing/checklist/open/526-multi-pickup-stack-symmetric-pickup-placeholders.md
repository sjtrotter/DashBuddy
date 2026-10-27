status: review
issues: #526, #565

- **🆕 NEW — multi-pickup stack: symmetric pickup placeholders + store re-attribution (#526 / PR).**
  Accept a **multi-store stack** (two+ orders from DIFFERENT stores in one offer — e.g. the 07-05
  Bill Miller BBQ + Mama Margies). Watch the whole run: both pickups AND both drops.
  How to tell it's working: (1) **both pickups get confirmed** — each store's pickup shows a
  completed pickup card / PICKUP_CONFIRMED, not just the last one (Bug10a). (2) **per-store delivery
  rows are correct in analytics** — each drop is attributed to its OWN store (the drop's customer is
  hash-joined to its pickup), with **no "Unknown store" $0 row** for a drop whose card shows no store
  (F2). (3) Store names are right on BOTH drops, not swapped. (4) The job's economics/pay are present
  even if a `waiting_for_offer` flash appeared right after accept (F3 — the accept stash recovers it).
  **Watch the D5c residual:** if a pickup is retired via the grace timer (pickup → idle/offer → next
  pickup, rather than pickup → next pickup directly), that displaced pickup may still get NO confirm —
  note if a pickup card never completes. Also watch: with drop cards that parse no ADDRESS, two drops
  at different customers may fold onto one delivery card (a separate #565 stacked-dropoff limitation,
  noted in the replay test). (#526 / PR)
  - Confirmed: 0/2
  desk: desk-verifiable: replay a multi-store stack through both pickups/drops and inspect confirmations, store joins and the recorded grace-retire residual.
  Triage source: first-line blame 2026-07-06 (`fe0e73ccc`, original README L1798).
