status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #503, #525
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — live $/mi on the task card, read off the Job (#503 deliverable 2, PR #525). CONFIRM ON DASH.**
  The live pickup/delivery card's "Running at $X/hr" co-hero now shows a **$Y/mi** sub line (fixed
  efficiency off `Job.blendedDistanceMiles`; "—" when no offer distance is known, never `$X/0mi`).
  **Confirm on dash: 0/2 —** while driving a delivery the card should show a sensible **$/mi** under the
  $/hr (e.g. `$1.85/mi`), it should **persist** through nav→arrival (distance doesn't erode like the
  $/hr does), and on a **stacked/add-on** it should reflect the **summed** job distance (not a single
  offer's). If $/mi shows "—" on a normal offer (that carried a distance), or looks wrong on a stack,
  note the offer's quoted miles + net pay.
  Triage source: first-line blame 2026-06-18 (`cfa4b22ea`, original README L2457).
