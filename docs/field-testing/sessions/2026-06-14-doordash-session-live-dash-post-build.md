## 2026-06-14 — DoorDash session (live dash, post-#494 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `4a81d34` (post the 2026-06-13 batch through #494 — incl. #473
  durable-last-dash, #470/#458 double-dropoff, #466/#467 money-formatter SSOT, #461/#476 shop cards,
  #460/#324 co-hero task cards, #462/#463 recognition + privacy batches).
- **Field conditions:** one live dash, narrated post-dash. Several offers (took some, declined some),
  including at least one stacked Shop & Deliver. This entry both records new findings and folds in the
  field confirmations that cleared/advanced the "Next field test" checklist this dash. Recorded for
  triage — **hypotheses, not concluded fixes.**

### Bugs

#### 1. Shop & Deliver hero **item count shows the # of stacked orders, not the # of items** (#461)
On a **stacked** Shop & Deliver offer the #461 hero item count surfaced the **number of stacked orders**
instead of the **number of items to shop**. On a single order the hero item count looked correct (see
confirmations below). FOUND BROKEN on the stacked case.

- **Likely cause (hypothesis):** the hero is bound to an orders/stops count rather than the shop item
  count, so it only diverges once `orderCount > 1`.
- **To confirm (desk):** pull this dash's stacked-offer `offer_popup` capture + the parsed fields feeding
  the #461 hero, and check what the hero count binds to when there are multiple orders (items vs. order
  count). Desk call — not a concluded fix.

### Research / design (improvement ideas — explore, not yet scoped)

#### 2. Offer badges should use icons; the SHOP badge carries the item count (#461)
Developer **design direction** on #461 — the item count in the co-hero **feels too surfaced** ("almost
too surfaced"), and it should not live in a co-hero slot at all:

- **Icons are the norm for offer badges** (Shop & Deliver, Red Card, etc.) — use the icon, not a text
  label. We already had icons for these somewhere worth revisiting.
- **The Shop & Deliver badge specifically is the shop icon WITH the number of items** — icon + item
  count together, in the badge, **full stop**. Not "icon *or* count," not a separate co-hero slot — the
  shop badge is `[🛒 N]`.
- The **Red Card** likewise gets its own icon badge.

Recorded as the developer's stated design direction for #461, not a fix applied here.

### Verification TODOs (checklist outcomes this session)

Confirmations that cleared or advanced the "Next field test" checklist on this dash:

- ✅ **In-bubble Accept/Decline (#425) — VALIDATED (2/2).** Both Accept and Decline tapped in the
  expanded bubble registered on DoorDash (2nd clean confirmation after the 2026-06-12 Accept-only
  sighting). The separate **notification-shade** buttons remain broken — tracked by **#457** (2026-06-12
  #11). Removed from the checklist.
- ✅ **Receipt grace, once-per-delivery (#431 pt 2, sub-case a) — VALIDATED (2/2).** No double "Saved"
  receipt anywhere this dash (2nd confirmation after 2026-06-12). Sub-cases (b) stacked-split and (c)
  misrecognition-survival are still unverified — kept on the checklist.
- ⏳ **Durable last-dash (#473/#459) — 1/2.** After the dash ended the bubble kept showing the last dash
  (chat + completed cards), not empty.
- ⏳ **"Saved: $X.XX" money format (#456) — 1/2.** The "Saved" bubble shows the `$` and 2-decimal format
  on all of them now. (The "tip added" bubble is the remaining raw-float straggler — 2026-06-13 #3.)
- ⏳ **Deadline caption, no double "by" (#460) — 1/2.** Reads fully fixed / different from before.
- ⏳ **Co-hero pickup card (#460/#324) — 1/2 (pickup).** Pickup co-hero rendered (timer + $/hr), though
  the dasher flagged it "maybe not wired right"; the drop-off `$/hr` still reads nil (broken —
  2026-06-13 #2).
- ⏳ **Session-end attribution + no mid-dash splits (#431/#279) — 1/2.** Dash ended cleanly, summary on
  the right session, no spurious splits or lingering session.
- ⏳ **No stale heads-up after resolving offers (#436 sub-case a) — partial.** None observed this dash.
- ⏳ **Per-offer dedupe (#427) / offer-eval matches screen (#345) — needs desk log check.** Dasher
  believes both are working but couldn't verify in the field; needs a desk pass over this dash's
  `captures/` + event log.
- 🔁 **New-dash-after-ending (#286/#290) — not exercised.** Only one dash today, so the
  end-then-start-fresh path couldn't be tested.
- ⚠️ **Extra drop-off card recurred (2026-06-13 #1, premature-frame class).** Another premature/extra
  drop-off card appeared this dash — now seen on a 2nd separate dash, so upgrade from "stray one-off" to
  a real recurring partial-render bug. Still distinct from the #458 frozen-twin case; grab the dropoff
  frame + `app_events` to confirm the unsettled-frame root (a shared settle/validity gate on recognition
  is the likely direction — desk call).
- ✅ **Watches — no recurrence this dash:** no "ghost offer" / blank-store offer cards (2026-06-13 #1
  ghost watch), and no mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5 watch).

### Recognition screens (need desk verification after capture download)

The #462 recognition batch + #433 (`pickup_picked_up`) + #149 alcohol flow couldn't be verified from
memory in the field — they need a desk pass over this dash's downloaded `captures/` to confirm the
screens classified (not UNKNOWN), the flow stepped correctly, and customer PII was hashed (never raw).
Kept on the checklist at 0/2.
