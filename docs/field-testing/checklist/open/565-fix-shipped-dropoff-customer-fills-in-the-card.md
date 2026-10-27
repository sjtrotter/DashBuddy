status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #565, #571
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — dropoff customer fills in the card instead of re-minting it (#565, PR #571). CONFIRM ON DASH.**
  06-21 Walgreens: at the dropoff the bubble showed **"the customer"** for a bit, then when you
  started navigating the card **re-minted** (a fresh card appeared) instead of just filling in the
  name — leaving a dead blank task behind. Fixed so the customer **resolves onto the same dropoff
  card**. **Confirm on dash: 0/2 —** on a normal single delivery, when the dropoff begins it may
  briefly read "the customer" (that's expected until the nav screen carries the name), but it should
  then **fill in the real customer on the same card** — you should **not** see the card visibly
  re-create itself. On a genuine multi-customer stack, each customer should still get its **own**
  card. If you see a re-mint or a leftover "the customer" card that never resolves, note the store +
  grab the dropoff capture sequence. (Earnings were never affected by this — it was cosmetic/ledger
  hygiene.)
  Triage source: first-line blame 2026-06-22 (`04bb471f6`, original README L2333).
