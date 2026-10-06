# DashBuddy privacy — what the app does, from the code

**Status: engineering ground truth, pre-counsel.** This document describes what the shipped code does, with the
file that does it named for each claim, so it can be checked against the source and kept honest by code review.
It is not yet a legal privacy policy; wording for the Play listing and the in-app disclosure is reviewed
separately (#246). Where a claim and the code ever disagree, the code is the fact and this file is the bug.

DashBuddy is open source. Every statement below can be verified against the repository.

## 1. The one-sentence version

DashBuddy is an **assistive tool for delivery drivers**: it reads the delivery app's screen so it can tell you,
out loud and on a floating card, what an offer is worth while you are driving, and — only under rules you set
and only for taps you have individually allowed — accepts or declines on your behalf so you never have to reach
for the phone. Everything it computes stays on your device unless you turn on a network feature yourself.

## 2. What DashBuddy reads

### 2.1 The accessibility service (the screen reader)

Declared in `app/src/main/res/xml/accessibility_service_config.xml`:

- `android:isAccessibilityTool="true"` — DashBuddy declares itself an accessibility tool because that is what it
  is: dashers are usually driving when an offer arrives, and the app reads the offer to them for the decision and,
  under the dasher's own rules, acts on it so they do not touch the phone. That flag is also what lets the service
  see a platform's offer overlay (Uber renders offers in a system overlay window).
- `android:packageNames` — the service is bound to the delivery apps only: `com.doordash.driverapp`,
  `com.ubercab.driver`, `com.instacart.shopper`, `com.walmart.spark`. Nothing from any other app reaches the
  pipeline. (One opt-in exception, §2.2.)
- `android:accessibilityEventTypes` — four event types: window state changed, window content changed, view
  clicked, windows changed. No text-changed, focus or selection events.
- `android:canRetrieveWindowContent="true"` with `flagReportViewIds`, `flagRetrieveInteractiveWindows`,
  `flagIncludeNotImportantViews` — the service reads the visible view tree (text, content descriptions, view ids,
  bounds) of those apps' windows.
- `android:canTakeScreenshot="true"` — used only for the evidence screenshots in §4, which are OFF by default.

What the tree is used for: recognition against a published, rule-based ruleset (`matchers/rules/`, Apache-2.0)
that turns a screen into a named state (an offer, a pickup, a drop-off, a dash summary). Recognition, evaluation
and every economic computation run **on the device** (`core/pipeline`, `core/state`, `domain`).

### 2.2 The one wide-receipt opt-in ("Screen events")

By default the service receives events only from the apps above. A separately asked, affirmative consent
("Screen events", the first step of the permission flow, `EventReceiptPreferences` in `domain`, enforced by
`AccessibilityListener` through `ServiceInfoPolicy`) lets the service receive the system's window-change events
without a package filter so it can tell which app is in front. Undecided behaves as declined; a decline is
durable; it can be changed under Settings → Data & Privacy → Automation & Consent. Even when allowed, only the
delivery apps' screens are recognized; other apps' content is never parsed or stored.

### 2.3 Notifications

A separate `NotificationListenerService` reads the delivery apps' notifications (offer pushes, arrival and
pause notices, earnings notices) and recognizes them against the same ruleset (`core/pipeline/.../notification`).
Notifications from other apps are not processed.

### 2.4 Location

`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` are used for the on-device odometer (`core/location`):
GPS fixes are sampled **only while a dash is active** and stop when the dash ends. Each fix is judged by
`OdometerFixPolicy` (accuracy, speed and jump limits) before it can move the mileage. A location is never written
to a log line — a rejected fix is logged as numbers and a reason, never as a coordinate (`domain/.../OdometerFixPolicy`).

### 2.5 What DashBuddy never reads

Banking apps, messaging, browsers, email, photos, contacts, call logs, any app not in `android:packageNames`.
Inside the delivery apps, the dasher's own sensitive screens are **blocked at the recognition layer** before any
parsing or storage: DasherDirect / banking balances and transfers, payment screens, the dasher's own identity
documents, and every document-image capture surface (the license-scan camera, the signature pad). The block has
two layers: the platform's `sensitive` rules in the ruleset (`matchers/rules/doordash/sensitive.json5`) and a
rules-independent text-marker backstop (`SensitiveTextMarkers`, data from `census-contract`) that drops a frame
even when no rule matched. If the rulesets have not loaded, every frame is dropped (fail-closed, #432).

## 3. Customers are hashed, not stored

A delivery screen names the customer. DashBuddy needs to tell customers apart (to join a pickup to its drop-off)
but never needs to know who they are. Customer names and addresses are therefore replaced at the edge by a
`sha256` digest (`customerNameHash`, `customerAddressHash`) before anything is persisted; the plaintext is not
kept. The dasher's own first name and last initial (the main-menu greeting) is processed as the app's own user.
Merchant (store) names are kept as text: they are the driver's business data, not a person.

## 4. Screenshots (evidence) — off by default

`ScreenShotHandler` (`app/.../state/effects/`) can save a PNG of an offer card, a delivery receipt or a dash
summary to the device's own gallery under `Pictures/DashBuddy/` through `MediaStore`. Nothing fires unless the
**master toggle AND the matching category toggle** are on (`EvidenceConfig`, #426); the master default is OFF.
Screenshots never leave the device and can be deleted from the gallery at any time. A screenshot is the raw
screen image: do not enable evidence capture on screens you would not want kept.

## 5. Taps DashBuddy may perform — each one is its own consent

Rules never actuate (#425). A rule may only point at a target ("the Accept button"); the app owns the taps, and
each tap is gated three ways (`SideEffectEngine`, `UiInteractionHandler`):

1. **Per-tap consent.** Every automated tap is a *capability* that lands **undecided**; nothing is granted
   automatically (#843). You allow or deny each one in the prompt that appears when the app comes to the foreground,
   or later under Settings → Data & Privacy → Automation & Consent. A denial persists; a changed rule layout
   re-asks. The capabilities shipped today for DoorDash: `decline_offer`, `confirm_decline`, `expand_earnings`.
2. **Your rule.** A tap fires only when your own thresholds say so (your strategy settings). No machine learning
   makes acceptance decisions; the logic is deterministic and readable in `domain/.../evaluation/`.
3. **Fire-time verification.** Before tapping, the handler re-finds the target in the live screen, checks it
   belongs to the delivery app, checks its label against an allowlist, and aborts to manual on any doubt.

A tap you make yourself on the floating card (Accept / Decline) is your own action.

## 6. What leaves the device

By default: **nothing.** Each network feature is a separate opt-in:

| Feature | Default | Where it goes | What is sent |
|---|---|---|---|
| Gas-price auto-fetch (`IS_GAS_PRICE_AUTO`) | off | the U.S. EIA public price API (`core/network/.../eia`) | a request for the regional price; no account, no device data. The API key is never logged (#348). |
| UNKNOWN-screen census (developer builds only) | off | a census server you configure | a **skeleton** of a screen the ruleset did not recognize: view classes and ids, hashed text slots under a k-anonymity gate, never the text itself — see `docs/adr/ADR-0011-unknown-census-privacy-model.md`. Release builds bind a no-op sink (`NoOpCensusSink`) and cannot upload at all. |
| Bug-report export | manual | a share sheet you choose | the INFO-and-above log, scrubbed at the sink (`LogRepository`, `LogScrubber`): economics, counters, hashes — never raw store, customer or address text. |
| CSV export | manual | a file you choose | your own sessions and deliveries (merchant names included; customer and address hashes excluded). |

There is no account, no analytics SDK, no advertising identifier.

## 7. Where your data lives, and how long

- The event log and the analytics tables are a Room database on the device (`core/database`); the read-model
  tables are a rebuildable projection of your own event log.
- Preferences and consents are DataStore files on the device (`core/datastore`).
- Debug builds may keep capture envelopes for rule development under the app's files; release builds bind a no-op
  capture bus (`NoOpCaptureBus`) and keep none. Captures and census files are excluded from Android backup and
  device transfer (`app/src/main/res/xml/backup_rules.xml`).
- Retention is "until you delete it": uninstalling removes everything. A one-tap in-app wipe does not exist yet —
  tracked as a follow-up of #170.

## 8. Consent records

Each capability decision and the Screen-events decision are stored on the device beside the grant itself, with the
time it was made, the app version and the revision of this document that was shown (`RuleCapabilityDataSource`,
`EventReceiptPreferences`). They are readable under Automation & Consent and leave the device only in a bug
report you export.

## 9. Verify it

Repository: https://github.com/sjtrotter/DashBuddy — rulesets under `matchers/rules/`, the recognition pipeline
under `core/pipeline/`, the consent model under `docs/design/rule-capability-consent.md`, the census model under
`docs/adr/ADR-0011-unknown-census-privacy-model.md`, the DoorDash ICA posture under `LEGAL.md`.

_Document revision: 1 (2026-10-06). The in-app disclosure references this revision._
