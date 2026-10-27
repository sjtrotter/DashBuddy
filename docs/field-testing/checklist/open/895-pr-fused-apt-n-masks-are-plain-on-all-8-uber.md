status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #895, #927, #825, #986
confirmed: 0/2

- [review] **🆕 NEW — #895 / PR #927 — fused `Apt <n>` masks are plain on all 8 uber+doordash surfaces
  (desk-only).** The `Apt [redacted:<4hex>]` form (brute-recoverable ~10⁴ alphabet) is gone;
  the fused subpremise masks as `Apt [redacted]` with no hex on
  `dropoff_pin_entry`/`dropoff_handoff`/`dropoff_pre_arrival`/`dropoff_pre_arrival_completion` +
  the uber #825 family. **Desk:** `grep -r "Apt \[redacted:" captures/` → zero; names/streets on
  the same surfaces must still carry their 4 hex (the exemption/large-alphabet masks are
  untouched).
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: NEW RESIDUAL, not a bump — `dropoff_photo` is a 9th
    fused-`Apt` surface PR #927's 8-surface list didn't cover: three captures on this dash show
    `Apt/Suite [redacted:<4hex>]` (hex, not plain) while the SAME dash's `dropoff_handoff` correctly
    shows `Apt [redacted]` (plain) — the fix works everywhere it was applied, `dropoff_photo` was
    simply left off the list. Filed as #986; stays at 0/2 pending that fix.)
  desk: desk-verifiable: inspect exercised fused-subpremise envelopes for plain masks and intact name/street hashes, including the recorded #986 residual.
  Triage source: first-line blame 2026-07-30 (`557f25161`, original README L1082).
