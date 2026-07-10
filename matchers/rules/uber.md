# Uber recognition strategy

This README explains **how recognition works for Uber** and why — context for a future
contributor (in-tree today, community rule author once Pillar 2 / #192 lands) editing or adding
rules in `uber.json5`. It does not restate the rule DSL (that's
[ADR-0001](../../docs/adr/ADR-0001-matcher-rule-format.md)) or the build/canonicalizer (that's
[`matchers/README.md`](../README.md)) — read those first if you haven't. Uber stays a **flat
single file** rather than a directory of surface sub-files (contrast DoorDash) because its
recognition strategy below doesn't decompose cleanly into separable screen families — see
"Surface map" below for why.

## Surface map

`uber.json5` is one file, but its rules still group into families:

| Group | Owns |
|---|---|
| `sensitive.known` / `sensitive.catchall` | Wallet / Instant Pay / cash-out / bank / identity — blocked, never parsed |
| `offer` | The incoming-offer/match card and its accept target |
| `splash` | App launch/splash |
| `pickup_verification_*` | Pickup PIN/items/info verification sub-screens |
| `customer_chat` | In-app customer chat |
| `photo_capture` | Delivery photo capture |
| `delivery_confirmation` | Dropoff confirmation |
| `shop_and_pay_*` | Shop-and-deliver order list + checkout |
| `post_trip` / `session_summary` | End-of-trip and end-of-session summaries |
| `earnings_activity` | Earnings/activity screen |
| `active_trip` / `awaiting_offer` / `idle_map` / `home_dashboard` | The online/offline + trip-active state surfaces (see below) |
| `notifications.*` | System notifications, incl. the persistent "you're online" push and per-leg trip pushes |

## Recognition strategy: affordance-set matching, not exact tree match

Uber's screens **blend into each other** — shared chrome, a persistent map background, transient
sheets and overlays — rather than DoorDash's discrete, separable screens (2026-05-09 field
session). Recognizing a screen on Uber is less about "does this exact tree match" and more about
**"what set of affordances is currently visible."** Concretely:

- Rules anchor on stable `hasIdSuffix` widget ids (survives Uber's res-id churn) **combined with**
  the presence/absence of specific text, because a single id often exists on more than one surface
  in a given mode.
- **The online/offline "two valid screens" problem is handled by branches, one per surface.**
  Going online or offline is reachable from **two different surfaces** — the post-splash home
  dashboard (`glide_bottom_nav_home` + a `go_online_button` id) and the full map screen
  (`home_screen_overlay_container`) — and each surface's layout differs from the other. Instead of
  one matcher keyed on a telltale that only exists on one surface (which is what caused the
  online/offline flapping in the 2026-05-09 session), `uber.screen.home_dashboard` and
  `uber.screen.idle_map`/`uber.screen.active_trip`/`uber.screen.awaiting_offer` are separate rules,
  each keyed on ids specific to its own surface, all converging on the same `modeHint` (`online`/
  `offline`) so downstream state doesn't care which surface the driver is looking at.
- **Text presence/absence, not just presence, is a first-class signal.** `idle_map`'s `require`
  includes `notExists: "You're online"` and `notExists: "Finding trips"` alongside its id anchor —
  it has to actively rule out the online affordances, not just check for the offline one, because
  the container id it anchors on isn't unique to the offline state.

## Field extraction notes

- **Uber gives offer duration as minutes directly; DoorDash gives a deadline timestamp.** The offer
  rule's `timeToCompleteMinutes` field (`parseLeadingInt` off a `\d+\s*min` match) has no DoorDash
  analog — DoorDash offers carry a due-time instead. Anything consuming offer timing across both
  platforms needs to convert one representation to the other rather than assuming a shared raw
  field (2026-05-09 field session, item 3 — the TTS bug that motivated this).
- **Multi-order offers are parsed as a list within one offer, not multiple concurrent offers.** The
  offer rule's `orders.each` extracts one `storeName`/entry per order card inside a single offer
  (a shop-and-pay-style batch). This is a different thing from the **"match" screen** the
  2026-05-09 session also observed, which can show more than one *separate* offer at once — that
  screen is not yet a distinct recognized rule (open question #9 in the field log); the current
  `offer` rule only accepts `"Accept"` or `"Match"` as the button label on a single offer card.
- **The "Going to " prefix is store/address-ambiguous** — Uber's en-route pickup and en-route
  dropoff notifications share the same `"Going to "` prefix, distinguished only by whether what
  follows starts with a digit (a dropoff street address) or not (a store name):
  `uber.notification.trip_en_route_pickup` requires `^Going to (?!\d)`, `trip_en_route_dropoff`
  requires `^Going to \d`. This is a documented residual (CLAUDE.md) that a generic prefix-based PII
  scrub can't own on its own — the rule-declared `redact` on the dropoff notification is the primary
  control for that field, not the cross-platform text-marker backstop.
- **Overlay-rendered offers are captured via `getWindows()`.** The 2026-05-09 session found some
  Uber offers render as a `SYSTEM_ALERT_WINDOW`-style overlay that a normal single-window
  accessibility read misses; `AccessibilitySource.getWindows()` (with `flagRetrieveInteractiveWindows`
  in the service config) enumerates all windows so the overlay surface is captured like any other.

## Sensitive vs. hashed boundary

Same two-tier posture as DoorDash, applied to Uber's own screens:

- **Blocked, never parsed or stored** — the dasher's wallet, Instant Pay, cash-out, bank, and
  identity screens. `uber.screen.sensitive.known` is priority 0, `overrideable: false`
  (structurally first); `sensitive.catchall` is the low-confidence backstop (priority 998,
  `overrideable: true`) for anything the specific rules miss.
- **Recognized, not (yet) hashed — `customer_chat`/`delivery_confirmation` are recognize-only.**
  `uber.screen.customer_chat` and `uber.screen.delivery_confirmation` are recognized (so they
  graduate out of `UNKNOWN` capture) but declare no `parse` block at all — nothing is extracted
  from either screen today, so there's no customer name/address field to hash. `uber.json5` has
  zero `sha256` transforms; the file's only masking lives on the two per-leg trip
  **notifications**, `trip_en_route_dropoff`/`trip_at_dropoff`, which `redact` their title
  directly (not derived from any parsed/hashed field). The two differ in shape: `trip_at_dropoff`
  masks with a `keepPrefix` (`"Leave the order at "`/`"Meet at door for "`) so its own require (a
  literal-text match) still recognizes the redacted title on replay — the #598/#623 replay-
  fidelity property. `trip_en_route_dropoff` instead masks the **whole title, no keepPrefix** —
  deliberately, because its require is `titleMatchesRegex: "^Going to \\d"` (digit-anchored, to
  distinguish a dropoff address from the pickup notification's `^Going to (?!\d)` store name); if
  the mask kept the `"Going to "` prefix, the character right after it would be the
  `[redacted:…]` marker rather than a digit, and the rule's own require could no longer re-match
  the redacted envelope on replay — so whole-masking is the only replay-safe choice here, not an
  oversight. If/when a customer field is ever parsed off a Uber screen, follow the DoorDash
  hash+redact pattern (a `sha256`-terminated transform plus a matching `redact` block) — see
  [`doordash/README.md`](doordash/README.md)'s "Field extraction notes" — rather than inventing a
  second scheme.

A recognition change to either family must not blur this line — see CLAUDE.md's "Security &
privacy first" principle and the load-time sensitive-coverage guard that fails closed if a platform
ships screen rules without matching sensitive rules.

## Known gotchas / open questions

These are observed but not fully resolved in the rules yet — noted here so a future author doesn't
have to rediscover them:

- **Slide-to-confirm advance affordances** (pickup → dropoff → complete) may not surface as a
  normal click event depending on how Uber implements the widget (SeekBar-style progress event,
  a completion-triggered click, or a pure gesture with no accessibility action at all) — see the
  2026-05-09 field log item 7 for the three candidate shapes; whichever one a given Uber build uses
  determines whether a `click` rule can key on it directly or recognition has to infer the advance
  from the screen transition that follows.
- **The persistent "you're online" notification is still classified as noise**
  (`uber.notification.online_status`, `parse.as: "noise"`), even though its body text changes with
  the active leg (e.g. "picking up from …" vs. "going to …") and its action buttons change with leg
  too. Given Uber's blended UI and overlay-style offers, this notification is a plausible richer
  continuous signal of "what leg is the driver on right now" — but re-shaping it into a parsed,
  leg-correlated rule hasn't been done (2026-05-09 field log item 6).
- **The multi-offer "match" screen** (more than one offer visible at once) has no dedicated rule —
  see "Field extraction notes" above.

## Framing note

Everything above describes **empirical measurement of the visible offer/screen surface** the Uber
Driver app already renders on the dasher's own device — the same information a sighted driver reads
off their own screen. See `LEGAL.md` and `matchers/README.md`'s "Framing" section for the full
posture; nothing here characterizes Uber's internal systems (dispatch, pricing, matching), only the
screens a driver sees.
