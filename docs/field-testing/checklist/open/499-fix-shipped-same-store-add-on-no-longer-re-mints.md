status: active
issues: #499, #503

- **✅ FIX SHIPPED — same-store add-on no longer re-mints the task (#499 / #503 slice 2). CONFIRM ON DASH.**
  The task lifecycle now **resumes a prior subtask** instead of re-minting on a phase switch / after an
  offer interlude (re-match by store for pickups, customer address for dropoffs). **Confirm on dash:
  0/2 —** on a same-store shopping add-on (e.g. an offer accepted mid-shop at the same store), the shop
  task should **keep its identity and fold the add-on in** (combined item count bumps on the *same*
  card), not spawn a second/fresh task. Also watch a stacked order with *different* stops: those must
  still be **distinct** tasks. If a same-store add-on still re-mints, capture the shop→offer→shop frame
  sequence + note the timing.
  Triage source: first-line blame 2026-06-16 (`481cdf324`, original README L2396).
