## 2026-05-16 — DoorDash session

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `af54b87` (post-#145 personal-economy-v2 merge — the build that introduced the cost-breakdown bubble HUD)
- **Field conditions:** developer dashed on DoorDash; mix of accepts and declines.

### Bugs

#### 1. DoorDash decline → bubble says "Offer Timed Out" instead of "Offer Declined"

- **Repro:** Receive a DoorDash offer, tap **Decline**, confirm **Decline offer** in the dialog.
- **Observed:** Bubble shows "Offer Timed Out!" and the event is logged as `OFFER_TIMEOUT`. The decline isn't recognized at all.
- **Expected:** "Offer Declined" / `OFFER_DECLINED`.
- **Acceptance appears to work** correctly in the same session — only decline is broken.
- **Status:** Shipped in #255 + #259 + #261. The #255 attempt to treat `initial_decline` as a decline outcome was reverted (commit `e4dbe26`); the real fix landed in #259 (click-capture screen context dedup) + #261 (confirm-decline rule match-descendant-text), gated on field-test ground truth from item #8 of the 2026-05-17 flow-card session.
- **Hypothesis (from a quick desk read, not verified against field logs):** the decline flow is two clicks. First tap on the offer popup fires intent `initial_decline` and opens an "are you sure?" confirmation dialog; the confirm tap fires `decline_offer`. The confirm rule (`core/pipeline/src/main/assets/rules/doordash.json:2319-2328`) is gated on `screenIs: "offer_popup_confirm_decline"`. If the dialog closes before the click observation is matched against the dialog's screen classification, only `initial_decline` may end up on `PendingOffer.lastClickIntent`. `EffectMap.resolveOfferOutcome` (`core/state/.../EffectMap.kt:563-581`) only recognizes `decline_offer` / `accept_offer`, so any case where `decline_offer` doesn't land would fall through to `OFFER_TIMEOUT`.
- **What would confirm or refute this:** capture the click + screen event stream for a real decline session and check (a) whether the `offer_popup_confirm_decline` screen is being matched at all, and (b) which `lastClickIntent` value `PendingOffer` actually carries at the moment the offer resolves. If `initial_decline` is the value seen, the hypothesis above holds; if `lastClickIntent` is null/something else, the cause is elsewhere (rule text drift, ViewPipeline drop, dialog never matched as a screen, etc.).
- **One possible direction (if hypothesis holds):** treat `initial_decline` as a decline signal in `resolveOfferOutcome`. Worth considering vs. alternatives like making the confirm rule less screen-strict, or matching clicks against the screen at click-time rather than after the screen has changed. Not a recommendation — just a sketch for triage.

### Research / design

#### 2. Bubble HUD live $/hr is inflated right after accept

- **Field observation:** Immediately after accepting an offer and starting navigation, the bubble's order $/hr reads something like "$120/hr" because almost no time has elapsed. It re-anchors to a sensible number only after several minutes, by which time the dasher has already been looking at a misleading number that gives false confidence.
- **Status:** Open. Design call (hide-until-below-projection vs always-show-with-color) not yet made.
- **Why it matters:** every brand-new offer looks like a win at this point — the live rate is meaningless until enough elapsed time has passed for `payAmount / elapsed` to be informative. False optimism is worse than no number.
- **Proposal (developer's first instinct, exploratory — "I'm not sure, though"):** consider suppressing the live $/hr display until it actually drops *below* the offer's originally-projected $/hr. Above projection → silent (you're on or ahead of pace, nothing to act on). Below projection → display starts, because that's where the number becomes actionable ("I'm losing margin the longer this drags").
- **Where the wiring appears to live, for triage:**
  - Live rate is computed in `BubbleScreen.formatDollarsPerHour()` (`app/src/main/java/.../ui/bubble/BubbleScreen.kt:897-906`) as `earnings / hours` with a 60-second "--" gate (gate is on display only, not on signal).
  - `BubbleViewModel` currently captures only `payAmount` into `lastAcceptedOfferPay` on the offer→task-flow transition (`BubbleViewModel.kt:78-97`); the projected $/hr from `OfferEvaluation` isn't carried forward into the task flows, so the bubble doesn't currently have the projection to compare against.
- **Tradeoff to consider:** hide-until-below also hides the metric for the entire expected duration of the order, so if the projection was way off (bad merchant estimate, unexpected traffic), the dasher wouldn't see the problem until late. Alternative shape: always show, but mute/desaturate when above projection and escalate color when below. Not a recommendation — just two shapes to weigh.
