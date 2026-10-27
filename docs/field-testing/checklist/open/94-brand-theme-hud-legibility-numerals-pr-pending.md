status: review
issues: #94, #313

- **Brand theme — HUD legibility & numerals (#94 / #313, PR pending).** With the
  new fixed brand palette + Hanken/Space Grotesk fonts, glance at the bubble HUD
  while driving: confirm it's **legible at a glance** in the dark, the phase/status
  colors read correctly (WAITING green · OFFER blue · PICKUP/DELIVERING green ·
  PAUSED amber · OFFLINE/DONE grey), and that **live-ticking numbers** (offer
  countdown, task timers, $/hr) render in the tabular-figure font and **don't
  jitter / shift width** as they tick. Colors should now be identical regardless of
  phone wallpaper (dynamic color is gone).
  - Confirmed: 0/2.
  desk: needs field: assess HUD legibility, colors and ticking numerals under the requested lighting conditions.
  Triage source: first-line blame 2026-06-09 (`767233633`, original README L2862).
