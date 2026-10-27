status: active
issues: #5

- **⚠️ WATCH FOR RECURRENCE — mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5, root cause confirmed, fix NOT yet shipped).** Confirmed once on 06-06: a
  transient **"Start your scheduled dash"** (`idle_scheduled_dash_ready`,
  `modeHint:offline`) frame seen **during an active pickup/delivery** armed the 10s
  `SESSION_END` grace; after an app-switch the dash **ended** (`DASH_STOP early_offline`)
  and **restarted fresh, resetting the session odometer** mid-dash. **How to tell it
  recurred:** the bubble flashes **"Done Dashing!"** then **"Started Dashing!"** and
  the **session miles/earnings reset to 0** while a delivery is still on screen — most
  likely when you have a **next dash scheduled** *and* you switch apps mid-task.
  **What to capture if it happens:** note the **time** (so we can pull the
  `idle_scheduled_dash_ready` / offline frame + the `DASH_STOP(early_offline)`), and
  whether a **scheduled dash was queued**. Goal: confirm recurrence + see whether any
  screen *other* than `idle_scheduled_dash_ready` ever triggers it — that decides the
  fix direction (narrow rule-gate **A** vs. the broad "never end a dash with an active
  task" guard **C**).
  - Sightings: 1 (2026-06-06). Gathering more before implementing.
    **2026-06-07 (desk review): did NOT recur** — all 4 `DASH_STOP` were
    `source:summary_screen` (authoritative); zero `early_offline`. **But the
    discriminating case never ran:** all 4 `DASH_START` were `source:interaction`
    from `WaitingForOffer` — no `idle_scheduled_dash_ready` start path this session,
    so still **no second data point** on whether another screen can trigger it. Fix
    stays held; keep watching (esp. dashes with a scheduled block queued).
    **2026-06-14 (DoorDash): did NOT recur** — no mid-dash "Done Dashing!" / odometer reset. Single dash,
    and the discriminating `idle_scheduled_dash_ready` start path wasn't confirmed, so still no second
    data point on the trigger. Fix stays held.
  Triage source: first-line blame 2026-06-07 (`9e289355b`, original README L2987).
