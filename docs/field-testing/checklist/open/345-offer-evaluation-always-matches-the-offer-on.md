status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #345
confirmed: 0/2

- [review] **Offer evaluation always matches the offer on screen (#345).** Evaluations are now
  hash-correlated, so a rapidly replaced offer can't inherit the previous offer's verdict —
  the heads-up notification + spoken read should always describe the CURRENT offer's economics.
  Watch for any mismatch between the card's numbers and what's spoken/notified, especially when
  offers arrive back-to-back.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** dasher believes the eval matched the on-screen offer but
    couldn't verify in the field — needs a desk check of this dash's heads-up/TTS vs. the card numbers.
    Stays 0/2 until the log confirms.
  desk: needs field: compare spoken/notified economics with the current card during rapid offer replacement.
  Triage source: first-line blame 2026-06-11 (`950cef6ff`, original README L3063).
