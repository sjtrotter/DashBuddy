## 2026-05-19 — DoorDash session (live capture during dash)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `b282a3d` (post-#263 merge — `fix/pickup-arrival-storename-scope-to-contact-card`; also includes #261 confirm-decline click fix and #262 stacked-pickup task-mint fix)
- **Field conditions:** developer dashing on DoorDash; entry captured live while at the first pickup (Whataburger) at ~17:43 Central. Notes are about the active Pickup card in the bubble HUD.

### Bugs

#### 1. Pickup card still doesn't surface the actual pickup-by wall-clock time (still open from 2026-05-17 #2)

- **Field observation:** En-route-to-pickup HUD continues to show only the relative countdown ("till pickup-by") with no wall-clock anchor anywhere on the active card. Dasher still can't answer "what time do I need to be checked out by?" from the card alone.
- **Status:** Shipped in #271 (2026-05-20).
- **Prior status (at log time):** This is the same gap logged as #2 on 2026-05-17 — no code changes have shipped to the active-card branch of `FlowCardItem.kt:351-356` since then. Re-logging because the field discomfort persists (and it directly compounds bug #2 below — if the wall-clock deadline were on the card, the dasher would have caught the corrupted countdown in #2 instantly).
- **Re-affirms the prior sketch:** add a `"by ${formatTime(deadlineMillis)}"` secondary caption beneath the countdown. Same shape applies to the Delivery card (`FlowCardItem.kt:312-325`).

#### 2. Pickup card hero shows a ~24-hour ghost countdown ("1434:38") once the pickup-by deadline has been passed

- **Repro:** Arrive at a pickup store after the pickup-by deadline has already passed. Look at the bubble's Pickup card hero.
- **Observed:** At Whataburger at ~17:43 Central, with pickup-by actually at 17:38 (≈5 min past deadline), the active Pickup card showed **"1434:38"** under "till pickup-by". 1434 minutes is 23h 54m — almost exactly one day. The dasher reaction: "??? not sure what that's supposed to mean."
- **Status:** Shipped in #267 (2026-05-20).
- **Hypothesis (from a desk read, not verified against field logs):**
  - The arithmetic is suspiciously clean: 24h − 5m 22s = 23h 54m 38s = **1434m 38s**. Treating "1434:38" as minutes-and-seconds (the output shape of `formatCountdown` at `FlowCardItem.kt:555-560`), this is what you'd see if `deadlineMillis` was anchored to **tomorrow 17:38** instead of today's missed 17:38.
  - `TransformRegistry.kt:265-297` (`parseDeadlineMillis` → `parseTimeTextToMillis`) parses the screen text "Pick up by 5:38 PM" into a `LocalTime` and then resolves it to a `Calendar`. Line 295: `if (target.timeInMillis < now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)`. So any deadline that has already passed at parse time gets pushed forward a full day. This logic is correct for offers (where the deadline is always in the future at receive time) but wrong for in-progress pickups where the deadline has been blown.
  - `PlatformRegionStepper.kt:461` re-writes `deadlineMillis = taskFields?.deadline?.time ?: currentTask.deadlineMillis` on **every** same-phase observation. So once the dasher's clock crosses the deadline, the next pickup-screen parse re-resolves "Pick up by 5:38 PM" → tomorrow 17:38, and the freshly parsed value clobbers the previously-correct one on the active `Task`.
  - End result: `FlowCardItem.kt:351-355` reads `deadlineMillis - now` ≈ +86,078,000 ms, feeds it to `formatCountdown` which prints `1434:38`. No "ahead/late" label because this is the active-card branch, not the frozen-card branch.
- **What would confirm or refute this at the desk:**
  - For today's Whataburger pickup, pull the `PICKUP_NAV_STARTED` / `PICKUP_ARRIVED` events around 17:38–17:45. Check the `deadlineMillis` value on each: expected to be ≈ today 17:38 in early events, then flip to ≈ tomorrow 17:38 on the first event re-parsed after 17:38 passes.
  - Confirm the screen text DoorDash was actually rendering at that time matches "Pick up by 5:38 PM" (i.e. the platform kept the original deadline on screen rather than auto-extending it — if DoorDash itself bumped the deadline, the bug is elsewhere).
- **Possible directions (sketches only, defer to desk review):**
  - *A — kill the day-rollover for active tasks.* Let `deadlineMillis` go negative when past; render `Xm late` (red) on the active card. Trivially small parse-side patch; matches the frozen-card branch's existing "X late" handling at `FlowCardItem.kt:362-363`. Risk: an offer received late at night for a tomorrow-morning deadline (does this exist on DoorDash?) would now resolve to "this morning" and read as already-late.
  - *B — clamp the rollover.* Only roll forward if the past-gap is small (e.g. < N minutes), so a 5-min-late deadline stays late but a 23h-stale "5:38 PM" rolls forward. Picks a threshold out of thin air; brittle.
  - *C — pin the deadline at first parse.* Make `PlatformRegionStepper.kt:461` keep the existing `deadlineMillis` instead of overwriting (or only overwrite when the new value is meaningfully different, e.g. > 1 min delta). Treats the deadline as set-once. Risk: if DoorDash legitimately extends a deadline mid-pickup, we'd miss it.
  - The A+C combination is probably the cleanest: parse honestly (no rollover for past times), and only update the stored deadline when the new parse genuinely differs. But that's two decisions, not one — desk-side call.
- **Why this matters now:** combined with #1 (no wall-clock anchor on the card), the dasher has no way to sanity-check the countdown. "1434:38" alone reads as a render bug; "1434:38 by 5:38 PM" would have read instantly as a stale-deadline interpretation problem.

#### 3. Frozen Drop-off card never transitions to a sensible final state — keeps showing "—" / "till deliver-by"

- **Field observation:** When the Drop-off card does freeze (currently end-of-dash, per 2026-05-17 #3), the frozen card body still reads `—` as the hero with caption `till deliver-by` — i.e. the same shape as an active card with no countdown, not a closed/summary shape. Dasher's preferred direction, verbatim: "it should show the time the dropoff started vs completed like the pickup blocks." The Pickup cards' tertiary row reads "arrived 17:43 · picked up 17:51" — that "what happened and when" framing is what's missing on the Drop-off side.
- **Status:** Shipped in #269 (2026-05-20).
- **Hypothesis (from a desk read, not verified against field logs):**
  - `FlowCardItem.kt:357-369` is the frozen-card branch of `DeadlineBody`. It computes the hero from `arrivalRemaining = deadlineMillis - arrivedAt`. If `arrivedAt == null` the whole branch falls to `HeroBig("—") + Caption(deadlineLabel)` at `:367-368`. That's the exact "— till deliver-by" the dasher sees.
  - For most DoorDash drop-offs (especially no-contact), `task.arrivedAt` stays null all the way through completion — same root cause as 2026-05-17 #3: `EffectMap.kt:402-432` only emits `DELIVERY_ARRIVED` when `nextTask.arrivedAt != null && prevTask?.arrivedAt == null`, which never fires if DoorDash rolls nav → completion without a discrete arrival sub-state. So the Delivery card freezes with `arrivedAt = null` and renders the broken "—" hero.
  - The snapshot already carries `phaseStartedAt` (drop-off nav began) and `phaseEndedAt` (card frozen) — see `FlowCardSnapshot.kt:96-107`. Neither is currently read by `DeliveryBody` (`FlowCardItem.kt:312-325`); the body only passes them through to `DeadlineBody` which only uses them on the active-card branch as the elapsed-time fallback.
- **What the dasher's proposed shape implies:**
  - Frozen Drop-off hero could be `formatDuration(phaseEndedAt - phaseStartedAt)` — the total drop-off-leg duration, analogous to "Pickup took 8m" — with a caption like "drop-off duration" or similar.
  - Tertiary row picks up the "started HH:MM · completed HH:MM" framing the dasher asked for, paralleling Pickup's "arrived · picked up" line at `FlowCardItem.kt:380-389`.
  - When `arrivedAt` *is* populated (e.g. dropoffs where the arrival screen was caught), the existing "+Xm ahead / Xm late vs deliver-by" delta is still meaningful — could be preserved as a secondary line instead of replacing it.
- **Relationship to the existing entries:**
  - **2026-05-17 #3** is about *when* the Drop-off card freezes (end-of-dash via DASH_STOP, not at delivery completion).
  - **Today's #3** is about *what the frozen card displays* even after it does freeze. They share the same root cause for the `arrivedAt == null` case (no `DELIVERY_ARRIVED` for no-contact deliveries), but fixing one doesn't automatically fix the other. Freezing earlier without changing the renderer would still produce "—" + "till deliver-by" for any drop-off without an observed arrival.
- **What would confirm or refute this at the desk:** for any frozen Drop-off card from this session, inspect the corresponding `Task` row — expected `arrivedAt == null` and the rendered hero matches the `:367-368` fallback. If a frozen Drop-off card shows up with `arrivedAt != null` and *still* renders "—", the cause is elsewhere (mapper not threading the field through, etc.).
- **Possible direction (sketch only, defer to desk review):** extend `FlowCardSnapshot.Delivery` with `completedAt` (or just lean on `phaseEndedAt` as the de-facto completed-at) and teach `DeliveryBody` to render a frozen-specific layout: duration as hero, "started · completed" as tertiary. Two-piece change; the data already exists, so the patch is renderer-side.

#### 4. Post-task pay-breakdown announcement is flaky on the first delivery; collapse-then-expand may re-fire the bubble; whole pipeline is all-or-nothing instead of best-effort

- **Field observations (end of first dash):**
  - **(a)** First post-task screen: auto-click on the breakdown didn't complete. Dasher didn't observe a successful expansion + bubble announcement for delivery #1.
  - **(b)** Second post-task screen: auto-click worked, bubble announced normally.
  - **(c)** On one of the two (or in general), dasher manually collapsed the expanded breakdown and re-expanded it. Suspicion: a second auto-click may have fired on the re-collapse, and/or the bubble announcement may have re-fired on the second expansion. Not 100% certain — flagged for verification against captures.
- **Status:** Shipped in #266 (2026-05-20).
- **Dasher recall from prior implementation (worth weighing):** "before, when I implemented this, there was a slight delay to allow the screen to load all the way. It may be the case that it's trying to click it too early." The current rule has `throttleMs: 1000` but no explicit initial delay before the first click attempt — so the click can race the screen layout.
- **Hypothesis (from a desk read, not verified against field logs):**
  - **For (a) — first-click race.** `doordash.json:586-597` fires `click: $expandButton` as soon as the collapsed screen matches, gated only on `isExpanded == false`, deduped under the key `expand_pay_breakdown` with a 1-second throttle. `$expandButton` is bound from `hasIdSuffix: "expandable_view" | "expandable_layout"` with `optional: true`, so if the node hasn't materialized yet at first parse, the click target is null and the dispatch becomes a no-op. The throttle then prevents retry for 1 second; if the dasher dismisses the screen before the next collapsed observation re-fires the rule, the breakdown is never captured.
  - **For (c) — bubble re-fire on collapse → re-expand.** `EffectMap.kt:502` gates the announcement on `next.lastPostTaskPayHash != prev.lastPostTaskPayHash`. `PlatformRegionStepper.kt:292-293` sets `lastPostTaskPayHash = parsed.parsedPay?.hashCode()`. The collapsed parse produces `parsedPay = null` (no `payLineItems` to feed `ParsedFieldsFactory.kt:141`), so the sequence is:
    - Expanded #1: `prev.hash = null → next.hash = H` → `null != H` → **bubble fires**
    - Manual collapse: `prev.hash = H → next.hash = null` → hash now back to null (no announcement on this transition)
    - Re-expand: `prev.hash = null → next.hash = H` → `null != H` → **bubble fires AGAIN**
    The hash gate intends to dedupe, but it's transitively non-monotonic because the collapsed observation resets it. A per-task / per-job idempotency gate (e.g. `lastAnnouncedPayForJobId`) would be monotone and survive collapse cycles.
  - **For the broader "all-or-nothing" shape (c.f. dasher direction).** The announcement only fires when `payData != null` (`EffectMap.kt:499`), and `payData` is only populated by the expanded parse. The collapsed parse already captures `totalPay` and `sessionEarnings` (`doordash.json:536-568`) — useful enough to announce on its own — but those fields don't trigger any bubble effect today. Net result: a failed expand-click swallows the whole announcement, including the headline number.
- **Dasher's proposed direction (recorded verbatim for desk review):** "the post delivery stage should be best effort. If it only sees collapse, it should just record the total, and then it should add on the breakdown if it sees it later instead of only firing the bubble if it sees the breakdown. Also, it should not refire if I collapse it and then re-expand it, so there should be some kinda gate to stop it from the same one refiring."
  - That decomposes into three independent changes; each is reasonable on its own:
    - **(i) Announce on first sighting**, whichever shape it's in. Fire the bubble with `totalPay` (+ `sessionEarnings`) as soon as `PostTaskFields` lands, regardless of `parsedPay`. Auto-click still tries to expand; if/when expanded data lands, *enrich* the existing message (or skip, depending on how Earnings persona handles updates) — don't re-fire as a new announcement.
    - **(ii) Per-task idempotency gate** on the announcement. Track `lastAnnouncedForTaskId` (or `lastAnnouncedForJobId`) on `PlatformRegion`; only emit the announcement once per task identity. Replace or complement the existing `lastPostTaskPayHash` check, which is hash-based and breaks on collapsed-screen interleaving.
    - **(iii) Click-timing robustness** for the auto-expand. Options: small initial delay before the first click on the collapsed screen, or rely on the throttle but make it retry several times across observations (currently 1s throttle, but if the screen dismisses before the second tick the retry never lands). Worth verifying first whether the first-click failure is actually a layout race vs the click target being null vs the dispatch landing but the platform ignoring it.
- **What would confirm or refute this at the desk:** for today's first-dash captures, look at the post-task event stream for both deliveries:
  - Delivery #1: expect to see a `click` effect dispatched for `expand_pay_breakdown` but no subsequent expanded `post_task` observation (or the expanded observation arrives after the screen has been dismissed). Check whether the `expandable_view` node was present and clickable at the moment of first dispatch.
  - Delivery #2: expect to see the click land successfully, followed by an expanded `post_task` observation and exactly one `UpdateBubble(receiptText, ChatPersona.Earnings)` effect.
  - If captures show two `UpdateBubble` effects for the same `taskId`, that confirms (c) — the hash-gate is non-monotonic across the collapse cycle. The `lastPostTaskPayHash` value in the region snapshot before each emission would be the smoking gun (null → H twice).

#### 5. Maps nav view while navigating to a zone / hotspot misclassifies — should resolve as "still awaiting offer"

- **Field observation (start of second dash, ~19:19 Central):** Dasher went online, tapped a zone or hotspot to navigate toward it, and was on the Google Maps nav view inside the DoorDash app. The screen classifier didn't treat this as "still awaiting an offer" — the dasher's read is it was being interpreted as in-task (pickup or dropoff navigation), even though no offer had been accepted.
- **Status:** Shipped in #270 (2026-05-20).
- **Dasher's mental model (verbatim direction):** "if I'm navigating to the zone or to a hot spot, that means I'm not on an offer. So I'm awaiting an offer still … we might need to move that normal map view screen … as a branch of the awaiting offer screen."
- **Hypothesis (from a desk read, not verified against field logs):**
  - `doordash.screen.navigation_generic` at `core/pipeline/src/main/assets/rules/doordash.json:1673-1700` is the likely culprit. It requires `min` + `exit` + (`mi` | `ft`) on screen — the standard Google-Maps-in-DoorDash navigation chrome. It rejects only on `accept` / `decline` text (i.e., a live offer popup). **It has no `state` block**, so it matches the same screen text regardless of flow context.
  - `Ruleset.kt:13,23` sorts ascending by `priority` — *lower number wins*. `navigation_generic` is priority **95**, which evaluates before `on_dash_map` (110), `dash_along_the_way` (111), and `idle_map` (140). So if the dasher is online + offerless + navigating-to-zone, `navigation_generic` matches first and the more specific awaiting-offer matchers never get a chance.
  - `pickup_navigation` (`:686`, priority bound to flow `task:pickup:navigation`) and `dropoff_navigation` (`:775`, `task:dropoff:navigation`) are state-gated, so they *shouldn't* match outside a task flow. The way the dasher experiences this as "in-task" is most plausibly via `navigation_generic` swallowing the screen into a no-state-change classification that suppresses the awaiting-offer matchers from setting flow back to `idle/online`.
  - There's already a `dash_along_the_way` rule at `:1832` keyed on a `navigate_button` id — it covers the DoorDash widget *before* the dasher taps Navigate. Once the dasher is in the Maps nav view itself, that rule no longer matches and we fall back to `navigation_generic`.
- **What the dasher's direction translates to architecturally:**
  - Two distinct nav contexts share the same UI shape: (a) navigating to a pickup/dropoff for a *committed* task, (b) navigating to a zone/hotspot to *seek* an offer. They look identical on screen — the only disambiguator is upstream state (is there an `activeTask`?).
  - One shape: gate `navigation_generic` on `flow: idle/online` and surface it as an awaiting-offer variant (e.g., `idle_navigating_to_zone` with priority above `navigation_generic`'s current 95 — actually *lower* number, since lower wins — say 90, with the idle/online state). The existing `navigation_generic` then stays as the task-flow fallback.
  - Alternative shape: leave `navigation_generic` as a state-neutral classification and have the flow stepper interpret a `navigation_generic` match while in idle/online as a still-awaiting variant rather than a flow transition. Smaller blast radius but defers the categorization into the stepper instead of the rule layer.
- **What would confirm or refute this at the desk:**
  - For tonight's ~19:19 zone-nav window, pull the captured `screenIs` value over the period the dasher was on the Maps screen. Expect `doordash.screen.navigation_generic`. If it's something else (e.g., a leftover `pickup_navigation` from a stale flow state), the cause is in the stepper rather than the rule.
  - Cross-reference the `FlowRegion.flow` at the same timestamps — if flow stayed in `idle` but the bubble behaved as if a task were active, that points at the classifier-only path; if flow itself flipped to a task variant, the stepper is involved.
- **Possible direction (sketch only, defer to desk review):** introduce a `doordash.screen.zone_navigation` (or similar) ahead of `navigation_generic` in priority order, gated to `flow: idle, modeHint: online`, surfacing as an awaiting-offer branch. Leave the generic fallback in place for any flow where a task is genuinely active. Cheap rule addition; no state-machine change required if `navigation_generic` is already state-neutral.

#### 6. Dash summary screen didn't get recognized at end of dash (~19:55 Central)

- **Field observation:** Dasher ended the dash around 19:55 Central. The dash-summary screen appeared as expected on DoorDash, but DashBuddy didn't recognize it (no `SESSION_ENDED` ingestion / no summary captured into the bubble or DB-side aggregates).
- **Status:** Open. Blocked on the next field session capturing the actual snapshot so InboxProcessorTest can X-ray which selector drifted.
- **Hypothesis (from a desk read, not verified against field logs — desk should pull the actual snapshot):**
  - The only matcher for this screen is `doordash.screen.dash_summary` at `core/pipeline/src/main/assets/rules/doordash.json:2109-2235`, priority 150. It requires **both** of:
    - A node with `hasText: "Dash summary"` (exact, case-sensitive)
    - A node with `hasIdSuffix: "textView_prism_button_title"` **AND** `hasText: "Done"`
  - Failure modes worth checking against the captured snapshot from ~19:55:
    - **Text drift on the title** — DoorDash redesigned the screen or renamed the header (e.g. "Dash Summary" / "Summary" / a localized variant). `hasText` is exact-match, not contains; any wording change drops the rule.
    - **Button id drift** — `textView_prism_button_title` is a Prism design-system id. If DoorDash shipped a non-Prism CTA or renamed the resource, the second clause fails even with the same visible "Done" label.
    - **Button label drift** — "Done" could now read "Finish", "Close", "OK", "Got it", etc. Same effect.
    - **Priority shadowing** — unlikely but worth a glance. Anything lower-priority than 150 that requires content present on the summary screen would shadow it. `navigation_generic` (95) needs `min` + `exit` + `mi`/`ft` so probably safe; `notifications_view` (96) keys on the word "notifications" — also unlikely to match a summary screen, but worth verifying the snapshot text doesn't accidentally contain it.
    - **SENSITIVE blocker** — `SensitiveScreenMatcher` runs first per `CLAUDE.md`. If the summary screen tripped it (some kind of payout / banking-adjacent text?), nothing downstream gets a shot.
- **What would confirm or refute this at the desk:**
  - Pull the snapshot captured at ~19:55 Central from this branch under test. Run `InboxProcessorTest` against it — if it lands in `INBOX/` unrecognized, the X-Ray report will show what text + ids the screen actually has. Compare to the two clauses above to pinpoint which drift fired.
  - If captures show the snapshot *was* matched but the parse failed (`totalEarnings` / `sessionDurationMillis` / etc. null), the bug is in one of the field selectors (`hasIdSuffix: "header_pay"`, `hasIdSuffix: "name"` sibling pattern), not in the require block.
- **Why this hits harder than a normal screen miss:** the dash-summary parse is the only path that emits `SessionEnded` fields (`session_ended` `parse.as`, `:2138`), which is presumably how runs are reconciled against the platform's authoritative totals. A missed summary = a session that has to be reconstructed from per-task events without ground-truth cross-check.

#### 7. Historical card stack shows only the first Awaiting card — between-delivery awaiting periods missing

- **Field observation (post-session view):** Looking at the bubble's card stack after the dash, only the **first** Awaiting block appears (the one at the start of the session). The dasher had multiple deliveries with awaiting periods between them; each of those between-delivery "waiting for the next offer" stretches should have produced its own Awaiting block in the stack, interspersed between the PostTask of delivery N and the Offer of delivery N+1. None do. Dasher noted uncertainty about whether the **live** HUD showed awaiting blocks between deliveries — only sure the post-session reconstruction is missing them.
- **Status:** Shipped in #268 (2026-05-20).
- **Hypothesis (from a desk read, confident enough to call out the offending line):**
  - The card stack has two producers (`BubbleViewModel.kt:135-139`):
    - `LiveCardBuilder.build(state)` for the **active** card. `LiveCardBuilder.kt:28-37` returns a fresh `FlowCardSnapshot.Awaiting` whenever `flow == Idle && mode == Online`, with `phaseStartedAt = region.idleEnteredAt`. So during the dash, the live HUD presumably did show an Awaiting card between deliveries — the dasher's "not 100% sure about during the dash" suggests they likely saw one, just wasn't tracking it deliberately.
    - `FlowCardMapper.fold(events)` for the **completed** list. **This is where the bug lives.** Awaiting is only opened in one place — the `DASH_START` branch at `FlowCardMapper.kt:44-58`. It is closed on `OFFER_RECEIVED` (`:60-73`) and defensively on `OFFER_ACCEPTED/DECLINED/TIMEOUT` (`:75-86`). After it closes for the first offer, **nothing re-opens it**. The `DELIVERY_COMPLETED` branch at `:226-245` builds a `PostTask` card and resets `lastDeliveryArrivedAt = null`, but doesn't open a new `Awaiting`. Same for `OFFER_DECLINED` / `OFFER_TIMEOUT` (which also represent a return to awaiting from the dasher's POV).
  - End state of fold for a typical N-delivery session: `[Awaiting₀, Offer₀, Pickup₀, Delivery₀, PostTask₀, Offer₁, Pickup₁, Delivery₁, PostTask₁, …]` — no awaiting block ever appears after the first. Live HUD diverges from the historical reconstruction.
- **What would confirm or refute this at the desk:**
  - Pull the `app_events` rows for tonight's dash and walk the event sequence by hand. Confirm there's exactly one path that could create an Awaiting card (`DASH_START`) and that subsequent transitions back to awaiting (`DELIVERY_COMPLETED` → next `OFFER_RECEIVED`) have no Awaiting card spanning the gap.
  - If the historical stack already lines up with the prediction above (single Awaiting at session start, then Offer/Pickup/Delivery/PostTask interleaved), the diagnosis is settled and the fix is mapper-side.
- **Possible direction (sketch only, defer to desk review):**
  - In `FlowCardMapper.fold`, open a fresh `Awaiting` at the same points the live HUD would (i.e., whenever the dasher returns to idle/awaiting):
    - On `DELIVERY_COMPLETED` (right after pushing the PostTask card).
    - On `OFFER_DECLINED` / `OFFER_TIMEOUT` (after pushing the Offer card — dasher returns to awaiting if they decline / let the offer time out).
    - Possibly on `DASH_RESUMED` if such an event exists (otherwise the pause/resume cycle is opaque to the mapper).
  - Each newly-opened Awaiting then naturally closes via the existing `OFFER_RECEIVED` / defensive-`OFFER_*` paths, producing the interspersed shape the dasher expects.
  - Open question worth flagging for the desk: what `phaseStartedAt` to use for between-delivery Awaiting cards. The natural value is the `completedAt` of the prior PostTask (or the `decidedAt` of the prior declined offer) — i.e. the moment the dasher returned to awaiting. Each Awaiting card then represents the literal "I'm hanging in idle, looking for the next ping" period.
