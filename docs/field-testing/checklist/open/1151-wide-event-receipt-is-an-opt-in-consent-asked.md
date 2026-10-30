status: active
issues: #1151, #1148, #1152

- **🆕 NEW — wide event receipt is an opt-in consent, asked BEFORE the accessibility grant (#1151).**
  The accessibility service receives window-change notices from other apps (what the #1148/#1152
  overlay path needs) ONLY after you allow it, and the question now comes first in the setup
  sheets. **How to tell it works:**
  1. Fresh install: the "Let gig.log notice windows from other apps" step appears BEFORE the
     accessibility-service request (Allow / Don't allow, no "Not now"). The accessibility card never
     appears before you answer. On an install where the service was already on, the step still
     appears once.
  2. Allow → the accessibility step follows; once the service is on, exactly one
     `Event receipt: wide=true` INFO line (tag `Pipeline`) at connect, and
     `WINDOWS_CHANGED (coalesced …)` DEBUG lines begin in `app.log`.
  3. Release, Don't allow → the accessibility step still follows; at connect the line reads
     `wide=false` and no `WINDOWS_CHANGED` lines appear.
  4. Debug build, Don't allow → NO accessibility request; the "Wide event receipt is off" notice
     replaces every screen (a bubble deep link or the Sunday plan notification lands on it too). If
     the service was already on, it is turned off (`event receipt declined on a debug build —
     disabling the accessibility service`) and the bubble stops. **Exit** closes the app; **Open
     Automation & Consent** shows that screen inside the notice — turning "Notice windows from other
     apps" on restores the app and the setup sheet asks you to turn the service back on.
  5. Turning the switch off in Settings logs `wide=false` and the `WINDOWS_CHANGED` lines stop (on a
     debug build the service is also turned off).
  6. The phone is Android 13+ (a Pixel 7): no `may not take effect on Android 11` WARN and no caveat on
     the switch. (On an Android 11 device both appear — a known limitation, see the sensor reference.)
  7. Debug build, a bubble deep link that arrives while the notice is up, then a rotation, then
     Allow: the app opens the linked screen exactly once.
  - Issue: #1151. Confirmed: 1/2 (2026-09-30 smoke test on `1fe9243f`: items 1–2 — the step came first and was allowed, `wide=false` → `wide=true`, `WINDOWS_CHANGED` lines began; items 3–7 not exercised)
  Triage source: first-line blame 2026-09-30 (`e8205c8ac`, original README L252).
