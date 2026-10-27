status: active
section: §4 1/2 items older than 6 weeks / state machine
issues: #286, #279, #290, #4, #5
confirmed: 1/2

- [active] **New dash right after ending one starts fresh (#286 / #279-B / #290).** End a
  dash, then start a new one within ~10s. The bubble should treat it as a
  **brand-new dash** (fresh session / earnings reset), not "Session resumed
  (grace)". Cover **both** start paths, because they emit the fresh-dash signal
  from different screens:
    - **On-demand** start (tap Dash → the set-end-time screen) — the original
      `startingDash` carrier.
    - **Scheduled** start (#290): in your zone with a scheduled block, the idle
      map reads **"Start your scheduled dash"** and tapping Dash auto-starts with
      *no* set-end-time screen. This is the path that previously resumed the old
      session. Confirm the new dash is fresh, and that "You have another dash
      starting soon" (when you're *not* starting) does **not** reset anything.
  - Also regression-watch the grace refactor: backing out of the app mid-pickup
    and returning still **keeps the active task**; a brief offline blip mid-dash
    still **resumes the same** dash (no spurious new session).
  - Confirmed: 1/2. **Partial — 2026-06-03 (DoorDash):** the *brief-offline-blip
    resumes same dash* sub-case was seen — an app-switch return fired
    "Session resumed (grace)" (same session, no fresh start).
    **2026-06-07 (desk review):** two more sub-cases landed — (a) **resume-same-dash**
    seen again at 16:30:59 ("Session resumed (grace)", same session `9072f690`); and
    (b) the **on-demand fresh start** path confirmed at 11:24 — `DASH_STOP(summary_screen)`
    → 8 s later `DASH_START` with a **new** sessionId (not a grace resume). Still
    unconfirmed: that the **active task** survived the blip (no event proves task
    retention either way), and the **scheduled** fresh-start path. See 2026-06-07 log
    entry #4/#5.
    **2026-06-14 (DoorDash): not exercised** — only one dash this session, so the
    end-then-start-fresh path (esp. the scheduled-start variant) couldn't be tested. Stays 1/2.
  Triage source: first-line blame 2026-06-03 (`eb1543cc1`, original README L2902). Recorded first confirmation: 2026-06-03 (partial), with further subcases recorded 2026-06-07 (existing evidence only).
