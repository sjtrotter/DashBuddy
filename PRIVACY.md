# gig.log privacy — what the app does, from the code

**Status: engineering ground truth, pre-counsel.** This document describes what the shipped code does, with the
file that does it named for each claim, so it can be checked against the source and kept honest by code review.
It is not yet a legal privacy policy; wording for the Play listing and the in-app disclosure is reviewed
separately (#246). Where a claim and the code ever disagree, the code is the fact and this file is the bug.
A change to a claim here updates the in-app privacy page (`privacy_*`) and every disclosure string that states it, in the same PR.
The public privacy policy, https://gig.log.trotter.cloud/privacy/, is built from those same `privacy_*` strings and
this file's current revision (`tools/site/build_site.py`, deployed by `.github/workflows/pages.yml`), so it changes
with them.

gig.log is **source-available**: the app is licensed under PolyForm Shield (`LICENSE`) and the recognition
ruleset under `matchers/` is Apache-2.0 (`matchers/LICENSE`). Every statement below can be verified against the
repository.

## 1. The one-sentence version

gig.log assists delivery drivers by reading enabled delivery apps and speaking offers while driving. Recognition, evaluation and economic calculations run on the device. Pressing Accept or Decline in gig.log requests a verified tap in the delivery app; scoring rules do not accept or decline offers automatically. Automated decline confirmation and pay-breakdown expansion require their own grants. Gas-price refresh is off by default on new installs; an existing setting is kept. Setup vehicle lookup asks first and saves the choice (§6). Other app-managed uploads require separate opt-in. Android backup, gallery sync and a chosen export provider can also copy data off the device.

## 2. What gig.log reads

### 2.1 The accessibility service (the screen reader)

Declared in `app/src/main/res/xml/accessibility_service_config.xml` (the Play build) and its copy
`app/src/accessibilityTool/res/xml/accessibility_service_config.xml` (the sideload and debug builds); the two
differ only in the first flag below (`AccessibilityToolFlagGuardTest`). There are three builds (`app/build.gradle.kts`):
the **Play** build (`cloud.trotter.log.gig`), the **sideload** build from outside Play (`cloud.trotter.log.gig.sideload`,
a release build with none of the debug extras) and the **debug** build (`cloud.trotter.log.gig.debug`).

**The debug build is the tester build, and it is public.** Every version tag attaches it to the GitHub Release as
`giglog-debug-<version>.apk` (`.github/workflows/release.yml`), beside the sideload APK, so anyone can install it.
It is meant for the developer and the testers the developer invites, not for other drivers. On top of everything
the sideload build does, it keeps capture envelopes of UNKNOWN screens on the device under the app's files (§3, §7),
writes a verbose on-device log, and offers capture sharing, a switch in developer settings that sends the text of
UNKNOWN screens to the census server for a trusted install (§6). Capture sharing needs the census, which every build
offers (§6), and is off until the tester turns it on. The census server's address is built into the app
(`https://census.dashbuddy.trotter.cloud`, `DevSettingsDataSource.DEFAULT_CENSUS_BASE_URL`); turning the census on
enrols the install there at the next daily run. Nothing reaches a server before the tester turns a switch on.

- `android:isAccessibilityTool` — `false` in the Play build, `true` in the sideload and debug builds. Google Play
  allows `true` only for apps built for people with disabilities, so the Play build does not declare it. The flag
  changes how Android classifies and warns about the service and which events it is eligible to receive: since
  Android 14 the system delivers events an app marks accessibility-data-sensitive only to services declaring `true`.
  Uber's offer pop-up is such a surface, so the Play build cannot read it; it reads Uber Driver's other screens, and
  its disclosure card says so. The sideload build exists for drivers who want Uber offers read. Accept and Decline
  happen when the dasher presses them in gig.log. Automated actions need separate per-action grants; auto-accept is
  not built.
- `android:packageNames` — in the Play and sideload builds the service is bound to the delivery apps gig.log has
  rules for: `com.doordash.driverapp` (DoorDash) and `com.ubercab.driver` (Uber Driver). Nothing from any other app
  reaches the pipeline. (One opt-in exception, §2.2.) The manifest's `<queries>` names the same two packages.
  Instacart Shopper and Walmart Spark stay in the platform registry (`Platform`) with no rules: in those builds they
  are not bound, cannot be enabled in Settings, and a saved preference from an older install that enables them is
  ignored (`Platform.readable`, `PlatformPreferencesRepository`). **The debug (tester) build also reads Instacart
  Shopper (`com.instacart.shopper`) and Walmart Spark (`com.walmart.spark`)** when the tester turns them on in
  Settings, so a tester who drives them feeds their screens to the census (build table `readsUnboundPlatforms`, its
  own `src/debug` service config and `<queries>`). They have no rules, so every screen of theirs is UNKNOWN and is
  handled like any UNKNOWN screen in that build: the sensitive-marker backstop first, then a capture envelope kept on
  the device (§3, §7) and, only with the census switches on, the census outline or capture text (§6).
  `AccessibilityPackageParityTest` pins, per build, the service config and `<queries>` to the registry's readable
  set, and the bound set to the rules assets.
- `android:accessibilityEventTypes` — four event types in the Play and sideload builds: window state changed, window content
  changed, view clicked, windows changed. No text-changed, focus or selection events. A **debug** build subscribes to
  every event type for rule development (`AccessibilityListener`, `BuildConfig.DEBUG`).
- `android:canRetrieveWindowContent="true"` with `flagReportViewIds`, `flagRetrieveInteractiveWindows`,
  `flagIncludeNotImportantViews` — the service reads the visible view tree (text, content descriptions, view ids,
  bounds) of those apps' windows. `flagRetrieveInteractiveWindows` plus `getWindows()` is what lets it see a
  platform's offer **overlay** (Uber draws offers in a system overlay window).
- `android:canTakeScreenshot="true"` — used only for the evidence screenshots in §4, which are OFF by default.

**The disclosure.** Before sending the dasher to Android's accessibility settings, the setup sheet shows the screen
reading card (`AccessibilityDisclosureCard`). It names the apps the service reads, from the registry; says what it
reads, what it drops and what it saves as codes; says that screenshots are whole-screen pictures saved to the
phone's gallery, where gallery sync or sharing can copy them off the phone; says where screen reading and
calculations happen and what can copy data off the phone; says when it taps a button; and links gig.log's own
Privacy page (§8). In the Play build it also says that this version can't read Uber Driver's offer pop-up
and that it reads Uber Driver's other screens. Only a build that offers Screen events (§2.2) carries the Screen events
sentence, on the card and in the service description Android shows in its settings; each build has one copy of that
description (main for Play, `src/sideload` and `src/debug`), and `AccessibilityDescriptionGuardTest` pins the names
and the sentence per build. Allow opens Android's settings; Don't allow leaves the service off and records nothing.

What the tree is used for: recognition against a published, rule-based ruleset (`matchers/rules/`, Apache-2.0)
that turns a screen into a named state (an offer, a pickup, a drop-off, a dash summary). Recognition, evaluation
and every economic computation run **on the device** (`core/pipeline`, `core/state`, `domain`).

### 2.2 The one wide-receipt opt-in ("Screen events")

**Not in the Play build.** Play allows the wide receipt only for an app that declares itself an accessibility tool,
so the Play build does not offer Screen events: it has no Screen events step or Settings switch, and its package
filter is always on, whatever an earlier install stored (`EventReceiptConsent.enforced`, fed by
`BuildConfig.WIDE_EVENT_RECEIPT_OFFERED`). The rest of this section describes the sideload and debug builds.

By default the service receives events only from the apps above. A separately asked, affirmative consent
("Screen events", the first step of the permission flow, `EventReceiptPreferences` in `domain`, enforced by
`AccessibilityListener` through `ServiceInfoPolicy`) lets the service receive the system's window-change events
without a package filter so it can tell which app is in front. Undecided behaves as declined; a decline is
durable; it can be changed under Settings → Data & Privacy → Automation & Consent. Even when allowed, only the
delivery apps' windows are inspected: another app's window is identified by package name only, and the pipeline
stores nothing of its content. The one surface that can still capture another app's content is a whole-display
evidence screenshot (§4), which is off by default and says so where it is turned on (`event_receipt_body`).

In the sideload build, declining Screen events retains the supported-package subscription and leaves gig.log usable. In debug builds, declining disables the accessibility service until Screen events is allowed again (`ServiceInfoPolicy.shouldDisableSelf`). The debug permission note states this requirement.

### 2.3 Notifications

The offer heads-up redesign (#1333 S2g) reads, stores and sends nothing new; the store name already appeared in the notification.

A separate `NotificationListenerService` reads the delivery apps' notifications (offer pushes, arrival and
pause notices, earnings notices) and recognizes them against the same ruleset (`core/pipeline/.../notification`).
A notification from a package that is not an enabled platform is dropped before anything is read
(`NotificationListener.onNotificationPosted` checks the enabled-package set; `NotificationFilter.isRelevant` gates
what is forwarded). Only the platforms the service is bound to (§2.1) can be enabled.

### 2.4 Location

`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` are used in two places:

- **The on-device odometer** (`core/location`, `OdometerEffectHandler`): GPS fixes are sampled **only while a dash
  is active** and stop when the dash ends. Each fix is judged by `OdometerFixPolicy` (accuracy, speed and jump
  limits) before it can move the mileage. A location is never written to a log line — a rejected fix is logged as
  numbers and a reason, never as a coordinate (`domain/.../OdometerFixPolicy`).
- **The gas-price refresh** (`FuelPriceRepository`, off by default on new installs; an existing setting is kept, §6): once per refresh the last known location
  is read (`FusedLocationDataSource.getUserLocation`), rounded to one decimal place of a degree (about 11 km;
  `GeocoderInput`), and that rounded point is passed to Android's `Geocoder` to resolve the state for the regional
  price. `Geocoder` never gets the exact fix. It is a platform service that may use the network, so on this feature
  the rounded location does leave the app, to Android's geocoding provider — not to gig.log and not to the EIA.
  The daily worker checks the saved automatic-refresh setting before fetching. Tap the bubble's gas price to enter manual mode, or turn off daily updates in setup and save setup, to stop automatic refreshes. Setup waits for the saved settings before its initial gas-price fetch (`WizardViewModel.loadExistingSettings`).

### 2.5 What gig.log never processes

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

A delivery screen names the customer. gig.log needs to tell customers apart (to join a pickup to its drop-off)
but never needs to know who they are. Customer names and addresses are therefore replaced at the edge by a
`sha256` digest (`customerNameHash`, `customerAddressHash` — the ruleset's `sha256` parse transform,
`core/pipeline/.../rules/ParsedFieldsFactory`) before anything is persisted; the parsed customer plaintext is not kept in the event log or analytics tables. The shareable INFO+ log checks every line against known sensitive markers before writing; that finite filter is not a guarantee against every possible name, address or other sensitive detail. Debug capture envelopes are a separate storage surface: recognized screens receive rule redaction, while UNKNOWN screens rely on marker and customer-PII backstops. One of those backstops looks for an address block (#1116): on an UNKNOWN screen, a street line followed by a `City, ST ZIP` line is replaced with `[redacted]`, together with the quoted notes and short number codes in the same block, and a code or quoted note directly above a city line. Other details can still remain in local debug captures, even when upload is off: free text without quotes, inside or outside an address block; a separate unit line such as `Apt 4B` (between the street and the city line it leaves the whole block raw); a street or city line on its own; a city line above its street; a city line inside a container that has its own label; a one-line full address; and a tapped element captured without its address block. On an UNKNOWN screen, the customer's name in a `Return <name> to <store>` task line is also replaced with `[redacted]`; the store name is kept. Whole-display evidence screenshots are raw images (§4). With both developer upload/share toggles enabled and server trust granted, UNKNOWN screen captures can also reach the configured census server, whose operator can read them (§6). The dasher's own first
name and last initial (the main-menu greeting) is processed as the app's own user. Merchant (store) names are kept
as text: they are the driver's business data, not a person.

## 4. Screenshots (evidence) — off by default

`ScreenShotHandler` (`app/.../state/effects/`) can save a PNG of an offer card, a delivery receipt or a dash
summary to the device's own gallery under `Pictures/gig.log/` through `MediaStore`. Nothing fires unless the
**master toggle AND the matching category toggle** are on (`EvidenceConfig`, #426); the master default is OFF.
A capture is skipped if this switch is off or another app is in front when it fires. Anything else on screen,
such as a video or keyboard, can appear in the image. After the 500 ms settle delay, the handler rechecks the
master and category switches, then reads only the active window's package name. The front app must be an enabled
delivery platform or gig.log itself, including its bubble; a missing or unreadable root skips the capture.
A capture already being saved finishes. No window enumeration, content mapping or sensitive-marker scan runs
at capture time. Pixel redaction is not done and remains tracked in #883.
A screenshot is the **whole display** (`Display.DEFAULT_DISPLAY`), so an offer drawn over another app captures
that app too. gig.log never uploads a screenshot. The file is an ordinary gallery image: your own gallery sync or
sharing applies to it, it survives uninstall like any photo, and you can delete it from the gallery at any time.
Do not enable evidence capture on screens you would not want kept.

## 5. Taps gig.log may perform — each one is its own consent

Rules never actuate (#425). A rule may only point at a target ("the Accept button"); the app owns the taps, and
each tap is gated three ways (`SideEffectEngine`, `UiInteractionHandler`):

1. **Per-tap consent.** Every app-initiated automated tap requires a granted *capability* that lands **undecided**; nothing is granted
   automatically (#843). You allow or deny each one in the prompt that appears when the app comes to the foreground,
   or later under Settings → Data & Privacy → Automation & Consent. A denial persists; a changed rule layout
   re-asks. Accept and Decline are not asked for until gig.log can tap them by itself; earlier decisions stay listed and can only be turned off. An automatic tap whose action has no automatic trigger is refused (`SideEffectEngine`). The capabilities shipped today for DoorDash (`matchers/rules/doordash/offer.json5`,
   `dash-lifecycle.json5`): `accept_offer`, `decline_offer`, `confirm_decline`, `expand_earnings`.
2. **User initiation and automated follow-ups.** Accept and Decline currently originate from an explicit user press in gig.log (`OfferEffects`, `ActionTrigger.USER`); scoring thresholds produce recommendations and flags, not autonomous offer acceptance or rejection. That press is its own consent and does not require a saved automation grant. Automated confirmation of a decline additionally requires quick declines to be enabled and the confirmation capability to be granted. Pay-breakdown expansion requires its capability grant and can run when a collapsed completed-delivery receipt is shown (`EffectMap.diffExpandAction`).
3. **Fire-time verification.** Before tapping, the handler re-finds the target in the live screen, checks it
   belongs to the delivery app, checks its label against an allowlist where the control has a label
   (`expand_earnings` is an icon-only chevron, verified by app boundary only — `RuleAction`), and aborts to manual
   on any doubt. Accept verification permits the labels “Accept” and “Add to route”; Decline verification requires a Decline label (`RuleAction`). Turning off an automation grant does not disable explicit user presses.

A tap you make yourself on the floating card (Accept / Decline) is your own action.

## 6. What leaves the device

There is no account, no analytics SDK, no advertising identifier. Gas-price refresh is off by default on new installs; an existing setting is kept. Setup vehicle lookup asks first and saves the choice; other app-managed network features require separate opt-in.

Every connection the app makes is HTTPS (#1620). The app's network security config
(`app/src/main/res/xml/network_security_config.xml`, referenced from the manifest of every build) refuses cleartext
traffic for every host with no exception, and `HttpsEndpointGuardTest` checks that every URL in shipped source is
`https`: the census server, `api.eia.gov` and `fueleconomy.gov`.

| Feature | Default | Where it goes | What is sent |
|---|---|---|---|
| Gas-price refresh (`isGasPriceAuto`, `AppPreferencesDataSource`) | **off by default on new installs; an existing setting is kept**; tap the bubble gas price for manual mode, or disable daily updates in setup and save | the U.S. EIA public price API (`api.eia.gov`, `core/network/.../eia`), after Android's `Geocoder` resolves your state from your location rounded to about 11 km, never the exact fix (§2.4) | a request for the regional price for your fuel type; no account, no identifier. The API key is never logged (#348). |
| Vehicle list in the setup wizard (`EpaVehicleDataSource`) | asks first (#1258): off until "Look up my vehicle" is tapped; choice is saved | the U.S. EPA public vehicle API (`fueleconomy.gov`) | vehicle-list request after opt-in, including on later setup visits; selected year, make, model and vehicle ID as the user chooses, to retrieve MPG; no account or install ID |
| Google MPG search | manual button press | Google in the user's browser (`VehicleCard`) | entered vehicle year, make and model plus `mpg`; browser cookies/account settings apply |
| Text-to-speech (`TtsEffectHandler`) | used for spoken offers | your phone's text-to-speech engine | the offer sentence; the engine may use the network under its own terms |
| UNKNOWN screen/notification census (every build: Play, sideload and debug, #1620; see "The census opt-in" and "When the census uploads" below) | off | gig.log's census server, built into the app (§2.1); turning the census on enrols the install | a **skeleton** of an admitted UNKNOWN screen or platform notification: screen view classes/ids, or a grammar-bounded notification channel id and five filtered slots; text slots carry coarse kinds or hashes under a k-anonymity gate, never source text — see `docs/adr/ADR-0011-unknown-census-privacy-model.md` — plus daily recognition-health counts per platform and app version, sent with this install's ID. Once the server lists `uinode.skeleton.v2` (and `notification.skeleton.v2`) as accepted, a skeleton also carries: for a money figure, 12-hour clock time, distance, minute count or item count, its format with the digits removed, from a closed list (`$#.##`, `#:## AM/PM`, `#.# mi`, `# min(s)`, `# item(s)`; AM and PM are one class, and the value never leaves); each node's click and visibility flags; and a notification's action labels as filtered slots of the same kinds as any text slot (a hash of a short chrome-like label, a coarse kind such as `digits` or `mixed`, a format template, or `withheld`; never the label). The skeleton's day is the UTC day. At most one skeleton of each layout and format is kept a UTC day, and only from the delivery apps the census choices cover (below). |
| Screen reviews, the contributor tier (every build, #1625; see "Screen reviews" below) | off; offered only by invitation | the same census server, under this install's census ID | to accept, one signed `POST /v1/contributor/grant` whose whole body is `{"eligible":true}`: a yes, never a count; later a bodyless signed `GET` of the grant's state before each review session. Reviewing a screen (#1626), only while the grant stands, each request signed and sent under the census upload lock: `POST /v1/contributor/classes` (the screen's variant: its public fingerprint, kind, delivery-app version, hash domain, filter revision and layout id, and the slot ids of its own skeleton, never a value), `POST /v1/contributor/review` (the variant, each confirmed slot with that slot's public census hash, and each label as a slot and a role from the server's closed list; never a word or a value), `POST /v1/contributor/gates` (the variant and the confirmed slot and hash pairs), and `POST /v1/contributor/words` (a confirmed word in plain text, with its slot and hash, only after `gates` answered open for that exact slot and hash). Ask the developer (#1627): `POST /v1/contributor/quarantine` with the artifact (the screen's outbound skeleton, the dasher's confirmations as slot and public hash, the labels, and only the words `gates` answered open for in that same request; base64url) and an optional note from the server's closed list |
| Share UNKNOWN captures (debug build only, §2.1; `censusShareCaptures`, trusted installs) | off | the same census server, only while its operator has marked this install trusted | the **text** of UNKNOWN screens (`CensusUploadWorker.uploadEnvelopes`). An UNKNOWN screen gets no rule redaction — only the marker and address-block backstops (§3) — so a capture can still contain customer details, and the operator can read them. The switch says exactly this (`developer_settings_census_share_captures_explainer`). |
| Bug-report export | manual | a folder/provider the user chooses (`DataExportViewModel.exportLog`) | INFO+ milestones checked against known sensitive markers before writing; matches are replaced, but unknown sensitive details can escape filtering. Then the consent receipts (§8). A cloud provider may upload the file. gig.log does not deliver it to the developer. |
| CSV export | manual | a folder/provider the user chooses (`DataExportViewModel` → `CsvExporter`) | all recorded sessions and deliveries, including merchant names and which saved vehicle drove each dash (its id and label — the dasher's own text), plus deductible miles per vehicle per tax year and logged expenses and fill-ups (amounts, categories, the dasher’s own labels, gallons and the app's tracked miles at the fill-up, plus the saved vehicle id and label for car-care and gas receipts), including voided entries. No customer data or customer/address hashes. A cloud provider may upload the files. |

**The census opt-in** (#1621). Every build has Settings > Data & privacy > Census (`CensusScreen`). Its switch turns
on only through a full-screen disclosure (`CensusDisclosure`) that ends with Accept and Not now. The disclosure says:
what is sent (outlines of screens and notifications gig.log doesn't recognize, each word replaced by a one-way code the
server can't turn back into the word but can match by trying a common word; amounts, clock times, distances, minutes
and item counts as their format such as `$#.##`; words that look like a name or an address left out before coding,
with the limit that the filters can miss a name, which then goes only as a code; never the screen as the dasher sees
it; daily recognition counts by delivery app and app version), why (delivery apps change screens in some areas
before others), who receives it (gig.log's census server, by host name, which also sees the phone's IP address), when
(at most once a day: a random time picked each day, a send that waits up to an hour for Wi-Fi, unless mobile data is
allowed, and for enough battery, and is skipped that day if it cannot start by then; over HTTPS), that it is not anonymous (the
server can tell one phone's uploads apart for 30 days, not after), that the census can be stopped and its data on the
server and on the phone deleted from Settings, and that the dasher's agreement with a delivery company is between the
dasher and that company. The timing, Wi-Fi and HTTPS statements are built in every build (#1620, below), and so is
the button that asks the server to delete its census data (#1622, "What was sent, and deleting it" below), and
deleting what gig.log keeps on the phone (Settings > Data & privacy > Delete everything on this phone, #1579, §7).
The server takes deletion requests (census#79) and holds the 30-day limit (census#86) only from its next deployment,
so the disclosure carries one line for the build it runs in: in the Play and sideload builds, "Settings > Data &
privacy > Delete everything on this phone deletes what gig.log keeps on the phone, and asks the census server first.
The 30-day limit and deleting the census server's data take effect with the census server's
next update; until then a deletion request waits on this phone" (`census_note_release`); in the debug build, that it
can also send the text of unrecognized screens from a phone the census server trusts, with capture sharing on in
developer options, that the 30-day limit doesn't apply to it yet, and the same server-deletion and on-phone lines
(`census_note_test_build`). The census screen
also holds the choices: which delivery apps (default: every app the build reads) and Wi-Fi only (default on), saved as
they change and read by every upload (below). Accept records the switch, a consent receipt bound to the census endpoint (§8) and the choices in one write;
Not now, or turning the switch off, records a decline, and only a later Accept turns the census back on. Every upload
stage checks the consent (`DevSettingsRepository.censusUploadEnabled`, `CensusUploadWorker.canRun`): it is on only
with the switch on and a granted receipt for exactly the endpoint the build sends to, so an install that turned the
census on before the disclosure existed, a declined receipt, or a receipt for another endpoint uploads nothing, and the
screen asks again. A server revoke turns the switch off and keeps the receipt.

**When the census uploads** (#1620, ADR-0011 §8 and §21). The same in every build. Nothing a screen or a notification
does asks for an upload: queueing a skeleton never schedules work (`HttpCensusSink`). The one scheduled run is daily
(`CensusUploadPlanner`, `CensusWorkScheduler`): a random time in the next UTC day, drawn on the phone with
`SecureRandom`, stored in the developer-settings store, and drawn again at the start of each daily run. Opening the app
keeps the stored time. The drawn time is the target, not the send: with Wi-Fi only on (the default) the run waits for an
unmetered network (`NetworkType.UNMETERED`), and with it off for any connection, and either way for a battery that is
not low. A daily run sends only if it starts between its target and an hour after it
(`CensusUploadPlanner.LATE_LIMIT_MILLIS`); a run that starts later, earlier, or with no stored target sends nothing
and only draws the next day's time. So a send can be delayed by up to an hour, and within that hour it can start
when the phone gets Wi-Fi or a charge, for example on reaching home; a send held back longer than that is skipped.
Before sending, every run (manual ones included) checks the current Wi-Fi only choice against the active network
(`CensusNetwork`): on a metered network with Wi-Fi only on it sends nothing, so nothing uploads over mobile data even
for a run queued before the choice changed; changing the choice also cancels a waiting manual run. A failed run waits
for the next day's time. Health
reports ride the same run and the same consent. The run checks the consent first: without it, nothing is sent. The
census choices filter every kind of data: a skeleton from a delivery app the choices leave out is not queued, one
queued before the dasher unchecked the app is removed unsent, that app's health rows are not sent (they age out on the
phone after 8 days), and in the test build its capture envelopes are neither queued nor sent. **Sampling:** at most one skeleton of each
variant a UTC day is queued (`CensusDailySample`, `CensusSampleKey`). A variant is the skeleton's schema, platform and
platform app version, then for a screen its structural fingerprint (classes, ids, nesting) and each node's flags and
text fields, and for a notification its channel and which fields and actions it has; every value adds only its format
template. It never includes a word hash, a word count, whether a filter withheld a word, or a notification's
fingerprint (which covers its words), so frames that differ only in a
customer's or a store's name are one variant. Later sightings of a variant that day add to a count in
`filesDir/census/sampled.json`, which never leaves the phone and keeps two days. The test build's developer options
keep Upload now for a manual run, which keeps to Wi-Fi only, is never skipped as late and does not move the daily time. Retention on the server follows ADR-0011's
server-side commitments.

**What was sent, and deleting it** (#1622, ADR-0011 §21). The census screen's "What gig.log sent"
(`CensusSentScreen`) lists the census requests the server read (it answered 200, 400 or 422) in the last 7 days: per
request its time and items, per item its kind (screen, notification, daily counts, or in the test build screen
text), its delivery app, and for an outline its node count and how many values went as a word code, as a format
template, as a coarse kind only, or withheld. The transport hands the exact bytes of each such request body to
`CensusSentLog` (`CensusApi`, `CensusSentObserver`), which keeps only that summary (`CensusSentBatch.read`): no word
code, format template, batch ID or other value from the request. The record is `filesDir/census/sent.json`, at most 60
requests and 7 days (expired entries are dropped on every read, and leave the screen while it is open), excluded from
backup and transfer with the other census files, and never uploaded. The screen also shows the stored target time of
the next daily run, with the time left. "Delete my census data" (`CensusDataDeletion`) is separate from the switch
and leaves the consent as it is. Its confirmation names what the server keeps: the install record, so the census ID
keeps working, and today's quota row (counts and the IDs of today's batches, used to refuse a repeat), which the
server keeps for 7 days; and what it cannot tie to the phone (cluster samples stored without an install ID, fleet
totals, vocabulary already approved at k, published rules and synthetic fixtures, rows under destroyed epoch keys);
and the server's backups (they keep the install's rows until they expire, at most 14 days later, and a restore
re-applies the deletion first). Under the upload lock it stores the request as pending, queues its own retry run on
any network (`CensusWorkScheduler.enqueueDeletion`, queued again at app start while a request is unresolved), cancels
a waiting manual run, clears the skeleton queue and its in-flight batch (the queue's clear boundary also drops an
outline handed to the queue before the clear and written after it), the debug build's envelopes and the unsent daily
counts, then sends a signed `DELETE /v1/installs/me/data` with an empty body with the key of the one enrolled
install ID the request binds at its first attempt. The screen shows the server's confirmation: the rows it erased and its UTC day (only a
well-formed date is kept). Until the server has confirmed and the phone's queue is cleared, every census run sends the
request first and sends nothing else, whatever the consent. The developer identity reset (test build only) never
lets an enrolled identity go undeleted: it first runs a fresh deletion for it and resets only after the server
confirms it and the phone's queue is cleared; otherwise it is refused, keeps the identity and says why ("The census
server hasn't confirmed deleting this phone's data yet, so the ID can't be reset"). A never-enrolled identity resets
freely. So the phone never keeps an old identity's key. A request the server does not answer, answers 404 (the deployed census server
takes deletion requests only from its next update, census#79; until then the request waits on the phone and the
screen says so) or answers 401 stays pending: a 401 can mean a bad signature as well as an unknown ID, so it never
counts as deleted, and the ID is kept. On a 401 the phone's clock does not explain, it re-enrols the SAME ID with the
SAME key (idempotent on the server; after a restore that erased the install, it re-creates it) and sends the DELETE
once more; only the DELETE's own confirmation resolves the request, and an enrolment alone never does. With no census
ID enrolled on the phone, no request is sent and the screen says so ("No deletion was
sent: this phone had no census ID at the time"), a fact about the request, not a claim about the server; that line
clears once the phone enrols again. A revoked install cannot delete: the census turns off and the credential is
wiped, as on any revoke (the server deletes a revoked install's sightings itself). After a server restore from a backup
older than a deletion, the server erases the whole install and answers 401 to uploads; once no deletion is pending,
the worker re-enrols the SAME install ID with the SAME key (idempotent on the server; it also covers a passing
signature failure), consent kept, and sends once more; if that enrolment fails it keeps the identity and tries again
on the next run. It never mints a new identity on a 401, so every ID that uploaded stays one this phone can delete
under: a new identity is minted only when there is no usable credential (none, or its key is lost), when a
never-enrolled one conflicts, or by the developer reset, which deletes first. A 401 after the server already accepted
a request from the same credential in that run is not treated so.

**Screen reviews (the contributor tier)** (#1625, ADR-0011 §15). Separate from the census switch and off unless the
dasher accepts an invitation. Eligibility is worked out on the phone from what it already holds
(`ContributorEligibility`, `ContributorEligibilityReader`): the census on, this phone's census ID enrolled at least 8
UTC calendar days ago, the census server's own rule, so the phone never invites an install the server would refuse
(`CensusCredentialStore.enrolledAtMillis`, the phone's clock at enrolment; an ID enrolled before this was recorded
counts from the first time it is read), a recorded shift on at least 3 of the last 7 local days, and at least
2 ended shifts, both from the analytics read model (`ContributorActivityDao`: shift start and end times and a count,
nothing else). Recognition is not an input. Only the answer is used, and only a yes is ever sent; the counts never
leave the phone. The invitation is offered only when the last running shift ends, 30 seconds later so the shift is
in the read model (`ContributorInvitationNotifier`, watching the state machine's shifts; a shift that starts meanwhile
cancels it), never during one and never at app start: a notification on its own "Census"
channel, and a "Screen reviews" row on the Census screen. It is offered at most once until the dasher answers; Not
now holds it back for 14 days, No thanks until the dasher accepts from that row, and a `403 install_too_new` for 2
days (`ContributorInvitation`). The reviewer screen (`ContributorScreen`) is the second disclosure: after a shift the
dasher may see a few screens gig.log didn't recognize, at most 3 a day, with customer and account details it can
detect covered; they can confirm a word is the delivery app's own wording and label what a field is; a confirmed word
goes as its one-way code and the word itself only after other drivers confirm it; "Ask the developer" sends the
outline as the review shows it, which only the developer can open and the server deletes after 3 days; to accept,
gig.log sends a yes and never the counts, and the server gives a 30-day reviewer permission and, while the dasher
reviews, keeps a log of which screens gig.log asked it about, tied to that permission, for up to 30 days; they can
stop at any time; their agreement with a delivery company is between them and that company. Accept (`CensusContributor.accept`) runs under the census upload lock: a
waiting deletion request is sent first and, while it waits, nothing else is; with no enrolled census ID nothing is
sent. It records a consent receipt (§8), then sends the one request. A grant is stored with its install ID, its UTC
days and the phone's time it arrived, in its own store (`census_contributor`, excluded from backup and transfer). A
`403 install_too_new`, `not_eligible` or `grant_revoked` is stored and shown as the server said it; no answer, a
401 or a 5xx stores nothing. `CensusContributor.mayUpload` is the check every contributor upload makes first (#1626):
true only with the census on, a grant issued to the phone's current census ID, no deletion request since the grant
arrived, and today's UTC day before the grant's expiry day. A check that fails ends the grant on the phone, and so does
a change to the census consent, the census ID or a deletion request (`CensusContributor.watch`), the start of the
grant's expiry day (the watch waits for that instant, and the screens show the end from their own clock), the
dasher's Stop reviewing, and a state read that answers expired, revoked or none; a `401 revoked` on either grant route
ends it and turns the census off with its ID forgotten, as any census revoke does (`CensusInstallRevocation`). A grant
request is decided under what was stored when Accept was pressed (the dasher's answers, every census consent change
including off then on, the census ID and any deletion request); if any of it changed before the request is sent,
nothing is sent, and a response that arrives after such a change is discarded. A stored grant keeps the version it
was granted under and counts only while that version is still current, so a change made after it was granted (an
answer, the census turned off and on, a new census ID, a deletion request) ends it whether or not anything was
watching; an answer also removes it in the same write: the phone never
stores it, so nothing is sent under it, and it lapses on the server after 30 days (the server offers no way for the
phone to give a grant back); every end clears the reviews waiting on the phone
(`PendingContributorReviews`, the review queue below); a clear that fails is marked in the record and run again
at the next start, and no contributor upload runs until it succeeds. An ended grant is not renewed by itself: an expired one
is offered again after a later shift. The grant request is not listed under "What gig.log sent".

**Reviewing a screen** (#1626, ADR-0011 §15). While a grant is stored, the skeleton builder also keeps, for an
UNKNOWN screen it builds as `uinode.skeleton.v2`, the words of the slots the skeleton HASHED (text the census filter
let through as possible app wording), keyed by the census server's slot id (`SkeletonBuilder.reviewWords`,
`ReviewWords`). A word is kept only if every one of its words is on an allow-list (`ReviewAllowList`): the 5,000 most frequent
lowercase words of English, Spanish and French (Google Books Ngram, CC BY 3.0; capitalized entries, the proper nouns,
left out; `census/review-common-words.txt`, credited in `THIRD_PARTY_NOTICES.md`), or a word of the on-screen text the bundled recognition rules match
(generated from `matchers/` at build time). A name or place outside both lists is never held; one that is also a
common word ("Grace", "Will", "Main") is not caught by the list and is a stated residual risk. The word must also pass
the review's own cover (`ReviewWordCover`): no customer marker,
sensitive marker, return-task name, mask, name or other PII shape, digit or `@`, no greeting, account or place
label in any case ("Hi, …", "Account …", "… in <place>"), no note or message, and no colon of any kind (a label and
its value); and only if the rules-independent address-block backstop did not select its node. Any text on the screen
that is withheld or not held (by a filter step, a customer or PII id, a text input, the address backstop or the cover
itself) also stops the same text being held anywhere else on that screen, the window title included. A withheld, coarse or format slot has no word, so no
other screen text is kept. When the census sink queues that skeleton for upload (the UTC day's first of its layout),
the review queue (`ContributorReviewQueue`) takes it as a candidate, after checking `CensusContributor.mayUpload`: at
most 3 new layouts a UTC day, one candidate per layout, at most 6 waiting, dropped after 7 days, and a layout sent or
deleted is not taken again that day. The candidate is stored in `filesDir/census/review/queue.json` (inside the
census directory every backup and transfer excludes) in its outbound form only: the skeleton item exactly as spooled
(hashes, kinds, formats, `withheld`), its layout id, the UTC days it was built and stored by the server, and the layout
ids sent or deleted today. The words stay in the app's memory only: never written to storage, logged or put in saved
screen state, and gone when gig.log's process ends; the review then says the words are gone and offers labels only.
The upload worker tells the queue which skeletons the server stored: only a batch the server accepted with no
rejected item counts, since its answer has no per-item results; a duplicate answer counts for none. A candidate past 7
days is dropped, with its words, on load, on every read and from the clock alone. A screen opens for review
only from the UTC day after the server stored it, because the server's slot classes for a new layout arrive with the
next day's release; before that, Screen reviews says "Ready tomorrow". After a shift ends, a notification on the
"Census" channel says screens are ready (`ContributorReviewNotifier`, at most once a UTC day, only with a standing
grant); Screen reviews lists at most 3 ready screens. The review screen (`ContributorReviewScreen`) first reads the
grant's state (`refresh`), then asks the server for the classes of that screen's own slots (`classes`). It shows one
row per slot, in the server's order, with what that slot sends: a word only for a hashed slot the server calls
`constant` whose word the phone still holds; for every other slot, what goes in place of the text (covered, a format,
a number or a kind) or that the slot changes between screens, differs by area or store, or has not been seen by
enough drivers, greyed and never openable. The dasher may confirm a shown word as the app's own wording and give any
row a label from the server's closed list (total pay, tip, base pay, deadline, distance, item count, customer info,
store name, other); a label never carries the field's value. **Send** (`ContributorReviewer.send`) checks
`mayUpload` first and again before every request, under the census upload lock: `review` with the confirmations (each
slot's public census hash, never the word) and the labels; then `gates` for the confirmed slot and hash pairs; then
`words` only for a pair the server reported open (at least 2 independent phones confirmed it and enough phones see
it), each word checked on the phone to hash to its slot's hash. A word whose gate is closed is held in memory and its
gate asked again at the start of the next review session; a process death drops it, and the server keeps the
confirmation. **Delete** drops the candidate and its words and sends nothing. A refused review (the server paused, a
daily quota spent, no network) keeps the candidate; `403 grant_required` or `401 revoked` on any review request
stops the session, runs the grant's state read, then drops the screen's words and every held word. The review screen
clears its rows, words and choices whenever the grant stops standing, even while it is not shown. A word whose grant
check fails is dropped, never held, and an answer that arrives after the reviews were cleared holds nothing. Review requests are not listed under "What gig.log sent". The INFO log says only how many
confirmations, labels and accepted words went, never a slot, hash or word.

**Ask the developer** (#1627, ADR-0011 §15, census#81 "Quarantine"). On a review that is ready (so only while the grant
stands), Ask the developer opens a sheet that says what goes (the screen's outline as the rows show it, with the
dasher's confirmations and labels; covered rows stay covered; a confirmed word goes as its one-way code unless other
drivers confirmed it), that only the developer can open it and that the census server deletes it after 3 days, and
offers one optional note from the server's closed list ("might show a customer's details", "might show my own details",
"not sure what this screen is"). Send first reads the grant's state (`refresh`, outside the lock), then
`ContributorReviewer.ask` runs under the census upload lock and checks `mayUpload` first and again before every
request. When the dasher confirmed a slot and the phone still holds its word, it asks `gates` for the confirmed slot and
hash pairs; then it sends `POST /v1/contributor/quarantine` with the artifact `ContributorRequests.artifact` builds,
in the server's canonical form: `{"skeleton":…,"confirmations":[…],"roles":[…],"words":[…]}`, where the skeleton is
the candidate's outbound skeleton as stored (hashes, kinds, formats, `withheld`; never screen text), the confirmations
are slot and public hash for rows shown confirmable, the roles are slot and role, and `words` holds only a word whose
gate `gates` answered open for that exact slot and hash in the same request and whose census hash is its slot's hash;
every other confirmed slot stays a hash. No `gates` answer means no word. The server refuses a word whose gate is
closed (`409 gate_closed`, nothing stored); the phone then sends the artifact once more with no words. The server
validates the artifact, seals its canonical form to the developer's public key (it cannot read it back), never clusters,
counts, drafts from or backs it up unless the developer approves a cleaned-up copy, and deletes it at 00:00 UTC on its
`expiresDay`, 3 UTC days after receipt. The phone hashes the exact bytes it sent and compares them with the server's
`artifactSha256`: on a match the screen says it went to the developer and the day the server deletes it by; on a
mismatch it says gig.log could not confirm the server kept the same outline, logs a WARN with no content, and never
sends it again. Either way the candidate and its words leave the queue, and no review is sent for it. When the
server may hold the item but the phone cannot confirm it (a 200 whose answer cannot be read; a connection that failed
after it was made; a 5xx with no known error; the screen left while the request was out), the candidate is retired the
same way, never sent again, the screen says gig.log couldn't read the confirmation or couldn't tell whether it reached
the developer, and a WARN with no content records it. A definite refusal (`422 bad_artifact`, `503
quarantine_unconfigured`, the server paused, today's quota spent, or no connection made at all) sends nothing and keeps
the candidate, and the screen says which; `403 grant_required` or `401 revoked` stops the session as for
Send. The request is not listed under "What gig.log sent". The INFO log says only how many confirmations, labels and
words went and whether a note was picked.

Exports replace existing files with the same names in the chosen folder. Exported files survive uninstall and remain until deleted through that provider. “No direct gig.log upload” does not mean that a selected cloud folder keeps the files on the phone.

Notification census publication runs after admission, dedup and capture, before UNKNOWN rejection,
independently of capture enablement, in every build. It scans the original five fields and all action labels with
`SensitiveMarkerScan`, including their whitespace-joined values, before the customer lead-in scrub and
shared 40-character/value filters. Any sensitive marker refuses the entire item. Action labels leave the phone
only in a v2 notification skeleton, each judged by the same filters as the five fields and sent as the same slot
kinds (a hash, a coarse kind, a format template such as `$#.##`, or `withheld`), never as text; a v1 skeleton
carries none, and a label never changes the five fields' slots. Channel ids must match `[A-Za-z0-9_.-]{1,64}` without repair. The screen and notification
paths share one sink, spool, daily sample, worker and server-authoritative 300/day budget, with common 429 deferral;
there is no separate client daily allowance.
Census failures leave recognition unaffected; INFO counters contain counts and reason/kind names,
never notification text or channel ids. Trusted capture sharing remains screen-only. CLICK skeletons
stay OUT until a reliable screen fingerprint can accompany clicks (ADR-0011 §10).

**Android backup and device transfer.** The manifest sets `android:allowBackup="true"`. Unless you turn app backup
off in Android's settings, the Room database (your event log and analytics tables) and general app/economy
preferences are included in your Google account's app backup and in device-to-device transfer, under Android's
backup encryption and Google's terms — gig.log does not send them anywhere itself. **The database follows the
user: restoring history and vehicle/economy settings on a new phone is expected.** The capability-grant/receipt
store (`datastore/rule_capability_grants.preferences_pb`) and the Screen-events consent/receipt store
(`datastore/consent_event_receipt.preferences_pb`) are excluded, so a new device re-asks consent. Debug logs
(`app.log`, `shareable.log`, `shareable.log.1`, and rotations under `logs/`), captures, census files, census
credentials, the developer-settings store (the census switch, its consent receipt and choices, capture sharing, the server policy cache, the next daily census time and a census deletion request), the screen-review record
(`datastore/census_contributor.preferences_pb`, #1625), the recognition
notice store (`datastore/recognition_notices.preferences_pb`, #1560) and the wipe commitment (`wipe_committed`, #1579) are also excluded from both backup and transfer (`app/src/main/res/xml/backup_rules.xml`,
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


- Storage and transport: the Room database and the DataStore files live in the app's private storage, which other
  apps cannot read and which Android's device encryption covers; gig.log adds no encryption of its own. The gas-price
  (`https://api.eia.gov/`, `GasPriceApiModule`) and vehicle (`https://www.fueleconomy.gov/`, `VehicleNetworkModule`)
  lookups use HTTPS, and the app targets an SDK at which Android refuses plain HTTP by default. Screenshots (§4) and
  exports (§6) are ordinary files outside that storage, readable by any app the user lets see photos or files.
- The event log and the analytics tables are a Room database on the device (`core/database`); the read-model
  tables are a rebuildable projection of your own event log. The database is backed up and transferred so your
  history can be restored on a new phone (§6).
- Expenses and fuel fill-ups the dasher logs from the bubble are events in that log and rows in two
  read-model tables (`expense_records`, `fuel_records`): amount, category, gallons, the app's tracked miles at the fill-up, and an optional typed note. gig.log sends none of it anywhere and never writes the note to a
  log at INFO or above. The rows are in the database, so Android backup and device transfer include
  them like the rest of the history (§6). Since #1388, car-care and gas receipts also store which saved
  vehicle they are for (its id, `vehicleId`, on-device), defaulting to the active vehicle. A pick applies
  to one entry and never switches the active vehicle or the dash's vehicle. Other expense categories
  and older receipts carry no vehicle. Manual CSV export includes `vehicle_id` and `vehicle_label`
  in `expenses.csv` and `fuel.csv`, resolving retired vehicles too; an unknown id is labelled
  "Unknown vehicle", and receipts without an id leave both cells empty. The sheet's label is the
  existing expense note, and its fill-up GPS stamp is the existing `odometerMiles`. Recent-label
  chips are derived from non-voided expense notes, with no second label store. Expense actions
  keep labels out of diagnostic logs entirely; a failed write logs only the exception class at WARN.
- Each delivery receipt the app reads is also an event in that log and a row in `receipt_records`: the pay total, base
  pay and tips, the time, and the app's own job and delivery ids. It holds no customer data, is not in the CSV export,
  and is sent nowhere. When you match a receipt to deliveries, that is an event in the same log and rows in
  `receipt_match_rows`: the receipt's id, the delivery ids, and each delivery's pay before and after. It holds no
  customer data and is sent nowhere.
- Miles you correct for a shift stay on the device (#1535): a `SESSION_MILES_CORRECTION` event in the local
  database and its value on the shift's read-model row. The tracked GPS figure is kept beside it, and the CSV export
  marks each shift's miles as tracked or corrected.
- Vehicles you add and odometer readings you type stay on the device. A vehicle is a name you type,
  an id and its creation time, in the `vehicles` preferences file alongside the active vehicle id; since #1355 it
  also carries the vehicle's profile by type (since #1358: a gas car, hybrid, EV, motorcycle, scooter or moped,
  e-bike or bicycle; its identity where it has one, its consumption in MPG, kWh per 100 miles or Wh per mile, its fuel
  type where applicable, and optional part costs and lives: tires and brakes for every type; oil and fluids for
  gas cars, hybrids and motorcycles; coolant for EVs; chain for motorcycles and bicycles; battery replacement for
  e-bikes; belt for scooters; and miscellaneous upkeep where applicable. Depreciation, purchase price, lifetime miles,
  insurance and registration deltas and annual miles remain optional fixed-cost inputs. Scooters also save gas or
  electric powertrain. Since #1361 the vehicle record also holds an optional odometer-visibility setting
  (`odometerShown`): unset follows the type's default, and the vehicle page's Odometer switch stores
  an explicit choice. It controls the bubble's odometer block, cue, start line and resting footer;
  nothing new is read or sent. The electricity tariff (`electricityPricePerKwh`) is shared in app preferences, not on a vehicle.
  The vehicle details page is the only live editor of a vehicle's name and costs (#1385); Economy edits shared settings
  and links to that page. These editors, their resets and the Retired section (#1383) use the existing local records;
  nothing new is read, stored or sent.
  Restoring clears the retired mark on the existing vehicle record and keeps its readings and the active selection.
  A retired mark is kept when you remove a vehicle (its readings stay). Older entries are read into the typed shape
  with every value kept; unknown and incompatible fields ride along unread in the vehicle's `legacyExtras` map. The first time the app runs after this
  change it copies the car you entered in the setup wizard into the garage, once, and never adds a duplicate.
  A reading is an event in the history log: the miles you typed, the vehicle's id, and gig.log's
  own GPS mileage total at that moment. Readings never change GPS mileage or any dash figure.
  Since #1360 the event log also stores, per dash, which saved vehicle was active and the app's GPS
  total at that moment (`DASH_VEHICLE`, with the dash id in its payload, the time and START/SWAP kind).
  The header vehicle chip and swap menu (#1379) use these existing local records; nothing new is read, stored or sent.
  Switching within the first ten minutes can append a new stamp; the latest vehicle wins while the
  first stamp keeps the start time and GPS total. The stamps are part of the event log, so Android backup
  and device transfer include them with the rest of your history; gig.log never sends them anywhere
  itself. Since #1387, a manual CSV export includes the dash's vehicle id and label in `sessions.csv`
  and deductible miles per vehicle per tax year in `summary.csv`; dashes without a known saved vehicle
  are labelled "Unknown vehicle". Vehicle names in the start and switch lines are not logged.
  The vehicle's two most recent readings are compared on the device to the app's own GPS mileage
  over the same span, counting only that vehicle's dash miles when stamps cover the span (older or
  uncovered spans keep the original comparison rules); car, tracked and other miles are shown only to the dasher. gig.log sends none of it anywhere
  and never writes a vehicle name to a log at INFO or above. Android backup and device transfer
  include both, like the rest of the history and preferences (§6).
- The `odometer_prompts` app preference stores whether gig.log reminds you to log a reading: the
  dock's Odometer block lit in accent for the first ten minutes of a dash and after a dash until a
  reading, and one "Log your odometer" line in the session-start notification (default on). It stays
  on the device and is included in preferences backup.
- Preferences and consents are DataStore files on the device (`core/datastore`). General app/economy preferences
  are backed up; the two dedicated consent stores are excluded from backup and transfer so a new device re-asks.
  `EventReceiptConsentDataSource` keeps the decision and receipt only in `consent_event_receipt`. It best-effort deletes the old
  Screen-events keys from `app_prefs` without copying them: restored app preferences must never restore consent.
  An empty dedicated store re-asks once after this upgrade as well as on a new phone.
- `recognition_notices` (a DataStore file, #1560) holds, per delivery app, the time gig.log last showed a "screens
  not read" notice, so it shows at most one a day. No screen text, no customer or store data. It stays on the phone
  and is excluded from backup and device transfer. Which screens broke is kept in memory only and is gone after a
  restart.
- Your daily profit goal, in dollars, if you set one. Stays on the device.
- One mark of the day you first met that goal and the goal amount it was met at (`goal_met_mark` in `app_state`, e.g.
  `2026-10-18|7900`), so the HUD's goal animation plays once per day and goal. Stays on the device, backed up with the
  app's preferences; it holds no money figure beyond the goal you set yourself.
- Debug builds may keep capture envelopes for rule development under the app's files; every other build (Play,
  sideload) binds a no-op capture bus (`NoOpCaptureBus`) and keeps none. Captures and census files are excluded from Android backup and
  device transfer (`app/src/main/res/xml/backup_rules.xml`).
- Logs rotate automatically: `app.log` stays in the app's files root, firehose rotations live under `logs/`, and
  the shareable log keeps one `shareable.log.1` backup (`LogRepository`). All these log paths are excluded from
  Android backup and device transfer, in both external storage and the internal-storage fallback. Each log-store
  initialization moves legacy root rotations into that root's `logs/`; failed moves attempt deletion because
  debug rotations are disposable, and any remaining legacy rotations are retried on the next initialization.
- Retention of app-private data is "until you delete it": Delete everything on this phone (below), clearing the app's
  storage in Android's settings, or uninstalling removes the database, the DataStore files, the logs and any captures.
  Android may restore the backed-up database and app/economy preferences on reinstall
  or a new phone; the excluded consent stores and debug logs do not restore. Gallery screenshots (§4) and files
  you exported (§6) live outside the app and stay until you delete them.
- **Delete everything on this phone** (#1579, Settings > Data & privacy; `PhoneWipe`, `WipePhoneDialog`). One
  confirmation says what goes (every shift, delivery and offer, vehicles and plans, settings and consent choices,
  logs, captures) and what stays (exported files, gallery screenshots, any Android backup in the Google account), and,
  whenever a census request could be sent (the census is on, a census identity is stored, or the store can't be read),
  that the census server is asked first, with what the server keeps, in the census screen's own words (§6). One process owns the census
  upload lock for the whole wipe, and nothing is deleted without it: a census upload under way is waited for ("Waiting
  for an upload to finish", with Cancel), never deleted beside. Under the lock it reads whether the phone is enrolled;
  an enrolled or unreadable census ID sends the census deletion request (§6, "What was sent, and deleting it"), which is
  stored as pending with its retry run queued before any network attempt, and the dialog waits up to 20 seconds for the
  answer. A confirmation is shown ("The census server confirmed it deleted the uploads it can tie to this phone") and
  the wipe follows without releasing the lock, so no census upload runs in between. Without a confirmation (offline,
  no answer in time, a 404 until the server's update, an unconfirmed 401, or an ID that can no longer sign) nothing is
  deleted and the screen says why and offers two choices: Delete now, which says gig.log can't ask again and the
  census server keeps this phone's uploads until they age out (the 30-day limit takes effect with the server's next
  update), or Wait, which deletes nothing and leaves the stored request retrying. An answer that arrives later still
  counts: a confirmation goes on to the wipe. It never says the server deleted anything the server did not confirm.
  With no enrolled census ID it makes no network call. Once the wipe is decided, a commitment file
  (`filesDir/wipe_committed`, excluded from backup and transfer) is written first; if the process dies before Android
  clears the storage, the next start finishes the wipe before anything else runs (no sensing, workers or uploads). The
  file is written whole or not at all (a synced temporary file renamed into place, checked by its content), so a
  failed write leaves nothing and the screen says nothing was deleted. If Android refuses the clear at that start, the
  first screen says "Couldn't delete", and the app starts nothing (no recognition, workers or census uploads) until
  the dasher presses OK; the next start tries again until then. OK lets the app start only once the stored request to
  delete is verifiably removed; if it can't be, the screen says "gig.log couldn't clear its delete request. Try again."
  and nothing starts, so a later start never wipes data made after the OK. The same holds after a refused clear in the
  dialog itself: it closes only once that request is removed. A marker gig.log can't read at start (a filesystem error) is never wiped on
  and never taken as absent: the same screen holds startup until Try again removes it. The wipe deletes the census
  Keystore key, then calls Android's own Clear storage for the app (`ActivityManager.clearApplicationUserData`, the
  same operation as Settings > Apps > gig.log > Clear storage): Android stops every gig.log process, service and
  scheduled job, then deletes the app's private storage and its app-specific external storage (the database with
  its WAL and SHM files and pre-migration snapshots, every DataStore file including the consent and census stores,
  the logs, captures, census files and scheduled work) and the app's Keystore keys, revokes the runtime permissions
  Android granted it and removes its notifications. gig.log then closes; opened again it is a first run: setup
  starts, every automatic tap is undecided (no auto-grant) and the census is off. If Android refuses the clear, the
  screen says so and points to clearing the storage in Android's settings; the census key is already gone, so a census
  that stays on enrols a new ID.
- "What gig.log sent" keeps its summary of the census requests the server read for 7 days, at most 60 of them, in
  `filesDir/census/sent.json` (§6): counts and delivery apps only, never uploaded, excluded from backup and transfer.
  A census deletion request (its time, state and the server's confirmation) is kept in the developer-settings store.
- The screen-review queue (#1626, `filesDir/census/review/queue.json`): each waiting screen's skeleton exactly as it
  went to the census upload, its layout id and its days, and the layout ids sent or deleted today; at most 6 screens,
  each dropped after 7 days, on Send or Delete, and all of it when the reviewer permission ends. Never uploaded as a
  file, excluded from backup and transfer. The words a review may show are held in memory only.
- The screen-review record (#1625, `ContributorDataSource`, `datastore/census_contributor.preferences_pb`): the
  invitation answer and its time, when the invitation was offered, the reviewer grant (census install ID, the server's
  granted and expiry days, the time it arrived), why the last one ended, the last refused request and the decision's
  receipt. No eligibility count is stored. Never uploaded, excluded from backup and transfer. The census ID's enrolment
  time sits with the census credential.

## 8. Consent records

Each capability decision and the Screen-events decision are stored on the device beside the decision itself, in
the same DataStore write, with the time it was made, the app version, and the revision of this document that the
in-app disclosure referred to when it was recorded (`PrivacyDisclosure.REVISION`; `RuleCapabilityDataSource`,
`EventReceiptConsentDataSource`). The app does not verify that you opened this document — the revision records
which text the disclosure pointed at. They are readable under Automation & Consent. A store edit keeps a receipt
only while its capability is in the stored granted or denied set, so no receipt outlives its decision; a decision
for a capability the current rules no longer enable keeps its receipt (`RuleCapabilityDataSource.update`).

The bug-report export (§6) ends with a "Consent receipts" section: the Screen-events receipt and each capability
receipt, with its status, date, app version and privacy notice revision, by capability key (a sha256 hash), plus the
action and rule id where the loaded rules still enable it. These lines are added after the shareable log, so they
pass the same marker scrubber explicitly (`LogRepository.scrubForExport`); if the scrubber is missing or fails, the
section is left out and the file says so (`DataExportViewModel`). The receipts are read from their stores at export
time (`ConsentReceiptReader`), not from the in-memory copies the screens use; if a store cannot be read, the section
says the records are unavailable.

The census decision (#1621) has its receipt too: the same four fields plus the census endpoint it was given for, in
the developer-settings store with the census switch, written in the same DataStore write
(`DevSettingsDataSource.setCensusUploadEnabled`). A receipt for another endpoint does not count as consent (§6). The
bug-report export lists it after the Screen-events receipt, labelled "Census", without the endpoint.

The screen-review decision (#1625) has a receipt of the same four fields in the contributor record: Accept writes a
granted one before the grant request, No thanks and Stop reviewing a declined one; Not now writes none. The bug-report
export lists it after the census receipt, labelled "Screen reviews".

These decisions and their receipts are in stores excluded from Android backup and device transfer (§6), so a new
device re-asks consent while the database and general app/economy preferences restore.

The privacy links on the screen reading card and on Automation & Consent open gig.log's own Privacy page (Settings →
Data & Privacy → Privacy, `PrivacyScreen`), a plain-language summary of this file. Its foot links the privacy policy
(`PrivacyDisclosure.POLICY_URL`, https://gig.log.trotter.cloud/privacy/) and "Technical details": this file as of the
commit the build was made from (`BuildConfig.PRIVACY_REF`, `PrivacyDisclosure.urlAt`); a build from an uncommitted
tree or without git opens the current revision. The revision history below says what changed since the revision on a
receipt.

## 9. Verify it

Repository: https://github.com/sjtrotter/gig.log — rulesets under `matchers/rules/`, the recognition pipeline
under `core/pipeline/`, the consent model under `docs/design/rule-capability-consent.md`, the census model under
`docs/adr/ADR-0011-unknown-census-privacy-model.md`, the DoorDash ICA posture under `LEGAL.md`.

## Revision history

- **r26 (2026-11-14)** — §6: Ask the developer (#1627): the quarantine request and what its artifact holds (the
  outbound skeleton, confirmations as hashes, labels, and only words whose gate answered open in the same request),
  the closed note list, sealed to the developer's key, deleted after 3 days, never counted, the phone's hash check,
  and what each refusal does. The in-app Privacy page's Screen reviews section describes Ask the developer and the
  reviewer screen no longer says it is not in this version; the Privacy page links the open-source notices
  (`THIRD_PARTY_NOTICES.md`). Receipts created under r25 retain that revision.
- **r25 (2026-11-14)** — §6: reviewing a screen (#1626): while a reviewer permission is stored, the words of the slots
  a v2 skeleton hashed are kept in memory only, after the review's own cover (customer, sensitive and address
  backstops, the dasher's greeting, account and place labels, notes, labelled values, digits); a review candidate is
  taken only after `mayUpload`, at most 3 new layouts a UTC day, and stored only as its outbound skeleton; a screen opens
  for review the UTC day after the server stored it; a notification after a shift says screens are ready; the classes,
  review, gates and words requests and what each carries; a word leaves only through `words` after its gate opens;
  Delete sends nothing; a revocation mid-session stops it and drops the words. §7: the review queue file. The in-app Privacy page's Screen reviews section and the reviewer
  screen's note now describe the review and say Ask the developer is not in this version. Receipts created under r24
  retain that revision.
- **r24 (2026-11-13)** — §6: screen reviews, the contributor tier (#1625): eligibility worked out on the phone (census
  on, a census ID 8 UTC days old, a shift on 3 of the last 7 days, 2 ended shifts; recognition is not an input), only a yes
  sent (`{"eligible":true}`), never the counts; the invitation only when a shift ends, once until answered, Not now for
  14 days, No thanks until Settings; the reviewer screen's second disclosure, including the server's 30-day log of
  which screens gig.log asked about, tied to the reviewer permission; the grant stored with its census ID and expiry,
  checked before any contributor upload, and ended by expiry, a revocation, Stop, the census off, a new census ID or a
  deletion request, which clears pending reviews; a grant response arriving after a decline is discarded; a `401
  revoked` on a grant route turns the census off as any revoke does; no renewal without asking. §7: the contributor record and the
  enrolment time. §8: the screen-review receipt, and the bug report lists it. The in-app Privacy page gains a Screen
  reviews section (`privacy_census_review_*`). Receipts created under r23 retain that revision.
- **r23 (2026-11-13)** — §7: Delete everything on this phone (#1579): one confirmation naming what goes and what
  stays; with an enrolled census ID it first sends the census deletion request and waits up to 20 seconds, wipes on
  the server's confirmation without letting a census upload run in between, and otherwise deletes nothing until the
  dasher picks Delete now (the server keeps this phone's uploads until they age out) or Wait; with no census ID no
  network call. One process owns the census upload lock throughout and waits for a running upload rather than
  deleting beside it; the request is stored pending with its retry queued before the network, so Wait holds after the
  dialog's 20 s; a stored commitment makes the next start finish a wipe the process died in. The wipe is Android's own
  Clear storage for the app, after deleting the census Keystore key. §6: the
  disclosure's per-build lines name it instead of saying it isn't in this version. The in-app Privacy page's "How long
  it stays" follows. Receipts created under r22 retain that revision.
- **r22 (2026-11-13)** — §6: "What was sent, and deleting it" (#1622): the census screen lists the requests the
  server read in the last 7 days, summarized on the phone from the request bytes (kinds, delivery apps, counts; no code,
  value or batch ID), and "Delete my census data" clears the phone's census queue and asks the server to delete this
  install's data, with what the server keeps (the install record, today's quota row for 7 days), what it cannot tie
  to the phone and the 14-day backups stated before it is sent; a request the server has not confirmed (offline, a
  401, or a 404 until the server's update) stays pending, bound to its install ID, and is sent before anything else;
  a 401 retries after re-enrolling the same ID and key. The developer identity reset first deletes an enrolled
  identity's data and resets only on the server's confirmation.
  A 401 on an upload re-enrols the same ID with the same key, consent kept; a 401 never mints a new identity. The disclosure's per-build lines drop
  "no delete button yet", say server deletion and the 30-day limit take effect with the server's next update, and
  that deleting what gig.log keeps on the phone is not in this version. §7: the sent record and the deletion request. The in-app Privacy page's Census
  section follows. Receipts created under r21 retain that revision.
- **r21 (2026-11-13)** — §6: the census sends from every build, Play and sideload included, once the dasher accepts
  its disclosure (#1620); the Play and sideload builds bind the census sink and keep the no-op capture bus and envelope
  sink, so no screen text leaves those phones. New "When the census uploads": at most one send a day, at a random target
  time drawn on the phone, never triggered by a screen or a notification, sent only within an hour after that target
  (so it can still start when the phone gets Wi-Fi or a charge) and otherwise skipped that day, Wi-Fi only by default (unmetered network, checked again before sending), the census choices
  filtering skeletons, envelopes and health rows, and at most one skeleton per variant a UTC day, keyed on layout and
  formats, never on words. Every connection is HTTPS: a network security config refuses cleartext app-wide. The
  disclosure's per-build lines change: the Play and sideload line says there is no delete button yet and that the
  30-day limit takes effect with the server's next update; the debug line names capture sharing and keeps the 30-day
  and deletion caveat. The Play and sideload accessibility texts say gig.log sends nothing it reads unless the census
  is on. The in-app Privacy page's Census, storage and Test builds sections follow. Receipts created under r20 retain
  that revision.
- **r20 (2026-11-13)** — §6: the census opt-in moves from developer settings to Settings > Data & privacy > Census in
  every build, behind a full-screen disclosure with Accept and Not now (#1621); its consent receipt is bound to the
  census endpoint, and a missing receipt, a decline or another endpoint keeps every upload stage off, in the debug
  build too. The census screen stores which delivery apps and Wi-Fi only (default on) for #1620. The Play and sideload
  builds still send nothing; the disclosure says so in those builds, and in the debug build says how its census
  differs. §2.1 and the backup paragraph follow. §8: the census receipt, and the bug report lists it. The in-app
  Privacy page gains a Census section (`privacy_census_*`), and its Test builds section, the debug accessibility and
  notification-access texts and the debug Delivery apps text point to the new screen. Receipts created under r19
  retain that revision.
- **r19 (2026-11-13)** — §6: the census row and the notification-census paragraph describe `uinode.skeleton.v2` and
  `notification.skeleton.v2` (#1619): value formats from a closed list (money, 12-hour clock, distance, minutes,
  item counts; digits removed, AM and PM one class), node click and visibility flags, and notification action labels
  as slots of the same kinds as any text slot (never the label); the skeleton's day is the UTC day. The debug build sends v2 only after the census server
  lists it as accepted. The in-app strings say the census sends "outlines", which stays true, and are unchanged.
- **r18 (2026-11-09)** — §2.4 and §6: the gas-price lookup gives Android's `Geocoder` the location rounded to one
  decimal place of a degree (about 11 km, `GeocoderInput`), never the exact fix; the Privacy page's "What leaves
  your phone" (`privacy_leaves_body`), the location permission card (`perm_body_location`) and setup's gas-price
  card (`wizard_gas_price_auto_update_desc`) say so.
- **r17 (2026-11-09)** — §7: how the data is stored (app-private storage under Android's encryption, no app-level
  encryption, HTTPS for the two lookups, screenshots and exports outside it), shown in the app as "How it is stored"
  (`privacy_storage_*`) and on the public policy page; the public policy at https://gig.log.trotter.cloud/privacy/ is
  built from the in-app Privacy page's strings.
- **r16 (2026-11-09)** — §2.1: the service is bound to DoorDash and Uber Driver only, the apps gig.log has rules for;
  Instacart Shopper and Walmart Spark are no longer bound or enableable, and a saved preference for them is ignored
  (#170, #1413). The disclosure card names the apps, links gig.log's own Privacy page and, in the Play build,
  says it can't read Uber Driver's offer pop-up; the Screen events sentence shows only in builds that offer Screen
  events; the debug (tester) build also reads Instacart Shopper and Walmart Spark, which have no rules, so their
  screens are UNKNOWN and handled as in §3, §6 and §7, and its card and description name all four apps; the card and every service description say screenshots go to the gallery, where gallery sync or sharing can
  copy them. §8: a receipt is kept only while its decision is stored, and the bug-report export now ends with the
  consent receipts, read from their stores at export time and scrubbed like the log; Automation & Consent's privacy
  link also opens the in-app Privacy page, whose foot links the privacy policy and this file as of the build's
  commit ("Technical details"). §7: the one-tap wipe is tracked in #1579. The in-app privacy page's "What
  gig.log reads" shows the Screen events line only where Screen events are offered, and "Your choices" says the
  records leave the phone only in a bug report you export. Receipts created under r15 retain that revision.
- **r15 (2026-11-09)** — §2.1: the debug build is published on every GitHub Release as the tester build;
  it keeps UNKNOWN capture envelopes and a verbose log on the device, and its census switches (off by default) send
  to gig.log's census server, whose address is built in, enrolling the install when census upload is turned on
  (#1561). §6: the census rows say "debug build", not "developer builds", and name that server instead of "a
  server you configure". Nothing the app reads, stores or sends changed. The debug build's privacy section
  (`privacy_test_builds_body`) now names the server; the Play and sideload privacy page is unchanged.
- **r14 (2026-11-09)** — §7: a `recognition_notices` file on the device holds when gig.log last showed each
  delivery app's "screens not read" notice; §6: it is excluded from backup and device transfer (#1560). Which
  screens broke is kept in memory only. The in-app privacy page does not list every exclusion and is unchanged.
- **r13 (2026-11-06)** — §7: shift miles you correct are stored on the device beside the tracked figure, and
  the CSV export marks which one each shift uses (#1535). The in-app privacy page lists them with what you enter.
- **r12 (2026-10-31)** — §5: Accept and Decline are not asked for until gig.log can tap them by itself;
  earlier decisions stay listed and can only be turned off, and an automatic tap whose action has no
  automatic trigger is refused (#1457). §6: a text-to-speech row (spoken offers go to the phone's
  engine, which may use the network), and the census row names the daily recognition-health counts and
  the install ID. The in-app privacy page (Settings > Data & privacy > Privacy) restates this file.
  Receipts created under r11 retain that revision.
- **r11 (2026-10-20)** — car-care and gas receipts store which saved vehicle they are for
  (its id, on-device), defaulting to the active vehicle with a per-entry pick (#1388).
  `expenses.csv` and `fuel.csv` include its id and label in the chosen folder/provider.
  Receipts created under r10 retain that revision.
- **r10 (2026-10-20)** — CSV export adds each dash's saved vehicle id and label (the dasher's own text)
  to `sessions.csv` and deductible miles per vehicle per tax year to `summary.csv` (#1387), including
  retired vehicles; unknown vehicles are labelled explicitly. The files go to the chosen folder/provider.
  Receipts created under r9 retain that revision.
- **r9 (2026-10-19)** — per-dash vehicle stamps store the saved vehicle id and the app's GPS total
  in the event log (#1360; backed up and transferred with the database), with a ten-minute grace to change the vehicle. Stamps support
  per-vehicle tracked-mile comparisons; nothing new is sent and no export column is added.
  Receipts created under r8 retain that revision.
- **r8 (2026-10-19)** — CSV export includes logged expenses and fill-ups, including voided entries, in
  `expenses.csv` and `fuel.csv`. Amounts, categories, the dasher’s own labels, gallons and the app's tracked miles at the fill-up
  go to the chosen folder/provider; no customer data. Receipts created under r7 retain that revision.
- **r7 (2026-10-19)** — vehicle profiles are typed (gas car, hybrid, EV, motorcycle, scooter or moped, e-bike, bicycle) and store
  the type's own consumption and part-cost fields, shared electricity tariff and `legacyExtras`; older entries keep their values (#1358). Nothing
  new leaves the device. Receipts created under r6 retain that revision.
- **r6 (2026-10-19)** — the garage vehicle gains a profile (year, make, model, MPG, class, fuel type, cost figures) and a
  retired mark; the economy's vehicle-shaped values now come from the active vehicle, seeded once from the wizard's saved
  car (#1355). Nothing new leaves the device. Receipts created under r5 retain that revision.
- **r5 (2026-10-18)** — two new stored items: the vehicles you add (name, id, creation time, the active one) in the
  `vehicles` preferences file, and typed odometer readings as `ODOMETER_READING` events (the miles you typed, the
  vehicle id, gig.log's own GPS total at that moment), compared on the device to the app's GPS mileage and shown only
  to you; plus the `odometer_prompts` preference (#1333 S2f). Receipts created under r4 retain that revision.
- **r4 (2026-10-18)** — one new stored item: the day and goal amount at which the HUD's daily goal was first met
  (`goal_met_mark` in `app_state`), so the goal animation plays once per day and goal (#1333 S2e). On device,
  backed up with the app's preferences. Receipts created under r3 retain that revision.
- **r3** — corrected user-initiated versus automated taps, accepted button labels, debug-decline behavior, filtering limitations, network recipients and controls, cloud-folder exports, and in-app backup/screenshot disclosures. Fixed the setup startup fetch ordering. Gas refresh is off by default on new installs; an existing setting is kept. Current in-app disclosures reference this revision.

- **r2 (2026-10-07)** — consent stores and debug logs excluded from Android backup and device transfer;
  re-consent on a new device, while the database and app/economy preferences follow the user (#1236, includes #1214).
  Receipts created under r2 retain that revision.
- **r1 (2026-10-06)** — first revision.
