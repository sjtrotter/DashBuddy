status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #501, #530
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — GoPuff (Drive) bin-scan mints the pickup arrival (#501, PR #530). CONFIRM ON DASH.**
  The warehouse leg used to be all-UNKNOWN with **no `PICKUP_ARRIVED`** (the machine jumped
  nav→confirmed). Now the GoPuff **bin-scan steps** screen ("Pickup steps" / "Scan barcodes on" /
  "Complete pickup") is recognized as a branch of `pickup_steps` and declares `task:pickup:arrived`;
  the wait-survey + barcode-scan screens are recognized too. **Confirm on a GoPuff dash: 0/2 —** the
  bubble should show the pickup ARRIVING at the warehouse (a Dwell timer at store), exactly **one**
  pickup arrival for the batch (not zero, not two), and the bin-scan/scan-fail screens shouldn't sit
  on a stale card. If the arrival never fires or fires twice, capture the bin-scan frame sequence +
  `app_state_snapshots`. (Still UNKNOWN, deferred to a follow-up: the zone-arrival "Navigate to zone"
  card and the dropoff multi-order-confirm — those are noise, the arrival is the load-bearing fix.)
  Triage source: first-line blame 2026-06-18 (`635294559`, original README L2466).
