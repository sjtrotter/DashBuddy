status: active
issues: #578, #583

- **👁 VISUAL-ONLY — rich offer notification looks right: gauge ring, countdown, badges (#578/#583). CONFIRM BY EYE.**
  The mechanical halves are validated (buttons fire from the floating heads-up; the card posts
  with live PendingIntents; zero RemoteViews errors across a full week) — what desk data cannot
  see is the **rendering**. **Confirm on dash: 0/2 —** (a) the score shows as a **filled circular
  gauge ring** with the number centered (not a flat bar, not blank/garbled/missing when the offer
  scored); (b) the **countdown ticks** on the collapsed banner; (c) expanding shows the verdict
  banner + **colored** (not black) badges + store. If anything looks off, screenshot collapsed +
  expanded — that screenshot is the whole test.
  Triage source: first-line blame 2026-07-01 (`deffa9458`, original README L2285).
