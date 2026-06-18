# Design: running $/hr and its degradation over time

**Status:** Proposal / problem statement. No code yet. Captures the gap between
what the bubble HUD shows today as a live "$/hr", what an ideal on-device metric
would be, and what we can realistically build from the data we already persist.
Written off the developer's observation (2026-06-14) that the live "Running at
$/hr" co-hero (#460) "doesn't make sense as a human looking at it" — it doesn't
degrade the way real earnings do.

Cross-refs: the #460 co-hero (`app/.../ui/bubble/cards/FlowCardItem.kt`), the
offer evaluator ($/hr at accept, `domain/.../evaluation/OfferEvaluator.kt`), the
event log we'd mine for history (`core/database/.../event/AppEventEntity.kt` +
`domain/.../model/event/payload/AppEventPayloads.kt`), and the field-testing
"Bubble HUD live $/hr is inflated right after accept" research note
(`docs/field-testing/README.md`).

---

## 0. Why this exists

"$/hr" is the single number a dasher steers by. It is also the easy number to
get subtly wrong, because the honest version depends on a denominator (time)
that is partly *estimated*, partly *not yet elapsed*, and partly *not even your
own choice* (a dry market, a slow merchant). DashBuddy currently shows three
different "$/hr" numbers with three different definitions, none of which behave
the way the developer expects when watched live. This doc fixes the definition
before anyone touches the rendering again.

The driving complaint, in the developer's words:

> the amount I'll make per hour depend[s] [on] if I finish this order I'm on
> within that hour and then if I get another offer in that time … like the next
> hour minus the minutes for this offer.

That is an **opportunity-cost** framing, and it is the correct one. The value of
the order you're on is not just its own pay rate — it is how much of your scarce
earning-time in the hour it consumes, and therefore how much *other* earning it
crowds out. Today's metric does not model that at all.

---

## 1. What we show today (and why it feels wrong)

There are three "$/hr" surfaces, and they do not share a definition.

### 1a. Offer card $/hr — pre-accept projection

`OfferEvaluator.evaluate()` (`OfferEvaluator.kt:24-29`):

```
estTimeMinutes = distance * avgMinutesPerMile + basePickupMinutes   // 2.5 min/mi, +7 min
dollarsPerHour = netPay / (estTimeMinutes / 60)
```

This is a pure a-priori projection from the offer's quoted distance and two
constants from `UserEconomy` (`avgMinutesPerMile = 2.5`, `basePickupMinutes =
7.0`). It is scale-invariant, so stacks evaluate fairly, and it is the right
*input to the accept/decline decision*. It is **not** a running rate and is not
the subject of the complaint — but note its denominator: it has no term for shop
time, wait time, or traffic, even though we measure all three elsewhere.

### 1b. Task card "Running at $/hr" — the one that feels wrong

`projectedHourly()` (`FlowCardItem.kt:669-673`):

```
pastMin   = max(0, (now - deadlineMillis) / 60_000)        // 0 until you're late
hourly    = netPay / ((estMinutes + pastMin) / 60)
```

- **Numerator:** the job's blended net pay, fixed at accept time.
- **Denominator:** the offer's *estimated* minutes, plus only the minutes you
  are past the platform's deliver-by deadline.

Four problems, all structural:

1. **It is frozen, not running.** Until you cross the deadline, `pastMin = 0`,
   so the denominator is a constant and the number never moves — despite the
   live dot and the "on track / dropping" label that say it is tracking you. It
   is the offer projection (1a, roughly) wearing a live costume.
2. **Your actual elapsed time is invisible.** Offer estimates 18 min, you've
   burned 35 but the deadline is 40 out → it still shows the 18-minute rate. The
   thing actually eroding your earnings (time you have genuinely spent) has no
   effect on the number.
3. **The deadline is the wrong erosion trigger.** DoorDash deliver-by times
   carry large slack and are about *lateness penalties*, not your pace. Your
   $/hr decays with every minute, not only minutes past a deadline. Keying
   erosion to the deadline means the number looks healthy through the entire
   window where it is actually decaying, then lurches down at an arbitrary
   moment — backwards from "glance and trust."
4. **The estimate itself is thin** (see 1a) and is reused here as the whole
   denominator.

There is also a separate, already-logged wiring bug where the drop-off leg shows
"Running at —" because the blended economics don't survive the pickup→dropoff
transition (field log 2026-06-13 #2). That is a plumbing defect, not a modeling
one, and is out of scope for this doc.

### 1c. Session $/hr — currently absent

The bubble top bar (`BubbleScreen.kt` `SessionMetricsActions`) shows session
earnings + miles only. An older `earnings / hours` session rate appears to have
been removed. So the one number that could be **measured with no estimate at
all** is the one we don't show.

---

## 2. The ideal metric (if we had every number)

Strip away data limits. What is the *correct* thing to display?

### 2a. The two denominators that matter

Real platforms (and real dashers) separate two rates:

- **Active $/hr** — pay ÷ time spent *engaged* on deliveries (driving to/from,
  waiting at store, at the door). This is the efficiency of the work itself.
- **Scheduled $/hr** — pay ÷ total clock time online, *including* the dry gaps
  between offers. This is what actually lands in your pocket per hour of your
  life spent dashing.

A dasher needs both: active $/hr tells you whether an order is worth doing;
scheduled $/hr tells you whether the market is worth being in. Today we conflate
and estimate; the ideal shows both, measured.

### 2b. The ideal live order metric: marginal opportunity cost

At any instant while you're on an order, the decision-relevant number is:

```
value of staying on this order =
    pay_remaining_on_this_order  −  (time_it_will_still_take) × market_going_rate
```

where `market_going_rate` ($/min) is **what you could be earning right now on the
next-best available offer in this market**. As the order drags, `time_it_will_
still_take` grows and the value erodes smoothly and continuously — the real
"degradation over time." Once the value goes negative, the order is actively
costing you money relative to the alternative, which is exactly the "drop it"
signal the #460 floor banner gropes at today.

### 2c. The ideal forward metric: the developer's "next hour"

```
projected_hour = this_order_net + market_going_rate × max(0, 60 − minutes_this_order_consumes)
```

A quick $7 order leaves ~50 minutes to refill at the going rate → high projected
hour. A long $10 slog leaves almost nothing → low projected hour, *even if its
own $/mile looked fine*. This is the opportunity-cost realism the developer
asked for, and it degrades correctly: as the current order runs long, the
minutes it consumes grow, the refill window shrinks, and the projected hour
falls — for the right reason (lost opportunity), not because of a deadline.

### 2d. What the ideal version fundamentally requires

Every ideal metric above leans on one quantity: **`market_going_rate` — the
live distribution of offers available to you in this market right now.** That is
the counterfactual "what else could I be doing this minute." It is the one
number that makes opportunity cost real rather than self-referential.

---

## 3. What we actually have

We persist an **event-sourced log** (`app_events`, one row per phase boundary,
JSON payloads — `AppEventPayloads.kt`). There is no Session/Delivery/Job table;
history *is* the event stream. What that stream already contains, per the
payloads:

| Datum | Source event / payload | Fidelity |
|---|---|---|
| Per-delivery actual pay + itemized breakdown | `DELIVERY_COMPLETED` → `DeliveryPayload.totalPay` / `parsedPay` | **Exact** (parsed from the receipt) |
| Per-phase timestamps (nav start, arrived, confirmed, completed) | Pickup/Delivery payloads | **Exact** (`obs.timestamp`) |
| Per-phase miles | odometer-at-entry / -arrival deltas | Good (odometer-derived) |
| Session running earnings at each completion | `DeliveryPayload.sessionEarningsAtCompletion` | **Exact** |
| Session start / end, duration, totals, offers accepted vs total | `DASH_START` / `DASH_STOP` payloads | **Exact** when summary screen seen |
| Every offer's full eval (est minutes, $/hr, net, distance) | `OfferPayload.evaluation` | Estimate (it's the projection) |
| Offer accept **and decline** outcomes + timing | `OFFER_ACCEPTED` / `_DECLINED` / `_TIMEOUT` | **Exact** |
| Live elapsed on the active job | `job.startedAt` / `acceptedAt` + `rememberNow()` | **Exact** |

So from the dasher's own on-device history we can *measure* (not estimate):

- realized pay per delivery and per session,
- actual engaged minutes per phase and per order,
- actual dry gaps (time between `DELIVERY_COMPLETED` and the next
  `OFFER_ACCEPTED`),
- the dasher's own recent **active $/min** and **scheduled $/min**,
- the dasher's own accept rate and offer cadence.

What we **cannot** get:

- **The live market offer distribution** (§2d). Only the platform sees every
  offer being sent to every nearby dasher in real time. Our pledges (on-device,
  no reverse-engineering, no real-time market scraping) deliberately forbid
  reconstructing it. The paid/academic tiers (#193/#194) deal only in
  k-anonymized *aggregate* query results under DP budget — explicitly **not** a
  real-time per-driver opportunity feed, and not available to the free local
  tier this metric lives in.
- **Platform-accurate "active time."** In some earn modes DoorDash pauses its
  own clock between offers; our wall-clock engaged-time approximation will
  diverge from the platform's number.
- **Break vs. dry-market disambiguation.** A 25-minute gap could be a thin
  market or the dasher eating lunch. Without an explicit "I'm on break" signal
  we cannot tell, which caps the trustworthiness of any scheduled-$/hr
  denominator that spans long gaps.

---

## 4. Can we build the ideal version? — honest gap analysis

**No — not the literal ideal, and the reason is specific and unfixable on the
free local tier: we do not and (by pledge) will not have the live market offer
distribution.** Every ideal metric in §2 multiplies time by `market_going_rate`,
and that rate is the platform's private information.

**But the gap is exactly one term, and it has a defensible proxy.** Substitute
the dasher's *own recent realized rate* for the unknowable market rate:

```
market_going_rate  ≈  the dasher's own trailing active $/min (from their event log)
```

This is honest in a way the current metric is not, because:

- The trailing realized rate is **measured**, not projected — it is the dasher's
  actual recent experience of this market, which is the best on-device estimate
  of "what I'd be making instead."
- It is computed **entirely on-device from the dasher's own history** — no
  aggregation, no network, fully inside the free-tier privacy posture.
- It self-corrects: in a hot market the proxy rises, in a dry one it falls, so
  the opportunity cost tracks conditions with a lag instead of a constant.

Its limits must be stated **in the UI**, not buried: the forward/opportunity
number is an *estimate from your own recent pace*, not a market truth. In a thin
market (few recent deliveries) the proxy is noisy and should widen to a range or
fall back to the measured-only rate. This is the difference between the ideal
(§2) and the buildable (§5): the ideal knows the market; the buildable knows
*you*.

So: we cannot show "what the market would pay you instead," but we *can* show
"what you've actually been earning, and what this hour is on track to earn at
that pace" — which is what the developer actually asked for.

---

## 5. What we can realistically build, layered

Four buildable layers, cheapest/most-honest first. Each is independently
shippable.

### Layer 0 — Restore the measured session rate (no estimates)

Bring back a **session $/hr** in the top bar, defined as `sessionEarnings ÷
elapsedOnline`, and — once we compute engaged time from phase timestamps — show
**active $/hr** beside it (§2a). This is the most trustworthy number we can
display because it contains zero estimates. Cost: trivial.

### Layer 1 — Make the task co-hero honest (fix the §1b complaint directly)

Replace the deadline-anchored denominator with an **elapsed-vs-estimate** one:

```
effectiveMinutes = max(estMinutes, elapsedSinceAcceptMinutes)
hourly           = netPay / (effectiveMinutes / 60)
```

- Inside the estimate: holds at the accept-time rate (no spike, matches the
  accept decision).
- Past the estimate: every extra minute erodes it, smoothly and immediately —
  the degradation the developer is missing.
- The deadline stops driving the rate (it keeps driving the *timer* co-hero,
  which is its correct job).

This is a few lines in `projectedHourly()` and makes the "on track / dropping"
label finally truthful. It also defuses the "inflated right after accept" note
(no tiny-denominator $120/hr spike, because the denominator is floored at the
estimate). **Recommended first change.**

### Layer 2 — The opportunity / "next hour" projection (the developer's model)

Compute a trailing **baseline $/min** from the event log (active variant for the
order metric, scheduled variant for the session metric) and surface:

```
projected_hour = this_order_net + baseline_per_min × max(0, 60 − minutes_consumed)
```

The order metric in §2c, with the §4 proxy substituted for the market rate.
Needs a small rolling computation over recent completed deliveries (pull from
`DELIVERY_COMPLETED` payloads). Label it explicitly as a pace-based estimate.

### Layer 3 — Better time estimates from the dasher's own history

The §1a/§1b estimate (`distance × 2.5 + 7`) ignores shop time, wait time, and
traffic that we already observe. Replace the constants with values learned from
the dasher's own completed deliveries: per-mile drive time, per-item shop time
(we already track shop pace), and typical store dwell. This improves every
downstream rate and the accept/decline projection itself. Largest effort;
deferred until Layers 0–2 prove the framing.

---

## 6. Recommendation

1. **Ship Layer 1 now** — it directly answers the complaint, is small, and
   removes the misleading frozen-then-lurch behavior.
2. **Restore Layer 0** alongside it so the glance surface has at least one
   fully-measured rate.
3. **Spec Layer 2** as the headline metric (the developer's "next hour"), with
   the §4 honesty caveat baked into the label, once Layer 1 is validated in the
   field.
4. **Hold Layer 3** until the framing is proven.

The honest north star: we will never show "what the market would pay you
instead," but we can show, truthfully and on-device, **what you have actually
been earning and what this hour is on track to earn at that pace** — and we
should never dress an estimate up as a measurement again (the §1b mistake).

---

## 7. Open questions

- **Active vs. scheduled as the default headline** — which does the dasher steer
  by? (Leaning: show active as the order metric, scheduled as the session
  metric.)
- **Trailing-window length** for the baseline — last 60 min? last N deliveries?
  last session? Tradeoff: responsiveness vs. noise in a thin market.
- **Thin-market fallback** — below how many recent deliveries do we drop the
  opportunity projection and show measured-only?
- **Break detection** — is an explicit "I'm on break" toggle worth it to keep
  long gaps from poisoning scheduled $/hr? (Single-user alpha: maybe later.)
- **Display shape for the eroding number** — always-show with color escalation,
  or hide-until-below-projection (the field-note debate)? Layer 1 makes
  always-show safe because the number is no longer spiky.
