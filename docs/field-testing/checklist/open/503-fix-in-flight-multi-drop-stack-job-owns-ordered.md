status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #503, #523, #501
confirmed: unspecified

- [active] **🔧 FIX IN FLIGHT — multi-drop stack: Job owns ordered dropoffs, routed by customer name (#503 slice 3b, PR #523). CONFIRM ON DASH.**
  The structural multi-drop fix: an offer pre-creates **one dropoff placeholder per order**, and each
  dropoff screen routes to its own customer subtask by the **stable name hash** (addresses drift).
  **Confirm on a STACKED / multi-drop dash: 0/2 —** a 2-order stack should show **exactly two** dropoff
  cards, each resolving to the **right customer** (6-char hash), and the counts/earnings should match
  **two** deliveries (not 4, the 06-17 Jim's-stack symptom; not 1). Returning to an earlier drop (or
  the app re-showing it) should **resume** that drop, not spawn a duplicate. This is the real test of
  the 06-17 dropoff thread end-to-end — needs an actual stacked order (or a GoPuff multi-drop, though
  GoPuff recognition is still #501). If a drop duplicates, mis-resolves, or a phantom appears, capture
  the dropoff frame sequence + `app_state_snapshots` for the stack.
  Triage source: first-line blame 2026-06-18 (`7b1b39eb7`, original README L2446).
