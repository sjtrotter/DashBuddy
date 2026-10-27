## 2026-06-12 — DoorDash session (offer-copilot field test)

- **Platform tested:** DoorDash
- **Branch under test:** `master` field offer-copilot build (includes the #425 rule-bound
  Accept/Decline path; the freshest watch-list items reference #437/#436/#427). Exact SHA not
  captured this session — developer to correct if needed.
- **Field conditions:** **two dashes this session** (a first dash, then a second later the same day).
  At least one Shop & Deliver order (HEB, 25 items), offers accepted from the bubble, and two
  separate deliveries on the second dash. Observations narrated in real time; everything below is
  **recorded for triage — hypotheses, not concluded fixes.**

### Bugs

#### 9. "Saved: $X" earnings bubble omits the `$` sign (currency not formatted as dollars)
Dasher flagged the post-delivery **"Saved"** bubble's amount "doesn't [render] as a dollar amount"
and asked whether it was already fixed. **It is not.** Desk finding:

- The "Saved" bubble builds its text with a **local** `formatCurrency` in the state layer —
  `EffectMap.kt:704/710` call `formatCurrency(...)`, defined at `EffectMap.kt:870-871` as
  `String.format(locale, "%.2f", amount)`. That format **has no `$`** — so the bubble renders
  `Saved: 5.50`, not `Saved: $5.50`, even though the method's own docstring describes the output as
  `"Saved: $X"` (`EffectMap.kt:666`).
- It **does** zero-fill the cents (`%.2f` → `5.5` becomes `5.50`), so if the dasher saw a missing
  trailing zero that'd point elsewhere — **capture the exact on-screen string** next time to settle
  which symptom it is. Most likely the perceived defect is the **missing `$`**.
- **SSOT smell (#358 family):** this is a **separate, divergent currency formatter** from the
  app-wide `DashFormats.money` (`DashFormats.kt:25`, `"$%.2f"`, which *does* include the `$`). The
  docstring even flags it as a "known wart" pending #366 (move rendered copy out of the state layer).
  **Hypothesis:** the clean fix routes the bubble copy through the one money formatter rather than a
  local `%.2f`, but where the formatter can live is bound up with #366 — a desk call, not a drop-in.

#### 11. **FOUND BROKEN** — the notification-SHADE Accept/Decline buttons don't work (the in-bubble ones do)
**Surface taxonomy (dasher clarified — investigate the right one):**
- ✅ **In-bubble buttons** — the Accept/Decline buttons inside the **expanded bubble** (the in-app
  Compose `OfferActionRow`) **work** (entry #1: bubble Accept clicked DoorDash).
- ❌ **Notification-shade action buttons** — the Accept/Decline buttons on the **heads-up / shade
  notification** are what the dasher tapped, and **neither did anything** on DoorDash. **This is the
  broken surface.** Moves the notification half of the #425 checklist item to broken.
- ℹ️ Side note: the bubble's own (collapsed/conversation) notification "doesn't have buttons on it" —
  so the buttons the dasher pressed were unambiguously the **shade notification's action buttons**,
  not the in-bubble ones.

The two surfaces are NOT the same code path at the click edge even though they share the dispatch:

- **What's the same (so it's not the wire string):** the notification's Accept/Decline send
  `OfferIntent.ACCEPT`/`DECLINE` = `"accept_offer"`/`"decline_offer"`
  (`BubbleManager.kt:216-217`, `OfferIntent.kt:10-11`) — the **identical** strings the bubble
  dispatches — and `OfferActionReceiver` then dispatches the **same** `Observation.UiInput`
  (`OfferActionReceiver.kt:32-39`). So EffectMap's offer-action handling isn't the divergence.
- **What's different (the hypothesis):** the receiver is **manifest-registered**
  (`AndroidManifest.xml:80-81`) and the PendingIntent is an **explicit, same-app broadcast** with
  `FLAG_IMMUTABLE` (`BubbleManager.kt:238-245`), so it *should* reach `onReceive`. The likely break
  is **downstream of dispatch**: acting from the **system notification shade**, DoorDash's offer
  button isn't reachable to the accessibility click the way it is when the **in-app bubble overlays
  the live offer** — the shade (or an advanced/expired offer) is foreground, so
  `UiInteractionHandler`'s all-windows click finds **no live target** and aborts to manual. Same
  `Could not find any live node` *class* as the 06-09 bubble bug, but a different cause (wrong
  foreground window, not wrong-window search). The in-bubble buttons working fits this: the bubble is
  drawn over the live offer, so the target window is present.
- **One log line splits the two hypotheses (desk):** pull logcat around the taps. If
  `OfferActionReceiver: accept_offer` / `decline_offer` is **absent**, the broadcast never landed
  (PendingIntent/registration). If it's **present** but followed by a target-resolution failure /
  `Could not find any live node`, it's the click-target-absent path. Capture which.
- **Field note for next time:** retry the **shade** action **while still on the DoorDash offer
  screen** (shade pulled down over the live offer, offer not yet expired) and see if it behaves
  differently from tapping it after navigating away.

### Verification TODOs (confirmations this session)

#### 1. Bubble **Accept** clicks DoorDash — #425 rule-bound tap CONFIRMED (1/2)
Dasher tapped **Accept** in the bubble and DoorDash registered the Accept (offer accepted). This
is the first clean live confirmation of the `acceptButton`/`declineButton` rule-bound, label-verified
tap path (#425) — the same surface that was **broken on 06-09** (`Could not find any live node`,
wrong-window search). Bumped the checklist item to 1/2; still want a second sighting covering the
**Decline** side and the **notification** surface (not just bubble Accept).

#### 2. Post-delivery earnings **auto-expand** — #425 EXPAND_EARNINGS + #417 gate CONFIRMED (2/2, both retired)
The collapsed pay breakdown **auto-expanded on its own** after a delivery, as before. Confirms the
app-owned EXPAND_EARNINGS action path (#425) AND — implicitly — the live consent gate (#417): a
denied capability would have left it collapsed (`Denied expand_earnings — no granted capability`),
so the asset-rule auto-grant is covering it. **Dash 1:** expand fired + bubble Accept (#1) fired —
both gated taps with no regression. **Dash 2 (2026-06-12):** the post-delivery expand auto-tapped
**again** — second clean sighting. Two independent dashes of gated automation taps firing with zero
`Denied`/throttle → both the **#425 expand-earnings** and **#417 consent-gate** checklist items moved
out as validated (the gate reliably auto-grants bundled-rule capabilities).

#### 3. Bubble re-attaches to the active dash across a restart — #437 CONFIRMED (2/2, retired)
**Dash 1 (2026-06-12):** force-quit + reload **after resetting the accessibility permission** mid-dash
— a harder restart than a plain force-stop, since the accessibility service rebinds from scratch —
reloaded the bubble with the **current dash still active**. **Dash 2 (2026-06-12):** after a restart
the **completed cards repopulated** (not just the dash reading active) — the missing half of the
first sighting. Two clean sightings of the mid-dash active-dash re-attach → #437 moved out as
validated. The bubble's dash id derives from restored state, not the crash-suppressed effect.
**Caveat — does NOT cover the no-active-dash case (see investigation #8):** both #437 sightings were
restarts **while a dash was active**. Separately this session, after a **crash with no active dash**,
the bubble **cleared out** instead of showing the last dash — that's a distinct gap tracked as #8,
not part of #437's validated scope.

#### 4. Shop & Deliver terminal `total/total` — #302 CONFIRMED (2/2, retired)
The pickup/shop card read **`shop 25/25 · 0.6/min`** at end of shop — the terminal `total/total`
frame, no longer the `total−1/total` off-by-one. Second clean sighting → #302 moved out of the
watch list as validated. (The add-on / second-order-mid-shop case is still tracked under #276.)

#### 10. "Saved" fires once per delivery (#431 sub-case a, 1/2) + offers feel instant (#436 partial)
**Dash 2** had two separate deliveries and each produced **exactly one "Saved" bubble** — no
double-fire. Confirms #431 receipt-grace sub-case (a) ("Saved" fires exactly once per delivery);
bumped that checklist item to 1/2. The (b) stacked-order split and (c) misrecognition-survival
sub-cases were not exercised. (The bubble's currency formatting is bug #9 above — separate from the
once-per-delivery behavior, which is correct.) Separately, the dasher reports offer **Accept/Decline
feels fully quick — no perceptible delay** (loosely supports #436 (b) "verdicts land a touch
quicker"); #436 stays 0/2 since its dedupe/restart sub-cases weren't deliberately tested.

### Open questions / investigations

#### 12. 7-Eleven alcohol pickup: initial pickup screen UNKNOWN, but self-corrected to drop-off on nav (cross-refs #149/#433)
On a **7-Eleven alcohol** order (~**19:59 local**), the **initial pickup screen was not recognized**
(UNKNOWN — no pickup classification). The dasher picked the order up anyway and watched whether it
would recover: **as soon as the screen changed to the map (drop-off navigation), the app "automatically
knew" it was on drop-off** and corrected itself.

- **What this tells us:** the **state machine recovered via the nav/flow transition** — even though
  the pickup-confirm screen itself didn't classify, the drop-off-nav frame did, and the platform
  region advanced to DROPOFF. Good resilience signal (the missed pickup frame didn't strand the task).
- **The gap to chase:** the **7-Eleven (alcohol) pickup/confirm screen is an unrecognized variant**.
  Two ties: #433 notes `pickup_picked_up` had **zero corpus** and could never match before its
  mojibake fix — this may be exactly that screen failing to classify on a 7-Eleven layout; and #149
  is the alcohol ID-verify flow. **To confirm:** pull the ~19:59 capture for this task — expect an
  UNKNOWN around the pickup/confirm step; if so it needs a rule/corpus addition for the 7-Eleven
  (and possibly alcohol) pickup variant. **Capture is the unblock here** — this screen has little/no
  corpus.
- **Did NOT observe:** whether the missed pickup screen cost anything downstream (arrival/confirm
  timestamps, the pickup card's store/items) — worth checking the event DB for this task vs a clean
  pickup.

#### 5. Transient **double drop-off card** during the at-door window (cross-refs #297)
On the **Great Greek Mediterranean** delivery the dasher saw **two drop-off cards** while at the
dropoff; **after** completing it and getting the **paid (PostTask) card, only one remained.** No
crash. The dasher's read — "maybe it auto-corrected" — matches a desk hypothesis:

- **Likely cause (hypothesis):** the card stack is `completed` (folded from the event log) + one
  `active` live card. On an **arrival-bearing dropoff** (Great Greek is a hand-it-to-customer
  restaurant order, so `DELIVERY_ARRIVED` fires), `FlowCardMapper` **closes the delivery into
  `completed`** on `DELIVERY_ARRIVED` (`FlowCardMapper.kt:216-239`) — a frozen card with id
  `delivery:<taskId>`. Meanwhile the state machine is still in `TaskDropoffArrived`, so
  `LiveCardBuilder` **also builds an active Delivery card** for the same task
  (`LiveCardBuilder.kt:80-92`), same id `delivery:<taskId>`.
- **Why no crash (and why two cards show):** the active card is keyed `"live:${live.id}"` while the
  completed card is keyed `it.id` (`BubbleScreen.kt:238` vs `:256`). The `live:` prefix means the two
  keys **don't collide** — so #297's fatal duplicate-key crash is avoided (good — #297 holds) — but
  the frozen + active cards for the **same stop render as two visible cards** during the at-door
  window.
- **Why it resolves to one:** on payment, `DELIVERY_COMPLETED` adds the PostTask card and the flow
  moves to `Flow.PostTask`, so `LiveCardBuilder` now emits an **active PostTask** card (not a
  Delivery). The duplicate active Delivery card disappears, leaving the single frozen Delivery card
  — exactly the "only one now" the dasher saw.
- **To confirm:** pull the Jun-12 event DB for the Great Greek task — expect `DELIVERY_ARRIVED`
  **before** `DELIVERY_CONFIRMED`/`DELIVERY_COMPLETED` (arrival-bearing). If confirmed, this is a
  **cosmetic transient** (a frozen+live overlap), distinct from #297's crash. One direction to weigh
  would be suppressing the frozen `completed` card whose id matches the current `active` card's id, or
  not closing the Delivery into `completed` until `DELIVERY_CONFIRMED` — but that's a design call for
  the desk, **not** a concluded fix.

#### 8. Bubble clears instead of showing the last dash — after a crash AND after a normal dash-end (cross-refs #437, #367)
Distinct from #3 (which was a re-attach **while the dash was still active**). This item now has **two
triggers**, and a second sighting makes it look like a **real gap, not just transitional**:
- **8a — after a crash with no active dash (earlier this session):** the bubble **cleared out**
  (empty chat + empty card stack) instead of showing the most-recently-completed dash.
- **8b — after a normal dash END (2026-06-13 ~late):** ending a dash **again cleared the cards**. The
  dasher's read: "we might be in a state where it's just not displaying the last dash … might just be
  we're in transition." Desk finding below suggests it's the **same root cause as 8a**, not a benign
  transition.

- **Likely cause (hypothesis, same for both triggers):** the bubble's `displayedDashId` is a purely
  **in-memory latch** — `bubbleManager.activeDashId.scan(null) { last, current -> current ?: last }`
  (`BubbleViewModel.kt:95-96`). `activeDashId` is null whenever there's no live session
  (`AppState.activeSessionId()` returns a session id only while `session != null`,
  `AppState.kt:48-52`). The `scan` holds the last **non-null** active id — but it gets **reset to
  null** two ways: (8a) **process death** wipes the scan; (8b) the downstream `messages`/`cardStack`
  flows are `SharingStarted.WhileSubscribed(5000)` (`BubbleViewModel.kt:104,128`), so when the bubble
  is collapsed / has no subscribers for >5s and then **re-subscribes after the dash has ended**, the
  `scan` chain is torn down and **restarts from `null`** while `activeSessionId()` is already null.
  Either way `displayedDashId` → null → `messages` empty (`:98-104`) and `cardStack.completed` empty
  (events query returns empty for a null dash id, `:119-122`) — the bubble empties.
- **The code comment claims a fallback that isn't implemented:** `BubbleViewModel.kt:106-107` says the
  card stack uses "the current active dash when one is running, **otherwise the most-recently-completed
  one**" — but there's **no persisted** last-completed-dash-id feeding `displayedDashId`; the only
  "otherwise" is the in-memory `scan` latch. So the documented "review the previous dash until you go
  Online again" behavior holds **only** as long as the scan survives — and it does **not** survive a
  crash (8a) **nor** a post-dash-end bubble re-subscribe (8b). The 8b sighting **contradicts** the
  earlier "survives a normal idle transition" assumption.
- **To confirm / one direction to weigh (desk, not a fix):** for 8b, end a dash, collapse the bubble
  >5s, reopen → check whether cards/chat are gone and whether `activeDashId.value` is null. A durable
  fix sources `displayedDashId`'s fallback from a **persisted** "most-recently-completed dash id"
  (DB/datastore) instead of the in-memory `scan` — overlaps with #367 post-dash HUD persistence.

### Research / design (improvement ideas — explore, not yet scoped)

#### 6. Offer & finished cards under-surface Shop & Deliver (item count + type badge)
Two related gaps the dasher noticed about the #324 card redesign and the completed-card stack:

- **Item count is buried on the offer card.** Today the count renders only as a small footer
  caption — `FlowCardItem.kt:392-401` builds `"$store · $itemCount items"` via `Caption(...)`, and
  only when `itemCount > 1`. For a Shop & Deliver, the dasher wants the item count promoted to the
  **same visual tier as the $/hr hero and the mi / $/mi line** (the offer hero `Row` at
  `FlowCardItem.kt:298-340` is score-ring + `$/hr` hero + a secondary `Net · mi · $/mi` line). The
  data already exists on the snapshot (`FlowCardSnapshot.Offer.itemCount`,
  `FlowCardSnapshot.kt:57`) — **hypothesis:** this is a pure presentation change (elevate item count
  into the hero row), no parser/state work needed. Dasher noted there's **space on the right** of
  the hero row to place it.
- **No Shop & Deliver indicator / badge, and the finished card doesn't show the type at all.**
  The offer card has a badge pill row (`FlowCardItem.kt:379-390` + `badgeMeta` `:421-433`), but
  `badgeMeta` has **no Shop & Deliver / shopping entry**, and the **PostTask (finished) card carries
  no order-type or activity field whatsoever** — `FlowCardSnapshot.PostTask`
  (`FlowCardSnapshot.kt:124-135`) has `storeName`/`totalPay`/`parsedPay` but nothing that says "this
  was a shop." (The `SHOPPING` activity only lives on `FlowCardSnapshot.Pickup.activity`,
  `:94`.) **Hypothesis:** surfacing "Shop & Deliver" on the finished card would need an order-type/
  activity field threaded onto `PostTask` (and possibly `Offer`), i.e. it's **not** purely cosmetic
  — it touches the snapshot model + the mapper (`FlowCardMapper`/`LiveCardBuilder`), not just
  `FlowCardItem`. Would need to confirm where order type is known at PostTask time. A SHOP badge in
  `badgeMeta` for the offer card, by contrast, is cheap **if** a shop badge/flag reaches `Offer.badges`.

#### 7. Pickup card not visually upgraded to the redesign + double-"by" wording
The dasher reads the pickup card as **"still the old style"** next to the redesigned offer card —
it's the line-based `DeadlineBody` (`FlowCardItem.kt:484-582`): a `HeroBig` countdown + caption rows
(`HEB · arrived 16:39 · shop 25/25 · 0.6/min`), with none of the offer card's ring/banner/pill
visual language. Two sub-items:

- **Visual parity.** Pickup (and by extension Delivery, which shares `DeadlineBody`) didn't get a
  comparable redesign pass. **Hypothesis:** this is a deliberate-or-not gap from the #324 redesign
  (which targeted the offer card); worth deciding whether the pickup/delivery cards should adopt the
  same component vocabulary (gauge ring for deadline pressure, etc.) or stay deliberately minimal.
- **Double "by".** The deadline caption renders **`till pickup-by · by 17:10`** — `deadlineLabel =
  "till pickup-by"` (`FlowCardItem.kt:454`) concatenated with `Caption("$deadlineLabel · by
  ${formatTime(deadlineMillis)}")` (`:512`). The two "by"s read awkwardly; trivial wording fix
  (drop one "by", e.g. `"pickup by 17:10"` or `"till pickup · by 17:10"`).
