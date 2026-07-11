# DoorDash recognition strategy

This README explains **how recognition works for DoorDash** and why — context for a future
contributor (in-tree today, community rule author once Pillar 2 / #192 lands) editing or adding
rules under this directory. It does not restate the rule DSL (that's
[ADR-0001](../../../docs/adr/ADR-0001-matcher-rule-format.md)) or the build/canonicalizer
(that's [`matchers/README.md`](../../README.md)) — read those first if you haven't.

## Surface map

DoorDash's rules are split into surface sub-files (#639) instead of one flat file, because the
DoorDash Driver app itself is organized as a sequence of **discrete, separable screens** — each
sub-file owns one family of those screens:

| File | Owns |
|---|---|
| `_manifest.json5` | Platform header only (`format_version`, `platform_id`) — no rules |
| `sensitive.json5` | Dasher banking/identity + document-image capture surfaces — blocked, never parsed |
| `offer.json5` | The incoming-offer popup and its accept/decline/confirm-decline actuation targets |
| `dash-lifecycle.json5` | Start/pause/resume/end, scheduling, idle & waiting-for-offer, the end-of-dash summary |
| `pickup.json5` | Pickup navigation, arrival, shopping, item verification, issue/resolution sub-screens (incl. GoPuff/Drive warehouse batch) |
| `dropoff.json5` | Dropoff navigation, arrival/handoff, photo & PIN, delivery receipt/summary, alcohol-ID recognition |
| `nav-comms.json5` | Generic navigation and in-app chat |
| `ratings-feedback.json5` | Post-delivery ratings and safety feedback |
| `chrome.json5` | App chrome — side menu, earnings, help, promos, notifications view, camera capture |
| `notifications.json5` | System notifications (order/tip/earnings/weather/etc.) |

The canonicalizer merges these (sorted by filename, `_manifest` excepted) into one
`assets/rules/doordash.json`; splitting a screen family into its own file is purely an authoring
convenience — see `matchers/README.md` for why the merge is behaviorally inert.

## Recognition strategy: discrete screen matching

DoorDash screens are largely **discrete and separable** — one screen, one purpose, a stable set
of widget IDs and copy. The dominant matching style here is **exact tree match**: a rule anchors
on a widget `hasIdSuffix` (an Android resource-id suffix, stable across the app's res-id
prefix/version churn) or a small set of literal/regex text conditions unique to that screen, and
often both together for precision. Contrast this with Uber's affordance-set strategy below —
DoorDash rarely needs to reason about "what's currently visible" because each screen's tree shape
is, on its own, close to unambiguous.

That said, several real surfaces blend into each other and need care:

- **Shared anchors across adjacent screens.** The dropoff arrival card and the pickup arrival
  card both carry the text `"Delivery for"`; a dropoff-specific discriminator (`"Hand it to
  recipient"` / the `complete_delivery_steps_button` id) is required alongside it so the pickup
  card doesn't get stolen — that's `dropoff.json5`'s `dropoff_pre_arrival` rule, whose own comment
  documents the three require branches (#462/#460/#549). `dropoff_navigation` (the earlier,
  en-route rule) has the opposite relationship to that same CTA set: it `reject`s
  `complete_delivery_steps_button`/"Continue"/"Complete Delivery"/"Mark as delivered" (the #603
  arrival discriminator), so an at-the-door frame falls through to `dropoff_pre_arrival` instead of
  being claimed early by the en-route rule.
- **Priority ordering as a disambiguator, not just a tie-break.** Several `pickup.json5` rules
  are commented with explicit priority reasoning — e.g. the GoPuff zone-arrival rule's anchors
  (`go_to_store_action_view` + an "Arrived at store" label) also appear on every regular
  pre-arrival tree, so it only stays scoped to the GoPuff case because a more specific regular
  pre-arrival rule is evaluated first (lower priority number) and the GoPuff rule's `reject`
  clauses explicitly exclude a degraded regular frame from falling through to it.
- **Recognize-only rules for transient interstitials.** Confirmation modals, surveys, and
  issue-menu sub-screens that don't change the driver's actual task phase are recognized (so they
  stop appearing as `UNKNOWN` captures) but declare no `state.flow` — they can't perturb the state
  machine, only pull noise out of the capture pipeline. `pickup.json5`'s wait-survey and
  `dropoff.json5`'s multi-order-confirm are both this shape.

### GoPuff (DoorDash Drive) — warehouse batch is a branch, not a platform

GoPuff orders route through the DoorDash app but present a **warehouse batch** flow instead of
a normal single-store pickup: one store, a bin-scan step for N items, a single "complete pickup"
action, then N customer dropoffs. This is handled as **branches of the existing pickup rules**,
not a separate platform or a parallel rule tree — see `pickup.json5`'s bin-scan-steps and
zone-arrival rules. The warehouse leg was previously almost entirely `UNKNOWN` (no arrival ever
fired because the ordinary pickup-arrival anchors don't appear), so the bin-scan-steps branch
declares the one `task:pickup:arrived` anchor for the whole batch; the zone-arrival screen that
precedes it is recognize-only (nav is already established by that point, and its "Arrived at
store" button label is an affordance, not the arrival anchor).

### Offer attribution and multi-store/multi-order batches

A DoorDash offer can bundle multiple stores and/or multiple orders (grocery batches, GoPuff).
`dropoff.json5`'s multi-order-confirm screen exists specifically because mix-ups are common at
drop-off when a dash carries more than one order — it is not GoPuff-specific, it's the generic
DoorDash multi-order confirmation. Store/task attribution across a multi-drop batch is a state-
machine concern (see CLAUDE.md's job-container model), not something the rules encode directly;
the rules' job is just to recognize each screen and extract its fields.

## Field extraction notes

- **Customer identity is hashed at the edge, never stored raw.** Where a rule parses customer
  name/address, the field hashes it via a `sha256`-terminated transform chain — e.g.
  `dropoff.json5`'s `dropoff_navigation`, `dropoff_pre_arrival`, and
  `dropoff_pre_arrival_completion` rules, the dropoff task-phase parses that actually extract
  `customerNameHash`/`customerAddressHash` — this is how customers are differentiated for
  dedup/correlation without keeping their actual info (the Pledges' "customers are hashed, not
  blocked").
- **The capture envelope is redacted independently of the parse.** Every rule that hashes PII in
  its `parse` also declares a `redact` block (compiler-enforced, #598) masking the same node text
  in the serialized capture, so a debug capture of a recognized screen never carries plaintext
  customer text either. Some redacts are shape-based rather than id-based — e.g. the multi-order-
  confirm card's customer line has no `viewId` and no `"Deliver to "`/`"Message from "` marker for
  the cross-platform `CustomerTextMarkers` backstop to catch, so its `redact` matches the
  first-name + last-initial name shape directly (the same regex `SnapshotRedactor` uses on the
  test-corpus side, kept byte-equal by `CaptureRedactionCorpusTest` so the two can't drift).
- **Merchant/store names are kept, not hashed.** They're driver-owned business information, not
  customer PII — see any `dropoff.json5`/`pickup.json5` parse that reads a store name plainly.

## Sensitive vs. hashed boundary

Two different postures apply to two different kinds of screen, per the Pledges (CLAUDE.md):

- **Blocked, never parsed or stored** — the dasher's own banking/identity screens (DasherDirect /
  Crimson balance & transfers, card details, cash-out) and document-image capture surfaces (the
  license-scan camera, the signature pad), regardless of whose ID/signature it is. These live in
  `sensitive.json5`: `doordash.screen.sensitive.known` is priority 0, `overrideable: false` —
  structurally first, so nothing else in the ruleset can pre-empt the block — plus a low-
  confidence `sensitive.catchall` backstop (priority 999, `overrideable: true`) that only fires
  when nothing more specific already recognized the screen.
- **Recognized and hashed** — customer-facing delivery screens, including an alcohol delivery's
  ID-CHECK instruction screen and arrival card (`dropoff.json5`'s
  `dropoff_alcohol_id_intro`/`alcohol_id_check`/`alcohol_verify_*` rules). Only the literal
  scanner/signature capture surfaces are sensitive; the surrounding instruction and confirmation
  screens are ordinary recognized-and-hashed customer screens.

A recognition change to either family must not blur this line — see CLAUDE.md's "Security &
privacy first" principle and the load-time sensitive-coverage guard that fails closed if a
platform ships screen rules without matching sensitive rules.

## Framing note

Everything above describes **empirical measurement of the visible offer/screen surface** the
DoorDash Driver app already renders on the dasher's own device — the same information a sighted
driver reads off their own screen. See `LEGAL.md` and `matchers/README.md`'s "Framing" section for
the full posture; nothing here characterizes DoorDash's internal systems (routing, pricing,
dispatch), only the screens a driver sees.
