## 2026-06-21 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `claude/field-testing-bubble-notifications-qcaocb` (field-testing build off
  `master` at `20063db`, post-#299 merge).
- **Field conditions:** Live Sunday dash. **Double stack: PetSmart + Target** (two stores, two
  drops). Observation narrated at **~11:38** while transitioning from the completed Target pickup
  toward the PetSmart pickup. No screenshots; in-field marker only. **Hypotheses, not concluded
  fixes.** No code changes from this session.

### Desk review (2026-06-21, verified against the dash db/captures/source — Claude)

Every claim below independently verified against `dashbuddy-v2.db`, the captures, the rotated logs,
and current source (ultracode workflow).

- **Merged build HELD.** **#556 shop `$/hr` WORKING** — all 13 offers recompute within $1/hr of the
  logged bubbles; the two big grocery shops that caused the $116 bug now read **$22/hr** (31 items)
  and **$13/hr** (38 items). **ShopRate learning WORKING** (6 sane samples 0.27–0.88/min; under the
  5-sample gate so the 0.8 seed was correctly in force). **#517/#518 WORKING** (0/13 null pay; one
  real completion/job; sessions 0 & 9 reconcile penny-exact) — **2nd clean confirmation, validated.**
  **#553/#554** single-store **6/6 correct**; the multi-store `store=''` (seq23/seq70) is the gap
  **#557 already fixes (now merged)** — not a regression.
- **Bug #8 (BK add-on) — CONFIRMED, HIGH → filed #564.** seq98 `$0`/null-customer "Smokey Mo's"
  DELIVERY_COMPLETED is a **NEW class, not a #498 regression**: the BK add-on's loading frame was
  misrecognized as `delivery_summary_collapsed`, and a **grace-retired, never-picked-up** pickup task
  was completed via the PostTask-exit path (`EffectMap.kt:304-322`). Smoking gun: TASK_RETIRE
  `since` == BK offer `presentedAt` == seq98 `completedAt` = `1782080300717`. Fabricated $0 row +
  −$8.40 under-capture. (#518 taskId-dedup can't stop a *first* spurious completion; #498's guard is
  in the dropoff-mint path → inapplicable.)
- **Bug #7 (Sprouts unassign) — CONFIRMED gap → #301.** Sprouts was **unassigned before pickup** and
  never formed a task (benign here — no orphan), but confirms the state machine has no
  unassign/remove-task model. Tracked under #301.
- **Bug #3 (Walgreens) — CONFIRMED, HIGH → filed #565.** An en-route frame matched `dropoff_handoff`
  (priority 64, ARRIVED, parse-null) over `dropoff_pre_arrival` (73) → "the customer" + false
  "already arrived" (`arrivedAt` set 4.5 min early) + a **duplicate dropoff task** (job ended with 2).
  The real arrival frame carries "Complete Delivery"; the peek doesn't — the discriminator no rule keys on.
- **Bug #5 (Popeyes+McD) — REFUTED (not a bug).** Both restaurants are **one customer** (single hash)
  → correctly 2 pickups → 1 dropoff → 1 completion (seq70, $10.02). The "never recognized a dropoff"
  was **~4.5 min HUD staleness** (no dropoff frame captured for minutes) — spun out as #567 (flip the
  bubble on the transition) + #568 (raw-hash label). seq70 `store=''` is the #557 gap (now merged).
- **Bug #6 (True Texas BBQ decline → TIMEOUT) — CONFIRMED, MED.** The 2nd-step `decline_offer`
  confirm click classified as `UNKNOWN click id=null text=null`; the confirm *screen* WAS recognized;
  `resolveOfferOutcome` else → TIMEOUT (`EffectMap.kt:934`). Design call (decline-confirm fallback) +
  needs a fresh decline capture with the click frame.
- **Bug #1 (double Navigator fly-away) — CONFIRMED, LOW → filed #566.** `UpdateBubble` has a null
  `effectKey` so two consecutive emissions (Site A `EffectMap.kt:582` + Site B `:691`) don't dedup.
- **INFO PII leak — CONFIRMED, MED.** `ShadowProjector` logs 9 raw merchant names at INFO → folded
  into #551.

**Top priority: #564** (the only ledger-corrupting bug). Fix in flight (recognition + state guard +
replay test). Lower: #565 Walgreens, then the design calls (#6 decline fallback, #301 unassign model).

### Bugs

1. **Double-mint of the fly-away bubble notification on the stacked-pickup hand-off — two
   "Navigator" heads-up flyouts, the first with no icon.** After completing the **Target** pickup
   and turning toward **PetSmart**, the transient heads-up notification (the little fly-away that
   pops off the bubble — **not** the in-bubble card) fired **twice**, both showing the sender
   **"Navigator"**. The first of the two (the one the dasher caught) rendered the **Navigator name
   but no avatar icon**; tapping into the bubble shows the icon correctly, so the drawable itself
   resolves — it's the **flyout** that came up icon-less.
   - **Why "Navigator": it's the heading-to-pickup persona.** `determinePickupPersona`
     (`core/state/.../EffectMap.kt:938-950`) returns `ChatPersona.Navigator` when a PICKUP task is
     active but **not arrived, not shopping, not confirmed** — exactly "driving to the next store."
     So the PetSmart leg correctly wants a Navigator "Pickup: PetSmart" message.
   - **Likely double-mint cause (hypothesis): two independent `UpdateBubble` emission sites both
     fire across the consecutive frames of the stacked hand-off, and nothing dedups them.** In
     `diffTask` there are two Navigator-capable `UpdateBubble("Pickup: …")` sites:
     - **Site A — new PICKUP task minted** (`EffectMap.kt:563-583`): fires when
       `prevTask?.taskId != nextTask.taskId` — i.e. the moment the PetSmart pickup becomes the
       active task after Target.
     - **Site B — same-task store/activity change** (`EffectMap.kt:672-692`): fires when the store
       name or activity changes *within* a PICKUP task (`storeChanged || activityChanged`).
     A plausible sequence: frame N mints the PetSmart pickup task (Site A → "Pickup: …" Navigator),
     then frame N+1 resolves/changes the PetSmart store text (Site B → "Pickup: PetSmart" Navigator
     again). Both effects are `AppEffect.UpdateBubble`, both route through
     `SideEffectEngine.kt:202-204` → `BubbleManager.postMessage` → `showNotification`, and
     **`UpdateBubble` carries no idempotency key** — the `effects_fired` dedup only guards
     `LogEvent` (`SideEffectEngine.kt:187-200`), so two `UpdateBubble`s in a row each call
     `notificationManager.notify(BUBBLE_NOTIFICATION_ID, …)` and each raises its own heads-up
     flyout. Would need the captured frame sequence around 11:38 to confirm which two sites fired
     (and whether the two texts were identical "Pickup: PetSmart" or differed by an unresolved
     store name on the first).
   - **Likely missing-icon cause (hypothesis): rapid re-`notify` of the same notification id drops
     the `Person` avatar on the heads-up.** The flyout is a `MessagingStyle` notification whose
     sender icon is the persona avatar (`BubbleManager.showNotification`, `BubbleManager.kt:180-214`
     — `ic_chat_navigation` via `getIconResId`, `ChatFormatters.kt:16`). The drawable is valid (the
     expanded bubble shows it), so this isn't a missing-resource bug. When two notifications hit the
     **same** `BUBBLE_NOTIFICATION_ID` back-to-back, Android often renders the first heads-up before
     it has loaded/attached the `Person` icon, showing the name without the avatar — consistent with
     "first one had no icon, the bubble has it." If the double-mint (above) is fixed, this likely
     stops being visible; worth confirming whether a single Navigator flyout ever comes up icon-less
     on its own.
   - **Status:** Open — **marker only**, awaiting end-of-dash log/capture upload for the desk
     cross-reference above (which two `UpdateBubble` sites fired, the two texts, the frame
     timestamps around 11:38). Recorded only, no code changes. Note: this is the live specimen the
     `claude/field-testing-bubble-notifications-qcaocb` branch exists to chase.

3. **Walgreens drop-off card cluster: shows "the customer" (unresolved), reads "already arrived",
   and minted a *fresh* drop-off task the moment navigation started (~12:19).** On a single
   **Walgreens** drop-off, three things looked wrong at once on the drop-off card:
   - **a) Card shows "the customer" — the unresolved-recipient sentinel.** "the customer" is
     `CUSTOMER_FALLBACK` (`domain/.../state/DisplayNames.kt:19`, via `customerDisplayName(null)`),
     deliberately lowercase to read as "recipient not yet known." Per #503 slice 3 it's expected
     **briefly** while the dropoff is unresolved, then should resolve to the **6-char hash** once a
     customer-bearing dropoff frame lands. If it **lingered** on the card, the resolve-onto-subtask
     step didn't fire — the customer hash never got written onto the active dropoff task.
   - **b) Card reads "already arrived" when the dasher was just starting to drive there.** A
     drop-off `arrivedAt` is only meant to be set when a frame comes in as `TaskSubFlow.ARRIVED`
     (`PlatformRegionStepper.kt:764,778` — `justArrived = subFlow == ARRIVED && arrivedAt == null`).
     A false "arrived" at nav-start means either (i) an `arrivedAt` was **inherited/carried** onto
     the new dropoff task from a prior leg, or (ii) a frame was mis-classified as a dropoff
     **ARRIVED** when it was really a nav/arriving screen.
   - **c) Starting navigation minted a NEW drop-off task** ("as soon as I started the navigation it
     minted a new dropoff task; it knows I'm navigating now"). The #503-slice-3 design is to
     **resolve onto the offer-spawned, customer-TBD dropoff subtask** (`PlatformRegionStepper.kt:744-783`)
     rather than mint fresh. A new mint at nav-start means the **resolve path's guard didn't match**
     — `expectedDropoff` (`:749-758`) came back null, so it fell through to the new-task mint
     (`:805+`). That guard requires a pending dropoff with `customerNameHash == null &&
     completedAt == null` that isn't the current task and isn't already displaced; if the
     pre-created dropoff was already consumed/displaced, or didn't exist for this Walgreens job, the
     fall-through mints a fresh one.
   - **These three are very likely one cluster, not three bugs.** A fresh-minted dropoff (c) would
     start with **no customer hash** → renders "the customer" (a), and if that same mint or a
     following frame mis-set `arrivedAt`, it reads "already arrived" (b). The single question for the
     capture is: **at nav-start, did the stepper resolve onto the pre-created Walgreens dropoff or
     mint a new one** — and if it minted, why was `expectedDropoff` null (no offer-spawned dropoff
     subtask for this job? already displaced? customer hash already non-null on it?).
   - **What to pull / check at desk (from this dash's `app_events` + `app_state_snapshots` around
     12:19):** the Walgreens dropoff frame sequence — how many dropoff tasks exist for the job
     (want exactly one), whether `customerNameHash` ever resolves off null, the `arrivedAt` value at
     nav-start and which frame set it (a real ARRIVED screen vs nav/arriving), and whether a
     `DELIVERY_NAV_STARTED` / new-task mint fired at the navigation start. Cross-reference the
     #503-slice-3 resolve-onto-subtask path.
   - **Developer hypothesis (post-dash, the likely (b) mechanism): a post-arrival "host" drop-off
     rule fired where the pre-arrival rule should have — "maybe those rules aren't different
     enough."** This grounds out in the ruleset. Three drop-off rules key on the generic
     `drop_off_workflow_host_fragment` container id and all set **`task:dropoff:arrived`** —
     `dropoff_photo`, `dropoff_pin_entry`, and **`dropoff_handoff`** (`doordash.json:2552/2563/2577`,
     handoff at **priority 64**, keyed on host fragment + `"hand it to customer"`) — whereas the
     en-route **`dropoff_pre_arrival`** is **priority 73** and sets `task:dropoff:navigation`
     (keyed on `"Deliver to"`/`"Delivery for"` + `"Hand it to recipient"`). Rules evaluate
     **ascending by priority, first match wins** (`Ruleset.kt:22-43`, "lower = evaluated first"), so
     **64 beats 73**: if an en-route/arriving frame carries the workflow-host fragment *and* trips
     one of those arrived rules' text, the **arrived** rule wins and flips `arrivedAt` before the
     dasher is actually there — exactly the false "already arrived." The discriminators are thin
     ("hand it to **customer**" vs "Hand it to **recipient**"; a host-fragment container that may be
     present pre-arrival too), which is the "not different enough" the dasher flagged. **Confirm
     against the capture:** which drop-off rule id matched the nav-start Walgreens frame — if it's a
     `*_host_fragment` / arrived-flow rule on an en-route screen, that's the bug, and the fix is
     tightening the arrived rules' discriminators (or rejecting the en-route markers) rather than
     anything in the stepper.
   - **Status:** Open — **marker only**, awaiting capture upload. Recorded only, no code changes.

5. **Stacked double (Popeyes + McDonald's, ~15:20): after completing the SECOND pickup, the app
   never recognized the dasher was on a drop-off.** On a two-store stack, both pickups completed,
   but the transition into the **drop-off phase didn't register** after the second pickup — no
   drop-off card / drop-off state. Dasher's read: **"I think it's related to the other one"** (the
   #3 Walgreens drop-off cluster). Very likely the **same drop-off-recognition family**, surfacing
   as a *miss* (no drop-off at all) here rather than a *false arrived* there.
   - **Candidate causes (hypotheses, same family as #3):**
     - **(i) #498 phantom-dropoff guard suppressing the real drop-off.** A transition into a dropoff
       whose frame carries **no `customerNameHash` AND no `customerAddressHash`** is treated as a
       transient confirm/geofence screen and the stepper **returns the region unchanged**
       (`PlatformRegionStepper.kt:798-803`). If the first drop-off frame after pickup 2 didn't parse
       a customer (wrong layout, or a rule that recognizes the screen but doesn't parse name/addr),
       the guard would swallow it and the drop-off never starts. This is the inverse failure mode of
       #3c — there a fresh dropoff minted with no identity; here the guard eats it.
     - **(ii) The pickup→dropoff transition never fired because pickup 2 didn't *confirm*.** The
       `PICKUP_CONFIRMED` → `DELIVERY_NAV_STARTED` hand-off (`EffectMap.kt:601-637`) requires
       `prevTask.phase == PICKUP && nextTask.phase == DROPOFF`. On a stacked double the second
       pickup's confirm screen (often a barcode/QR/handoff variant) may not have been recognized as
       a pickup-confirm, so the task never flipped to DROPOFF — same thin-discriminator risk as the
       drop-off rules in #3.
     - **(iii) Stacked-dropoff resolve mismatch.** With two drops queued, the #503-slice-3 resolve
       (`PlatformRegionStepper.kt:744-783`) picks the first pending customer-TBD dropoff; if both
       drops' subtasks were already consumed/displaced or the second pickup didn't spawn its dropoff
       subtask, there's nothing to resolve onto and (combined with the phantom guard) no drop-off
       surfaces.
   - **What to pull / check at desk (from this dash's `app_events` + snapshots around 15:20):** the
     frame sequence right after the second pickup — did a `PICKUP_CONFIRMED` fire for pickup 2, did
     any frame classify as `task:dropoff:*`, what rule id matched those frames, and did they parse a
     `customerNameHash`/`customerAddressHash` (if both null, suspect the #498 guard). Compare the
     Popeyes and McDonald's legs to see if one resolved and the other didn't. This is a **two-store**
     stack, so it also feeds the multi-store-stack frontier (#557 / the 06-20 markers).
   - **Status:** Open — **marker only**, awaiting capture upload; likely same root family as #3.
     Recorded only, no code changes.

6. **True Texas BBQ offer (~16:19): dasher *declined*, but it was recorded as an `OFFER_TIMEOUT`,
   not `OFFER_DECLINED`.** A real decline logged as a phantom timeout — a data-fidelity bug in the
   offer accounting (declines vs timeouts feed accept-rate / offer stats).
   - **Mechanism (well-grounded in the rules + stepper): a DoorDash decline is two-step, and only
     the SECOND step counts as a decline — if that confirm-step click isn't captured, the outcome
     falls through to TIMEOUT.** `OfferIntent.DECLINE` is the literal wire string **`"decline_offer"`**
     (`domain/.../state/OfferIntent.kt:11`). Only one click rule produces it —
     **`doordash.click.decline_offer`** (`doordash.json:2999-3008`), gated **`screenIs:
     offer_popup_confirm_decline`** and requiring a tap on **"Decline offer"** (the confirmation
     dialog's button). The **first** tap — the X on the offer popup — is
     `doordash.click.initial_decline` with intent **`"initial_decline"`** (`:2987-2996`), which
     **does not equal `"decline_offer"`** and so is **never** treated as a decline outcome. Outcome
     resolution (`EffectMap.kt:918-936`, `resolveOfferOutcome`) checks `prevOffer.lastClickIntent`
     and the resolving click for `OfferIntent.DECLINE`; finding neither (only an `initial_decline`,
     or nothing), it returns **`OFFER_TIMEOUT`** as the `else` branch. So when the offer vanished
     (`prevOffer != null && nextOffer == null`, `EffectMap.kt:172-183`) without a captured
     `decline_offer` confirm click, a genuine decline was logged as a timeout.
   - **Why the confirm click was likely missed (hypotheses to check against the capture):**
     - **(i) The confirm dialog wasn't recognized as `offer_popup_confirm_decline`.** That screen
       rule keys on `"sure you want to decline"` (`doordash.json:305-313`); the click rule's
       `screenIs` gate depends on it. If True Texas BBQ's confirm dialog rendered different text or
       the frame wasn't captured, the `decline_offer` click can't classify → no DECLINE signal.
     - **(ii) The confirm-tap accessibility click event wasn't emitted/captured** (fast dismissal,
       debounce, or the popup tore down before the click frame landed).
     - **(iii) The offer flow went to null off the `initial_decline` step** (or a timeout-looking
       transition) before the confirm click was processed.
   - **Design fragility worth flagging regardless of root cause:** a real decline is only counted if
     the **second-step** confirm click is recognized; a missed confirm silently becomes a phantom
     timeout. The `initial_decline` intent is captured but deliberately not counted (tapping X then
     *cancelling* brings the offer back, so it isn't a decline on its own) — but there's **no
     fallback** for "initial_decline seen, then offer genuinely disappeared," which is exactly a
     confirmed decline with a dropped confirm frame. Whether to treat `initial_decline`-then-vanish
     as a decline is a design call (to be decided, not concluded here).
   - **What to pull / check at desk (`app_events` + snapshots ~16:19, True Texas BBQ):** the click
     sequence on the offer — was an `initial_decline` click captured? a `decline_offer` click? was
     the `offer_popup_confirm_decline` screen ever recognized (any frame with "sure you want to
     decline")? what `lastClickIntent` did the `PendingOffer` carry when it resolved, and what
     emitted the `OFFER_TIMEOUT`? Confirms which of (i)/(ii)/(iii) it was.
   - **Status:** Open — **marker only**, awaiting capture upload. Recorded only, no code changes.

### Field UX context / Open questions

2. **The PetSmart leg of the stack was a barcode-scan "batch"-style pickup — similar in feel to
   GoPuff, but not identical.** The dasher had to **scan a barcode several times** during the
   PetSmart pickup, reminiscent of the GoPuff (Drive) warehouse bin-scan workflow but **not exactly
   the same** flow. Flagged to **review against the capture corpus** when it comes back — specifically
   whether PetSmart's scan screens are **recognized or fall to UNKNOWN**.
   - **Why this matters / hypothesis: the existing barcode-scan recognition is GoPuff-keyed, so
     PetSmart's screens may not match.** The #501 batch-pickup rules in
     `core/pipeline/src/main/assets/rules/doordash.json` are keyed on **GoPuff-specific** text/ids:
     `doordash.screen.pickup_steps` matches `"Scan barcodes on"` and declares
     `task:pickup:arrived` (it's what mints the only clean PICKUP_ARRIVED for an otherwise
     all-UNKNOWN warehouse leg), and `pickup_barcode_scan_issue` / the at-store survey lean on the
     GoPuff Compose container ids. A **PetSmart** batch pickup with different on-screen copy and
     widget ids likely **won't trip those branches**, so its scan steps could land in UNKNOWN and
     **no PICKUP_ARRIVED would fire** for the PetSmart leg — the same gap #501 closed for GoPuff,
     re-opened for a new store. Would need the captured PetSmart frames to confirm what text/ids it
     actually carries before deciding whether it's the same rule family or a new one.
   - **What to pull / check at desk (from this dash's capture corpus):** the PetSmart pickup frame
     sequence — does any frame recognize (intent ≠ UNKNOWN), does a PICKUP_ARRIVED fire for the
     PetSmart leg, and what stable non-PII strings / view ids do the scan screens expose (so a rule
     can be keyed on them if they're UNKNOWN)? Compare against the GoPuff #501 corpus to see how much
     of the workflow is shared vs store-specific.
   - **Status:** Open — **marker only**, awaiting capture-corpus review. Recorded only, no code
     changes.

### Research / design

4. **Offer evaluation: flat per-metric thresholds may be too blunt; the real decision is
   *conditional* (pay × distance). Conjecture / thinking-out-loud, not a work item.** Dasher's
   framing: a rule like "decline anything under $5" is a fine rule of thumb on its own, but the
   actual judgment is **joint** — *"if it's $5, what's the most distance I'll drive for it?"* A $5
   offer that's 1–2 miles to a fast restaurant is a take; the same $5 at a longer distance isn't. So
   a single global floor on one dimension throws away the nuance that lives in the **interaction** of
   pay and distance (and pickup speed). "I feel like there's a lot more nuance we might need to
   capture in the problem."
   - **What plumbing already exists (so this is a refinement, not a greenfield build):** the offer
     engine is already rule-based — `ScoringRule.MetricRule` (sealed type, `metricType` +
     `targetValue`) over `MetricType.{PAYOUT, DOLLAR_PER_MILE, ACTIVE_HOURLY, MAX_DISTANCE,
     ITEM_COUNT}` (`domain/.../evaluation/OfferEvaluator.kt:144-220`). Each metric is scored
     **independently** (`calculateMetricScore`, `:195-220`) and the per-metric scores are folded by
     **rank weight** into a composite (`:143-158`), then cut at `ACCEPT_THRESHOLD` /
     `DECLINE_THRESHOLD` (`:164-165`). `DOLLAR_PER_MILE` already encodes a pay/distance *ratio*, but
     a ratio isn't the same as a conditional curve — $5/1mi and $50/10mi are the same $5/mi yet very
     different takes.
   - **The gap (conjecture): there's no way to express a threshold on one dimension that's a
     *function of* another** — e.g. an acceptance curve `maxMiles = f(payout)` (at $5 → ≤2 mi; at $8
     → ≤4 mi; …), or a small pay×distance accept/decline table. The current model can weight
     "distance matters" and "pay matters" separately but can't say "distance only matters *this much*
     **when** pay is low."
   - **A couple of directions this could go (purely speculative, dasher decides):** (i) a **guided
     rule-builder** that nudges the user toward conditional rules — instead of one "min $5" slider,
     prompt "at $5, what's your max distance? at $8? at $12?" and store the resulting points; (ii)
     model it as a **pay-vs-distance acceptance curve** (a few user-set anchor points, interpolated)
     that becomes a new `ScoringRule` variant or a hard accept/decline gate layered on the existing
     composite. Either would reuse the `ScoringRule` sealed hierarchy and the
     evaluate/score/threshold pipeline rather than replace it. If this hypothesis holds, the design
     question is whether conditional logic lives as a richer `ScoringRule` subtype or as a separate
     gate ahead of the weighted score — to be decided, not concluded here.
   - **Status:** Open — **conjecture / design note only**, no work item filed, no code changes.
     Recorded to feed a later triage/RFC if the dasher wants to pursue it.

9. **Onboarding / setup-wizard / permissions philosophy — prompted by a Reddit "delete your
   onboarding" post. Strategic note, not a work item.** A solo dev's post (built on Claude Code,
   shipped to TestFlight) reported real-user data killing his elaborate onboarding: users walked the
   tutorial/setup/value-props and **bailed at the sign-in wall before reaching the product**. He tore
   it out — drop users into the core action free, no account, ask for sign-in only *after* value is
   felt. Lesson: *"every screen between 'opened the app' and 'felt the value' is a place to lose
   someone."* (Product referenced: War Table, https://wartable.co/ — "five AIs debate your
   decision." The original Reddit post URL wasn't locatable via search at log time; quote is
   paraphrased from the dasher's relay.)
   - **Why DashBuddy is a different archetype (so "delete the wall" doesn't transfer directly):** the
     post's exemplars (ChatGPT/Claude/Perplexity/War Table) deliver value the instant you open them,
     so a sign-in gate is *arbitrary* friction. DashBuddy's permission gate is **load-bearing, not
     arbitrary** — the core value (reading the DoorDash UI via `AccessibilityService`, mileage via
     location) is physically impossible without those grants. You can't "drop the user into the
     value" because the value is recognizing offers on a live dash. So the wall can't simply be
     deleted; the *principle* still applies, on a different surface.
   - **The translation that does apply — split capability from scaffolding:**
     - **Load-bearing (can't defer):** the accessibility-service grant and location permission.
     - **Scaffolding (the post's real target):** the economy editor (MPG/maintenance/depreciation/
       gas), vehicle/strategy config, value-prop or tutorial screens. **None of this should stand
       between "opened" and "saw True Net Profitability."** The code already supports deferring it —
       `UserEconomy` ships sane defaults (`DEFAULT_GAS_PRICE_PER_GALLON`, `DEFAULT_MINUTES_PER_MILE`,
       `DEFAULT_BASE_PICKUP_MINUTES`) and tracks `userSetFields` separately, so the economy editor can
       be **optional refinement**, never a first-run gate. If the wizard currently forces economy
       config before the first dash, that's exactly the "commit before value" wall and it can come out
       without touching the data model.
   - **The one move that *is* the post's lesson, adapted:** sequence value **ahead of** the scary
     accessibility dialog — let the user feel the bubble HUD + net-profit math on a sample/demo offer
     first, *then* request the grant, now motivated ("earn the permission"). Same instinct (no
     commitment before value), applied to a permission that can't be removed.
   - **Stage check (the most important caveat):** per `CLAUDE.md`, **"exactly one user: the
     developer."** The post is a **conversion-funnel** lesson — about not losing *acquired strangers*,
     which DashBuddy doesn't have yet. So building (or tearing out) elaborate onboarding *now* would
     itself be the over-scaffolding the post warns about. Right-now question: *does the wizard slow the
     tester down, and does it bury the two permissions that matter?* Optimize the wizard for the
     tester. **Save the funnel teardown for the first-500 / paid-tier launch (#141)** — that's when
     "every screen to value is a place to lose someone" becomes literally true with money attached.
   - **Status:** Open — **strategic/design note only**, no work item filed, no code changes. Bookmark
     for the #141 monetization launch; near-term, only ensure economy config is deferred behind
     defaults rather than gating the first dash. *[Editor's note 2026-07-12: #141 has since closed —
     it was the cloud-data-platform RFC, not the pricing plan; the paid-tier launch plan lives in
     CLAUDE.md pillar 1. The "funnel teardown at paid-tier launch" bookmark itself still stands.]*

10. **Voice accept/decline of offers — hands-free, on-device. Feasibility / overhead note (dasher
    asked "what would the overhead be").** Let the driver say "accept" / "decline" instead of
    reaching for the bubble button while moving. Estimated overhead: **moderate-small for a v1**,
    because the expensive half is already built.
    - **Already done (the actuation half):** accept/decline is already an intent SSOT
      (`OfferIntent.ACCEPT/DECLINE`) with **multiple front-ends** — the bubble buttons and the
      notification actions both dispatch an `Observation.UiInput` carrying that intent. The app-owned
      tap path (`RuleAction.ACCEPT_OFFER/DECLINE_OFFER` via `UiInteractionHandler`, the only thing
      that clicks the third-party app, #425) and the fail-closed action gates (#417: tier check +
      package-scope + label allowlist + strict click) sit behind it. **Voice is just front-end #3** —
      a spoken word → `UiInput(intent)` → the exact path the button uses. TTS output already exists
      (`TtsEffectHandler`), giving the confirmation/readback half for free.
    - **New work (the input half):** (1) `RECORD_AUDIO` runtime permission (sensitive — same opt-in
      framing as other capabilities); (2) a **bounded** speech handler — Android on-device
      `SpeechRecognizer` armed **only during the offer window** (R0 `OfferPresented`) with a 2-word
      grammar, symmetric to `TtsEffectHandler` (e.g. a `VoiceCommandHandler` that `EffectMap` arms on
      offer-presented / disarms on offer-resolved); (3) a confirm beat (TTS readback + grace, e.g.
      "Declining — say cancel to stop") for noisy-car reliability — dovetails with DoorDash's
      already-two-step decline (cf. this session's #6); (4) trigger classification — a voice accept is
      a **`USER`** `ActionTrigger` (the dasher spoke = its own consent), which already exists, so it
      slots into the #417 consent model without change.
    - **Privacy posture (the real gate, fits the pledges):** on-device recognition only, **no audio
      leaves the device**, mic live **only** in the bounded offer window, opt-in per-feature.
      Explicitly **not** always-on/hotword for v1 (battery + wake-word model + privacy review blow up
      the cost and strain the on-device story); push-to-offer-window keeps it cheap and pledge-clean.
    - **Status:** Open — **design/feasibility note only**, no work item filed, no code changes. Clean,
      well-bounded enhancement with a hands-free field-safety win; candidate to file against the
      effects/offer-action area if the dasher wants to pursue it.

### Verification TODOs — desk-review markers (need captured data)

7. **Partial unassign of a stacked order (~17:16): dasher dropped ONE half of a stack — desk agent,
   trace this whole window for unknown screens and the effect on job/task tracking.** Advanced
   maneuver: an accepted stack of **a regular pickup (Smoky Mo's BBQ) + a Shop & Deliver order
   (Sprouts Farmers Market)**; the dasher **unassigned the Sprouts shopping order** (it was too far)
   and kept the Smoky Mo's pickup. This exercises a path the app has **almost no model for** — pull
   the capture and trace it end to end. Marker only; recorded, not concluded.
   - **Why this is a known-thin area (so the desk review has a starting hypothesis):**
     - **Recognition: only ONE screen of the unassign flow is known.** The single relevant rule is
       `doordash.screen.pickup_resolution_options` (`doordash.json:2802-2811`), keyed on
       `"Resolution options"` + `"Unassign with no pay"`, and it's **recognize-only** (no
       `state.flow`, no parse). The rest of the unassign sub-flow — the issue picker before it
       (`pickup_select_issue`), any "which order do you want to unassign?" stack selector, the
       confirm dialog, and whatever the app shows **after** one order is dropped — is likely
       **UNKNOWN**. **Expect unknown frames in this window**; the X-Ray report on the capture is the
       way to find and rule them.
     - **Task tracking: there is NO unassign/remove-task handling in the state machine.** A repo-wide
       search for `unassign` / `removeTask` / `dropTask` in `core/state` and `domain` returns
       **nothing** — the steppers model task *completion* and *re-mint*, not **removal of a still-open
       task from a multi-task job**. So when the Sprouts task is unassigned, the job very likely still
       carries it as an open subtask that can **never complete** — a candidate **orphan/phantom task**
       that could skew the job's task list, its economics (the dropped order's offer pay still folded
       in?), and any "all drops done?" completion logic. This may also interact with the same-store /
       dropoff-resolution paths flagged in #3 and #5.
   - **What the desk agent should pull / check (`app_events` + `app_state_snapshots`, window ~17:16,
     DoorDash):**
     - **Unknown screens:** run the capture through the X-Ray / inbox sort for this window; list every
       UNKNOWN frame in the unassign sub-flow and note its stable non-PII strings/ids so rules can be
       written (issue picker → resolution options → stack selector → confirm → post-unassign state).
     - **Job/task tracking effect:** how many tasks did the job hold before vs after the unassign?
       Did the **Sprouts** shopping task get removed, left open (orphan), or mistakenly marked
       complete? Did the **Smoky Mo's** pickup survive intact as the sole remaining task? Was there a
       spurious re-mint or a dropoff phantom (cf. #3/#5)?
     - **Economics:** was the dropped Sprouts order's pay still counted in the job/session economics
       after unassign, or correctly removed? ("Unassign with no pay" implies it should contribute
       nothing.)
     - **State integrity:** did the machine end the window in a clean single-task state, or in a
       confused/stuck state (e.g. waiting on a dropoff for the order that no longer exists)?
   - **Status:** Open — **desk-review marker**, awaiting capture upload. Recorded only, no code
     changes; flagged as a likely **new recognition + new task-lifecycle (unassign) frontier**.

8. **Same job got *weirder* (~17:16, continues #7): a Burger King add-on arrived while approaching
   Smoky Mo's, DoorDash forced BK to be picked up FIRST, the dasher reshuffled — and the closeout
   shows an erroneous "paid" PostTask and TWO Smoky Mo's pickups.** This is the high-value specimen
   of the session. Sequence as narrated: stack was Smoky Mo's + (dropped) Sprouts (#7); then a **BK
   add-on** came in en route to Smoky Mo's; DoorDash **re-ordered the route to pick up BK first**;
   the dasher did "shenanigans" to move things around. Two concrete defects observed: **(a) an
   erroneous paid PostTask** (a payout/receipt closeout fired when it shouldn't have), and **(b) two
   Smoky Mo's pickups** (a duplicated same-store pickup task). "I know it's not handling it perfectly
   yet." Marker only — recorded, not concluded.
   - **Why these two are plausible given the code (starting hypotheses for the desk agent):**
     - **(b) Two Smoky Mo's pickups — the #499 same-store re-match was likely defeated by the
       reorder.** The pickup re-match ("fold the add-on into the existing same-store task, don't
       re-mint") only runs **when `!isStackedPickupTransition`** (`PlatformRegionStepper.kt:710-714`):
       it resumes a `recentTasks` pickup with the same `storeName`. But when DoorDash **re-ordered**
       the stack (BK inserted *ahead* of Smoky Mo's), the platform very likely signaled a
       **genuinely-new stacked pickup transition** on returning to Smoky Mo's, so
       `isStackedPickupTransition` was **true** → the re-match branch is skipped → a **second** Smoky
       Mo's pickup task mints. The guard is built for "two distinct orders at the same store stay
       distinct"; a reorder of the *same* order can look identical to that and trip the same path.
     - **(a) Erroneous paid PostTask — a receipt/closeout fired mid-job during the reshuffle.**
       `DELIVERY_COMPLETED` is emitted on **leaving PostTask** (`EffectMap.kt:286-…`), carrying the
       `lastPostTaskFields` pay breakdown (`PlatformRegion.kt:40-65`). The #518 guard keys
       `DELIVERY_COMPLETED` on the *completed task* to stop a re-entered `PostTask→nav→PostTask`
       double-count (`AppEffect.kt:34-35`) — but a forced reorder + manual reshuffle is exactly the
       kind of out-of-order PostTask/nav flapping that stresses that guard. Suspect a **PostTask
       frame got attributed to the wrong leg** (or fired before a leg was really done), emitting a
       spurious paid completion. Cross-references the dropoff-attribution issues in #3/#5.
   - **What the desk agent should pull / check (`app_events` + `app_state_snapshots`, ~17:16
     onward, DoorDash — same job as #7):**
     - **Reconstruct the task timeline:** offer/add-on events for BK; how many pickup tasks the job
       held and their stores (looking for the **duplicate Smoky Mo's**); whether the BK add-on
       folded in vs minted; the order in which pickups/dropoffs minted and completed.
     - **The erroneous PostTask:** find every `Flow.PostTask` entry/exit and each `DELIVERY_COMPLETED`
       — which task each attributed to, the pay on each, and whether one fired for a leg that wasn't
       actually delivered (or fired twice). Check `lastAnnouncedPostTaskTaskId` / the #518 key.
     - **`isStackedPickupTransition` at the Smoky Mo's return:** if logged/derivable, confirm whether
       the reorder set it true and that's what skipped the re-match.
     - **Net integrity:** did the job's economics / completion count end correct despite the duplicate
       pickup and erroneous PostTask, or are there phantom/double legs? Reconcile against actual pay.
     - **Unknown screens:** as with #7, X-Ray the window — the add-on-reorder and reshuffle screens
       may be UNKNOWN.
   - **Status:** Open — **desk-review marker**, awaiting capture upload. Recorded only, no code
     changes. Likely touches the **#499 same-store re-mint guard**, the **#518 PostTask
     double-count guard**, and the add-on/reorder task-lifecycle frontier (cf. #503/#505); a strong
     candidate for a `SessionReplay` Level-B repro once the capture is in hand.
