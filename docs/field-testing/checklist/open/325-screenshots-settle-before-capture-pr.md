status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #325
confirmed: 0/2

- [review] **Screenshots settle before capture (PR #325).** Captures saved to `Pictures/DashBuddy` should
  be **clean / fully-rendered** (UI settled), not grabbed mid-transition or half-drawn — there's now
  a 500ms settle before every screenshot. Spot-check the offer + post-task captures after a dash.
  - Confirmed: 0/2.
  desk: desk-verifiable: inspect saved offer and post-task screenshots for settled rendering rather than partial transition frames.
  Triage source: first-line blame 2026-06-09 (`8e17112a5`, original README L2850).
