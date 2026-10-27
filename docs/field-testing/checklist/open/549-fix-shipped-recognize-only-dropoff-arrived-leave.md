status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #549, #574
confirmed: unspecified

- [active] **🔬 FIX SHIPPED (recognize-only) — dropoff-arrived 'Leave it at the door' card now recognized (#549, PR #574). READ THE LOG / WATCH UNKNOWNs.**
  A dropoff-arrival card whose instruction is *not* "Hand it to recipient" (e.g. "Leave it at the
  door", a refined map pin, "Complete delivery steps") was falling to **UNKNOWN** — the state machine
  got no clean dropoff signal and leaned on the grace window. Now recognized (customer name + address
  **hashed**; the gate-code/instruction text is never stored). **Confirm: 0/2 —** after a dash with a
  "leave at door" / gate-code delivery, grep the captures/log: that arrival screen should recognize as
  a dropoff (not pile into UNKNOWN), and INFO/db must show **no** raw address/gate-code (hashes only).
  This is recognize-only — it does **not** yet flip an "arrived" state (deferred, needs a fresh
  capture to decide), so don't expect a behavior change, just cleaner recognition.
  Triage source: first-line blame 2026-06-22 (`5be3a6852`, original README L2306).
