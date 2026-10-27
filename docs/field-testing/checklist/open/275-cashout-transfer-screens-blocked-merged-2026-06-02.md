status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #275
confirmed: 0/2

- [review] **Cashout / transfer screens blocked (#275, merged 2026-06-02).** Open the
  DasherDirect/Crimson balance, a card-details screen, or initiate an instant
  transfer and confirm the bubble does **nothing** (sensitive → skipped),
  rather than reacting to it.
  - Confirmed: 0/2.
  desk: desk-verifiable: correlate the named cashout/transfer surfaces with sensitive-gate handling and absence of captured data/state effects.
  Triage source: first-line blame 2026-06-02 (`33bdb650d`, original README L2889).
