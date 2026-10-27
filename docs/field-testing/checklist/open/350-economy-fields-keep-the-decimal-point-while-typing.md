status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / other
issues: #350
confirmed: 0/2

- [review] **Economy fields keep the decimal point while typing (#350; desk-verifiable, no dash
  needed).** In Settings → Personal Economy (or the wizard's costs step), type `12.5` into any
  cost field — the `.` must survive (the value round-trip used to reset the text mid-typing and
  eat the separator). Also confirm: the live `$ /mi` footer still updates per keystroke, and the
  field normalizes (e.g. `012.50` → `12.5`) when you tap away.
  - Confirmed: 0/2.
  desk: desk-verifiable: on the device at the desk, type 12.5 and 012.50 in the cost editor, checking keystroke footer updates and blur normalization.
  Triage source: first-line blame 2026-06-11 (`c69ea9bc2`, original README L3078).
