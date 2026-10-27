status: active
issues: #1054, #732, #1140

- **🆕 NEW — #1054 — a grace that lapses while nothing is on screen still commits, and survives a
  restart.** Both older grace timers (`GRACE_COMMIT` for a dash end / task retire,
  `MODE_RESUME_COMMIT` for a resume out of Paused) are armed for exactly `deadline − now` but their
  fire is stamped with the wall clock, so the ordinary case lands ON the deadline — which a strict
  `>` expiry ignored, with nothing left to wake the pending. And crash recovery restored those
  pendings without re-arming anything at all. **On-dash:** go offline (or let the dash summary
  come up) and then leave the app backgrounded, untouched, with the phone idle — the dash should
  end on its own about a grace window later (~10 s from an idle/offline screen, ~2.5 s from the
  summary), NOT the next time you happen to open DoorDash. Then the harder half: while a dash-end
  grace is open, force-stop DashBuddy and relaunch it — the pending end must still commit within
  its window rather than hanging around. **Desk, after the pull:**
  1. `grep "Recovery re-armed" *.log` — one INFO line under the `StateMachine` tag per recovery
     that restored a live grace, carrying a count only. Absent on a recovery with no pending
     grace, which is the normal case.
  2. `SELECT eventType, occurredAt, payload FROM app_events WHERE eventType='DASH_STOP'` — the
     backgrounded end's payload `endedAt` is the ARM time (the moment the offline/summary screen
     appeared, `pend.since`, unchanged by #1054), while the row's own `occurredAt` is the COMMIT
     observation's time (#732). So `occurredAt ≈ endedAt` + the grace window (~10 s from an
     idle/offline screen, ~2.5 s from the summary) — **not** hours later when the app was next
     opened, which is what the bug produced.
  3. No dash left with a live `session` in the HUD after the app has been closed on an offline
     screen for minutes.
  - Confirmed: 0/2
    - desk 09-09 (the 09-08 dash on `8028691a`): NOT EXERCISED — both restores were `no tail` with no
      session, no force-stop mid-grace, no backgrounded end. Healthy baseline only: both summary-ended
      `DASH_STOP`s show `occurredAt − endedAt` = 2.508 s / 2.575 s (the ordinary 2.5 s window).
    - desk 09-13 (3rd pull): NOT EXERCISED — no backgrounded end, no force-stop mid-grace; the only restart (09-11 22:34, after the last dash) replayed 4 observations with no session and correctly logged no `Recovery re-armed`. Baseline healthy: six summary ends at 2.501–2.506 s, two early-offline ends at 10.003/10.005 s.
    - desk 09-15 (4th pull): NOT EXERCISED — one process for three days; the single startup replayed 2 observations with no session and correctly logged no `Recovery re-armed`. Baseline healthy: eleven summary ends 2.501–2.508 s, three early-offline ends 10.002–10.003 s. Fourth consecutive pull — only the two on-dash exercises (background an offline screen; force-stop mid-pause) can move this.
    - desk 09-20 (5th pull): NOT EXERCISED — no backgrounded end, no force-stop mid-grace; neither of the two process starts (09-15 23:51, 09-17 23:56) restored a live grace, so `Recovery re-armed` is correctly absent. Baseline healthy: eleven summary ends at 2.502–2.506 s, four early-offline ends at 10.002–10.004 s, no end drifted.
    - desk 09-21 (6th pull): NOT EXERCISED — one process for the whole day, no backgrounded end, no
      force-stop mid-grace, no `Recovery re-armed` (correctly — nothing to restore). Baseline
      healthy: three summary ends at 2.502 / 2.504 / 2.504 s, two early-offline ends at 10.003 s.
    - desk 09-26 (7th pull): NOT EXERCISED — one mid-dash restart (09-23 15:06:29, session 732 Online, coinciding
      with the DoorDash 8.99.20 update) replayed 3 obs with no live grace, so "Recovery re-armed" is correctly
      absent and the session continued. Baseline healthy: 8 summary ends at 2.501–2.513 s, 3 early-offline ends at
      10.002–10.004 s.
    - desk 09-27 (8th pull): NOT EXERCISED — one process; startup replayed 1 obs with no session (no "Recovery
      re-armed", correct). Summary end at 2.501 s.
    - desk 09-29 (9th pull): NOT EXERCISED — one process for the whole window, no recovery; ends healthy (7 summary
      2.501–2.503 s, 4 early-offline 10.003–10.010 s). BUT the item's own symptom appeared by a DIFFERENT mechanism:
      session 802 — End dash tapped 10:59:13, no screen admitted for 31.7 min, the dash ended only when DoorDash was
      reopened (11:30:54 → commit 11:31:04). Nothing was armed, so not the strict-`>` bug → filed as #1140.
  Triage source: first-line blame 2026-09-05 (`31764c1cd`, original README L553).
