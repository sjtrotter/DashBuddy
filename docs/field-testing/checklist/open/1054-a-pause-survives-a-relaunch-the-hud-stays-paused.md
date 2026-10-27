status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1054
confirmed: 0/2

- [active] **🆕 NEW — #1054 — a pause survives a relaunch: the HUD stays PAUSED, and the dash still ends on
  its own when the countdown runs out.** Two behaviours that used to be broken in opposite
  directions. A graced resume is now DROPPED on restore (it is 8 s of *un-contradicted* observation,
  and dead process time is not that), so a relaunch mid-pause must NOT come back Online — a phantom
  resume, or a brand-new dash appearing with no online screen behind it, is the bug. And the
  pause-safety deadline is now real state rather than a coroutine in memory, so it is re-armed on
  restore: before this, a restore into Paused left the dash with no timer of any kind and a pocketed
  phone kept the session alive indefinitely, which the next morning's dash then RESUMED.
  **On-dash:** pause the dash, force-stop DashBuddy while the pause sheet is up, relaunch. The HUD
  must read PAUSED, and stay that way until you actually bring DoorDash back online. Then do it
  again and simply let the DoorDash countdown run out with the app relaunched but idle. **Two
  separate things happen, about ten seconds apart** — the safety net fires ~1 s after the countdown
  reaches zero and takes the dash Paused→Offline, which then arms the ORDINARY 10 s `SESSION_END`
  grace, so the dash actually ends ~11 s after zero. **Desk, after the pull:** a `DASH_STOP` in
  `app_events` whose payload `endedAt` is ≈ countdown + 1 s (the safety transition) while the row's
  own `occurredAt` is ≈ countdown + 11 s (the graced commit) — two assertions, not one; no
  `DASH_START` in the seconds after a recovery; and no `Timer Expired` WARN for a
  `MODE_RESUME_COMMIT` or `SESSION_PAY_SETTLE` after a restart (those pendings are dropped on
  restore, and a replayed arm for them is never executed at all).
  - Confirmed: 0/2
    - desk 09-09: NOT EXERCISED — zero `DASH_PAUSED`/`DASH_RESUMED` on 09-08, no `MODE_RESUME_COMMIT` /
      `SESSION_PAUSED_SAFETY` timer in the pull.
    - desk 09-13 (3rd pull): NOT EXERCISED — zero `DASH_PAUSED`/`DASH_RESUMED`/`MODE_RESUME_COMMIT`/`SESSION_PAUSED_SAFETY`.
    - desk 09-15 (4th pull): NOT EXERCISED — zero pause/resume/safety events; one process for the entire three-day window.
    - desk 09-20 (5th pull): NOT EXERCISED — zero `DASH_PAUSED` / `DASH_RESUMED` / `MODE_RESUME_COMMIT` / `SESSION_PAUSED_SAFETY` in four days, no force-stop mid-pause, no `Recovery re-armed`.
    - desk 09-21 (6th pull): NOT EXERCISED — the day's ONE pause (11:52:50, `remainingText 34:58`)
      was hand-resumed two seconds later and committed by `MODE_RESUME_COMMIT` at 11:53:06 →
      `Session grace resume`: the ordinary path, not the force-stop exercise. Zero
      `SESSION_PAUSED_SAFETY`, no restart mid-pause.
    - desk 09-26: NOT EXERCISED — two `DASH_PAUSED` (09-23 11:40:39, the pause-after-delivery on the last drop, dash
      ended on the summary 17 s later; 09-24 16:24:49, resumed, `MODE_RESUME_COMMIT` fired 16:25:35.946). No restart
      mid-pause, no `SESSION_PAUSED_SAFETY`.
    - desk 09-27: NOT EXERCISED — 0 `DASH_PAUSED` / `MODE_RESUME_COMMIT` / `SESSION_PAUSED_SAFETY`.
    - desk 09-29: NOT EXERCISED — 2 `DASH_PAUSED` (09-28 11:57:19, 11:58:56), both hand-resumed via
      `MODE_RESUME_COMMIT`; no force-stop, no `SESSION_PAUSED_SAFETY`.
  Triage source: first-line blame 2026-09-06 (`5ae03a9eb`, original README L596).
