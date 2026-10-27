status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #600
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — automated taps now rank click candidates by evidence instead of a dead exact-bounds check (#600).**
  Every automated Accept/Decline/Confirm tap re-resolves its target against the live accessibility
  tree, then disambiguates among label-verified candidates. The old disambiguator compared
  `getBoundsInScreen()` for exact equality against the bounds captured at recognition time — that
  match died to TEMPORAL drift (an animating confirm sheet moves between recognition and re-resolve),
  so it silently fell back to "clicking first" on effectively every automated click (~40+/week),
  landing right only by luck of enumeration order. Now it ranks by exact stored text first, then max
  bounds overlap (IoU) — the WARN only fires on a genuine unresolvable tie. **Confirm on dash: 0/2 —**
  after an automated Accept/Decline/quick-decline-confirm tap, the log should show a DEBUG
  `Resolved click target for … via EXACT_TEXT/BOUNDS_OVERLAP tier` line **or**
  `Single verified candidate … clicking it` (a 1-candidate pool — also success), **not** the old
  `No exact bounds match … — clicking first` WARN on every fire; the tap should still land on the
  correct button (behavioral no-change on the happy path). Broken = the WARN reappearing on routine
  taps, or a tap landing on the wrong control (e.g. Accept firing when Decline was intended).
  Triage source: first-line blame 2026-07-02 (`13a0ae0a9`, original README L2159).
