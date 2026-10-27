status: review
issues: #944, #957

- **🆕 NEW — #944 / PR #957 — permission gate re-hoisted (UDF).** The permission bottom sheet's
  behavior should be unchanged: it opens when permissions are missing, advances card-by-card as
  each is granted, closes cleanly when all are granted — and, the edge the refactor specifically
  defends, RE-OPENS with a fresh (not stale) card queue after revoking a permission from system
  Settings and returning to the app.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — this is a UI interaction test that
    needs dev eyes revoking a permission from system Settings mid-session; nothing in a desk pull
    bears on it either way. Stays at 0/2.)
  desk: needs field: revoke and regrant a permission to observe a fresh queue on return to the app.
  Triage source: first-line blame 2026-07-30 (`2b418cf28`, original README L1038).
