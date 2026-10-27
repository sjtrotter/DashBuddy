status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #436, #10
confirmed: 0/2

- [review] **Engine latency + dedupe pack (#436).**
  Four behaviors to watch: (a) accepting/declining an offer FAST (inside ~1s of the verdict
  landing) should no longer pop a stale Accept/Decline heads-up afterwards; (b) offer verdicts
  should land a touch quicker (config no longer read cold per offer); (c) relaunching the app
  mid-dash (non-crash restart) should NOT duplicate session-start bubbles or re-log events on
  the next screen; (d) nothing else regresses — notifications still post normally when the
  offer is left alone. Broken = stale heads-up after resolving an offer, duplicated chat
  entries after an app restart, or a missing offer notification.
  - Confirmed: 0/2. **2026-06-12 (DoorDash, partial):** dasher reports offer Accept/Decline feels
    **fully quick — no perceptible delay** (loosely supports (b) "verdicts land a touch quicker").
    The (a) stale-heads-up-after-fast-resolve, (c) restart-dedupe, and (d) sub-cases were NOT
    deliberately exercised — so this stays 0/2 until those are checked. (See 2026-06-12 log entry #10.)
    **2026-06-14 (DoorDash):** no stale Accept/Decline heads-up observed after resolving offers (loose
    support for (a)); (c)/(d) still not deliberately exercised. Stays 0/2 pending a clean (a)/(c) check.
  desk: needs field: fast offer responses and a mid-dash relaunch are needed to assess stale notifications, latency and duplicate session effects.
  Triage source: first-line blame 2026-06-12 (`8a465daa3`, original README L2665).
