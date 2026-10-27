status: review
issues: #604, #619

- **🔧 FIX SHIPPED — rule effects from notifications key per-arrival, not per-install (#604), AND
  FrameGate no longer collapses two distinct notification arrivals sharing a contentless identity
  (#619). CONFIRM ON DASH.**
  A rule-declared log effect attached to a notification with no `dedupeKey` (e.g.
  `doordash.notification.new_order`) built its `effects_fired` idempotency key from the rule id
  alone — a GLOBAL forever-key. The first `new_order` notification of the week fired it; every
  later `new_order` notification all week silently no-opped as "already fired" (the
  `effects_fired` table is only pruned at engine init, so a long-lived process never recovered).
  The key now includes the notification's own timestamp (postTime, replay-stable), so each
  distinct arrival gets its own key; an identical repost (same postTime) still dedups correctly.
  Screen-effect dedup is unchanged (intended cross-frame behavior, e.g. `offer-ss-{parsedHash}`).
  **SECOND LAYER — NOW ALSO FIXED (#619):** parse-less notification rules (e.g. `new_order`) have
  a CONSTANT `ObservationIdentity` (no parsed fields to hash), so the pipeline's identity-dedup
  layer (`FrameGate`) could still suppress a CONSECUTIVE `new_order` arrival even with #604's
  per-arrival effect key fixed. `FrameGate` now mixes the notification's content hash into the
  identity comparison for recognized notifications ONLY (screens are unaffected — pinned by a new
  test); two observably-distinct arrivals (different store/text) both admit, while an identical
  repost (same content) still dedups. **Accepted residual (documented, not a bug):** two
  same-store distinct offers back-to-back have identical notification text, hence identical
  content hash, and still dedup — the only full separator would be `postTime`, which risks turning
  every re-render/repost into a fresh forward, so it was deliberately not used. **Precaution:** the
  `dash_status_ongoing` "still dashing" heartbeat notification (reposts constantly; whether its
  body churns per repost is unconfirmed against real captures) is excluded from content-mixing at
  the call site and keeps the old pure-identity dedup, so it can't regress into per-repost spam.
  **Confirm on dash: 0/2 —** two **different-store** `new_order` notifications in one dash with
  **nothing recognized in between** (previously required something else between them to prove
  #604 alone) should now BOTH produce a `NOTIFICATION_RECEIVED`/`NEW_ORDER` row in `app_events`.
  Also watch that the `dash_status_ongoing` heartbeat does NOT spam `NOTIFICATION_RECEIVED` rows
  once per repost. Broken = a later distinct-store arrival missing from `app_events` with no
  "Skipping already-fired effect" line for it (a FrameGate regression), or a flood of
  `DASH_STATUS_ONGOING` log rows (the precaution failed to hold).
  - Confirmed: 0/2
  desk: desk-verifiable: compare distinct notification arrivals with rule-effect/idempotency records; identical content must not suppress a later arrival.
  Triage source: first-line blame 2026-07-03 (`af7177614`, original README L2106).
