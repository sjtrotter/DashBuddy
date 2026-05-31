# UNKNOWN Capture Triage — `logs/2026/05`

**Analysis date:** 2026-05-30  ·  **Branch:** `docs/2026-05-capture-triage`  ·  **Scope:** every session under `/home/betty/dashbuddy/logs/2026/05` (15, 17, 19, 21, 22, 23, 25, 29), all platforms (DoorDash + Uber), all three capture sources (`accessibility.window`, `accessibility.click`, `notification`).

> **This is a triage report, not a change.** No ruleset or code has been edited. Every "proposed rule" below is a sketch in the existing JSON DSL for review — anchors should be confirmed against 1–2 live captures before anything is implemented. Notification *actions* (parse-able buttons) are deliberately out of scope per the request.

---

## TL;DR

The corpus holds **5,210 `UNKNOWN` capture files** (4,444 window + 465 click + 301 notification). That volume is **not** redundant re-saves of recognized screens (the capture bus already dedups those — validated below). It is two things:

1. **Real, recognizable screens/clicks/notifications that simply have no rule yet** — by far the majority. The entire **shop-and-deliver surface**, **customer chat**, the **drop-off completion workflow**, **side-nav/menu**, and **most notification types** are unclassified. Roughly **~2,930 of 4,444 window UNKNOWNs (~66%)** are identifiable DoorDash screens.
2. **Genuine noise that should never reach the classifier** — Android **lockscreen / always-on-display**, the **launcher/recents** switcher, **other foreground apps**, **media-player / notification-shade** chrome, and **transient loading / empty-shell frames**. Roughly **~980 window UNKNOWNs (~22%) + most "OTHER" tail** are this.

**The single most important structural finding** (see mechanism below): *classifying a recurring screen doesn't just add data — it removes data*, because recognized screens are identity-deduped on capture and UNKNOWN screens are not. So writing rules for the big recognizable families is simultaneously the fix for the file-count explosion.

---

## Why there are so many files — the capture mechanism (validated against the logs)

Tracing `app.log` around real captures (e.g. `15/field-test` at `2026-05-15 18:31:05`) shows the live pipeline:

```
💧 DRIP: triggered by android.view.ViewGroup, accumulated types=0x1001     ← every content change
🌳 Tree snapshot: 79 nodes, pkg=com.doordash.driverapp
INFO/ObservationClassifier: SCREEN: pickup_pre_arrival                       ← re-classified each drip
DEBUG/AccessibilityPipeline: Captured screen: target=pickup_pre_arrival … captured=true
DEBUG/DiskCaptureBus$offer: Captured: …/pickup_pre_arrival/…json
```

The classifier fires on **every `ContentChangedPipeline` "DRIP"** — many times per second while a screen animates, scrolls, or loads. Two dedup layers normally tame this:

- `AccessibilityPipeline` drops a snapshot whose **semantic identity** equals the previous one (`AccessibilityPipeline.kt:131‑144`).
- `DiskCaptureBus` drops a payload whose **content hash** was already seen in that bucket this session (`DiskCaptureBus.kt:54‑56`).

**But the identity-dedup only updates `lastIdentity` for *recognized* screens.** An UNKNOWN snapshot never becomes the "previous identity," so consecutive UNKNOWN snapshots never suppress each other. Combined with the content-hash layer (which only collapses *byte-identical* trees), the result is:

> Every structurally-distinct frame of an **unrecognized, dynamic** screen is written to disk. A shopping list that re-lays-out as items load, a chat that gains a message, a map that repaints — each drip is a "new" UNKNOWN and each is saved.

That is the whole explanation for the 4,444 window files. The recognized folders are *not* the problem — when I sampled `pickup_pre_arrival` above, the screen was re-classified ~6× in 1.5 s but `captured=true` fired **once**. The two remedies fall straight out of the mechanism:

- **Recognize the screen** → it gets an identity → repeats are deduped → the family collapses from hundreds of files to a handful.
- **Gate the screen out before classification** (for off-app / system UI) → it is never snapshotted or saved.

A noise example, same log style, captured while not even dashing:

```
[DoorDash:Offline] INFO/ObservationClassifier: SCREEN: UNKNOWN
[DoorDash:Offline] DEBUG/AccessibilityPipeline: Captured screen: target=UNKNOWN  ruleId=null  captured=true
…/UNKNOWN/2026-05-15_16-42-59-725__doordash__…__72d438.json   ← this is the phone lockscreen/AOD
```

---

## Method & caveats

- **Clustering.** Every UNKNOWN payload was fingerprinted by its **set of resource-id suffixes** (which collapses repeated list rows and dynamic text) with a normalized-text fallback for id-less Compose trees, then grouped. Windows were additionally bucketed into **screen families** by anchor ids/text. Scripts are in `/tmp` (not committed); they are pure read-only analysis.
- **Counts overstate uniqueness.** Several session folders contain **byte-identical copies** of the same capture (same `timestamp__…__hash.json` appears under `15/field-test`, `17/field-test`, `17/bubble-test`, …). Clustering merges them, but per-cluster `count` includes the duplicates. Treat counts as *relative* signal, not exact unique-screen tallies.
- **Log correlation.** Capture timestamps (ms) line up directly with `app.log` lines in the same session folder; I used this to confirm purpose/state for ambiguous and noise clusters.

### Corpus map (UNKNOWN only)

| Source | Files | Distinct clusters | Headline |
|---|---:|---:|---|
| `accessibility.window` | 4,444 | ~1,030 raw → **18 families** | ~66% recognizable DoorDash screens, ~22% system/foreign/transient noise |
| `accessibility.click` | 465 | 106 | meaningful action buttons vs. navigation/map chrome |
| `notification` | 301 | 43 | ~12 recognizable types; existing rules match on title text, but **`channelId` is the reliable key** |

---

# 1. Notifications

**301 UNKNOWN files, 43 clusters.** The existing 5 DoorDash notification rules key on *title text* (`titleContains:"new order"`, `titleContains:"Dash now"`, `titleContains:"Incentives Update"`, …). Real notifications either use a *different* title ("New Delivery!" not "new order") or vary their title per-message ("Message from Jennifer"), so they fall straight through to UNKNOWN. The compiler already supports **`channelIdEquals` / `channelIdContains`** (`RuleCompiler.kt:1061‑1067`) — and the Android channel id is the single most stable discriminator DoorDash gives us. Every proposal below keys on channel first, text second.

## 1A. Recognizable — should classify

### `new_order` (offer arrival)  ·  **110 files, the #1 notification**  ·  HIGH value
- **Signals:** `channelId = dasher-notification-channel-new-order-v2`; title `"New Delivery!"`; text `"New order: go to {store}"` / `"… and N other store"`.
- **Why it misses:** existing `doordash.notification.new_order` requires `titleContains:"new order"`, but the live title is **"New Delivery!"** — the phrase "new order" is only in the *body*.
- **Why it matters:** this is the *offer-arrived* signal at the OS level — fires even before/independently of the on-screen offer popup. Direct value for the offer pipeline and the R0 store-correlation queue.
```json
{ "id": "doordash.notification.new_order_v2", "priority": 18,
  "require": { "any": [ { "channelIdEquals": "dasher-notification-channel-new-order-v2" },
                        { "titleEquals": "New Delivery!" },
                        { "textContains": "New order: go to" } ] },
  "intent": "new_order", "parse": { "as": "notification" } }
```

### `customer_message`  ·  **~40 files across many per-customer clusters**  ·  HIGH value
- **Signals:** `channelId = dasher-notification-channel-inapp-chat`; title `"Message from {name}"`; body = the customer's message (gate codes, substitutions, addresses).
- **Why it misses:** title is per-customer, so no text rule can catch it; channel is constant.
- **Note:** bodies are PII-dense — classification is fine, but content handling must respect the edge-scrub pledge.
```json
{ "id": "doordash.notification.customer_message", "priority": 22,
  "require": { "any": [ { "channelIdEquals": "dasher-notification-channel-inapp-chat" },
                        { "titleContains": "Message from" } ] },
  "intent": "customer_message", "parse": { "as": "notification" } }
```

### `order_ready_for_pickup`  ·  **13 files** (`delivery-update` + `delivery-background`)  ·  value
- **Signals:** channel `dasher-notification-channel-delivery-update` (and `…-background`); title `"Delivery Update"`; text `"{name}'s order is ready for pickup at {store}…"`.
- **Why it matters:** confirms the store has the order ready — a pickup-timing signal.
```json
{ "id": "doordash.notification.order_ready", "priority": 24,
  "require": { "all": [ { "channelIdContains": "delivery-update" },
                        { "anyFieldContains": "ready for pickup" } ] },
  "intent": "order_ready_for_pickup", "parse": { "as": "notification" } }
```

### `earnings_deposit`  ·  **32 files**  ·  value (earnings tracking)
- **Signals:** channel `dasher-notification-messages`; title `"Dasher"`; text `"your dasher earnings for $N have been deposited to your DoorDash Crimson account."`
```json
{ "id": "doordash.notification.earnings_deposit", "priority": 26,
  "require": { "all": [ { "channelIdEquals": "dasher-notification-messages" },
                        { "anyFieldContains": "have been deposited" } ] },
  "intent": "earnings_deposit", "parse": { "as": "notification" } }
```

### `arrived_in_zone` (Dash Along the Way — entered zone)  ·  **11 files**  ·  value (geo/state signal)
- **Signals:** channel `dasher-notification-channel-dash-update`; title `"You have arrived"`; body in the captured samples `"tap here or return to the dasher app to look for more orders"`.
- **What it actually is** (per developer): fires when, while **dashing along the way**, you cross into the dash **zone boundary** — *independent of task state*. It occurs both while idle/navigating to the zone **and** while on-task (en route to a pickup or delivery). It is **not** a delivery-destination or post-delivery signal.
- **Why it matters:** marks the "along the way → in zone" transition. Key on **title + channel**, not body — the body text likely differs in the on-task case (all 11 captured here were the idle/look-for-orders variant, so the on-task wording is unconfirmed).
```json
{ "id": "doordash.notification.arrived_in_zone", "priority": 40,
  "require": { "all": [ { "channelIdContains": "dash-update" }, { "titleEquals": "You have arrived" } ] },
  "intent": "arrived_in_zone", "parse": { "as": "notification" } }
```

### `order_verification_warning`  ·  **1 file**  ·  value (issue signal)
- **Signals:** channel `dasher-notification-channel-dash-update`; title `"Confirm you have the right order"`; text `"the receipt you uploaded may not match…"`. → `intent: order_verification_warning`.

### `missed_delivery`  ·  **1 file**  ·  value
- **Signals:** channel `dasher-notification-channel-delivery-update`; title `"Missed Delivery"`; text `"you missed a delivery opportunity…"`. → `intent: missed_delivery`.

### Lower-value but distinct (classify mainly to *silence* / route, not act on)

| Proposed intent | Channel / title | Files | Reason to classify |
|---|---|---:|---|
| `dash_status_ongoing` | `…channel-status` · "DoorDash Driver Dash" / "You're still dashing…" | 14 | Persistent foreground-service heartbeat; classify so it stops being UNKNOWN (it re-posts constantly). Confirms online state. |
| `crimson_balance` | `dasher-notification-messages` · "You're building momentum 💪" / savings-jar balance | 32 | Crimson banking nudge; classify to route away from offer logic. |
| `transfer_complete` | `com_appboy_default_notification_channel` · "✅ Transfer complete." | 6 | Banking/earnings event. |
| `demand_nudge` (extend) | `…channel-dash-update` · "Update!" / "{zone} is busy…" + "you've scheduled yourself to start dashing in N minutes" | 15 | Demand + schedule-reminder; existing `demand_nudge` only matches `titleContains:"Dash now"`. |
| `marketing_promo` | `com_appboy_default_notification_channel` (Braze) · "Earn an extra $85…", "Reminder: Challenge ends soon!", "This one deserves a tap 🔍", "Nice one, Stephen 👏", referral bonuses | ~12 | Braze marketing catch-all; classify the *channel* to silence the long promo tail in one rule. |
| `severe_weather` | `com_appboy…` · "A severe weather warning has been detected…" | 2 | Safety-relevant; worth splitting out of the marketing catch-all. |
| `insight_achievement` | `dasher-notification-channel-new-insight` · "Order accuracy streak" etc. | 2 | Gamification; low value, classify to silence. |
| Uber `quest_promo` / `earnings_promo` | `normal_priority` · "Earn extra with Quest", "Earnings support for under 3¢/mile", "Earnings backup…" | ~5 | Uber promo/quest; route to Uber promo handling. |

## 1B. Notification noise — do not classify (or classify only to drop)

| Cluster | Files | Why it's noise |
|---|---:|---|
| `NAVIGATION_NOTIFICATION_CHANNEL`, empty title/text | 10 on disk | The turn-by-turn nav service re-posts **~once per second** (visible all through `app.log` as `UNKNOWN notification —`). Content-hash dedup collapses it to ~1 file/session, but the **classifier still churns every second**. Best handled by a channel/empty-text **drop gate**, not a rule that captures. |
| Empty-content notifications (`__0.json`, various channels) | ~6 | Notification *updates/cancellations* with no title/text (e.g. the empty `new-insight` posted 205 ms after the streak message). Nothing to recognize. |

**Cross-cutting:** matching `channelId` instead of title would move the bulk of these 301 files out of UNKNOWN in a handful of rules. There are only ~10 distinct DoorDash channels in the whole month.

---

# 2. Clicks

**465 UNKNOWN files, 106 clusters.** Each click payload carries `screenTarget` + the tapped node, so `(screenTarget, idSuffix)` is a near-perfect signature — and the click DSL keys on exactly that (`screenIs` + `require:{hasIdSuffix}}`). Only 11 DoorDash click rules exist today, so almost every real action button on the post-offer screens is UNKNOWN. A recurring root cause: several **completion-flow clicks are UNKNOWN because the *screen* under them is under-recognized** (e.g. `complete_delivery_steps_button` taps land on `screenTarget=dropoff_navigation`, but the existing `complete_delivery` rule is scoped `screenIs=dropoff_pre_arrival_completion`).

> **Do screens before clicks (this whole section is provisional).** Click rules are gated by `screenIs`, which is `lastScreenTarget` — the *recognized* screen at tap time. Since recognizing the missing screens (§3) will change what `lastScreenTarget` is on many of these taps, the `screenIs` scoping for the clicks below will shift. Treat the click catalog as a follow-on to the screen work, not parallel to it.
>
> **Stale/dead existing click rules:** only **6 of 11** defined intents ever fired this month (`accept_offer`, `decline_offer`, `initial_decline`, `go_online`, `go_offline`, `start_dash_set_end_time`). In particular **`doordash.click.checkout` is dead** — its anchor `button_checkout` appears **0×** in any capture; the real UI uses an id-less "prism" button and "Checkout" is a non-clickable heading. The end-of-shopping gate should be the **checkout *screen*** (`fragmentContainerView_genericCheckout`, §3.1 `shopping_checkout`), not a click.
>
> **Prism buttons *are* text-matchable.** Their clicked node has empty own-`text` (the label sits in a descendant `textView_prism_button_title`), so `hasText`/`hasTextContaining` won't match — but **`hasAnyText`** reads the subtree (`node.allText`) and matches the label (exact, case-insensitive). The existing `decline_offer` rule already uses `hasAnyText:"Decline offer"` this way. So id-less prism actions can be keyed on their label via `hasAnyText`.

## 2A. Recognizable actions — should classify

### Delivery completion (currently UNKNOWN)  ·  HIGH value
The whole "mark delivered" flow on `dropoff_navigation` is unclassified: `complete_delivery` ("Complete delivery"), `complete_delivery_steps_button` ("Complete delivery steps", 3×), `received_order_button` ("I have received this order"), `confirm_button`, `step_action_red`.
```json
{ "id": "doordash.click.complete_delivery_nav", "priority": 61, "screenIs": "dropoff_navigation",
  "intent": "complete_delivery",
  "require": { "any": [ { "hasIdSuffix": "complete_delivery" }, { "hasIdSuffix": "complete_delivery_steps_button" },
                        { "hasText": "Complete delivery" }, { "hasIdSuffix": "received_order_button" } ] } }
```

### Proof photo / receipt capture  ·  **~35 files across 4 screens**  ·  HIGH value
`capture_button` (desc "capture image") on `pickup_arrival` (10), `dropoff_pre_arrival` (10), `pickup_shopping` (7), `dropoff_navigation` (2). Screen-agnostic — propose **no `screenIs`**:
```json
{ "id": "doordash.click.take_photo", "priority": 72, "intent": "take_photo",
  "require": { "all": [ { "hasIdSuffix": "capture_button" }, { "hasDesc": "Capture image" } ] } }
```

### Arrival / primary action  ·  **~20 files**  ·  HIGH value
`primary_action_button` on `pickup_pre_arrival` (13) and `pickup_arrival` (7), `primary_button_right` ("Done"). Existing `arrived_at_store` only covers `pickup_arrival`+text "Arrived"; the `pickup_pre_arrival` primary button ("Directions"/"Arrived at store") is unmatched. Recommend a small rule per screen keyed on `primary_action_button` with text disambiguation.

### "Stop orders after this delivery" (pause / wind-down intent)  ·  value — but **single capture, confirm intent**
`bottom_sheet_stop_orders_toggle_button` ("Stop orders after this delivery") on `dropoff_navigation`. Per developer this is an intent to **pause** the dash — finish the current delivery, then stop receiving new orders — *not* End Dash (and distinct from the timed Pause). **Provenance:** a real `accessibility.click` event (log `2026-05-29 20:01:57.071`: `UNKNOWN click — id=…bottom_sheet_stop_orders_toggle_button`), captured once, in one session, no repeats — and the developer doesn't recall tapping it, so a deliberate-vs-incidental tap can't be confirmed from this single sample. The rule is valid; just verify intentionality (and whether it's a toggle that also fires an "un-stop" click) before relying on it as a signal.
```json
{ "id": "doordash.click.stop_orders_after_delivery", "priority": 62, "screenIs": "dropoff_navigation",
  "intent": "stop_orders_after_delivery", "require": { "hasIdSuffix": "bottom_sheet_stop_orders_toggle_button" } }
```

### Remaining meaningful actions (compact)

| Group | screenTarget · idSuffix (text/desc) | Files | Proposed intent | Reason |
|---|---|---:|---|---|
| Shopping | `pickup_shopping` · `primary_button` ("add to cart") | 7 | `shopping_add_to_cart` | shop-and-deliver progress |
| Shopping | `pickup_shopping` · `add_item_button` (desc "add … item") | 6 | `shopping_add_item` | item added |
| Shopping | `pickup_shopping` · `primary_button_right` (desc "done") | 8 | `shopping_done` | item/step complete |
| Shopping | `pickup_shopping` · `issue_refund_button` (desc "issue refund") | 2 | `shopping_issue_refund` | out-of-stock handling |
| Shopping | `pickup_shopping` · `edit_text` / `barcode_input_text` (qty/PLU) | ~11 | `shopping_enter_qty` | weight/qty entry |
| Shopping | `pickup_shopping` · `item_self_help` (desc "help") | 1 | `shopping_item_help` | issue path |
| Chat | `dropoff_navigation`/`pickup_arrival`/`pickup_shopping` · `chat_button_internal` | ~10 | `open_chat` | opens customer chat |
| Chat | `*` · `message_input` (typed text present) | ~7 | `compose_message` | driver→customer message (PII) |
| Drop-off | `dropoff_pre_arrival` · `button_primary_action` (desc "got it") | 3 | `dropoff_got_it` | ack instructions |
| Drop-off | `dropoff_*` · `button_secondary_action` (desc "i need help") | 4 | `dropoff_need_help` | help/issue path |
| Drop-off | `dropoff_navigation` · `bottom_sheet_call_button` / `bottom_sheet_text_button` | 2 | `contact_customer` | call/text customer |
| Drop-off | `dropoff_pre_arrival` · `left_Photo_button` | 1 | `open_photo` | proof-photo entry |
| Rating flow | `pickup_pre_arrival`/`chat` · `buttonToggle_rating_good` + `button_submit` (desc "submit") + `end_chat_button` + `action_close_button` (desc "end") | ~10 | `rate_*` / `end_chat` | end-of-interaction rating |
| Idle controls | `waiting_for_offer` · `show_all_hotspot_button` (4), `secondary_action_button_dash_plus` (1), `go_back_button` ("don't switch zones") | ~6 | `hotspot_view` / `dash_plus` / `keep_zone` | idle/zone UI |
| Summary | `delivery_summary_collapsed`/`expanded` · `expandable_view` | 24 | `toggle_summary` | borderline — UI expand/collapse; classify low-priority or treat as chrome |
| Earnings | `earnings` · `expand_button` (desc "expand") | 1 | `expand_earnings` | low value |

## 2B. Click noise — navigation/map & generic chrome (do not act on)

| Cluster | Files | Why it's noise |
|---|---:|---|
| `maneuverView` on `dropoff_navigation` (23) + `pickup_navigation` (22) + `navigation_generic` (11) | **56** | Taps on the turn-by-turn maneuver banner — expands/collapses the route step list. No app-state meaning. **Largest single click-noise source.** |
| `my_location_button` (`dash_along_the_way`, `waiting_for_offer`) | ~5 | Re-center map. |
| bare `text:` nodes (no id, no text), descs "current dash" / "navigate up" | **~120** | The persistent "current dash" banner, the back/up affordance, and map/scroll-container taps. The single biggest *click* bucket and almost entirely chrome. |
| `torch switch` (desc), `keyboard_toggle`, `overlay_view`, `loading_layout`, `prism_sheet`, `itemMapView`, `imageView_item`, `side_nav_content_container` | ~30 | Flashlight, keyboard, scrims, map widgets, loading layers — UI chrome with no domain meaning. |

> Several of these would stop being captured the moment their screen is recognized (identity-dedup), so the window-rule work below also shrinks the click-noise tail.

---

# 3. Windows (screens)

**4,444 UNKNOWN files.** Family breakdown (refined classifier, all sessions):

| Family | Group | Files | Clusters |
|---|---|---:|---:|
| Shop-and-deliver (item/scan/weight/checkout) | **DD** | 1,394 | 209 |
| OTHER (tail — see §3.4) | ? | 527 | 82 |
| Customer chat conversation | **DD** | 457 | 70 |
| Loading / sign-in (transient) | noise | 290 | 73 |
| Drop-off workflow (photo/PIN/handoff) | **DD** | 275 | 28 |
| Lockscreen / always-on-display | **noise** | 269 | 111 |
| PRISM bottom-sheet dialogs | **DD** | 269 | 58 |
| Side-nav drawer / schedule | **DD** | 258 | 11 |
| Address / safety / self-help panel | **DD** | 182 | 86 |
| Navigation full-screen (maneuver) | noise* | 105 | 48 |
| Launcher / recents | **noise** | 77 | 13 |
| Off-target foreground apps (non-target packages) | **noise** | 146 | 39 |
| Camera / photo / receipt capture | **DD** | 72 | 13 |
| Media controls / notif shade | **noise** | 58 | 47 |
| Near-empty (<=6 nodes) | **noise** | 38 | 8 |
| Ratings / survey / stats | **DD** | 26 | 6 |
| **Recognizable DoorDash total** | | **~2,934** | |
| **System / foreign / transient noise** | | **~983** | |

\* navigation is *recognized* most of the time; these are frames that drop to UNKNOWN — see §3.3.

## 3.1 High-value recognizable screens — should classify

### Shop-and-deliver flow  ·  **1,394 files (the #1 window family)**  ·  HIGH value
The single biggest source of window UNKNOWNs. `pickup_shopping` (pri 72) requires the literal text **"Shop and Deliver"** + a tab layout — that only matches the shopping *landing* page. Every deeper screen in the flow lacks that text and falls through. These are stable, id-rich native screens — prime dedup candidates. Suggest a sub-family under `flow: task:pickup:arrived`, each `reject`-ing the others' anchors:

| Proposed screen | Distinctive signals (ids / text) | ~Files |
|---|---|---:|
| `shopping_item_detail` | `fragmentContainerView_shopDeliver` + text "item details", "scan item barcode", "item unavailable", "aisle N - section …", "$N\|" | ~300 |
| `shopping_item_list` | `item_timeline` + "add item", "# × items", "confirm quantity" | ~330 |
| `shopping_weight_entry` | `edit_text`+`end_button` + "review item" / "how many pieces" / "what's the total weight" / "lb" + desc increment/decrement | ~22 |
| `shopping_barcode_scan` | `fragmentContainerView_barcode` / `imageView_ItemCard` + "scan barcode" | ~70 |
| `shopping_substitution` | "wrong item scanned" / "customer requested" / "different size" / "substitution added to cart" | ~50 |
| `shopping_checkout` | "checkout", "go to any cashier lane", "order id: heb#", "take a receipt photo", `fragmentContainerView_genericCheckout` | ~22 |

```json
{ "id": "doordash.screen.shopping_item_detail", "priority": 72, "state": { "flow": "task:pickup:arrived" },
  "require": { "all": [ { "exists": { "hasIdSuffix": "fragmentContainerView_shopDeliver" } },
                        { "exists": { "any": [ { "hasText": "Item details" }, { "hasTextContaining": "scan item barcode" } ] } } ] },
  "reject": [ { "exists": { "hasText": "Shop and Deliver" } } ],
  "parse": { "as": "shopping_item" } }
```

### Customer chat conversation  ·  **457 files**  ·  HIGH value
The open conversation thread (substitution Q&A, gate codes, ETAs). Existing `chat` (pri 92, `allTextContainsAll:[dasher,messages]`) matches the chat **inbox list**, not a thread. The thread is reliably keyed by a single id.

**No flow/state (per developer):** chat is reachable from *any* lifecycle state — not dashing, idle, on a pickup, on a drop-off, even from a different/later task — and the screen carries **no indication** of which. So this rule must **declare no `state`/flow at all**; recognizing it leaves the current flow untouched (asserting a flow would corrupt the state machine). This matches the existing flow-agnostic screen rules (`chat`, `ratings`, `earnings`, `help`, menus). *Same principle applies to any from-anywhere overlay — menus, dialogs, the task list: recognize without asserting flow.*
```json
{ "id": "doordash.screen.chat_conversation", "priority": 92,
  "require": { "any": [ { "exists": { "hasIdSuffix": "ddchat_holder_base" } },
                        { "exists": { "hasIdSuffix": "inputChannelView" } } ] },
  "parse": { "as": "chat" } }
```

### Drop-off completion workflow  ·  **275 files**  ·  HIGH value  ·  **also an "arrived at drop-off" signal**
`drop_off_workflow_host_fragment` host with step screens: "take photo of drop-off location", "hand it to customer", "collect pin from customer / ask the customer for the unique 4-digit pin", "complete delivery". `dropoff_pre_arrival_completion` (pri 74) needs "Deliver to" + "Complete Delivery" — the deeper photo/PIN steps don't have "Deliver to" so they miss.

**Flow correction (per developer):** these steps only appear *once the dasher has reached the customer's door*, so they mean **arrived at the drop-off location** — they should carry `flow: task:dropoff:arrived`, **not** `task:dropoff:navigation`. Recognizing any of them is therefore itself a reliable "arrived at drop-off" signal (the navigation→arrived transition), mirroring the pickup side's `task:pickup:navigation` → `task:pickup:arrived`.

Propose three steps, all keyed on the host fragment + the distinctive step text:

| Proposed screen | Distinctive text | Value |
|---|---|---|
| `dropoff_photo` | "take photo of drop-off location" / "take a photo showing where you left the order" | proof-of-delivery |
| `dropoff_pin_entry` | "collect pin from customer" / "ask the customer for the unique" | hand-it-to-customer flow |
| `dropoff_handoff` | "hand it to customer" / "leave it at the door" + step content | delivery method |
```json
{ "id": "doordash.screen.dropoff_photo", "priority": 73, "state": { "flow": "task:dropoff:arrived" },
  "require": { "all": [ { "exists": { "hasIdSuffix": "drop_off_workflow_host_fragment" } },
                        { "exists": { "hasTextContaining": "photo of drop-off" } } ] },
  "parse": { "as": "dropoff_step" } }
```

### "Current dash / current task" task list — **this is the `timeline` screen** (recognition gap, not a new screen)  ·  ≥9 frames
Per developer, the task list *is* the `timeline` screen — confirmed in the data: a recognized `timeline` capture and these UNKNOWN frames share the **identical id-set** (`Artwork Image` / `action_bar_root` / `content` — it's a Compose screen with no distinctive resource-ids) and both contain "Current dash". The UNKNOWN frames are just the **task-list scroll/expansion state**: "Current task", "Pickup for {name}", "by {time} • {store}", "Deliver to {name}", "N min to complete", sometimes "Pause orders after delivery". The existing rule misses them because `allTextContainsAll:["dash ends at","pause orders"]` requires the dash-control header, which that scroll state doesn't show. (My earlier "41 files / `current_dash_tasklist`" was wrong — it came from the over-merged `{action_bar_root,content}` cluster; the real signal is ~9 frames, likely an undercount of timeline scroll-states that miss.)

**Fix = broaden the existing `timeline` rule**, e.g. accept the task-list state as an alternative anchor:
```json
{ "id": "doordash.screen.timeline", "priority": 10, "state": { "flow": "idle" },
  "require": { "any": [
    { "allTextContainsAll": ["dash ends at", "pause orders"] },
    { "allTextContainsAll": ["current dash", "current task"] } ] } }
```
**Flag (your area):** these task-list frames appear *mid-task* (active pickup/deliver rows), yet `timeline` is `flow: idle` — so broadening recognition here interacts directly with the "keep active task through a transient idle (timeline round-trip)" fix (#274, memory `project_timeline_fix_and_r0_queue`). Decide the flow treatment with that in mind; I'm not prescribing a flow change.

### End-dash confirmation dialog  ·  **28 files**  ·  HIGH value
PRISM sheet "End your current dash? / End dash / Go back". Pairs with the `end_dash` click; recognizing the dialog gives a clean dash-ending signal.
```json
{ "id": "doordash.screen.end_dash_confirm", "priority": 51,
  "require": { "all": [ { "exists": { "hasIdSuffix": "prism_sheet" } },
                        { "exists": { "hasTextContaining": "End your current dash" } } ] } }
```

## 3.2 Other recognizable screens — should classify (compact)

| Proposed screen | Signals (ids / text) | Files | Value / reason |
|---|---|---:|---|
| `side_nav_drawer` | `side_nav_content_container` + menu text (schedule/account/ratings/earnings/promos/help) + desc "Side menu" | 258 | Drawer open over home; classify to dedup + know menu state |
| `dash_schedule` | `side_nav_content_container` + "this week"/"start"/"available"/"scheduled"/"start around N am"/time slots | 24 | Dash scheduling; existing `schedule` rule's anchors are too narrow |
| `address_detail_panel` | `address_line_1` / `address_instructions_view` / `action_dasher_safety` / `action_self_help` | 182 | Expanded delivery address + instructions + safety/help |
| `camera_capture` | `camera_preview`/`capture_button`/`image_capture_view`/`image_preview`/`education_photo_receipt_screen`/`btn_retake_photo`/`pizza_bag_uploading_*` | 72 | Proof / receipt photo viewfinder + review/retake |
| `pickup_issue_menu` | "I have an issue"/"select an issue"/"what pickup issues can we help with"/"store related"/"helpful resources" | 76 | Pickup self-help / issue reporting |
| `send_intro_message` | `prism_sheet` + "introduction texts can help boost your ratings"/"send this intro"/"don't send" | 30 | Pre-shop intro prompt |
| `delivery_feedback` | `prism_sheet` + "how did this delivery go?"/"app issues"/"submit"/"confirm order was completed" + thumbs | 28 | Post-delivery feedback |
| `qr_scan_confirm` | `prism_sheet` + "confirm that the code was scanned successfully"/"scan code again"/"{retailer} requires scanning" | 23 | Retail QR pickup (e.g. PacSun) |
| `pay_adjusted` | `prism_sheet` + "we have adjusted your pay"/"# items have been added"/"got it" | ~21 | Pay-change event |
| `pickup_confirm` | `confirm_pickup_button` + `drop_off_container` + `collapsingToolbar_navBar` | ~27 | Pickup confirmation/order details |
| `ratings_detail` | `current_rating_value_text_view` / `chart` (+ "rating") / `btn_submit_survey` | 26 | Ratings stats / survey; extend existing `ratings` |
| `sign_in` | `progress_message` ("Signing in…") + `image_logo` + `cancel_button` | (in Loading) | Recognizable startup state; existing `app_startup` wants "Starting…" not "Signing in…" |

## 3.3 Navigation frames (recognized → UNKNOWN)  ·  105 files

`navigation_generic` (pri 95) requires text **"min" + "exit" + ("mi"|"ft")**. Full-screen nav frames that momentarily lack the ETA/"exit" strip (e.g. just the maneuver banner + map, or a reroute) drop to UNKNOWN. These aren't a *new* screen — recommend **broadening the nav anchors** (e.g. accept `maneuverView`/`mainManeuverLayout` id presence) rather than adding a rule, so they re-join `navigation_generic` and dedup. Borderline noise: low intrinsic value, but cheap to absorb.

## 3.4 The "OTHER" tail (527 files, 82 sigs) — what's actually in it

Decoded top signatures: ~**231 "content"-only framework windows** (tiny `android` pkg dialogs/toasts, often 7–11 nodes — transient, **noise**); ~**76 empty DoorDash shells** (`action_bar_root\|container\|content\|fragment`, 7 nodes, no text — mid-load **noise**); plus *more recognizable DoorDash* that the family pass didn't anchor: `confirm_pickup_button` order-details (~27), `education_photo_receipt_screen`/`btn_retake_photo` (~12, → `camera_capture`), `dxdr_nav_host_fragment` loading (~18), generic `actions_group`+`btn_primary_action`/`btn_secondary_action` action screens (~28), `pizza_bag_uploading_*` (4), and `Artwork Image` media (~23, **noise/foreign**). Net: the tail is roughly half transient/system **noise**, half additional recognizable DD screens already covered by the proposals above.

## 3.5 Window noise — should never reach the classifier

| Family | Files | Package(s) | Why it's noise / proposed handling |
|---|---:|---|---|
| Lockscreen / always-on-display | 269 | `com.android.systemui` | `ambient_indication`, `auth_ripple`, `burn_in_layer`, `alarm_text_view`. Captured **while `[DoorDash:Offline]`** (phone locked). **Gate by package.** |
| Launcher / recents | 77 | `…nexuslauncher` | `task_thumbnail`, `overview_panel`, "clear all". App switcher. **Gate by package.** |
| Off-target foreground apps | 146 | various non-target packages | Whatever app happens to be foreground is captured in full — including personal apps. **Privacy issue + noise. Gate to `com.doordash.*` / `com.ubercab.driver` only.** |
| Media controls / notif shade | 58 | systemui | `actionPlayPause/Next/Prev`, `actions_container`+`app_name_text`. **Gate.** |
| Near-empty / content-only / empty shells | ~345 | android / doordash | Transient mid-transition & loading frames (≤6–11 nodes, no content). Mostly eliminated once destination screens are recognized (identity-dedup). |
| Loading frames | ~290 | doordash | `progress_bar`/`loading_indicator`/blocking overlays. Transient (the `sign_in` state is the one worth keeping). |

> **The biggest single noise lever is a package gate.** Lockscreen + launcher + foreign apps + media ≈ **550 files** that are captured purely because the accessibility service sees *every* window. Restricting window classification/capture to DoorDash/Uber packages removes them outright and closes a privacy gap (personal-app UI trees are currently written to disk). This is a pipeline change, not a ruleset change — flagged here as a recommendation, not an edit.

---

# 4. Consolidated noise summary

"Noise" = captures that carry no domain signal and should be **dropped before capture** (not classified). Reasons fall into four buckets:

| Bucket | Source(s) | ~Files | Reason it's noise | Cleanest handling |
|---|---|---:|---|---|
| **Off-target packages** | window | ~492 | Lockscreen/AOD (269), launcher/recents (77), foreign apps (146 files, various non-target packages) — the a11y service sees every window. Also a **privacy gap**. | Package gate: classify/capture only `com.doordash.*` / `com.ubercab.driver`. |
| **System chrome** | window, click | ~120 | Media-player controls + notification shade (58); map widgets (`maneuverView` 56, `my_location`, torch, keyboard) | Package/structure gate; or recognize-and-ignore. |
| **Transient frames** | window | ~635 | Loading/sign-in frames (~290), content-only framework dialogs (231), empty shells (76), near-empty ≤6 nodes (38) — snapshots mid-transition. | Mostly auto-resolved once destination screens are recognized (identity-dedup); a min-node / settle-delay guard would catch the rest. |
| **Re-posted / empty notifications** | notification | ~16 | `NAVIGATION_NOTIFICATION_CHANNEL` empty re-posts (~1/s; the classifier churns even though disk dedups), empty `__0.json` update/cancel events | Channel/empty-text drop gate before classification. |
| **Low-value UI toggles** | click | ~24 | `expandable_view` summary expand/collapse, `expand_button`, hotspot/zone fiddling | Optional: classify at low priority or treat as chrome. |

**Why the recognized window/click folders are *not* in this list:** I validated the capture bus already limits them. The identity-dedup (`AccessibilityPipeline.kt:131‑144`) + content-hash dedup (`DiskCaptureBus.kt:54‑56`) mean a recognized screen is written once per content-state, not once per drip — confirmed in the logs (`pickup_pre_arrival` re-classified ~6× in 1.5 s, `captured=true` once). The volume problem lives entirely in the UNKNOWN buckets.

---

# 5. Recommendations (proposals only — nothing applied)

Framed as options for you to decide on; this report does not change any rule or code.

**A. Ruleset additions (the requested deliverable).** Highest value × volume first:

1. **Notifications by `channelId`** — ~250 of 301 files, ~12 rules, zero code change. Start with `new_order_v2` (110), `customer_message`, `order_ready`, `earnings_deposit`, `arrived_in_zone`.
2. **Shop-and-deliver screen family** — ~1,400 files; biggest window win and it collapses the count via dedup.
3. **Customer chat conversation** (`ddchat_holder_base`) — 457 files.
4. **Drop-off completion workflow** (photo / PIN / handoff) — 275 files; carries `task:dropoff:arrived` (it's also the "arrived at drop-off" signal) and unblocks the completion *clicks* that are UNKNOWN because their screen is under-recognized.
5. **Completion + photo + arrival clicks** — `complete_delivery_nav`, `take_photo`, arrival `primary_action_button`. **Do these *after* the screen work above** — `screenIs` scoping depends on the new screen rules, so the click defs will shift. (`stop_orders_after_delivery` is valid but a single, unconfirmed capture — defer.)
6. **Broaden `timeline` to catch the task-list scroll state** + add `end_dash_confirm` — small, high-signal, tied to active timeline/dash-end work (the task-list frames are timeline misses, not a new screen).
7. Remaining dialogs/menus/screens in §3.2 and the click table in §2A.

**B. Pipeline-level (separate from rulesets, would need code — flagged, not proposed as edits):**

- **Package allow-list gate** before window classification/capture → removes ~490 off-target files and closes the personal-app privacy leak. Single biggest noise reduction.
- **Notification drop gate** for empty-text / `NAVIGATION_NOTIFICATION_CHANNEL` re-posts → stops the ~1/s classifier churn.
- **Consider updating `lastIdentity` for UNKNOWN too** (or a per-window settle delay / min-node threshold) so a stream of transient unknown frames doesn't each get saved. This is the structural root of the count explosion for any not-yet-recognized dynamic screen.

**Net:** the ruleset work (A) reclassifies the bulk of the 5,210 files and shrinks future capture volume through dedup; the gates (B) remove the irreducible system/foreign noise that no rule should ever match.

---

# Appendix — methodology & reproducibility

- **Inputs:** 5,210 `UNKNOWN` JSON envelopes (`{captureId, pipelineId, schemaId, timestamp, platform, classificationName, payload}`) across 8 session-days; window payload = raw `UiNode` tree, click payload = `{node, screenTarget}`, notification payload = `{title, text, bigText, channelId, …}`.
- **Existing ruleset surveyed:** `core/pipeline/src/main/assets/rules/doordash.json` (51 rules) + `uber.json` (32 rules); predicates confirmed in `RuleCompiler.kt` (incl. `channelIdEquals`/`channelIdContains`, `hasIdSuffix`, `hasText*`, `allTextContains*`).
- **Capture mechanism refs:** `DiskCaptureBus.kt:42‑77` (path + per-bucket content-hash dedup), `AccessibilityPipeline.kt:131‑144` (identity dedup; `lastIdentity` only set for recognized), `NotificationPipeline.kt:79‑85`, `ObservationClassifier.kt` (`SCREEN:`/`Captured screen` log lines).
- **Analysis scripts** (read-only, in `/tmp`, not committed): `cluster_unknowns.py` (id-suffix-set fingerprint + text fallback → clusters), `family_classify.py` (window family/noise bucketing + foreign-package tally), `rule_sigs.py` (compact dump of existing rule predicates), `paths_for.py` (representative-file locator).
- **Caveats:** (1) per-cluster counts include **cross-folder duplicate captures** — treat as relative; (2) family bucketing is heuristic — anchors in every proposed rule should be confirmed against 1–2 live captures before implementation; (3) Uber UNKNOWNs are sparse here (windows mostly via the separate SYSTEM_ALERT_WINDOW path) and were only lightly covered.




