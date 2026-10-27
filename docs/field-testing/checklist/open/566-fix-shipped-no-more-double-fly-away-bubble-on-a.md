status: active
issues: #566, #573

- **🔧 FIX SHIPPED — no more double fly-away bubble on a new/stacked pickup (#566, PR #573). CONFIRM ON DASH.**
  When a pickup started (or a stacked pickup handed off), the "Pickup: <store>" heads-up bubble flew
  out **twice** in quick succession (the second often icon-less). Fixed with a per-task dedupe key.
  **Confirm on dash: 0/2 —** on each new pickup (especially the second store of a stack), the
  "Pickup: <store>" notification should fly out **once**, not twice. A genuine change should still
  show: e.g. heading-to-store → start-shopping may update the bubble (that's expected, different
  state), and a later separate trip to the same store should still announce. If you still see an
  immediate identical double, note the store + whether it was the first or a stacked pickup.
  Triage source: first-line blame 2026-06-22 (`a2500d6af`, original README L2316).
