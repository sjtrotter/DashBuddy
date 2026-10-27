status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / other
issues: #422, #417, #863, #1167, #843
confirmed: 0/2

- [review] **🆕 NEW — Capability consent surface: honest copy + revoke aborts automation to manual (#422 PR 3).**
  Settings → Data & Privacy → **Automation & Consent** now lists, per bundled ruleset source, every
  automation tap the rules enable (Accept, Decline, Confirm a decline, Open the pay breakdown) with a
  Google-Play-consistent disclosure header and one grant/revoke switch each. Bundled (asset) capabilities
  show as on by default; the switch writes through the same grant store the fail-closed engine gate (#417)
  reads at fire time.
  **What to watch:** (1) the screen lists the DoorDash capabilities with plain, accurate copy — each says
  what DashBuddy taps, inside which app, and that it never acts without your go-ahead (no marketing fluff).
  (2) **Revocation is fail-closed:** turn OFF "Confirm a decline" (or "Open the pay breakdown"), then on the
  next dash confirm that automation *aborts to manual* — the quick-decline second tap no longer fires
  (you confirm the decline yourself) / the summary no longer auto-expands — while the same action left ON
  still fires. Turning it back ON restores the tap. **Desk-side:** the `Consent` INFO line
  `consent revoked/granted for capability key <sha256>` on each toggle (PII-safe — hash only), and the
  `Effects` WARN `Denied <action> — no granted capability for rule '…' (fail closed)` when a revoked
  action would have fired.
  - Confirmed: 0/2 (desk 07-26: the desk half-signals showed — `Consent` grant lines are PII-safe
    hashes-only, and the fail-closed abort WARN fired 4× (though from target-resolution failure
    (#863), not revocation). The revoke-toggle round-trip still needs dev eyes.)
  desk: needs field: exercise grant/revoke and manual fallback against #1167's newer per-rule-action consent model; the old default-on assertion was replaced by #843, but revoke checks remain.
  Triage source: first-line blame 2026-07-18 (`d27716b7d`, original README L1418).
