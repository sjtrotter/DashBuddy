status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1113
confirmed: 0/2

- [active] **Per-order handling (#1113 slice 1):** a two-order DoorDash stack's bubble card $/hr should equal
  `pay ÷ (2.5 × miles + 14) × 60` (two base overheads), not `… + 7`; e.g. $12.15 / 10.7 mi → ≈ $17.9/hr,
  not $21.6. Check one stacked card against that arithmetic (the card shows no duration field; the $/hr is
  the observable). Also: no copy anywhere in Settings or the wizard promises an
  automatic decline or accept (the wizard's threshold footers now say "flagged to decline"); Strategy still shows the Quick declines switch; one
  `Strategy: Purged dead automation keys (#1113)` INFO line on the first launch of this build only. — Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`ed9194101`, original README L59).
