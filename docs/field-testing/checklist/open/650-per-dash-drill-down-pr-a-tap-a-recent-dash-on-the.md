status: active
issues: #650

- **🆕 NEW — per-dash drill-down (#650 PR A): tap a recent dash on the Money tab.** Analytics → Money
  tab → scroll to RECENT DASHES → tap any dash row. It should open a read-only "Dash detail" screen.
  How to tell it's working: the header figures (date, start–end clock times, duration, gross, miles,
  deliveries) match that dash's summary, and the per-delivery rows list each drop (store, completion
  time, pay, tip, net, miles/min) matching what you actually delivered. When the platform-reported
  total exceeded the captured delivery pay, an "unaccounted on this dash" callout appears. (PR #650-A)
  - Confirmed: 1/2 — 2026-07-05 (DoorDash, 5 deliveries): used live — header + per-delivery rows matched the dash; the unaccounted callout showed the real $39.45 gap.
  Triage source: first-line blame 2026-07-05 (`2a3add132`, original README L1825). Recorded first confirmation: 2026-07-05 (existing evidence only).
