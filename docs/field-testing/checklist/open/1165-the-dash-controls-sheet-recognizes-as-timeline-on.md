status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1165
confirmed: 1/2

- [active] **🆕 NEW — the dash-controls sheet recognizes as `timeline` on a per-offer dash with no task (#1165).**
  Open the dash controls (the "Current dash" sheet with Pause orders / End dash) on a dash with no
  scheduled end and no active task. **How to tell it works:** the capture lands under
  `accessibility.window/timeline/`, not `UNKNOWN/`; its parse carries `sessionEarnings` equal to the
  "This dash" figure and empty `tasks`; the mid-inflate frame (title alone) stays UNKNOWN; the dash is
  still `Online` while the sheet is up (the rule asserts `modeHint: online`).
  - Issue: #1165. Confirmed: 1/2 (desk 2026-10-05: all 7 dash-controls frames recognized `timeline`, all while Online)
  Triage source: first-line blame 2026-09-30 (`c7510dcb5`, original README L315).
