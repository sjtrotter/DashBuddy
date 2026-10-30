status: active
issues: #967, #968

- **🆕 NEW — #967 / PR #968 — ratings stamp during an Offline browse (the early-return fix).**
  The pre-fix bug: `updateLifecycle`'s Offline early-return sat ABOVE the opportunistic ratings
  stamp, so browsing the Ratings hub while DoorDash mode = Offline silently produced NO fresh
  `ratings.capturedAt` stamp — opportunistic capture only worked mid-dash. Fielded on the very
  build that predates the fix (desk 07-31, the 07-30 dash: master @ 3bff50dd browsed Ratings
  14:51–14:52 while Offline and got no fresh stamp, corroborating #967 independently of the fix).
  **What to watch (next install, once #968 is on the phone):** browse gig.log's Ratings screen
  while DoorDash mode is Offline (not dashing) — the ratings figures (points/tier/acceptance rate)
  should populate immediately, not stay blank/stale until the next Online dash. **Desk:** in
  `app_state_snapshots`, `ratings.capturedAt` should advance on an Offline-mode browse, not only on
  an Online one.
  - Confirmed: DESK HALF **2/2** — open only for the dev-eyes half (desk 09-09, the 09-08 dash on `8028691a`: `ratings.capturedAt` advanced to 18:57:32 on the second `ratings` frame of an `[DoorDash:Offline]` browse — session 447 had ended 18:55:47. Earlier: desk 08-09, the 08-01→08-08 window: DESK HALF PASS — first pull whose device
    build actually carries #968, and `ratings.capturedAt` advanced on five separate Offline
    browses: 08-07 20:31 plus 08-08 10:40 / 10:41 / 15:09 / 15:10, every one stamped while the
    mode snapshot read `[DoorDash:Offline]` (the two same-minute pairs are two browses each).
    Pre-#968 that combination produced no stamp at all, so this is the fix working. The dev-eyes
    half — the Ratings screen visibly populating during an Offline browse instead of reading
    blank/stale — still wants a look on the phone.)
    - desk 09-15: third corroboration — the 09-15 12:01:31 Offline-browse stamp advanced `ratings.capturedAt`. Dev-eyes half still open.
  Triage source: first-line blame 2026-07-31 (`382458b30`, original README L874).
