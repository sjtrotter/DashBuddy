status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #110
confirmed: 0/2

- [review] **Offer TTS now speaks the EVALUATION, not the raw offer (#110 step ii, PR pending).** The spoken
  read used to announce the parsed offer (`DoorDash offer. $7.50. <store>. 3.2 miles.`); it now speaks
  the verdict + headline economics: e.g. **"Accept. H-E-B. 22 dollars an hour net. Net 22.48, 12.9
  miles, score 74."** Confirm on an offer: (1) it speaks the **verdict word** (Accept/Decline/Review)
  first; (2) the numbers match the card; (3) it fires **once**, right after the eval (≈ in sync with
  the heads-up notification, after the screenshot settle) — not before the eval, not twice. Watch the
  log for `TTS speaking: Accept. …`. Real orders — listen on a quiet leg.
  - Confirmed: 0/2.
  desk: needs field: listen to an evaluated offer and compare the single spoken verdict and numbers with its visible card.
  Triage source: first-line blame 2026-06-09 (`62aaf29e8`, original README L2833).
