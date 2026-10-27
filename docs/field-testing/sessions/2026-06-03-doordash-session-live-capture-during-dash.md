## 2026-06-03 — DoorDash session (live capture during dash)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `776b0a8` (post-#272 merge; latest code on
  `master` is the #271 card-polish + #270 nav-generic-idle merges) — inferred,
  developer to correct if the build came from elsewhere.
- **Field conditions:** developer dashing on DoorDash; entry captured live.
  Includes a Shop & Deliver leg (pacing observed), an app-switch grace-resume,
  and a **reproducible crash in the post-delivery phase on both deliveries**
  (around the auto-expand click). Multiple short back-to-back dashes.

### Bugs

#### 1. App crashes in the post-delivery phase, at/around the automated "expand delivery details" click (REPRODUCED — both deliveries this dash)

- **Field observation:** Crashed in the post-delivery phase on **both** deliveries
  this session. First: right after a dropoff, "as soon as the app clicked, or just
  after the click" — the **automated** click that expands the post-delivery pay
  breakdown. Second: started a new dash immediately after the first delivery and it
  crashed again in the same post-delivery phase. Dasher's read: "there is something
  going on in the post-delivery phase," and it's "probably something to do with"
  the **recent state-machine / hooks-and-triggers changes.** No stack trace yet —
  dasher plans to pull captures + logcat.
- **Status:** Open — **reproducible (2/2 deliveries this dash).** The repeat on
  both deliveries rules out the earlier "one-off stale-node race" framing: a
  consistent crash points at a **code path** in the post-delivery phase, not
  timing luck. **Blocked on the crash stack trace** to pin the layer.
- **Recent-change surface (the dasher's "hooks and triggers" hunch, corroborated
  at the desk).** The post-delivery phase has the most recent churn anywhere in
  the state machine, all in the build under test: `4575441` (post-task
  best-effort + dup-skip + UDF click delay + safety screenshots, #266) reworked
  the exact expand/announce flow; `d584060` (DELIVERY_CONFIRMED closes drop-off
  task on transition away) changed how the task is retired here; `5f44413`
  (transition override system) is the trigger plumbing — the post-delivery
  transition fires `triggerOverrideEffects(obs, TASK_COMPLETED)`
  (`EffectMap.kt:206`).
- **Sharpened hypothesis — the new SETTLE_UI deferred-click path (now favored).**
  The #266 work no longer clicks inline; the `delayMs: 500` expand click
  (`doordash.json:670`) is routed through a brand-new round-trip:
  `diffRuleEffects` (`EffectMap.kt:653-662`) sees CLICK with `delayMs > 0` and
  emits `ScheduleTimeout(SETTLE_UI)` carrying a **serialized** click context
  (`serializeClickContext`, `:695-714`); when it fires, `diffSettleUiTimeout`
  (`:673-693`) **reconstructs** a `NodeRef` (`deserializeNodeRef`, `:716-732`)
  and re-dispatches the click against possibly-changed live UI. That serialize →
  defer ~500ms → reconstruct → re-dispatch chain is new surface sitting exactly
  in the crashing phase, and the ~500ms delay matches "just after." It then
  re-enters `UiInteractionHandler.performClick` (see hypothesis (a) below — the
  unguarded `findNodeByBounds` recursion / raw node ops).
- **What the desk pass ruled OUT:** the `effect.delayMs!!` at `EffectMap.kt:659`
  is **guarded** by `(effect.delayMs ?: 0L) > 0L` at `:653` (not the NPE);
  `serializeClickContext` / `deserializeNodeRef` use null-safe casts throughout;
  `parsedFieldsToMap`'s reflection (`:753-758`) is `try`-wrapped. No obvious throw
  site among them — consistent with needing the trace.
- **What fires at that exact moment (the post-task collapsed screen):**
  `doordash.json:659-678` runs **two** effects when the collapsed `post_task`
  screen matches: (1) `click: $expandButton` (gated `isExpanded == false`,
  `dedupeKey: expand_pay_breakdown`, `throttleMs: 1000`, **`delayMs: 500`**),
  then (2) a `screenshot` (`prefix: "Delivery - {totalPay}"`, `throttleMs:
  60000`). So "at/just after the click" overlaps the click dispatch, the
  platform's expand animation, **and** the screenshot capture — three places a
  crash could originate.
- **Hypotheses (desk read, not verified against a trace — all speculative):**
  - **(a) The auto-click dispatch itself.** `SideEffectEngine.kt:113-116` →
    `UiInteractionHandler.performClick` (`UiInteractionHandler.kt:19-64`) →
    `AccNodeUtils.clickNode`. `performClick` re-resolves the target against the
    *live* root via `findAccessibilityNodeInfosByViewId` / `…ByText` / a bounds
    walk (`findNodeByBounds`, `:70-88`). The empty/null paths are guarded
    (returns with a `Timber.w`), but the recursive `findNodeByBounds` and the
    raw `AccessibilityNodeInfo` operations aren't wrapped in try/catch — a stale
    / recycled node mid-expand could throw `IllegalStateException`. Plausible but
    not obviously the most likely.
  - **(b) The screenshot effect that fires right after.** `ScreenShotHandler.kt`
    uses `service.takeScreenshot` → `Bitmap.wrapHardwareBuffer` →
    MediaStore write, then `result.hardwareBuffer.close()` in `onSuccess`
    (`:39-45`). The body is `try/catch(Exception)` wrapped and `saveToGallery`
    catches its own exceptions, so an *app-killing* crash here seems less likely
    — but the `hardwareBuffer.close()` sits *outside* `saveToGallery`'s guard, so
    if `saveToGallery` throws unexpectedly the buffer may leak rather than crash.
    Lower suspicion, but worth ruling out via the trace.
  - **(c) Processing the *expanded* screen the click produced (favored on
    timing).** "Just after the click" is also exactly when the breakdown expands
    and DoorDash emits a burst of accessibility events for the new content, which
    our pipeline then parses (the expanded `post_task` parse that yields
    `parsedPay` / `payLineItems`, c.f. `ParsedFieldsFactory` per 2026-05-19 #4).
    A null/format assumption in that expanded-pay parse would crash *as a result
    of* the click rather than *in* it — which matches the dasher's "just after"
    wording better than the click dispatch itself.
- **Relationship to prior work:** the post-task auto-expand pipeline was last
  touched in **#266** (2026-05-19 bug #4 — first-click race / re-fire). Note the
  rule now carries `delayMs: 500` (an initial delay before the first click),
  which is the #266 timing fix. This crash is a **new** symptom (a hard crash,
  not the previous "click didn't complete" / "bubble re-fired"), so it's either
  a regression introduced alongside that flow or a latent path #266 didn't touch.
- **What would confirm or refute this at the desk:**
  - **Pull the crash stack trace** (logcat / the on-device crash log). The top
    frame immediately disambiguates: `EffectMap.diffSettleUiTimeout` /
    `deserializeNodeRef` or `UiInteractionHandler` / `AccNodeUtils` → the new
    deferred-click path; a parse class → the expanded-screen path (c);
    `ScreenShotHandler` → (b).
  - Look for a `SETTLE_UI` timeout firing right before the crash — its presence
    ties the crash to the deferred-click round-trip. And test whether the crash
    still repros on a delivery where the expand never auto-fires (throttle/dedupe
    suppressed) — if it doesn't, the deferred-click path is the culprit.
  - If a snapshot of the expanded breakdown was captured this dash, run it
    through the parse path that builds `parsedPay`/`payLineItems` and check for
    a null/format assumption that the live expanded screen would violate.
  - Cross-check the screenshot output: a `Pictures/DashBuddy/… Delivery - …png`
    file existing for that delivery means the screenshot effect ran to
    completion (pushes suspicion toward (a)/(c), away from (b)).
- **RESOLVED — root cause found 2026-06-04 from the stack trace (≠ the
  hypotheses above).** The trace is a Compose layout crash, not an effect/parse
  crash: `java.lang.IllegalArgumentException: Key "delivery:<uuid>" was already
  used … provide a unique key for each item`, thrown from
  `LazyListMeasure → SubcomposeLayout` (the bubble card stack), **not** from
  `EffectMap` / `UiInteractionHandler` / a parse class / `ScreenShotHandler`. So
  the SETTLE_UI deferred-click and expanded-parse hypotheses (a)/(b)/(c) were
  red herrings — the auto-expand click only *triggers a recomposition* that
  re-measures the `LazyColumn`, and the completed-card list already contained a
  **duplicate `delivery:<taskId>` card**. `FlowCardMapper.fold` added a delivery
  card on *both* `DELIVERY_ARRIVED` and `DELIVERY_CONFIRMED`, assuming they were
  mutually exclusive; arrival-bearing dropoffs (photo / PIN / hand-it-to-customer,
  and the alcohol ID-scan from #149) fire both. Confirmed in this session's
  `db/dashbuddy-v2.db` (`app_events`): taskId `c0041f37` ARRIVED 17:59:23 →
  CONFIRMED 17:59:33 → crash 17:59:34; taskId `4d62f8ea` ARRIVED 21:56:44 →
  CONFIRMED 22:00:37 (×2) → crash 22:00:37. The `offer:` (05-25) and `posttask:`
  (05-22) crash variants are the same family. **Tracked as #297; fixed** (dedup
  the card list by id + a `distinctBy` backstop at the `LazyColumn` + regression
  tests). Field-validate via the #297 checklist item.

### Verification TODOs

#### 2. Shop & Deliver live pace ticked during the shop (#276 partial confirmation)

- **Field observation:** On a Shop & Deliver leg, the bubble pickup/shop card
  showed the live items/min pace and it **ticked** while shopping — the core
  #276 behavior. The dasher did **not** see the finalization (the frozen card
  after the leg) this dash, so the `total == "Done (x)" + "To shop (y)"`
  cross-check and the add-on-mid-shop case remain unconfirmed.
- **Status:** Partial confirmation logged against the #276 checklist item
  (Confirmed 1/2 — live ticking only). Needs a second dash to confirm
  finalization + add-on before the checklist item is retired.

### Open questions / investigations

#### 3. Switched apps mid-dash, came back to DoorDash, bubble showed "Session resumed (grace)"

- **Field observation:** Started another dash, switched to a different app, and
  on returning to DoorDash the **DashBuddy bubble** showed a message the dasher
  recalled as "recovered (grace)." Dasher wasn't sure why it fired.
- **Status:** Open — but **source now pinned** (see below). The likely-correct
  read is that this is the grace mechanism *working*; the open part is whether an
  app-switch *should* trip it and whether surfacing the message is desirable.
- **Source pinned (desk grep, high confidence):** the bubble string is literally
  **`"Session resumed (grace)"`** — `EffectMap.kt:319`,
  `add(AppEffect.UpdateBubble("Session resumed (grace)"))`. (This **corrects** the
  earlier hypothesis in this entry's first draft that the notice was DoorDash's
  own UI — it is a DashBuddy bubble message. The earlier grep missed it because it
  lives in `:core:state`, not `:app`.)
- **When it fires (`EffectMap.kt:299-320`):** on an **Offline → Online**
  transition where the resumed region's `session.sessionId` **equals** the prior
  session's id (`:316`). That branch is reached only when the session was held
  alive under the **grace window** (`DEFAULT_GRACE_MS = 10_000L`) rather than
  finalized — i.e. DashBuddy briefly saw the region go Offline, then back Online
  within ~10s, and resumed the **same** dash (no new `DASH_START`, no odometer
  restart — `:317` comment: "same session, no start effects needed").
- **What most likely happened (hypothesis):** while DoorDash was backgrounded
  during the app-switch, DashBuddy stopped seeing DoorDash's online/idle screen
  and the region read as **Offline**; returning within the grace window flipped it
  back **Online** with the same session → the grace-resume branch fired and posted
  the bubble. By construction (`:316` checks `prevSession?.sessionId ==
  nextSession.sessionId`) this means it **resumed the same dash**, which is the
  *desired* outcome for a brief mid-dash blip.
- **This is a (partial) positive for #286/#290.** That checklist item's
  regression-watch is exactly "a brief offline blip mid-dash still **resumes the
  same** dash (no spurious new session)." Seeing "Session resumed (grace)" — and
  *not* a fresh-session reset — on an app-switch return is one clean sighting of
  that path holding. Logged as a partial confirmation there.
- **The genuinely open parts (not defects yet — UX / scope questions):**
  - **(a) Should a mere app-switch register as Offline at all?** If DashBuddy is
    just backgrounded (its service alive, simply not receiving DoorDash events),
    treating "I stopped seeing DoorDash" as "the region went Offline" is the same
    class of concern as 2026-05-29 bug #2 ("looking at another screen mustn't
    mutate active-task state"). Here it recovered cleanly via grace, but it's
    worth confirming the *task* (not just the session) also survived intact.
  - **(b) Is surfacing "Session resumed (grace)" to the user desirable?** It reads
    as internal-mechanism jargon (the dasher didn't know what it meant). Even at
    alpha-single-user, it may be noise — candidate to demote to a debug log, or
    reword to something a dasher parses ("Picked your dash back up"). Dasher's
    call; logging as a UX observation, not prescribing.
- **What would confirm or refute this at the desk:**
  - Pull DashBuddy logcat around the app-return: expect a `"Session grace resume:
    <id>"` line (`EffectMap.kt:318`) and an Offline→Online region transition
    within 10s, with the **same** `sessionId` before and after. That confirms the
    grace path (vs a fresh start, which would log `DASH_START` with
    `source = "interaction"`/`"recovery"` at `:310-313`).
  - Confirm the **active task** survived the blip (not just the session): check
    `activeTask` was non-null across the transition and `pendingDestructive`
    (the retire-grace) was cancelled on return, per `TaskLifecycleGuardTest`'s
    "returning to a task cancels the grace" expectation.
