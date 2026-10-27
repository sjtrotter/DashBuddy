## 2026-06-13 — DoorDash session (desk review of post-#487 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `aaa8d94` (post the 2026-06-13 morning batch: #464–#490 — incl.
  #473 durable-last-dash, #470 double-dropoff, #466 money-formatter SSOT, #461 shop cards,
  #462/#463 recognition + privacy batches). The bubble now shows the last dash again (#473).
- **Field conditions:** desk review of a completed dash in the bubble's last-dash card stack, with a
  developer screenshot. Recorded for triage — **hypothesis, not a concluded fix.**

### Bugs

#### 1. **Ghost offer** — a phantom Offer card with EMPTY parse (no store / no pay / no miles), scored and logged
In the card stack, **between the Mello Mushroom and Pei Wei offers**, there is a ghost Offer card the
dasher doesn't recognize. From the screenshot:

- **Ghost card:** Score **24**, hero **`$-2/hr`**, **Net `-$0.36` · `-$0.36/mi`**, verdict
  **DECLINE / BAD OFFER**, outcome chip **Timed out** — and **no store name, no pay amount, no real
  distance** (the `-$0.36` / `-$0.36/mi` are cost-only artifacts of an empty parse).
- **Pei Wei card (contrast, fully populated):** Score 66, `$18/hr`, Net `$8.26 · 8.4 mi · $0.98/mi`,
  `2 items`, store `Pei Wei & Cold Stone Creamery`, Declined.

- **Likely cause (hypothesis):** a **transient/partial `offer_popup` frame**. The rule's `require`
  (`doordash.json` `doordash.screen.offer_popup`) is satisfied by the popup **chrome** — `"Decline"`
  text + (`"Accept"`/`"Add to route"`) + the `accept_button`/`accept_decline_footer_container` id —
  which renders **early**. But the **content** nodes the `parse` reads (`display_name` for the
  store/orders, the `$` pay text) hadn't rendered yet, so the parse produced **empty `orders`, null
  `payAmount`, and a near-zero/garbage distance**. The evaluator still scored the empty offer (Score
  24, Net `-$0.36` = $0 pay minus ~1 mi of cost), and because the frame was then replaced/expired
  without a real Accept/Decline, it was logged as **`OFFER_TIMEOUT`** → a ghost card. (This is the
  empty-parse cousin of the 06-09 #4 phantom, but **not** self-recognition — that gate is intact;
  this is a real DoorDash popup caught mid-render.)
- **Would the morning's fixes catch it? Probably not.** `#427`/`#436` dedupe the **same** offer by
  hash (a distinct empty parse has its own hash); the #4 self-recognition gate only drops **our**
  overlay. **Nothing currently rejects a real-but-empty partial offer frame** — there is no
  validity/settle gate before an `offer_popup` is presented, scored, and logged.
- **To confirm (desk):** pull the `offer_popup/*.json` capture in the Mello→Pei Wei gap + the
  `OFFER_TIMEOUT` `app_events` row. Expect a **partial tree** (footer/accept chrome present, but no
  `display_name` / no pay `$` node) and an **empty `parsedOffer`**.
- **Possible directions (desk call, NOT a concluded fix):** (a) a **validity gate** — don't
  present/score/log an `offer_popup` whose parse has **empty `orders` AND null `payAmount`**;
  (b) **settle/debounce** the offer frame before eval (the idea dropped in 06-09 #4 — but this empty
  partial-render is exactly its use case); (c) require at least a store **or** a pay value before the
  evaluator runs. Logged to the watch list so a live dash can confirm frequency before any fix.
