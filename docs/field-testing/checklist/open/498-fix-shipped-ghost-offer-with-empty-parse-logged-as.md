status: active
issues: #498, #1, #2, #503

- **✅ FIX SHIPPED — "ghost offer" with EMPTY parse logged as a card (#498). CONFIRM ON DASH.**
  Fixed by gating the `offer_popup` rule on a parsed `payAmount` (a blank/chrome-only frame with no
  pay is no longer recognized as an offer; proven by `GhostOfferReplayTest` replaying the real
  16:31:52 ghost capture — now classifies UNKNOWN, emits no OFFER_RECEIVED). **Confirm on dash: 0/2 —**
  no blank-store / `$-2/hr` Offer card should appear (mid-offer, between offers, or right after an
  accept), while **real offers still recognize normally**. If a blank card still appears, capture the
  `offer_popup` frame + the offer event. *(Below is the original watch context, kept for reference.)*
  A phantom Offer card appeared in the stack (between Mello Mushroom and Pei Wei) with **no store, no
  pay, no miles** — Score 24, `$-2/hr`, Net `-$0.36`, outcome **Timed out**. Hypothesis: a partial
  `offer_popup` frame whose chrome (Decline + Accept/footer id) satisfied `require` before the content
  (store `display_name` / pay `$`) rendered → empty parse, still scored + logged as `OFFER_TIMEOUT`.
  The morning's dedupe/self-recognition fixes wouldn't catch it (distinct empty hash; real DD popup,
  not our overlay). **What to watch:** any Offer card (live or in the last-dash stack) that shows a
  **blank store and no pay/miles** — note when it appears (mid another offer? between offers?) and
  **grab the `offer_popup` capture + `OFFER_TIMEOUT` event** so we can confirm the partial-render tree
  and decide a validity/settle gate. (See 2026-06-13 log entry #1.)
  - Sightings: 1 blank-offer (2026-06-13, desk/screenshot). **2026-06-14 (dash #1):** no blank-store
    *offer* card recurred — but the sibling **premature drop-off card** (2026-06-13 #1, same
    unsettled-frame class) DID recur, so the partial-render root is real. **2026-06-14 (dash #2,
    ~16:35–16:36 UTC):** the **blank-offer card itself RECURRED** — a preframe/chrome of the offer
    recognized as an Offer with **no value**, again **`$-2/hr`** (same Net `-$0.36`-class figure as
    the 2026-06-13 sighting — i.e. the all-zeros economics fallback). So now **2 separate
    blank-offer sightings** on top of the recurring premature-dropoff sibling — the empty-offer
    variant is confirmed reproducible, not a one-off. Grab this dash's `offer_popup` capture +
    `OFFER_TIMEOUT`/offer event near 16:35 UTC. **2026-06-15 (~00:33–00:34 UTC, dash #2 cont.):**
    **new trigger — a ghost offer fired immediately AFTER the dasher ACCEPTED an offer.** The app
    "acted like it got a new offer" right on accept, again blank / **`$-2/hr`** all-zeros. So the
    blank-offer card isn't only a pre-render of an *incoming* popup — it can also spawn on the
    **post-accept transition** (a 3rd blank sighting, new trigger). Hypothesis extension: the
    accept→job transition may re-emit/observe a stale or chrome-only `offer_popup` frame that
    re-satisfies `require` with no content → a phantom "new offer." Grab the `offer_popup` +
    offer/accept events near 00:33 UTC to see whether the ghost frame is a leftover of the
    just-accepted offer or a genuinely new partial popup.
  - **Triaged 2026-06-15 → [#498](https://github.com/sjtrotter/DashBuddy/issues/498)** (recognition
    rejects incomplete frames — the `offer_popup` rule must `require` the scored fields and an empty
    parse must not become an offer; the all-zeros economics is `sha256("null|null|null|")`). The
    post-accept ghost-offer trigger also feeds **#503** (Job container — the accept→job transition
    shouldn't re-observe a chrome-only offer frame).
  Triage source: first-line blame 2026-06-15 (`7cc0a01de`, original README L2494).
