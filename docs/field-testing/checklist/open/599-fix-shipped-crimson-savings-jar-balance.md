status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #599
confirmed: unspecified

- [active] **🔒 FIX SHIPPED — Crimson Savings Jar balance notification is now pledge-BLOCKED (#599). READ THE PULL AFTER A DASH.**
  The dasher's Crimson/DasherDirect balance notification was being recognized and captured raw
  (9 files found on the 06-25→30 pull — deleted from the device). The rule is now sensitive
  (priority 0, `parse: sensitive`) — the shared content gate drops it before capture and the
  state machine. **Confirm on next pull: 0/2 —** after a dash where the Savings Jar notification
  arrived: `captures/doordash/notification/` must contain **no** `crimson_balance` (or
  `sensitive.*`) folder/files, and the log shows `Sensitive gate: dropped sensitive.crimson_balance`
  at DEBUG. Broken = any capture file containing "Savings Jar balance". (Note: `earnings_deposit`
  and `transfer_complete` notifications still capture — their sensitive-vs-keep fate is an open
  dev call on #599's siblings.)
  Triage source: first-line blame 2026-07-02 (`c2c4d70a0`, original README L2274).
