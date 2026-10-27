status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / other
issues: #938, #954
confirmed: 0/2

- [review] **🆕 NEW — #938 / PR #954 — English-locale boundary notice.** Briefly switch the phone's
  language to Spanish (Settings → System → Languages), then toggle DashBuddy's accessibility
  service off and back on. Expect: exactly **one** system notification titled "DashBuddy solo lee
  pantallas en inglés" under a new "Avisos de la aplicación" channel, and a `WARN/LocaleBoundary`
  line naming `'es'` (and **not** a region) in the log. Toggle the service again — the WARN
  repeats, the notification does **not**. Switch back to English and toggle once more — neither
  appears.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — the device stayed on English
    throughout; the notice/WARN path needs the language-switch-and-toggle exercise on the phone.)
  desk: needs field: switch language and toggle the service twice to observe the one-notification boundary notice.
  Triage source: first-line blame 2026-07-30 (`d3f31f580`, original README L1072).
