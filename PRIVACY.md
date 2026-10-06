# DashBuddy privacy — what the app does, from the code

**Status: engineering ground truth, pre-counsel.** This document describes what the shipped code does, with the
file that does it named for each claim, so it can be checked against the source and kept honest by code review.
It is not yet a legal privacy policy; wording for the Play listing and the in-app disclosure is reviewed
separately (#246). Where a claim and the code ever disagree, the code is the fact and this file is the bug.

DashBuddy is **source-available**: the app is licensed under PolyForm Shield (`LICENSE`) and the recognition
ruleset under `matchers/` is Apache-2.0 (`matchers/LICENSE`). Every statement below can be verified against the
repository.

## 1. The one-sentence version

DashBuddy assists delivery drivers by reading enabled delivery apps and speaking offers while driving. Recognition, evaluation and economic calculations run on the device. Pressing Accept or Decline in DashBuddy requests a verified tap in the delivery app; scoring rules do not accept or decline offers automatically. Automated decline confirmation and pay-breakdown expansion require their own grants. Gas-price refresh is off until the dasher turns it on; setup vehicle lookup is on by default (§6). Other app-managed uploads require separate opt-in. Android backup, gallery sync and a chosen export provider can also copy data off the device.

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

In release builds, declining Screen events retains the supported-package subscription and leaves DashBuddy usable. In debug builds, declining disables the accessibility service until Screen events is allowed again (`ServiceInfoPolicy.shouldDisableSelf`). The debug permission note states this requirement.

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
- **The gas-price refresh** (`FuelPriceRepository`, off until the dasher turns it on, §6): once per refresh the last known location
  is read (`FusedLocationDataSource.getUserLocation`) and passed to Android's `Geocoder` to resolve the state for
  the regional price. `Geocoder` is a platform service that may use the network to do that, so on this feature the
  device's coordinates do leave the app, to Android's geocoding provider — not to DashBuddy and not to the EIA.
  The daily worker checks the saved automatic-refresh setting before fetching. Tap the bubble's gas price to enter manual mode, or turn off daily updates in setup and save setup, to stop automatic refreshes. Setup waits for the saved settings before its initial gas-price fetch (`WizardViewModel.loadExistingSettings`).

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

These are recognition and marker filters, not a guarantee that every sensitive layout is recognized. UNKNOWN debug captures can retain text the filters miss; known customer-content gaps are tracked in #1116. The matcher and privacy vocabulary is English-dependent, so non-English delivery screens reduce both recognition and filtering coverage. Returning to English restores those mechanisms, not a guarantee of complete filtering. Whole-display evidence screenshots bypass text redaction and can contain sensitive content (§4).

## 3. Customers are hashed, not stored

A delivery screen names the customer. DashBuddy needs to tell customers apart (to join a pickup to its drop-off)
but never needs to know who they are. Customer names and addresses are therefore replaced at the edge by a
`sha256` digest (`customerNameHash`, `customerAddressHash` — the ruleset's `sha256` parse transform,
`core/pipeline/.../rules/ParsedFieldsFactory`) before anything is persisted; the parsed customer plaintext is not kept in the event log or analytics tables. The shareable INFO+ log checks every line against known sensitive markers before writing; that finite filter is not a guarantee against every possible name, address or other sensitive detail. Debug capture envelopes are a separate storage surface: recognized screens receive rule redaction, while UNKNOWN screens rely on marker and customer-PII backstops. Missed details can remain in local debug captures (#1116), even when upload is off. Whole-display evidence screenshots are raw images (§4). With both developer upload/share toggles enabled and server trust granted, UNKNOWN screen captures can also reach the configured census server, whose operator can read them (§6). The dasher's own first
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

1. **Per-tap consent.** Every app-initiated automated tap requires a granted *capability* that lands **undecided**; nothing is granted
   automatically (#843). You allow or deny each one in the prompt that appears when the app comes to the foreground,
   or later under Settings → Data & Privacy → Automation & Consent. A denial persists; a changed rule layout
   re-asks. The capabilities shipped today for DoorDash (`matchers/rules/doordash/offer.json5`,
   `dash-lifecycle.json5`): `accept_offer`, `decline_offer`, `confirm_decline`, `expand_earnings`.
2. **User initiation and automated follow-ups.** Accept and Decline currently originate from an explicit user press in DashBuddy (`OfferEffects`, `ActionTrigger.USER`); scoring thresholds produce recommendations and flags, not autonomous offer acceptance or rejection. That press is its own consent and does not require a saved automation grant. Automated confirmation of a decline additionally requires quick declines to be enabled and the confirmation capability to be granted. Pay-breakdown expansion requires its capability grant and can run when a collapsed completed-delivery receipt is shown (`EffectMap.diffExpandAction`).
3. **Fire-time verification.** Before tapping, the handler re-finds the target in the live screen, checks it
   belongs to the delivery app, checks its label against an allowlist where the control has a label
   (`expand_earnings` is an icon-only chevron, verified by app boundary only — `RuleAction`), and aborts to manual
   on any doubt. Accept verification permits the labels “Accept” and “Add to route”; Decline verification requires a Decline label (`RuleAction`). Turning off an automation grant does not disable explicit user presses.

A tap you make yourself on the floating card (Accept / Decline) is your own action.

## 6. What leaves the device

There is no account, no analytics SDK, no advertising identifier. Gas-price refresh is off until the dasher turns it on. Setup vehicle lookup is on by default; other app-managed network features require separate opt-in.
The setup wizard's EPA lookup asking-first is tracked in #1258.

| Feature | Default | Where it goes | What is sent |
|---|---|---|---|
| Gas-price refresh (`isGasPriceAuto`, `AppPreferencesDataSource`) | **off until you turn it on**; tap the bubble gas price for manual mode, or disable daily updates in setup and save | the U.S. EIA public price API (`api.eia.gov`, `core/network/.../eia`), after Android's `Geocoder` resolves your state (§2.4) | a request for the regional price for your fuel type; no account, no identifier. The API key is never logged (#348). |
| Vehicle list in the setup wizard (`EpaVehicleDataSource`) | on while the wizard runs | the U.S. EPA public vehicle API (`fueleconomy.gov`) | automatic vehicle-list request when setup opens; selected year, make, model and vehicle ID as the user chooses, to retrieve MPG; no account or install ID |
| Google MPG search | manual button press | Google in the user's browser (`VehicleCard`) | entered vehicle year, make and model plus `mpg`; browser cookies/account settings apply |
| UNKNOWN screen/notification census (developer builds only, `censusUploadEnabled`) | off | a census server you configure | a **skeleton** of an admitted UNKNOWN screen or platform notification: screen view classes/ids, or a grammar-bounded notification channel id and five filtered slots; text slots carry coarse kinds or hashes under a k-anonymity gate, never source text — see `docs/adr/ADR-0011-unknown-census-privacy-model.md`. Release builds bind a no-op sink (`NoOpCensusSink`) and cannot upload at all. |
| Share UNKNOWN captures (developer builds only, `censusShareCaptures`, trusted installs) | off | the same census server, only while its operator has marked this install trusted | the **text** of UNKNOWN screens (`CensusUploadWorker.uploadEnvelopes`). An UNKNOWN screen gets no rule redaction — only the marker backstops — so a capture can still contain customer details, and the operator can read them. The switch says exactly this (`developer_settings_census_share_captures_explainer`). |
| Bug-report export | manual | a folder/provider the user chooses (`DataExportViewModel.exportLog`) | INFO+ milestones checked against known sensitive markers before writing; matches are replaced, but unknown sensitive details can escape filtering. A cloud provider may upload the file. DashBuddy does not deliver it to the developer. |
| CSV export | manual | a folder/provider the user chooses (`DataExportViewModel` → `CsvExporter`) | all recorded sessions and deliveries, including merchant names and excluding customer/address hashes. A cloud provider may upload the files. |

Exports replace existing files with the same names in the chosen folder. Exported files survive uninstall and remain until deleted through that provider. “No direct DashBuddy upload” does not mean that a selected cloud folder keeps the files on the phone.

Notification census publication runs after admission, dedup and capture, before UNKNOWN rejection,
independently of capture enablement. It scans the original five fields and all action labels with
`SensitiveMarkerScan`, including their whitespace-joined values, before the customer lead-in scrub and
shared 40-character/value filters. Any sensitive marker refuses the entire item; action labels are never
uploaded. Channel ids must match `[A-Za-z0-9_.-]{1,64}` without repair. The debug screen and notification
paths share one sink, spool, worker and server-authoritative 300/day budget, with common 429 deferral;
there is no separate client daily allowance. Release still binds `NoOpCensusSink` and builds nothing.
Census failures leave recognition unaffected; INFO counters contain counts and reason/kind names,
never notification text or channel ids. Trusted capture sharing remains screen-only. CLICK skeletons
stay OUT until a reliable screen fingerprint can accompany clicks (ADR-0011 §10).

**Android backup and device transfer.** The manifest sets `android:allowBackup="true"`. Unless you turn app backup
off in Android's settings, the Room database (your event log and analytics tables) and general app/economy
preferences are included in your Google account's app backup and in device-to-device transfer, under Android's
backup encryption and Google's terms — DashBuddy does not send them anywhere itself. **The database follows the
user: restoring history and vehicle/economy settings on a new phone is expected.** The capability-grant/receipt
store (`datastore/rule_capability_grants.preferences_pb`) and the Screen-events consent/receipt store
(`datastore/consent_event_receipt.preferences_pb`) are excluded, so a new device re-asks consent. Debug logs
(`app.log`, `shareable.log`, `shareable.log.1`, and rotations under `logs/`), captures, census files, census
credentials and the developer-settings store (census upload/share toggles + the server policy cache) are also excluded from both backup and transfer (`app/src/main/res/xml/backup_rules.xml`,
`data_extraction_rules.xml`; #1236, dev ruling 2026-10-07).

## 7. Where your data lives, and how long

Time-estimate learning (#254) stays on-device. The local analytics projection additionally retains
job offer count/single-offer hash, order count/shop classification, dropoff arrival odometer and pickup
confirmation odometer from existing event evidence. Numerical/provenance-only queries derive transit
minutes per mile and combined pickup/dropoff dwell per order. Per-platform preferences store two raw
medians and the cumulative eligible delivery count, separately from explicit user overrides; medians
use the latest 30 eligible deliveries. No event decoding or live-state lookup occurs in the learner,
and this change adds no network transmission, precise-location collection, or merchant/customer
identity export. Vehicle/default resets preserve these learned preferences.


- The event log and the analytics tables are a Room database on the device (`core/database`); the read-model
  tables are a rebuildable projection of your own event log. The database is backed up and transferred so your
  history can be restored on a new phone (§6).
- Preferences and consents are DataStore files on the device (`core/datastore`). General app/economy preferences
  are backed up; the two dedicated consent stores are excluded from backup and transfer so a new device re-asks.
  `EventReceiptConsentDataSource` keeps the decision and receipt only in `consent_event_receipt`. It best-effort deletes the old
  Screen-events keys from `app_prefs` without copying them: restored app preferences must never restore consent.
  An empty dedicated store re-asks once after this upgrade as well as on a new phone.
- Debug builds may keep capture envelopes for rule development under the app's files; release builds bind a no-op
  capture bus (`NoOpCaptureBus`) and keep none. Captures and census files are excluded from Android backup and
  device transfer (`app/src/main/res/xml/backup_rules.xml`).
- Logs rotate automatically: `app.log` stays in the app's files root, firehose rotations live under `logs/`, and
  the shareable log keeps one `shareable.log.1` backup (`LogRepository`). All these log paths are excluded from
  Android backup and device transfer, in both external storage and the internal-storage fallback. Each log-store
  initialization moves legacy root rotations into that root's `logs/`; failed moves attempt deletion because
  debug rotations are disposable, and any remaining legacy rotations are retried on the next initialization.
- Retention of app-private data is "until you delete it": uninstalling removes the database, the DataStore files,
  the logs and any captures. Android may restore the backed-up database and app/economy preferences on reinstall
  or a new phone; the excluded consent stores and debug logs do not restore. Gallery screenshots (§4) and files
  you exported (§6) live outside the app and stay until you delete them. A one-tap in-app wipe does not exist yet — tracked in #1237.

## 8. Consent records

Each capability decision and the Screen-events decision are stored on the device beside the decision itself, in
the same DataStore write, with the time it was made, the app version, and the revision of this document that the
in-app disclosure referred to when it was recorded (`PrivacyDisclosure.REVISION`; `RuleCapabilityDataSource`,
`EventReceiptConsentDataSource`). The app does not verify that you opened this document — the revision records
which text the disclosure pointed at. They are readable under Automation & Consent. No export carries them today
(the bug-report export carries the decision log lines, not the receipts). Both decisions and their receipts are
in dedicated stores excluded from Android backup and device transfer (§6), so a new device re-asks consent while
the database and general app/economy preferences restore. The in-app link opens the current revision of this file;
the revision history below says what changed since the revision on a receipt.

## 9. Verify it

Repository: https://github.com/sjtrotter/DashBuddy — rulesets under `matchers/rules/`, the recognition pipeline
under `core/pipeline/`, the consent model under `docs/design/rule-capability-consent.md`, the census model under
`docs/adr/ADR-0011-unknown-census-privacy-model.md`, the DoorDash ICA posture under `LEGAL.md`.

## Revision history

- **r3** — corrected user-initiated versus automated taps, accepted button labels, debug-decline behavior, filtering limitations, network recipients and controls, cloud-folder exports, and in-app backup/screenshot disclosures. Fixed the setup startup fetch ordering. Gas refresh is off until the dasher turns it on. Current in-app disclosures reference this revision.

- **r2 (2026-10-07)** — consent stores and debug logs excluded from Android backup and device transfer;
  re-consent on a new device, while the database and app/economy preferences follow the user (#1236, includes #1214).
  Receipts created under r2 retain that revision.
- **r1 (2026-10-06)** — first revision.
