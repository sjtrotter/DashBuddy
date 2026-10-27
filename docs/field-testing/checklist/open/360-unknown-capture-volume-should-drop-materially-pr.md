status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #360, #388
confirmed: 0/2

- [review] **UNKNOWN capture volume should drop materially (#360, PR #388).**
  UNKNOWN frames now dedup by content hash in a rolling seen-set (animations/list churn
  capture once instead of per frame), with a 200-per-process cap (logged loudly when hit).
  Post-dash check: count files in the capture INBOX vs a May session of similar length —
  the May baseline was ~66% UNKNOWN; expect a large drop. Also confirm genuinely NEW
  unknown screens (any screen you visited that DashBuddy doesn't know) still produce a
  capture, and grep the log for the cap warning — hitting 200 on a normal dash would mean
  the suppressor is too weak.
  - Confirmed: 0/2.
  desk: desk-verifiable: compare UNKNOWN capture counts and distinct content hashes with the baseline, checking cap hits and genuinely new shapes.
  Triage source: first-line blame 2026-06-11 (`2a0823095`, original README L2767).
