status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #1090
confirmed: unspecified

- [active] **"Dash paused" push is recognized (#1090):** after a dash in which you paused, the pull's
  `captures/doordash/notification/` should hold NO UNKNOWN envelope reading "Your current dash has been
  paused" — the envelope lands under `notification/dash_paused/` instead — and `app.log` carries
  `Rule LOG [NOTIFICATION_RECEIVED]: DASH_PAUSED_PUSH` (a DEBUG line; the `log` verb writes NO
  `app_events` row) a few seconds BEFORE the screen-derived `DASH_PAUSED`, while the dash's mode and
  timers behave exactly as before (the push is informational — it must not pause, resume, or shorten
  the safety net).
  Triage source: first-line blame 2026-10-05 (`0d3dc835e`, original README L89).
