status: active
issues: #1104, #1110, #1118, #1119

- **🆕 NEW — a decline you confirm fast must still be recorded as a decline, not a timeout.** Five of
  the 09-10/09-11 pull's seven `OFFER_TIMEOUT`s were real declines: the dasher's tap on the confirm
  sheet's **Decline offer** button arrived 62–171 ms before that sheet's own frame was admitted, so
  the click rule (`screenIs: offer_popup_confirm_decline`) did not match, the click fell to UNKNOWN,
  and `OFFER_EXPIRY` resolved the offer as a timeout. **On-dash (deliberate, both ways):** on one
  offer, tap Decline in the heads-up notification and then confirm **as fast as you can**; on the
  next, wait a full second before confirming. **Desk:** both must appear as `OFFER_DECLINED` in
  `app_events`; a `OFFER_TIMEOUT` with a preceding `OfferActionReceiver: decline_offer` line, or with
  a `Denied confirm_decline` WARN in the seconds before it, is the bug. Cross-check the Offers tab's
  accept/decline/timeout bar against what you actually did.
  - Issue: #1104. Confirmed: 1/2 (desk 2026-10-05: one `OFFER_TIMEOUT` beside a `Denied confirm_decline` on 10-01, ~0.3 s before the card's own countdown end — by design; no lost decline on 10-03/10-04)
    - desk 09-15: SECOND SIGHTING, not a confirmation — 9 of 12 `OFFER_TIMEOUT`s were declines (confirm tap 24–229 ms before the confirm sheet's admitted frame); the sheet itself WAS admitted in time every time (the `Denied confirm_decline` WARN proves it), so the race is between the click and window sub-pipelines. Dev reported it independently the same day. Counts on #1104.
    - desk 09-20: IMPROVED, not closed, and the MECHANISM HAS MOVED — 1 `OFFER_TIMEOUT` in 23 offers (09-16/17), against 9-of-12 on the 09-15 pull; the one that fired was still a real decline (offer `0c4fc920`, 09-16 18:40: `initial_decline` click → UNKNOWN confirm click → `offer_popup_confirm_decline` screen → `Offer Timed Out!`). But **zero** clicks classified `confirm_decline` in 1 440 envelopes, so PR #1110's `screenIs`-array widening cannot be shown to have fired at all: on 8.97.8 the confirm sheet renders **no button label**, and a human tap on the Compose card emits no click event at all (see the decline-bound item and the 09-16→19 entry's Bug 3).
    - desk 09-21: UNEXERCISED — `aab8d960` predates PR #1118, so all 22 offers logged
      `OFFER_TIMEOUT` (10 real accepts + 12 real declines, each decline carrying a `Denied
      confirm_decline` WARN or an `OfferActionReceiver: decline_offer` line). Zero clicks classified
      `accept_offer` / `decline_offer` / `confirm_decline` in 125 click envelopes — the Compose
      no-click-event finding holds on 8.98.5, which is exactly why #1118 infers from transitions.
    - desk 09-26: the tap race is MOOT on this build — #1118's sheet inference caught every decline whose exit
      landed within 15 s (44/44), and zero confirm-button click envelopes exist to race. The remaining
      decline→TIMEOUT losses are the flow-less-dwell class (Dash Control), not the tap race. Still 0/2 on the
      deliberate fast/slow repro.
    - desk 09-29: the deliberate fast/slow repro is not identifiable in the pull. 42/42 declines inferred from the
      confirm sheet (1.55–10.59 s); the 2 lost declines are the flow-less `on_dash_map` dwell (20.46 s; 15.12 s ending
      in a replace), not the tap race. 0/2.
    - desk 10-18: deliberate repro not identifiable. 34 `OFFER_DECLINED`, 3 `OFFER_TIMEOUT`. One timeout carries the
      bug's signature and needs the dev's account: 10-18 08:36:24 offer `b8c16588`, two heads-up Decline taps (08:36:26,
      08:36:47), the confirm sheet admitted after each (08:36:28, 08:36:48), the card back between them, then
      `waiting_for_offer` at 08:37:05 resolved `OFFER_TIMEOUT` (17 s after the last sighting, past the 15 s window). The
      other two: 10-18 08:36:11 (the `OFFER_EXPIRY` timer, 3 s after a sheet sighting) and 10-17 18:37:03, a real accept
      (#1119 class).
  Triage source: first-line blame 2026-09-13 (`bd4c8a71b`, original README L527).
