status: active
issues: #503

- **✅ FIX SHIPPED — dropoff created from the offer; the premature "Customer" card root (#503 slice 3). CONFIRM ON DASH.**
  The drop-off is now a known subtask created at **offer-accept** (customer TBD), and the dropoff screen
  RESOLVES the real customer onto it — instead of a phantom dropoff minted before its customer is known.
  **Confirm on dash: 0/2 —** a drop-off card should show the **real customer** (a short 6-char hash
  code); an unresolved one shows **"the customer"** (lowercase, briefly, never the name-like "Customer")
  and must not **linger** or appear as a **premature/duplicate** drop-off card. If a phantom/duplicate
  dropoff or a stuck "the customer" card shows, capture the dropoff frame sequence + note the timing.
  (Single-order this build; **multi-drop is slice 3b, not yet shipped** — a stacked/GoPuff multi-drop
  may still mis-handle the extra dropoffs.)
  Triage source: first-line blame 2026-06-16 (`183d03d44`, original README L2405).
