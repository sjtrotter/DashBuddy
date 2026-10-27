status: active
section: §4 1/2 items older than 6 weeks / HUD
issues: #722, #693, #763
confirmed: 1/2

- [active] **🆕 NEW — bubble gas quick-edit is mode-adaptive: refresh (AUTO) vs. Resume auto (MANUAL) (#722 / PR).**
  **Where to find it:** the idle dashboard card, shown whenever OFFLINE with no active dash (the
  #722/#693 reachability fix — a completed session's timeline no longer hides it; the full timeline
  now appears only while actively dashing, plus in the analytics drill-down).
  The idle card's gas control now shows ONE control set per mode instead of a stepper+refresh combo, because
  each action's meaning flips with mode. **AUTO mode:** price + "AUTO" caption + a refresh icon (no stepper
  visible) — tapping refresh fetches today's EIA price and stays auto (price updates in place, no mode
  change); tapping the price itself (or its small pencil) is the "take manual control" gesture — the
  stepper should appear and the caption should flip to MANUAL. **MANUAL mode:** stepper + "MANUAL" caption +
  a labeled **"Resume auto"** chip (never a bare refresh icon) — tapping it should re-enable auto AND pull a
  fresh price in one action, flipping the caption back to AUTO and hiding the stepper. Watch for: a spinner
  during the fetch, a small transient error indicator if the fetch fails (offline/no location — no toast
  spam), and that **Settings → Personal Economy** always agrees with whatever the bubble shows afterward.
  Frozen-economics invariant: any of this only changes FUTURE offer $/hr, never a past delivery's recorded
  net. Anything that shows a bare icon changing modes silently, a stepper visible in AUTO mode, or a refresh
  icon visible in MANUAL mode is a regression — capture it.
  - Confirmed: 1/2 (2026-07-12, dev exercised the idle-card gas control on the post-#763 build:
    "works fine" — first live exercise of the interactions after two dashes with no bubble-initiated
    fetch in any log. Second confirmation should re-touch the full cycle: AUTO refresh → take-manual
    → Resume auto, plus the Settings → Personal Economy agreement check.)
  Triage source: first-line blame 2026-07-07 (`c61291251`, original README L1703). Recorded first confirmation: 2026-07-12 (existing evidence only).
