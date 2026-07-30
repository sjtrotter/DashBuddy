# DashBuddy — Analytics & Home Redesign Brief

**For:** Claude Code (implementation)
**From:** design exploration in `Analytics Explorations.dc.html` (turns 1–5)
**Date:** 2026-07-28

Visual reference: open `Analytics Explorations.dc.html`. Option ids (`5a`, `3b`, …) below refer to cards in that file. Turn 5 + 3b/3c is the approved direction; turns 2 and 4 are explorations kept for context only.

---

## 1. The decision

Three structural changes:

1. **Home screen becomes "Today"** — forward-looking. Today's plan (projected from the user's own history), so-far-today numbers, a this-week recap pointer, review items, then entry tiles.
2. **Analytics becomes period-first.** A `‹ ›` pager and a date-range picker own the top of the screen. Tabs stay, but as *detail views* of the selected window — not as the primary navigation.
3. **New: Weekly Plan.** Not on Home. Delivered as a Sunday-evening notification → its own screen. This is the retention/subscription hook.

Tab set changes: `Money · Patterns · Decisions · Time` → **`Money · Offers · Time · Patterns`**. `Decisions` is renamed `Offers` and grown so it earns its place (§4). `Patterns` stays a tab but declares itself all-time (§5).

---

## 2. Home = "Today" (`5a`)

Replaces the current home content. Order top to bottom:

| Block | Content | Data |
|---|---|---|
| Header | `Monday, Jul 20` + clock, status pill (`READY`) | local |
| **Today's plan** | 24-cell strip = today's weekday row of the lifetime hour×day heatmap. Hours already passed render at 35% opacity. Tonight's best contiguous window is outlined in accent. Headline: `Best bet tonight: 5–8 PM · your Mondays run $19.20/hr` | existing heatmap aggregate, filtered to `today.dayOfWeek` |
| Weekly-plan pointer | Only rendered if a saved plan exists for the current week. `Your week is planned · 12 target hours across 4 windows · $280 projected` → Weekly Plan screen | saved plan row |
| **So far today** | 4 stats: kept (net), net/hr, drops, miles | existing `TODAY` PeriodEconomics — free |
| This week | net + `▲12%` + 7-point sparkline + `Recap →` | needs prev-window delta (§7.2) and net-per-day (§7.3) |
| Review items | one row per data-quality flag, with an action (`ASSIGN`) | existing `(No session)` / unattributed logic (#650, #660) |
| Entry tiles + Show bubble | unchanged behavior | — |

**Copy rule for the plan strip:** always say whose data it is and never promise. `from your own Mondays, lifetime — not a guarantee`. If fewer than ~5 samples exist for that weekday, say so instead of showing a rate.

---

## 3. Analytics = period-first (`3b`, `3c`)

### 3.1 Period control (replaces the 4-value segmented row)

```
[ ‹ ]  [  This week · Jul 13–19  ▾  ]  [ › ]
[ Day ][ Week ][ Month ][ Lifetime ][ Custom… ]
```

- `‹` steps one window back (Yesterday / Last week / Last month depending on granularity). `›` steps forward, **disabled** at the current window.
- Tapping the centre label opens the **range picker** sheet (`3c`).
- Granularity chips set the pager's step size. `Custom…` opens the same sheet on the calendar.
- Selected window persists across tab switches and across app restarts.

### 3.2 Range picker sheet (`3c`)

Bottom sheet, two parts:

- **Presets:** Today · Yesterday · This week · Last week · This month · Last month · Lifetime.
- **Calendar:** month pager; each day the user dashed carries a dot, brighter dot = more earned that day; range selection highlights the span with rounded caps; future days disabled; today outlined. Commit button states the range: `Show Jul 13 – 19`.

### 3.3 Recap hero (above the tabs)

Net for the window, delta vs the previous equivalent window, a sparkline, and a one-line factual summary (`$412.83 gross · 47 deliveries · 15h 49m online · 37% acceptance`). This is where the "your week" idea lives — above the tabs, not instead of them.

---

## 4. Money tab (`5c`, plus `2a`)

### 4.1 Remove the True Net waterfall

The four-bar Gross/Fuel/Non-fuel/Net chart fails: fuel and non-fuel are always a thin sliver against gross, so the bars carry no information. Replace with **"Where your money went"**:

```
$412.83 came in.
$116.73 went to the car.
$296.10 stayed with you.
```

plus one 3-segment bar (kept / gas / wear) and a legend with real dollars. Under it, a collapsed disclosure line: `Car costs = 208.6 mi driven × $0.56/mi, frozen when you accepted each offer`. Expanded, it can show the per-mile split.

Rules: plain words (`gas`, `wear on the car`, `stayed with you`) — not `non-fuel operating cost`. No cents-per-dollar math on the default view (tested as too abstract). Never restate costs as a percentage in the headline.

### 4.2 Other Money changes

- **What made up the gross** — 3-segment bar: base pay / tips / bonuses, with the one-line insight (`tips were 57% of your pay this week`). *Needs §7.1.*
- **Per hour / per mile / per drop** — three equal stat tiles.
- **Earnings by day** — keep, make each bar tappable (tooltip: day, gross, deliveries).
- **Platform split** (`2a`) — DoorDash vs Uber Eats rows with amount + delivery count. *No plumbing needed — `periodEconomics(period, platform)` already exists and is simply never called with a platform.*
- **Needs a look** — consolidate the stacked amber callouts into one card with a count in its header and one row per item, each with an action. Same underlying flags, less noise.
- **Top stores** — add the platform badge; otherwise unchanged here (the full leaderboard lives in Patterns).

---

## 5. Offers tab (was Decisions) (`2b`)

The tab is currently three cards and dead air. Grow it:

- **Offer funnel** — keep the acceptance-rate donut/bar, but pair it side by side with **"Said no to ~$182.40"** (est. net across declines) so the top of the screen carries two ideas, not one.
- **Estimate vs reality** — two bars: est. $/hr at decision time vs realized net $/hr for accepted offers, plus the plain-English ratio (`accepted offers realized ~89% of their decision-time estimate`). *Needs §7.5.*
- **This week's offers** — the missing feature. Filter chips (All / Accepted / Declined / Timed out) over a list: store name, time, payout, distance, est. $/hr, score, and a status pill. Declined/timed-out rows at reduced opacity. Footer: `See all 128 offers`. *Needs §7.4.*
- Keep the standing disclosure: these are **frozen decision-time estimates, never realized net**.

---

## 6. Patterns & Time

**Patterns (`2c`)** — the tab reads all-time regardless of the pager, which currently looks like a bug. Fix by declaring it: an `ALL TIME` badge with `patterns need history — this tab always reads your whole record`. Then:

- Heatmap gains a Rate/Hours toggle (both values are already computed per cell and thrown away at render).
- Keep the `too little time` / `worked, no net` legend — it's honest and rare among competitors.
- **Store cards → leaderboard.** The current stacked cards waste vertical space. One row per store: rank, name, area chip, a proportional net bar with `87 trips · 4m usual wait` inline, net figure, chevron. Sort chips: By net / By wait / Recent. Amber the wait figure when it's an outlier. Keep the unmatched-deliveries footnote.

**Time** — content is fine. Two additions worth making: `while working` vs `whole shift` net/hr as a pair (`4c`), and "your typical online hour" as a 3-segment bar (driving / at store / waiting) with the leak stated in dollars. Both come straight from `docs/design/running-hourly-rate.md`, which the UI currently doesn't surface at all.

---

## 7. Data plumbing required

Ordered by how much the design depends on it.

**7.1 Arbitrary-window aggregates** — *blocking for §3.* Every period query is bound to a 4-value enum. The pager and calendar need `start`/`end` variants of `periodEconomics`, `dailyEarnings`, offer aggregates, and time/deadhead stats. The CSV export already accepts start/end, so the pattern exists in the codebase.

**7.2 Previous-window economics** — *blocking for the delta chips.* Same aggregate over the immediately preceding equivalent window.

**7.3 Net per day** — `DailyEarnings` carries gross only. Add net so the sparkline and the day chart can show kept money.

**7.4 Recent-offers query** — *blocking for §5.* Rows exist in `offer_records`; the DAO only exposes `GROUP BY` aggregates. Need a paged, filterable recent-offers read (time, store, payout, distance, est. $/hr, score, status).

**7.5 Offer ↔ delivery join** — *blocking for est-vs-realized.* Join `linkedJobId` → delivery `netProfit` to compare frozen estimate against realized net.

**7.6 Pay mix** — `SUM(basePay)`, `SUM(tip)` per window; treat `gross − base − tips` as bonuses/other.

**7.7 Weekly plan generation + storage** — see §8.

**7.8 Hour composition & gap stats** — per-gap durations between completion and next accept. The events exist in the log; needs a query. Feeds §6 Time.

**Free — already computed, just not rendered:** platform-split economics, heatmap coverage/hours per cell, all store-card fields, today-so-far economics.

---

## 8. Weekly Plan (`5b`) — the new surface

**Trigger:** local notification, Sunday ~6 PM local: *"Check out your weekly plan."* Deep-links to this screen. Not surfaced on Home except as the pointer row in §2.

**Screen contents:**

1. **Target selector** — `8h · 12h · 20h · $ goal`. Drives how many hours the planner places.
2. **Headline value** — `12 hours on your best windows ≈ $280 kept`, and directly below, the plan's worth: `same 12 hours placed at random ≈ $224 — the plan is worth about $56`. This comparison is the whole pitch; don't drop it.
3. **Suggested windows** — one row per window: weekday, time range, the evidence (`your Fridays: $26.40/hr over 14 dashes`), projected kept. `BEST` pill on the top window. Windows with thin history render dimmed, labeled `NOT PICKED` with the reason (`only 3 Sundays on record · gaps ran 14m`). Drag to move a window; swipe to drop one and the planner re-fills from the next-best hour.
4. **Where the plan came from** — the lifetime heatmap with the picked cells outlined. The plan must be visibly derived from the user's own data, never a black box.
5. **Growth rows, present but locked** — designed in now so the screen doesn't need reworking later:
   - **Area demand** (`PLUS` badge, dashed border, 60% opacity): other dashers' earning hours near you, including hours this user has never tried. This is the subscription pull.
   - **Last July** (`NEEDS A YEAR`, 45% opacity): year-over-year comparison.
6. **Save this plan** — saved plans surface as the Home pointer each morning, and **next Sunday's notification grades the previous plan** (planned vs actual hours and dollars). That loop is what makes the notification worth keeping enabled.

**Algorithm (v1, deliberately simple and explainable):** rank weekday×hour cells by median realized net $/hr from the user's own history; require a minimum sample count per cell; merge adjacent qualifying cells into contiguous windows of ≥2h; greedily take windows until the hour target is met; project each window as `median rate × hours`. The random-placement baseline is `overall median rate × hours`. Every number on the screen must be traceable to that. Cells below the sample threshold are never picked and must state why.

---

## 9. Copy & honesty rules (carry over from the current app — do not regress)

- Net is **frozen** at decision-time costs; changing cost settings never rewrites history. Say so wherever net appears.
- Offer figures are **estimates** (`est.`); realized net is a separate, differently-labeled number.
- Cash tips are **additive** and labeled as such.
- Projections say whose data they come from and that they aren't promises.
- Thin data is stated, never smoothed over or hidden.
- Mileage/tax figures name the method (IRS standard mileage) and defer to a tax preparer.

---

## 10. Suggested build order

1. §7.1 + §7.2 → the period pager and range picker (§3). Everything else benefits immediately.
2. §4.1 Money "where your money went" + §7.6 pay mix. Small, high visible payoff.
3. §5 Offers tab + §7.4/§7.5. Turns the weakest tab into a real one.
4. §2 Home = Today (free data).
5. §6 Patterns badge + store leaderboard (free data).
6. §8 Weekly Plan + §7.7.
7. §6 Time additions + §7.8.
