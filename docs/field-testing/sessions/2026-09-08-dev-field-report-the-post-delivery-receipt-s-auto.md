## 2026-09-08 (dev field report — the post-delivery receipt's auto-expand tap is not firing; dev has to tap it manually)

**Date:** 2026-09-08 (reported from the dash in progress) · **Platform(s) tested:** DoorDash (app 8.95.6) ·
**Branch under test:** `master` at `3ef5eff9` (installed 09-07 evening; post-#1085) — but the symptom is
NOT new to this build, see below · **Field conditions:** dev's own words: "the button click on the delivery
summary post-delivery is not working (the auto click). I am having to click it manually again."

### Bugs

1. **The `EXPAND_EARNINGS` auto-tap on the collapsed delivery receipt does not fire.** Desk-side
   exploration against the 09-07 pull (same app version), all hypotheses:
   - **Likely cause A — the expand target's view id is gone (8.93.7+ id-less receipt, the #1029 class).**
     `doordash.screen.delivery_summary_collapsed` binds `expandButton` on
     `hasIdSuffix: expandable_view` (negated on `Total online time`, #734). On the fielded 8.95.6
     collapsed frames (`delivery_summary_collapsed/2026-09-07_08-20-05-311` and `…-366`) the only
     clickable nodes are `overlay_view`, `prism_sheet`, and three **id-less** `android.view.View`s whose
     subtrees read `This offer` / `$40.57`, `Send`, and `Continue dashing` — no node carries
     `expandable_view` or `expandable_layout`. 15 of the 16 committed `delivery_summary_collapsed`
     fixtures carry the id; the one that does not is the 08-23 (8.93.7) capture. The bind is
     `optional: true`, so a null target simply makes `EffectMap.diffExpandAction` return nothing — no
     `SETTLE_UI` arm, no `UiInteractionHandler` line, no `Denied` WARN. The 09-06 and 09-07 logs
     contain **zero** `EXPAND_EARNINGS` traces of any kind, consistent with the effect never being
     emitted (the 09-07 pull's only verified-click lines are the six `decline_offer` taps). If this is
     right, the tap has been dead since DoorDash 8.93.7 (~08-14), silently — an optional target bind that
     stopped resolving has no liveness signal (the #937/#1036 alarms cover recognition and parse fields,
     not `bind` targets).
   - **Likely cause B (stacked behind A) — the capability is not granted.** Even with a bound target the
     tap is an AUTOMATION fire and #843 grants nothing by itself: both recent pulls log
     `INFO/Consent: reconciled 4 capabilit(ies) from rule load (none granted — awaiting consent)`, and the
     sibling automation (`confirm_decline`) is being denied on every frame (`Denied confirm_decline — no
     granted capability … (fail closed)`, 68× on 09-06, 13× on 09-07). So fixing A alone would surface
     the tap as a `Denied EXPAND_EARNINGS` WARN until the capability is allowed in the consent prompt or
     under Settings → Data & Privacy → Automation & Consent. Worth confirming whether the prompt has been
     shown and deferred ("Not now") or never fired — the log has no line for either.
   - **Candidate re-anchor (for the dev to rule on, not applied):** bind the expand target on the id-less
     clickable node whose subtree carries `This offer` (and not `Total online time`), the way #1029
     re-anchored the money reads on text; `UiInteractionHandler`'s label verification would then need a
     label for a node that has none of its own (only descendant text) — check `clickNodeStrict`'s
     candidate ranking (#600) handles a text-less clickable container.
   - **Status:** Confirmed by the dev the same day → **#1093** (rule re-anchor on the id-less
     `This offer` row + the handler's relaxed bounds re-find + the optional-bind liveness signal +
     the three 09-07 receipt frames as fixtures). Checklist item added.

### Meta / architecture

- **This re-frames every "auto-expansion interval" the last three desk analyses measured.** The
  2.56–3.00 s collapsed→expanded intervals on 09-05, and the 2.865 s one on 09-07 that "confirmed"
  #1033 layer 1, were the dev's **manual** taps, not DoorDash auto-expanding and not our tap. #1033's
  8 s `receiptExpandGraceMs` was therefore sized against a human reaction time; it still did its job
  (the itemization landed inside the window), but the premise "the receipt auto-expands ~3 s after the
  grace" recorded in the 09-05 entry is wrong — the receipt does not expand on its own at all. Once the
  tap works again, the expansion should land at `expandSettleMs` (the settle delay) after the collapsed
  frame, well inside the 2.5 s expanded window, and layer 2's re-price path would be exercised only by a
  genuinely late manual tap.
- **Silent-death family, sixth member candidate:** an optional `bind` target that stops resolving on a
  surface whose rule still matches. Same shape as #1036 (matched-but-parsed-nothing), one block over.
