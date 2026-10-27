status: active
issues: #653, #673, #498, #501

- **🆕 NEW — identity-less completions now firewalled at PostTask exit (#653 / PR #673): watch a
  no-name drop for a MISSING completion.** The PostTask-exit `DELIVERY_COMPLETED` mint now mirrors
  the close-out path's #498 identity firewall: a dropoff task that never acquired a customer
  name/address hash mints no completion. Field history says identity-less dropoff tasks are
  phantoms, but the known residual is a REAL delivery whose only name-bearing screen is still
  unruled — e.g. a GoPuff last drop via the multi-order-confirm surface (#501 deferred). On any
  GoPuff batch (or other drop where the customer name never appeared on a recognized screen),
  check afterwards that the delivery still shows in the session's deliveries/earnings. How to tell
  it's broken: a real, physically-delivered drop is absent from the completion list / Money tab
  while its siblings are present.
  Triage source: first-line blame 2026-07-05 (`30f2cba80`, original README L1846).
