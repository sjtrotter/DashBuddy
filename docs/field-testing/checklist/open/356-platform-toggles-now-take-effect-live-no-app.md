status: review
issues: #356, #384

- **Platform toggles now take effect live — no app restart (#356, PR #384).**
  All notification/accessibility gating now reads one shared enabled-platforms state. To check:
  mid-session, toggle a platform OFF in gig.log settings — its notifications should stop
  reaching the HUD/log immediately (next notification, not next restart); toggle back ON and
  they resume. If convenient, also note whether gating still works after Android kills/rebinds
  the notification listener (e.g. after a long screen-off period) — the old code froze gating
  at the last value when that happened.
  - Confirmed: 0/2.
  desk: needs field: toggle a platform off/on and exercise service rebind to observe immediate gating changes.
  Triage source: first-line blame 2026-06-11 (`33680a6ec`, original README L2784).
