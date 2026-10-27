status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #427, #426, #830, #859
confirmed: 0/2

- [review] **Per-offer dedupe now engages (#427).**
  Offer screenshot/log dedupe keys used to resolve to one shared literal, so a second distinct
  offer within 60s was silently swallowed. Now keyed per-offer via `{parsedHash}`. Watch on a
  busy dash (with Evidence master + offers enabled, see #426 item): two different offers
  arriving close together should BOTH capture; the same offer re-rendering (collapse/expand,
  re-observation) should still capture only once. Broken = missing capture for a distinct
  second offer, or duplicate captures of one offer inside the same minute.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** dasher believes it's working but couldn't verify in the
    field — needs a desk check of this dash's `captures/` (two distinct offers close together → both
    captured?). Stays 0/2 until the log confirms.
  desk: desk-verifiable: compare screenshots/effect records for two close distinct offers and one re-rendered offer; account for newer presentation identity in #830/#859.
  Triage source: first-line blame 2026-06-12 (`1adca1bb9`, original README L2680).
