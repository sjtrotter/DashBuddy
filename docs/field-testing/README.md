# DashBuddy Field Testing Log

Running log of observations made while actively dashing in the field, captured
in real time during sessions. Each session is its own entry, **newest first**.

This is a freeform capture log — a mix of bugs, open questions, meta
observations about platform UI/UX, design proposals, and verification TODOs
that were noticed during a session. The intent is to preserve raw context
*before* it gets distilled into focused work items. Items here are not yet
triaged; the developer triages to the project board manually using the
Android Studio plugin or `gh` CLI.

## Format

Each session entry has:

- **Date** — YYYY-MM-DD
- **Platform(s) tested** — DoorDash, Uber, etc.
- **Branch under test** — the git branch the build came from
- **Field conditions** — anything that affects interpretation (offers
  accepted vs declined, weather, multi-app testing, etc.)
- **Observations** — grouped by kind:
  - **Bugs** — reproducible defects
  - **Field UX context** — what the platform's UI actually looks like in
    the wild; helps explain why a matcher behaves the way it does
  - **Open questions / investigations** — things to look at back at the desk
  - **Meta / architecture** — broader concerns that aren't single-bug shaped
  - **Research / design** — speculative or strategic proposals
  - **Verification TODOs** — items the session itself produced ground-truth
    for, but which need cross-referencing against captured data

Item numbers are **session-local** (reset each session) and intended for
cross-referencing within a single session entry, not across sessions.

---

## 2026-05-09 — Uber session

- **Platform tested:** Uber Driver
- **Branch under test:** `feature/click-rule-overhaul` (commit `90200bc`)
- **Field conditions:** developer dashed on Uber; accepted every offer
  received during the session.

### Bugs

#### 1. Uber: bubble stays "offline" after going online

- **Repro:** Tap "Go online" in Uber Driver.
- **Observed:** Bubble shows offline state. A "started dashing" notification
  appears (note: that notification's UI copy is stale — hasn't been updated
  for the multi-platform world — but that's a separate cosmetic concern).
- **Expected:** Bubble enters online/dashing state.
- **Likely cause:** `uber.click.go_online` intent
  (`app/src/main/assets/rules/uber.json:184-190`) fires the rule, but no
  handler in `state/EffectMap.kt` reacts to `go_online`. DoorDash's
  start-of-session path produces `AppEffect.StartDash`
  (`state/EffectMap.kt:184`); there's no Uber-equivalent wiring.
- **Proposed fix:** unify the intent vocabulary across platforms — rename
  DoorDash's start-dash click intent to `go_online` (or whatever shared
  term fits), rename `AppEffect.StartDash` → `StartSession` (or similar
  platform-neutral term), and remove DoorDash-specific language elsewhere
  in the state machine. Single intent, single handler, both platforms route
  through it.

#### 2. Uber: online/offline screen recognition flaps

- **Observed:** Immediately after going online, the screen matcher appears
  to oscillate between online and offline classifications.
- **Likely contributor to #1** — even with the intent wired up, a flapping
  classifier may immediately clobber the new state.
- **Field UX context (helps explain):** Uber has *two* surfaces from which
  a driver can go online or offline:
  - The **dashboard** (post-splash home screen) has a "start Ubering"
    button.
  - Tapping the map widget opens the **full map screen**, which has its own
    "Go" button.
  - Going offline is symmetrical: end from the map, or back out to the
    dashboard and end from there.
  - So "online" and "offline" each have **two valid screens** with different
    layouts. A matcher keying on a single UI element only present on one
    surface will flip when the user moves between them.
- **Hypothesis:** the current matcher is too strict — keying on a single UI
  telltale that's only present on one of the two surfaces.
- **Action:** capture all four screens (online-dashboard, online-map,
  offline-dashboard, offline-map), find a robust common signal per state,
  relax/rework the matcher.

#### 3. Uber offer TTS reads raw text; offer shape not standardized across platforms

- **Repro:** Receive an Uber offer with TTS announcement enabled.
- **Observed:** TTS reads minutes-as-miles (a field-mapping bug in the
  Uber parser), then continues reading the raw screen string — so the user
  hears the parsed-wrong value *and* the real miles trailing in the verbatim
  text.
- **Expected:** TTS speaks a constructed message built from parsed fields,
  not raw screen text.
- **Underlying problem — offer parse fields aren't normalized between
  platforms:**
  - Uber gives **duration in minutes** directly.
  - DoorDash gives a **deadline timestamp** (due time).
  - TTS / UI should work off a single canonical offer shape, computing
    whichever representation is needed (duration ↔ due time) rather than
    reading screen text verbatim.
- **Proposed fix:** canonical parsed offer schema; TTS announcement built
  from fields, never from raw strings.

#### 4. Uber offer overlay not captured by pipeline

- **Repro:** Receive an offer in Uber while in the field.
- **Observed:** Offer was not evaluated — no parsed data, no bubble update.
- **Hypothesis:** Uber renders some offers as a full-screen notification or
  system-overlay window rather than a normal app window; the current
  accessibility pipeline doesn't catch overlay-style surfaces.
- **Investigation TODO (back at desk):**
  - Check whether *any* data was captured for the missed offer.
  - If the `WindowChanged` pipeline still exists, see if it picked up
    anything. If not, this becomes a case for keeping/restoring it.

#### 6. Uber: persistent "currently online" notification dropped as noise

- **Current behavior:** `uber.notification.online_status`
  (`app/src/main/assets/rules/uber.json:218-225`) is classified
  `shape: "noise"` and dropped entirely. Match condition is
  `titleContains: "currently online"`.
- **Field observation — the body carries live flow state:** while on a
  delivery, the body reads things like "picking up from [store]" during
  the pickup leg, and "going to [customer address]" during the dropoff
  leg. The notification body reflects **which leg of the offer is active**.
- **Field observation — the actions also carry signal:** the active
  notification exposes action buttons that change with leg (e.g., a
  "Contact customer" button is present during dropoff). These are a
  structured, leg-correlated signal.
- **Why it matters:** given Uber's flowy UI (#5) and overlay-style offers
  (#4), this notification may be the most reliable continuous source of
  "what is the driver actually doing right now" on Uber.
- **Broader parser concern:** verify the notification parser is extracting
  **everything** Android exposes — title, text, sub-text, big-text,
  actions/buttons, action labels, action intents — not just title + body.
- **Proposed fix:** re-shape `uber.notification.online_status` from noise
  to parsed; expand parser to surface action buttons; route the parsed
  result as a flow-region/leg signal in the state machine. Likely tightly
  coupled to #1, #2, #5.

### Open questions / investigations

#### 7. How does Uber's slide-to-confirm surface in accessibility?

Uber uses slide-to-confirm widgets for advancing pickups and dropoffs (and
the "Go" button to start dashing may be similar). Three common
implementations:

1. **Slider/SeekBar-backed** — fires `ACTION_SET_PROGRESS` and emits
   `TYPE_VIEW_SCROLLED` accessibility events; we can detect "reached end."
2. **Custom view that dispatches a click on completion** — surfaces as a
   normal click event; a regular click rule keyed on the slider's node id
   catches it.
3. **Pure gesture-only surface with no accessibility action** — hardest;
   we'd have to infer from the screen transition that follows the slide.

Most production apps go with #1 or #2 because TalkBack users need it to
work. Worth confirming by capturing accessibility events while completing
a slide back at the desk.

**Field addition:** slide-to-confirm appears to be the standard
"advance to next leg" affordance on Uber pickups and dropoffs (likely
absent on shop-and-deliver — needs verification). This is the
**leg-transition signal** equivalent to DoorDash's "Arrived at store" /
"Complete delivery" buttons. Capturing it well is high-priority.

#### 9. Uber "match" screen — multiple concurrent offers

- **Observed:** Uber has a screen called the **match screen** that can
  display more than one offer at a time. Saw it in the field with multiple
  offers visible.
- **DoorDash analog:** none — DoorDash offers are presented one at a time.
- **Implication:** the offer evaluator and `OfferMatcher` may need to
  handle a list of offers, not a single-offer assumption.
- **Action:** capture this screen at the desk; design parser + evaluator to
  support N≥1 offers.

### Meta / architecture

#### 5. Uber UI is "flowy" — recognition strategy needs to differ from DoorDash

- DoorDash screens are discrete and separable; Uber screens blend into each
  other (shared chrome, persistent map background, transient sheets and
  overlays).
- Recognizing a screen on Uber is less about "exact tree match" and more
  about "what set of affordances is currently visible."
- **Action:** document the recognition strategy difference somewhere.
  Options considered:
  - This log entry (current home — fine for now).
  - A separate architecture issue.
  - `CLAUDE.md` addition.
  - **Per-rules-file README** — one alongside each `assets/rules/*.json`
    explaining how captures were used to identify screens, what fields were
    extracted, and any platform-specific quirks. (Probably the most
    maintainable; keeps platform-specific reasoning next to the rules it
    governs.)

### Research / design

#### 8. ZIP-derived zones as a first-class signal

- **Question:** is the platform-provided zone name even worth scraping, or
  is it an "extra" at best?
- **Problem with platform zones:** unreliable boundary semantics — dashing
  *in* zone X doesn't mean the pickup or dropoff is *in* zone X. A driver
  can leave the zone mid-offer (e.g., dashing in zone X but the offer's
  pickup is just outside, or the dropoff is several zones away).
- **Proposal:** extract the **ZIP code** from the customer dropoff address
  (and possibly pickup) and treat that as the canonical geo-signal. Hash
  the rest of the address as today, but keep the ZIP as a structured field.
- **Why it matters on both sides:**
  - **Academic federation:** "do tips correlate with ZIP demographics?" is
    a meaningful query and needs ZIP, not platform-zone.
  - **Driver side:** lets a dasher correlate earnings/tips by ZIP
    independent of platform zone definitions, which can change.
- **Open implementation questions:**
  - Pre-hash extraction (extract ZIP, then hash the rest) — needs the
    address parser to handle US format reliably.
  - ZIP → demographic classification — likely already exists (Census tract
    / USPS); confirm before reinventing.
  - Pickup-side ZIP useful too? Probably yes for restaurant-density /
    market context.

### Verification TODOs

#### 10. Accept-button capture consistency for this Uber session

- **Field condition:** developer accepted **every** offer in this session.
- **Action at desk:** cross-reference accept-button click events / sessions
  against the actual offers received during the session window. Any missing
  accepts indicate either matcher gaps, click-classifier gaps, or pipeline
  drops. Good ground-truth opportunity.
- **Related:** while doing this, also verify capture consistency of any
  pickup-confirm and dropoff-complete slide events from #7 — if those
  surface as click events, they should be present alongside the accepts.
