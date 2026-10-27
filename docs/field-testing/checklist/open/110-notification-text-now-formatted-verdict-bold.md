status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #110
confirmed: 0/2

- [review] **Notification text now formatted (verdict bold/colored/larger, headline bold) (#110, PR pending).**
  The heads-up offer notification's text is now an Android `SpannableString` — verdict word (ACCEPT /
  DECLINE / REVIEW) bold, ~1.2× size, colored good/warn/bad; the `$X/hr net` headline bold. To check:
  on an offer, **look at the heads-up notification** and note what actually renders — (a) is the
  verdict **bold + larger**? (b) is it **colored** (green/amber/red)? `MessagingStyle` on Android 12+
  may re-theme/strip the **color** even when bold survives — so report specifically whether the color
  shows. If color is stripped, the line still reads fine; we'd then weigh a `BigTextStyle` variant
  (more reliable spans, but can't coexist with the bubble's MessagingStyle).
  - Confirmed: 0/2.
  desk: needs field: Android notification rendering determines whether the requested bold, size and color spans are visible.
  Triage source: first-line blame 2026-06-10 (`d9b92e3a8`, original README L2804).
