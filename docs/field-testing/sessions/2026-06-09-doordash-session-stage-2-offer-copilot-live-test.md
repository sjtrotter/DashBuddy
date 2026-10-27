## 2026-06-09 — DoorDash session (Stage 2 offer-copilot live test)

- **Platform tested:** DoorDash
- **Branch under test:** `master` after **#327** (Stage 2b: manual Accept/Decline + collapse) — the
  offer-copilot build. Also includes #324 offer-card redesign, #326 auto-expand, #325 screenshot
  settle, #321–#323 brand system / components / job economics.
- **Field conditions:** one **$28 / 12.9 mi H-E-B** offer (looks **stacked** — see #4) at ~12:00:44;
  the dasher tapped the bubble's **Decline**, nothing happened on DoorDash, so they **declined
  manually**. Findings are grounded in the saved `app.log` + `captures/` + event DB at
  `/home/betty/dashbuddy/logs/2026/06/09`. All below are **hypotheses to triage — not concluded fixes.**

### Bugs

#### 1. In-bubble Accept/Decline can't click DoorDash — `performClick` searches the wrong window
The whole chain fired correctly — bubble Decline tap → `UiInput("decline_offer")` → EffectMap
`PerformOfferAction(DECLINE)` → `SideEffectEngine` → `performClick` — and failed only at the click:
```
12:01:16.910 SideEffectEngine: Performing offer action: DECLINE on doordash
12:01:16.912 UiInteractionHandler: Attempting click (Bubble DECLINE)
12:01:17.081 WARN  Could not find any live node for: Bubble DECLINE
             (id=com.doordash.driverapp:id/secondary_action_button_dash_plus, text=null, bounds=(0,0,0,0))
```
The viewId is **correct** — `…:id/secondary_action_button_dash_plus` is exactly what's in the captured
offer tree (`captures/…/offer_popup/…7f6048.json`), so the id mapping isn't the problem.
- **Likely cause:** `UiInteractionHandler` clicks against `AccessibilitySource.getLiveNativeRoot()`,
  which returns `service.rootInActiveWindow` (`AccessibilitySource.kt:34-36`). When the dasher taps
  the **bubble**, the *active* window is the bubble overlay (DashBuddy), **not** DoorDash's offer
  window — so the viewId search runs against the wrong tree and finds nothing. (The dasher then
  declined manually: captures `initial_decline` @12:01:19 → `decline_offer` @12:01:20.)
- **One direction to confirm:** resolve clicks across **all** windows via
  `AccessibilitySource.getWindows()` (already used by `WindowsChangedPipeline`; the service already
  requests `flagRetrieveInteractiveWindows`) instead of only `rootInActiveWindow`. Affects **both**
  Accept and Decline (shared path), so manual actions are fully non-functional until this lands.
- **Status:** Open. (Regression in #327's click path — found on first field test.)

#### 2. Offer bubble does not auto-expand from the background
The evaluation **did** post — `OfferEvaluationEvent` @12:00:44.483 → Chat
`[Good Offer] Recommended: ACCEPT | Score 74 | Net $22.48` @12:00:45.234 (~750ms later, matching
`OFFER_BUBBLE_EXPAND_DELAY_MS`, so the Stage-2a delay itself behaved) — but the bubble stayed collapsed.
- **Likely cause:** `setAutoExpandBubble(true)` has no effect when the posting app isn't in the
  foreground, and DashBuddy is backgrounded while DoorDash is foreground (the documented Android
  restriction flagged before the build). The heads-up notification posts (`IMPORTANCE_HIGH`);
  auto-expand is ignored.
- **Implication:** can't rely on background auto-expand. Options to weigh: (a) heads-up notification +
  "tap to review"; (b) a full-screen-intent surface; (c) keep the bubble but open it on tap. This
  **reshapes Stage 2/3** — the auto-action countdown was meant to anchor on "bubble shown"; if the
  bubble only opens on a tap, the countdown must anchor on the tap (or not auto-fire without an open
  surface). Worth deciding the surface before building 2c/3.
- **Status:** Open.

#### 3. Bubble did not collapse after tapping an action
Tapping Decline did not collapse the bubble to its head.
- **Likely cause (hypothesis):** the collapse bridge does `(context as? android.app.Activity)?.finish()`
  in `BubbleScreen`, but Compose's `LocalContext.current` is usually a `ContextThemeWrapper`, not the
  Activity — so the cast is null and `finish()` never runs. Standard shape: unwrap via
  `ContextWrapper.baseContext` (`findActivity()`).
- Note: collapse is dispatched on the **tap** (independent of whether #1's click succeeds), so it's a
  separate defect from #1 — also unconfirmed is collapse-vs-dismiss behaviour, which we can only test
  once `finish()` actually fires.
- **Status:** Open.

### Open questions / investigations

#### 4. Offer "recognized early" and re-evaluated with diverging results (stacked-offer parse?)
The one offer flip-flopped across three evaluations as the screen settled:
- 12:00:45 `[Good Offer] ACCEPT Score 74 Net $22.48` (TTS "**12.9 miles**")
- 12:00:53 `[Bad Offer] DECLINE Score 6 Net **-$9.62**` (TTS "**22.5 miles**")
- 12:01:01 `[Good Offer] ACCEPT Score 74` (TTS "12.9 miles")

Distance flips **12.9 mi ↔ 22.5 mi**, and there's an `UNKNOWN` window carrying `accept_button` at
12:00:44.029 → classified `offer_popup` at 12:00:44.344 (~300ms later).
- **Hypothesis:** a **stacked** offer (multiple orders) whose screen re-parses inconsistently —
  sometimes a single leg (12.9 mi → good), sometimes the total (22.5 mi → the −$9.62 mis-eval) — as it
  renders. Recognition + evaluation fire **per frame** and are **not** settle-gated like the screenshot
  now is, so a transient/partial frame yields a spurious DECLINE-6. Likely the "recognized it early"
  the dasher noticed.
- **To dig:** diff the two captured `offer_popup` JSONs (`…7f6048.json` @44.374 vs `…225fd4.json`
  @52.489) for the one-leg-vs-total parse divergence; consider debouncing/settling offer eval or
  de-duping re-evals of the same `offerHash`.
- **Root cause CONFIRMED (desk, 2026-06-09 — diffed the two captures):** **self-recognition, not a
  stacked-offer parse and not the loading-bar reject.** Frame `225fd4` @52.489 (the "DECLINE-6"
  frame) is **our own Bubble HUD overlay** — its tree is `…android:id/content →
  androidx.compose.ui.platform.ComposeView` with our text (`"Recommended: ACCEPT | Score: 74 | Net:
  $22.48"`, `"H-E-B · 40 items"`, `"AWESOME OFFER"`), yet it was tagged `platform: doordash`.
  `ContentChanged`/`StateChanged` snapshot `rootInActiveWindow` but labeled it with the **event's**
  package; while our bubble was the active window over DoorDash, our overlay got mislabeled DoorDash
  and matched `offer_popup` (whose `require` was just "Decline" + "Accept" — satisfied by the bubble's
  new #327 Accept/Decline buttons), then parsed to junk ($0.00 / 0.0 mi) → phantom re-eval. (No
  `progress_bar` in our overlay, so the removed loading-bar reject was a red herring.)
- **Fixed (PR pending):** (1) attribute active-window snapshots to the window's **real** package and
  drop non-target windows — our overlay is skipped (`🚫 Skip active window: non-target pkg=…`); (2)
  `offer_popup.require` now also demands the `accept_button` / `accept_decline_footer_container` id,
  which our `content`-only overlay lacks. Dropped the settle/dedupe idea — it would have masked this.
- **Status:** Fixed — needs field re-validation (see checklist).
