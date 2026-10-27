status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #810, #847, #818
confirmed: 0/2

- [review] **🆕 NEW — #810-B2 / PR #847 — orphan offer resolution (inference + attestation).**
  **What to watch:** after any dash where the `JOB_ACCEPT_MISMATCH` WARN fires (chat-path unassign
  class), the Money tab shows the review callout; the drill-down lists the job's accepted offers
  and attesting one marks it unassigned (undo stays reachable while the group is listed).
  **Desk:** `SELECT * FROM offer_records WHERE outcomeResolved IS NOT NULL` — cross-store orphans
  auto-resolve as `UNASSIGNED_INFERRED` (projector v8 retro-processes history; the session-114
  same-store orphan must sit UNRESOLVED awaiting attestation, never auto-stamped); Decisions-tab
  accepted counts exclude resolved rows. NOTE: the v14→v15 migration's instrumented test needs the
  standard device run at the next reinstall.
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE with one sub-check PASS — the v7→8 refold ran clean
    (979 events, 0 skipped) and `outcomeResolved` is correctly ZERO rows: no `JOB_ACCEPT_MISMATCH`
    has ever been written (the #818 tripwire postdates session-114), so Tier 1 had zero input and
    the session-114 orphan sits correctly UNRESOLVED, never auto-stamped. Needs a dash with a real
    invisible unassign to exercise either tier.
    Desk 07-31, the 07-30 dash: same shape again — `outcomeResolved` non-null count is ZERO (Tier-1
    had no `JOB_ACCEPT_MISMATCH` input this dash, so correctly nothing to resolve). Still needs a
    dash with a real invisible unassign to exercise either tier.)
  desk: needs field: exercise the orphan review/attestation UI after a mismatch; query offer resolutions and undo afterward.
  Triage source: first-line blame 2026-07-23 (`3745bc073`, original README L1299).
