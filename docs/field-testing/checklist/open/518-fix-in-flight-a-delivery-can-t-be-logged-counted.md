status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #518, #520, #522, #517
confirmed: 2/2

- [active] **🔧 FIX IN FLIGHT — a delivery can't be logged/counted twice (#518, PRs #520 + #522). CONFIRM ON DASH.**
  Two halves of the spurious-`DELIVERY_COMPLETED` bug: PR #520 stopped a **prior job's** task
  re-completing under a new job (cross-job leak); PR #522 makes `DELIVERY_COMPLETED` **idempotent per
  task** so a re-shown receipt (`PostTask → nav → PostTask → nav`) can't fire the **same** delivery
  twice. 06-17 evidence: two **real** deliveries ($23.62, $11.20) were each logged twice → doubled
  earnings. **Confirm on dash: 0/2 —** each completed delivery should produce **exactly one** "Saved:
  $X" bubble and **one** completed card, and the **session-earnings total must match the sum of the
  real deliveries** (no doubling). Watch especially when the receipt screen is shown, dismissed, and
  re-shown. If a delivery double-counts, capture the `app_events` (`DELIVERY_COMPLETED` rows) +
  `app_state_snapshots` for that order.
  - Confirmed: **2/2 ✅ VALIDATED** (2026-06-19 + 2026-06-20 — both dashes: exactly 1
    `DELIVERY_COMPLETED` per job, accepts pair 1:1 with completions, earnings reconcile to the cent
    (06-20: $130.23 = $130.23); each stack's drops → 1 combined receipt, expected, not a double-count).
    Safe to retire. (Also covers the #517 ghost-offer guard — all offers carried non-null pay both dashes.)
  Triage source: first-line blame 2026-06-18 (`b02614328`, original README L2431).
