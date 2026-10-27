status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #888, #595, #922, #989
confirmed: 0/2

- [review] **🆕 NEW — #888 — eight DoorDash screens that used to fall to UNKNOWN are now recognized.**
  All eight are **recognize-only** (no state claim, no parse), so the only intended change is that
  the frames stop landing in the UNKNOWN capture pile. The list: the post-accept **"accepting"
  spinner** (every accept was losing its own follow-frame), the mid-shop **pay-adjustment sheet**
  ("N items have been added / We have adjusted your pay"), the dropoff **geofence-warning help flow**
  (warning card mid-inflation → Help menu → "Continue to complete delivery"), **"Confirm you handed
  order directly to customer"**, the **pizza-bag verification** flow (instruction → uploading →
  result), the **shelf-photo substitution** sheet, the **"Confirm order was picked up"** dialog, and
  the **wait-survey sheet variant**.
  **What to watch (DoorDash dash) — this is a NEGATIVE test; nothing new should appear:**
  1. **Nothing changes visibly.** No new bubble cards, no new TTS, no "Declined/Expired" chatter
     around an accept. If any of these screens now makes the HUD say or do something, that is the
     bug — note which screen.
  2. **Accepting an offer still behaves exactly as before** — the accept lands, the pickup card
     comes up, no ghost offer, no "(offer replaced)". The accept spinner is the highest-frequency
     of the eight, so it is the one to watch. The rule deliberately does NOT re-assert
     `offer:presented` (that is the #595 ghost-accept class), but the field is the proof.
  3. **A pizza order and a shop-with-substitutions order** exercise two of the eight; if you get
     either, note whether the pickup/dropoff lineage stayed correct through them.
  **Desk (next pull):** the UNKNOWN pile should no longer contain these eight families — check the
  UNKNOWN census for `progress_message` offer frames, `pizza_bag_*`, `wait_survey_container`,
  `geofence_warning_map`, "items have been added", "photo of the shelf", "handed order directly",
  "Confirm order was picked up". Recognized captures for these intents should be PII-clean (they
  carry no customer nodes at all; the geofence card's `address_line_*` are covered by that rule's
  existing redact).
  - Confirmed: 0/2 (desk 07-30, the 07-29 dash: PARTIAL — 2 of 8 families confirmed recognized
    (`offer_accepting` ×1, `shopping_pay_adjustment` ×1), the other 6 never occurred; the negative
    test passes (no new bubble/TTS chatter around 4 accepts, no ghost offers). BUT the
    accept-spinner rule covered only 1 of 4 accepts and `progress_message` frames still land
    UNKNOWN — the offer-card inflation family is filed as #922; hold at 0/2 pending it.
    Desk 07-31, the 07-30 dash: a THIRD family confirmed — the full geofence help flow
    (`dropoff_geofence_warning` → `dropoff_issue_menu` → `dropoff_issue_resolution` →
    `dropoff_geofence_warning`, 19:29:23-33) recognized clean, zero new bubble/TTS chatter
    (`offer_accepting` ×2 of 4 accepts, `shopping_pay_adjustment` ×1 also this dash — same ~50%
    accept-spinner coverage as 07-29). NEW RESIDUAL inside the now-recognized geofence family: the
    flow's own `Continue / Go back` confirmation sheet (19:29:31) is still UNKNOWN — filed as #989.
    Still holding at 0/2 pending #922 and #989; 5 of 8 families remain unexercised.
    Desk 08-09, the 08-01→08-08 window: a FOURTH family sighted and the negative test holds across
    five dashes — recognized this window: `offer_accepting` ×7, `shopping_pay_adjustment` ×1,
    `shopping_shelf_photo` ×1 (new), `dropoff_multi_order_confirm` ×1 (new), with zero new
    bubble/TTS chatter around any of 26 offers and no ghost offers. The blocker is unchanged: the
    offer-card **inflation** frames still land UNKNOWN (~29 of them this window, #922, including
    two fully-populated offer cards), so accept-spinner coverage is still partial. Holding at 0/2
    pending #922 and #989.)
  desk: desk-verifiable: census all eight named capture families and correlate their timestamps with absence of new flow/TTS effects; unexercised families remain open.
  Triage source: first-line blame 2026-07-27 (`37f69f70a`, original README L1095).
