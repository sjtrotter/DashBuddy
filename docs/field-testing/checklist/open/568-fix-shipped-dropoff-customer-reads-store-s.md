status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #568, #575, #526
confirmed: 1/2

- [active] **🔧 FIX SHIPPED — dropoff customer reads "\<store\>'s customer", not a 6-char hash (#568, PR #575). CONFIRM ON DASH.**
  The dropoff bubble/card used to show the raw 6-char hash prefix (e.g. "Heading to 45ceda") or "the
  customer". Now it's store-flavored — "Heading to H-E-B's customer" / the card reads "Maple Street's
  customer" — which also tells apart a multi-store stack's drops. **Confirm on dash: 0/2 —** on a
  delivery, the dropoff bubble/card should name the **store's** customer (e.g. "Wendy's customer"),
  never a hex string; on a multi-store stack each drop should read its **own** store. If the store
  hasn't resolved yet it falls back to "the customer" (acceptable, brief). Privacy unchanged (still a
  hash under the hood; the store name isn't customer PII).
  - Confirmed: **1/2** (06-25→30 week — works on single-store jobs, e.g. "Flying Tiger Thai
    Restaurant's customer" 06-26; but fold-in/leg-2 drops stick at "the customer" and never
    resolve — that half is the week entry's Bug #10b, #526-widened scope).
  Triage source: first-line blame 2026-06-22 (`ac3557177`, original README L2294).
