status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #498, #521, #503
confirmed: 2/2

- [active] **🔧 FIX IN FLIGHT — phantom + over-minted dropoff tasks cleaned up (#498, PR #521). CONFIRM ON DASH.**
  Two state-layer guards from the 06-17 capture investigation: (a) a dropoff frame that parses **no
  customer at all** (a transient confirm/arriving screen) no longer mints a fresh **identity-less**
  dropoff — the "the customer" card that immediately completes (06-17 task-9 on a **single H-E-B**
  order); (b) a **drifting dropoff address** with the **same customer name** no longer splits one drop
  into two tasks (06-17 task-39/-40). **Confirm on dash: 0/2 —** on a **single** order, the dropoff
  card should resolve to the **real 6-char hash** with **no** brief "the customer" card flashing/
  completing alongside it, and exactly **one** dropoff card per real stop (no duplicate). The phantom
  also silently fired spurious `DELIVERY_COMPLETED`s — watch for any **$0.00 PAID** card that mints
  with no real delivery. If a phantom/duplicate dropoff still shows, capture the dropoff frame sequence
  + the `app_state_snapshots` for that order. (Stacks are still #503 slice 3b — extra dropoffs on a
  multi-drop may still mis-handle, separate from this.)
  - Confirmed: **2/2 ✅ VALIDATED** (2026-06-19 + 2026-06-20 desk analyses — both dashes: 0
    null-customer/$0.00 dropoffs; every completion has a distinct customer hash + non-null pay).
    Safe to retire from this checklist.
  Triage source: first-line blame 2026-06-18 (`44186b8aa`, original README L2415).
