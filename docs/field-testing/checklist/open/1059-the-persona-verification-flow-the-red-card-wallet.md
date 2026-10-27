status: active
issues: #1059

- **🆕 NEW — #1059 — the Persona verification flow, the Red Card wallet and the passport
  scanner are now blocked at the matcher layer.** Three of the dasher's OWN surfaces were
  reaching UNKNOWN capture: the embedded Persona selfie / ID-verification camera (12 envelopes
  on 08-27), the "Your Red Card" wallet screen (3), and the PASSPORT variant of the ID-scan
  camera (1 — the two existing scanner anchors were both driver's-licence specific). None of
  them leaked customer PII, but the Pledge blocks a document-image capture surface as a CLASS,
  and the Red Card screen is the dasher's own payment-card management surface. Each now has an
  id-anchored `sensitive.known` arm (locale-immune), plus four marker keywords as the
  rules-independent backstop.
  **On-dash:** nothing to watch while driving, and nothing should CHANGE — the block is
  capture-side. If you happen to hit an ID-verification prompt or open the Red Card screen,
  the app must behave exactly as before (no bubble, no narration, no state move).
  **Desk, after the pull:**
  1. `grep -rlE 'persona_container|personaComposeView|activate_physical_card_button|id_type_selector' captures/**/UNKNOWN/`
     must return **nothing** — a hit means the frame still fell to UNKNOWN capture.
  2. The same grep across the whole `captures/` tree should return nothing either: a sensitive
     frame is dropped at the content gate BEFORE any envelope is written, so it must not appear
     in a recognized folder either.
  3. `grep 'Sensitive gate: dropped' app.log` (DEBUG) — `sensitive.selfie_verification` /
     `sensitive.red_card` / `sensitive.id_verification` lines are the positive signal the new
     arms fired on the device build.
  4. The over-match check: an ordinary shopping-order pickup that says "pay with your Red Card"
     must still classify `pickup_*` normally — grep `app.log` for a `sensitive.red_card` drop
     with no wallet screen visit around it.
  - Confirmed: 0/2 (desk 09-07 on `aabb56d0`: NOT EXERCISED — no Persona / Red Card wallet / passport surface visited. Side readings: the pre-existing arms fired heavily (`sensitive.dasher_direct` ×76, `sensitive.balance` ×9, `sensitive.crimson_balance` ×2, `sensitiveDropped=84`), and the OVER-match check passes with force — two full Red-Card-paid shop-and-deliver orders (87 `pickup_shopping` + 26 `shopping_item` frames) produced ZERO `sensitive.red_card` drops. Desk 09-09 on `8028691a`: NOT EXERCISED again — the id grep returns nothing tree-wide and no `sensitive.selfie_verification` / `red_card` / `id_verification` drop; over-match check passes a second time (two `Red Card` shop offers + three shop pickups, 67 `pickup_shopping` + 28 `shopping_item` frames, zero `sensitive.red_card` drops).)
    - desk 09-13 (3rd pull): NOT EXERCISED — zero ids tree-wide, zero sensitive drops of the three new arms; the OVER-match check passes a third time (8 shop orders, 99 `pickup_shopping` + 53 `shopping_item` frames, zero `sensitive.red_card` drops).
    - desk 09-15 (4th pull): NOT EXERCISED; over-match check passes a fourth time (~20 Red-Card shop orders, 211 `pickup_shopping` + 80 `shopping_item`, zero `sensitive.red_card`). Caveat: `sensitive.id_verification` fired 16× on the alcohol licence scanner and the log names only the intent — NOT evidence for the passport alternative.
    - desk 09-20 (5th pull): NOT EXERCISED — the four ids return 0 tree-wide and none of the three new arms fired; over-match check passes a fifth time (269 `pickup_shopping` + 125 `shopping_item` frames, many Red-Card shop orders, zero `sensitive.red_card` drops). Pre-existing arms healthy: `dasher_direct` ×500, `balance` ×25, `crimson_balance` ×11, `catchall` ×34.
    - desk 09-21 (6th pull): NOT EXERCISED — the four ids return **0** tree-wide and none of the
      three new arms fired; the over-match check passes a **sixth** time (175 `pickup_shopping` + 70
      `shopping_item` Red-Card shop frames, zero `sensitive.red_card` drops). Pre-existing arms
      healthy: `dasher_direct` ×73, `catchall` ×7, `balance` ×5, `crimson_balance` ×2.
    - desk 09-26: NOT EXERCISED (the id grep is 0 tree-wide). Adjacent positive: `sensitive.id_verification` dropped
      39 frames across the two alcohol drops (09-21 12:53, 09-23 09:22); no scanner frame reached disk. Over-match
      check passes (Red-Card shop orders, 0 `sensitive.red_card`).
    - desk 09-27: NOT EXERCISED — the four ids are 0 tree-wide; none of the three arms fired. Pre-existing arms:
      `dasher_direct` ×74 (incl. a mid-dash visit at 14:05:41), catchall ×6, `crimson_balance` ×1; 0
      `sensitive.red_card` over 141 `pickup_shopping` + 51 `shopping_item` H-E-B frames (over-match passes a 7th
      time).
    - desk 09-29: NOT EXERCISED — the four ids 0 tree-wide. Pre-existing arms: `dasher_direct` ×388 (incl. a mid-dash
      visit 09-28 08:42), balance ×20, catchall ×19, crimson_balance ×7, savings ×4. 0 `sensitive.red_card` over 105
      `pickup_shopping` + 37 `shopping_item` frames (over-match passes an 8th time). "Open digital Red Card" (click
      09-27 12:23:08) produced NO admitted frame for 24 s — nothing reached disk.
  Triage source: first-line blame 2026-09-05 (`1fa9b552f`, original README L672).
