status: active
issues: #318

- **🆕 NEW — driving/glance-mode HUD font-scale toggle (#318).** Flip "Driving glance mode" on in
  Settings → General while a dash is running (or the bubble is up). The bubble HUD's text — the
  hero $/hr, task timers, captions, everything — should visibly grow (~12%) immediately, with no
  restart of the app or the dash. Toggle it back off and the text should shrink back immediately.
  Throughout, the **main app window** (Dashboard, Settings, etc. — outside the floating bubble)
  should look completely unchanged at every step. How to tell it's broken: HUD text doesn't
  change size on toggle, only *some* of the HUD text scales (e.g. the hero number but not the
  captions/labels), the toggle needs an app restart to take effect, or the main app window's text
  size changes too.
  Triage source: first-line blame 2026-07-05 (`21897cd88`, original README L1884).
