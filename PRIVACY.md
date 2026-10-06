# DashBuddy privacy — what the app does, from the code

**Status: engineering ground truth, pre-counsel.** This document describes what the shipped code does, with the
file that does it named for each claim, so it can be checked against the source and kept honest by code review.
It is not yet a legal privacy policy; wording for the Play listing and the in-app disclosure is reviewed
separately (#246). Where a claim and the code ever disagree, the code is the fact and this file is the bug.

DashBuddy is **source-available**: the app is licensed under PolyForm Shield (`LICENSE`) and the recognition
ruleset under `matchers/` is Apache-2.0 (`matchers/LICENSE`). Every statement below can be verified against the
repository.

## 1. The one-sentence version

DashBuddy is an **assistive tool for delivery drivers**: it reads the delivery app's screen so it can tell you,
out loud and on a floating card, what an offer is worth while you are driving, and — only under rules you set
and only for taps you have individually allowed — accepts or declines on your behalf so you never have to reach
for the phone. Recognition, evaluation and every economic computation run on your device. Two small network
features are on by default and carry no account or identifier (the gas-price refresh and the vehicle-list lookup
in setup, §6); everything else that touches the network is a separate opt-in.

## 2. What DashBuddy reads

### 2.1 The accessibility service (the screen reader)

Declared in `app/src/main/res/xml/accessibility_service_config.xml`:

- `android:isAccessibilityTool="true"` — DashBuddy declares itself an accessibility tool because that is what it
  is: dashers are usually driving when an offer arrives, and the app reads the offer to them for the decision and,
  under the dasher's own rules, acts on it so they do not touch the phone. The flag changes how Android classifies
  and warns about the service and which events it is eligible to receive (since Android 14 the system delivers
  events an app marks accessibility-data-sensitive only to services carrying it).
- `android:packageNames` — the service is bound to the delivery apps only: `com.doordash.driverapp`,
  `com.ubercab.driver`, `com.instacart.shopper`, `com.walmart.spark`. Nothing from any other app reaches the
  pipeline. (One opt-in exception, §2.2.)
- `android:accessibilityEventTypes` — four event types in a release build: window state changed, window content
  changed, view clicked, windows changed. No text-changed, focus or selection events. A **debug** build subscribes to
  every event type for rule development (`AccessibilityListener`, `BuildConfig.DEBUG`).
- `android:canRetrieveWindowContent="true"` with `flagReportViewIds`, `flagRetrieveInteractiveWindows`,
  `flagIncludeNotImportantViews` — the service reads the visible view tree (text, content descriptions, view ids,
  bounds) of those apps' windows. `flagRetrieveInteractiveWindows` plus `getWindows()` is what lets it see a
  platform's offer **overlay** (Uber draws offers in a system overlay window).
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
delivery apps' windows are inspected: another app's window is identified by package name only, and the pipeline
stores nothing of its content. The one surface that can still capture another app's content is a whole-display
evidence screenshot (§4), which is off by default and says so where it is turned on (`event_receipt_body`).

### 2.3 Notifications

A separate `NotificationListenerService` reads the delivery apps' notifications (offer pushes, arrival and
pause notices, earnings notices) and recognizes them against the same ruleset (`core/pipeline/.../notification`).
A notification from a package that is not an enabled platform is dropped before anything is read
(`NotificationListener.onNotificationPosted` checks the enabled-package set; `NotificationFilter.isRelevant` gates
what is forwarded).

### 2.4 Location

`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` are used in two places:

- **The on-device odometer** (`core/location`, `OdometerEffectHandler`): GPS fixes are sampled **only while a dash
  is active** and stop when the dash ends. Each fix is judged by `OdometerFixPolicy` (accuracy, speed and jump
  limits) before it can move the mileage. A location is never written to a log line — a rejected fix is logged as
  numbers and a reason, never as a coordinate (`domain/.../OdometerFixPolicy`).
- **The gas-price refresh** (`FuelPriceRepository`, on by default, §6): once per refresh the last known location
  is read (`FusedLocationDataSource.getUserLocation`) and passed to Android's `Geocoder` to resolve the state for
  the regional price. `Geocoder` is a platform service that may use the network to do that, so on this feature the
  device's coordinates do leave the app, to Android's geocoding provider — not to DashBuddy and not to the EIA.
  Turning the refresh off stops the scheduled refresh (`DailyGasPriceWorker` checks the setting first). One
  exception: the setup wizard attempts a fetch when it opens, and may do so before your saved "off" has loaded
  (`WizardViewModel.attemptAutoGasPriceFetch` reads the state's initial `true`) — a #1237 follow-up.

### 2.5 What DashBuddy never processes

Banking apps, messaging, browsers, email, photos, contacts, call logs, any app not in `android:packageNames`
(subject to §2.2 and §4). Inside the delivery apps, the dasher's own sensitive screens are **dropped at the
recognition layer**: the view tree has to be read for a screen to be classified at all
(`AccessibilityNodeMapper`), and a frame that classifies as sensitive is then discarded before any parse,
persistence or forwarding. That covers DasherDirect / banking balances and transfers, payment screens, the
dasher's own identity documents, and every document-image capture surface (the license-scan camera, the signature
pad). The block has two layers: the platform's `sensitive` rules in the ruleset
(`matchers/rules/doordash/sensitive.json5`) and a rules-independent text-marker backstop (`SensitiveTextMarkers`,
data from `census-contract`) that drops a frame even when no rule matched. If the rulesets have not loaded, every
frame is dropped (fail-closed, #432).

## 3. Customers are hashed, not stored

A delivery screen names the customer. DashBuddy needs to tell customers apart (to join a pickup to its drop-off)
but never needs to know who they are. Customer names and addresses are therefore replaced at the edge by a
`sha256` digest (`customerNameHash`, `customerAddressHash` — the ruleset's `sha256` parse transform,
`core/pipeline/.../rules/ParsedFieldsFactory`) before anything is persisted; the plaintext is not kept in the event
log, the analytics tables or the INFO log. Two surfaces are exceptions and say so where they are turned on: a
whole-display evidence screenshot (§4) is a raw image of the screen, and on developer builds the trusted-install
capture sharing (§6) uploads UNKNOWN screens with only the marker backstops as redaction. The dasher's own first
name and last initial (the main-menu greeting) is processed as the app's own user. Merchant (store) names are kept
as text: they are the driver's business data, not a person.

## 4. Screenshots (evidence) — off by default

`ScreenShotHandler` (`app/.../state/effects/`) can save a PNG of an offer card, a delivery receipt or a dash
summary to the device's own gallery under `Pictures/DashBuddy/` through `MediaStore`. Nothing fires unless the
**master toggle AND the matching category toggle** are on (`EvidenceConfig`, #426); the master default is OFF.
A screenshot is the **whole display** (`Display.DEFAULT_DISPLAY`), so an offer drawn over another app captures
that app too. DashBuddy never uploads a screenshot. The file is an ordinary gallery image: your own gallery sync or
sharing applies to it, it survives uninstall like any photo, and you can delete it from the gallery at any time.
Do not enable evidence capture on screens you would not want kept.

## 5. Taps DashBuddy may perform — each one is its own consent

Rules never actuate (#425). A rule may only point at a target ("the Accept button"); the app owns the taps, and
each tap is gated three ways (`SideEffectEngine`, `UiInteractionHandler`):

1. **Per-tap consent.** Every automated tap is a *capability* that lands **undecided**; nothing is granted
   automatically (#843). You allow or deny each one in the prompt that appears when the app comes to the foreground,
   or later under Settings → Data & Privacy → Automation & Consent. A denial persists; a changed rule layout
   re-asks. The capabilities shipped today for DoorDash (`matchers/rules/doordash/offer.json5`,
   `dash-lifecycle.json5`): `accept_offer`, `decline_offer`, `confirm_decline`, `expand_earnings`.
2. **Your rule.** An accept or decline tap fires only when your own thresholds say so (your strategy settings). No
   machine learning makes acceptance decisions; the logic is deterministic and readable in `domain/.../evaluation/`.
   `expand_earnings` is different and the consent copy says so: it fires whenever a delivery receipt is shown
   collapsed, to open the pay breakdown for reading (`EffectMap.diffExpandAction`).
3. **Fire-time verification.** Before tapping, the handler re-finds the target in the live screen, checks it
   belongs to the delivery app, checks its label against an allowlist where the control has a label
   (`expand_earnings` is an icon-only chevron, verified by app boundary only — `RuleAction`), and aborts to manual
   on any doubt.

A tap you make yourself on the floating card (Accept / Decline) is your own action.

## 6. What leaves the device

There is no account, no analytics SDK, no advertising identifier. Two public-data lookups are on by default; every
other network feature is a separate opt-in:

| Feature | Default | Where it goes | What is sent |
|---|---|---|---|
| Gas-price refresh (`isGasPriceAuto`, `AppPreferencesDataSource`) | **on** (Settings → Economy turns it off) | the U.S. EIA public price API (`api.eia.gov`, `core/network/.../eia`), after Android's `Geocoder` resolves your state (§2.4) | a request for the regional price for your fuel type; no account, no identifier. The API key is never logged (#348). |
| Vehicle list in the setup wizard (`EpaVehicleDataSource`) | on while the wizard runs | the U.S. EPA public vehicle API (`fueleconomy.gov`) | the year / make / model you pick, to fetch its MPG; nothing about you. |
| UNKNOWN-screen census (developer builds only, `censusUploadEnabled`) | off | a census server you configure | a **skeleton** of a screen the ruleset did not recognize: view classes and ids, hashed text slots under a k-anonymity gate, never the text itself — see `docs/adr/ADR-0011-unknown-census-privacy-model.md`. Release builds bind a no-op sink (`NoOpCensusSink`) and cannot upload at all. |
| Share UNKNOWN captures (developer builds only, `censusShareCaptures`, trusted installs) | off | the same census server, only while its operator has marked this install trusted | the **text** of UNKNOWN screens (`CensusUploadWorker.uploadEnvelopes`). An UNKNOWN screen gets no rule redaction — only the marker backstops — so a capture can still contain customer details, and the operator can read them. The switch says exactly this (`developer_settings_census_share_captures_explainer`). |
| Bug-report export | manual | a folder you choose (`DataExportViewModel.exportLog`) | the INFO-and-above log, scrubbed at the sink (`LogRepository`, `LogScrubber`): economics, counters, hashes — never raw store, customer or address text. |
| CSV export | manual | a file you choose (`DataExportViewModel`) | your own sessions and deliveries (merchant names included; customer and address hashes excluded). |

**Android backup and device transfer.** The manifest sets `android:allowBackup="true"`. Unless you turn app backup
off in Android's settings, the Room database (your event log and analytics tables) and the DataStore files
(preferences, consents, consent receipts) are included in your Google account's app backup and in device-to-device
transfer, under Android's backup encryption and Google's terms — DashBuddy does not send them anywhere itself.
Captures, census files and the census credentials are excluded (`app/src/main/res/xml/backup_rules.xml`,
`data_extraction_rules.xml`). Whether the database and the consent stores should be excluded too is an open
decision (#1236).

## 7. Where your data lives, and how long

- The event log and the analytics tables are a Room database on the device (`core/database`); the read-model
  tables are a rebuildable projection of your own event log.
- Preferences and consents are DataStore files on the device (`core/datastore`).
- Debug builds may keep capture envelopes for rule development under the app's files; release builds bind a no-op
  capture bus (`NoOpCaptureBus`) and keep none. Captures and census files are excluded from Android backup and
  device transfer (`app/src/main/res/xml/backup_rules.xml`).
- Logs rotate automatically: the firehose log keeps a bounded set of rotations and the shareable log keeps one
  backup (`LogRepository`).
- Retention of app-private data is "until you delete it": uninstalling removes the database, the DataStore files,
  the logs and any captures. Gallery screenshots (§4) and files you exported (§6) live outside the app and stay
  until you delete them. A one-tap in-app wipe does not exist yet — tracked in #1237.

## 8. Consent records

Each capability decision and the Screen-events decision are stored on the device beside the decision itself, in
the same DataStore write, with the time it was made, the app version, and the revision of this document that the
in-app disclosure referred to when it was recorded (`PrivacyDisclosure.REVISION`; `RuleCapabilityDataSource`,
`EventReceiptConsentDataSource`). The app does not verify that you opened this document — the revision records
which text the disclosure pointed at. They are readable under Automation & Consent. No export carries them today
(the bug-report export carries the decision log lines, not the receipts); they ride Android backup like the other
DataStore files (§6). The in-app link opens the current revision of this file; the revision history below says
what changed since the revision on a receipt.

## 9. Verify it

Repository: https://github.com/sjtrotter/DashBuddy — rulesets under `matchers/rules/`, the recognition pipeline
under `core/pipeline/`, the consent model under `docs/design/rule-capability-consent.md`, the census model under
`docs/adr/ADR-0011-unknown-census-privacy-model.md`, the DoorDash ICA posture under `LEGAL.md`.

## Revision history

- **r1 (2026-10-06)** — first revision. The in-app disclosure references this revision.
