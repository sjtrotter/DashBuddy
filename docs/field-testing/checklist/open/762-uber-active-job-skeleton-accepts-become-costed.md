status: review
issues: #762, #784, #785, #786, #826, #827

- **🆕 NEW — Uber active-job skeleton: accepts become costed jobs at trip start; honest "ON JOB" badge (#762 D2 / PR #784). NEEDS UBER ENABLED.**
  `active_trip` (the coarse `on_job_view` screen) now declares the phase-less `task:active` flow, and the
  accept grace is per-platform (Uber 600s vs the old global 120s). Together: an accepted Uber offer is
  consumed into a fully-costed job within seconds of accept instead of expiring into an uncosted corpse
  on any >2-min drive, and the badge/HUD show "ON JOB" instead of a stale OFFER card for the whole
  pickup drive.
  **How to tell it's working (any Uber trip):** immediately after accepting, the badge flips to ON JOB
  (not stuck on OFFER) and the live offer card clears; the trip's job carries offer economics (desk-side:
  the job's `OFFER_ACCEPTED`/job-mint events appear at accept time, not at the store, and the folded
  delivery is NOT a "no economics" row). Also watch: a **mid-trip stacked offer that you DECLINE or
  ignore** must NOT produce an `OFFER_ACCEPTED` event (desk-side grep) — the ambient-screen guard.
  While parked at the store, the odometer arbiter now sees interleaved `task:active` frames as "moving"
  (leg unknown ⇒ can't claim parked) — watch Uber mileage for dwell-drift inflation vs the odometer span.
  **Capture-first on the same dash** (feeds #785/#786 — see those issues): notification envelopes for a
  full trip incl. every update of the ongoing status notification, one multi-order/stacked job, the
  decline affordance frames, any "Going to <digit-leading store>" push.
  - Confirmed: 0/2. **BLOCKED-as-fielded (desk 07-22, second Uber attempt):** the consume path is
    unreachable because the accept itself is undetectable — the fielded accept-click node is TEXTLESS, so
    `uber.click.accept_offer` never latches and every offer resolves `OFFER_TIMEOUT` before `active_trip`
    appears (one job still minted from ambient frames: store "Unknown", uncosted — the exact corpse shape).
    Filed **#826** (accept detection, the D2 blocker) + **#827** (offer time/miles parse swap — poisons the
    economics this item would validate). Keep the item; it becomes testable when #826 lands.
  - (2026-07-19: first attempt — INCONCLUSIVE: Uber app restart churn; the only accept happened during
    a capture gap so no job minted; the one recognized offer timed out cleanly. Watch: offer-capture
    fragility during Uber app instability.)
  - (2026-07-19: decline affordance capture-first — WARN "No declineButton target bound for uber"
    fired; confirmed still open, feeds #786.)
  desk: needs field: accept and drive an Uber offer to observe the ON JOB badge and verify a costed job survives the long accept grace.
  Triage source: first-line blame 2026-07-15 (`19991604a`, original README L1500).
