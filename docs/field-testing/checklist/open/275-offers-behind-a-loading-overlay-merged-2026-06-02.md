status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #275
confirmed: 0/2

- [review] **Offers behind a loading overlay (#275, merged 2026-06-02).** When an offer
  briefly shows a spinner (on present, or right as you tap), confirm it stays
  recognized as an offer — the bubble shouldn't flicker out of the offer view
  or drop to a blank/idle state mid-offer.
  - Confirmed: 0/2.
  desk: needs field: observe a loading overlay on a live offer for transient card loss; a settled capture alone cannot prove continuity.
  Triage source: first-line blame 2026-06-02 (`33bdb650d`, original README L2884).
