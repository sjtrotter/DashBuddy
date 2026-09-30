> **Detailed architecture reference — moved out of `CLAUDE.md` on 2026-09-07 (context-budget trim v3).**
> This file is the full, receipt-level narrative for this layer: every rule, its issue/PR receipt, and the
> reasoning that produced it. `CLAUDE.md` keeps the short summary an agent needs every session; this file is
> where a change's history and invariants are recorded in depth. **The 'every PR ships with context updates'
> rule applies here exactly as it does to `CLAUDE.md`:** when a PR changes an invariant described below, update
> this file in the same PR (and the `CLAUDE.md` summary if it goes stale).
> A `§N` reference below means the sibling numbered file here (or its `CLAUDE.md` summary).

# Sensor Pipelines (`core/pipeline/`)

`AccessibilityListener` / `AccessibilitySource` capture raw Android `AccessibilityEvent`s;
`AccessibilityNodeMapper` normalizes window content into an immutable `UiNode` tree (defined in
`:domain`). Per-event-type sub-pipelines (`ContentChangedPipeline` — coalesced as one burst, #1148,
`StateChangedPipeline`, `WindowsChangedPipeline`, plus click handling in `AccessibilityPipeline`)
and a parallel `NotificationPipeline` (`NotificationListener` → `NotificationFilter` →
`NotificationMapper`) emit `PipelineEvent`s. `AccessibilityPipeline.output()` drops in stages:
a fail-closed **rulesets-not-loaded** gate (#432), then **sensitive**/**noise** (the shared content
gate, #399), then **disabled-platform** (defense-in-depth), then **UNKNOWN** (captured to disk for
triage, never forwarded to the state machine). Snapshots are attributed to the window's *real*
package (not the event's), so our own overlay is dropped (#4 / PR #334). Frame admission is
`FrameGate` (identity dedup + content-hash rolling suppression of UNKNOWN frames, #360); envelope
assembly is the shared `CaptureWriter` (#361), which also applies **rule-declared capture
redaction** (#598): a recognized rule's `redact` block masks customer name/address/gate-code node
text in the serialized envelope only, so recognized captures are PII-hashed-at-edge on disk. The
mask is `[redacted:<4hex>]` (#623) — the first 4 hex of the sha256 of the stripped/trimmed (and,
for a customer-NAME entry flagged `normalize: customerName`, canonical-key-**normalized**, #733)
customer token, so two customers redact distinctly (per-customer replay fidelity) without
persisting raw PII; fail-closed to plain `[redacted]`. The mask token derives by the SAME canonical
form as the parse's `customerNameHash` chain (`normalizeCustomerName` before `sha256`,
`CustomerNameKey` = first token + second-token initial), so a customer's mask/hash is stable across
the name FORMS a surface renders ("Brandy S" vs "Brandy Smith"). Two bounded-secret defenses
compose with the hash mask: `plainMask` (#795) — a redact entry opts into a hash-less plain
`[redacted]` when the value's ALPHABET is bounded (a 4-digit PIN on the four dropoff surfaces whose
id-less digit-shape entries can catch a keypad echo — `dropoff_pin_entry`/`_handoff`/`_pre_arrival`/
`_pre_arrival_completion`, #889 F1; a subpremise/unit number #986/#934; a length-bounded customer
note #920), since 4 hex over a small space is
brute-recoverable; compile-rejects `plainMask`+`normalize` together; **#987 gives the flat-string
notification path the same flag** (`NotifFieldMask.Whole.plainMask`, compile-rejected beside a
capture `match`, which has no keepPrefix and its own fail-closed path) for the earnings-deposit
push, whose masked remainder is a FIXED clause with the dasher's own banking amount as its only
variable — 4 hex over a bounded money space is an inversion oracle, and a deposit is nobody's join
key — and the rule-INDEPENDENT
**short-token floor** (#889), which degrades any sub-4-char token's suffix to plain `[redacted]`
(the floor bounds LENGTH where `plainMask` bounds alphabet). `normalize: customerName` entries are
EXEMPT from the floor: their hex must stay equal to the first 4 hex of the `customerNameHash` the
parse already persists (#623/#733). The subpremise family (`Apt <n>` fused or `Apt/Suite: <n>`
label-split) is ratcheted by a **structural scan** over both platforms' generated assets — any
redact entry whose own predicate/keepPrefix strings name `Apt` or a `subpremise_line` id
(`address_subpremise_line`/`bottom_sheet_subpremise_line`) must declare `plainMask` (#986/#934) — but a scan can only ratchet entries that EXIST, so the VALUE
spelling stayed open until #1039 anchored the split form's value on its `Apt/Suite` **label
sibling** (`hasPrecedingSiblingText`, BOTH label spellings enumerated — the predicate is an exact
un-trimmed equality — whole-node `plainMask`). That entry and the fused `Apt/Suite` one are
declared **blanket** (every screen rule in `dropoff.json5` plus `navigation_generic`, #993's
combined-frame doctrine) and sit **ahead of every generic shape entry**, because `maskNode` is
first-match-wins and a digit-leading value (`101 B`) would otherwise be claimed and HASHED by the
id-less street shape; a derived parity test reads the rule list out of the source file.
(`camera_capture`'s `bottom_instruction` is deliberately excluded: it masks a whole fused name+apt
line, unbounded alphabet, so the hash form is correct there.) Coverage spans the recognized
offer/pickup/dropoff/chat/nav/camera **screen** surfaces AND **notification** envelopes (#620 —
chat title/body, order-ready customer name via a per-field notif `redact`; store names kept; #987
adds the earnings-deposit push, whose `text`/`bigText`/`tickerText`/`title` all mask to
`Your Dasher earnings for [redacted]` — the FIGURE is the dasher's own banking amount and the
account clause is the #599 Crimson marker text, so both go, while the arrival itself stays a
recognized lifecycle signal. The `crimson_balance` BALANCE push remains a priority-0 sensitive
BLOCK; this rule only ever sees the deposit shape).
Hard-won enumeration rules baked into the ruleset (receipts in #885/#886/#992–#995/#985): a
recognized rule that forgets to redact has NO backstop (`ID_MARKERS` is UNKNOWN-only by design),
which is why #992's `pickup_wait_survey` and its recurrence #1031 (`pickup_issue_menu`, whose four
`For <customer> • <store>` sub-flow siblings all carried one) are fixed by copying the sibling
entry verbatim so the mask hex stays equal across the sub-flow; the shared id-less **name shape**
separates its tokens with `\s{1,4}`, never a literal space (#885 — a double-space render silently
missed; the shape string is byte-SSOT with `SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN`, so every
doordash/uber copy moves together); the embedded Google-Nav **maneuver cluster** is masked on the
dropoff-phase nav rules AND `navigation_generic`, while `pickup_navigation`'s MERCHANT address
stays raw by design (#886); id-less address/venue blocks anchor via sibling predicates
(`hasPrecedingSiblingText` #860, `hasFollowingSiblingTextMatchesRegex` #886) and a phase-ambiguous
slot over-masks toward privacy (#985's timeline order-detail sheet,
`doordash.screen.timeline_task_detail`, is anchored on its own chrome — `Copy address` text AND
`Close sheet` contentDescription, BOTH required — and masks the merchant render too; store NAMES
stay raw); a receipt-scan camera is NOT in the blocked document-image family — recognize-and-redact
like the #463 ID-CHECK screens (dev ruling, #995); the **combined-frame class** means a redact only
protects the frames its OWN rule wins, so a banner that can inflate over another rule's frame
(`arriving_at_title`) is declared by EVERY rule in `dropoff.json5` plus `navigation_generic`, pinned
by a parity test (#993); and a name entry enumerates every conjugation a surface renders —
`timeline`'s fourth is `Return <name> to <store>` (#994, whole-remainder keepPrefix, so the store
tail masks too while the #623 mask↔hash invariant survives because `customerNameKey` discards the
tail) — with `timeline`'s redact/parse prefix lists diverging only **by documented exclusion** (a
guard test fails on any undocumented divergence in either direction; `"Return "` is listed against
#998, which owns the return-task design question). **Recognize-only is NOT state-inert** — ruling a
previously-UNKNOWN surface makes its frames reach the state machine and moves
`FrameGate.lastIdentity`; what "no `state` block" buys is *lifecycle neutrality*, asserted
end-to-end by `FlowlessRecognitionNeutralityTest` (four surfaces now: `pickup_receipt_scan` #995,
`timeline_task_detail` #985, `dropoff_workflow_sheet` #1058, and **#1107's
`dropoff_step_instructions`** — DoorDash 8.97.8's "Drop off steps" WRAPPER activity, whose
`description_text_view` shipped a customer gate code raw to UNKNOWN capture. The rule anchors on
the activity's own `drop_off_step_instructions_activity_host_fragment` id plus the nav title, sits
at priority 144 BEHIND every other rule in the section so it can never pre-empt a lifecycle
classification, and plain-masks the instruction body whole — customer-AUTHORED text, the #803/#920
class, unbounded alphabet but bounded length. Residual, the combined-frame class inverted: a future
rule that OUTRANKS it on a `description_text_view`-bearing frame would need the entry itself, and
only the UNKNOWN path is structurally covered). **The 8.98.5 drop-off sheet (#1122/#1123, fielded
2026-09-20) is the #1116 class twice over.** (a) `dropoff_pre_arrival` (priority 73) out-ranks
`dropoff_workflow_sheet` (143) on the sheet's header-BEARING render, and its customer-name mask was
anchored only on the id-bearing `user_name` node — 8.98.5 also renders the name as an id-LESS
first-name + last-initial node in the bottom bar, and one of 14 envelopes shipped it raw while every
other slot masked. The sheet's id-less name entry is now copied VERBATIM into `dropoff_pre_arrival`
(the #992/#1031 sibling doctrine — same bytes, same 4 hex; `normalize: customerName`, `hasNoId`),
ordered after the #1039 subpremise entries and pinned by the FIX 1c SSOT list plus a mask-parity
proof (`DropoffSheetRedactionParityTest`, ALL-CAPS names included — the shape already matched them).
(b) The sheet renders in more shapes than the 08-30 pair the original `Continue` AND `Directions`
anchor was cut from: a header-less render (no `Deliver to`, no `Continue`), its twin with the
Call/Message row scrolled off, and an `Apt/Suite`-only partial — all fell UNKNOWN and shipped the
street, city/ST/ZIP, unit, the quoted note (a gate code) and the name raw. The anchor is now the
host fragment plus ANY stable sheet row (either prism CTA title on its own `textView_prism_button_title`
node, the id-less `Apt/Suite` row in any spelling, or the Call/Message pair); recognize-only at
priority 143 with the same rejects, so the wider anchor moves no lifecycle classification — what it
DOES move is `FrameGate.lastIdentity`, capture routing (the frame files under the rule, not UNKNOWN)
and, with that, the `ID_MARKERS` backstop, which is UNKNOWN-only: so the review added
`dropoff_pre_arrival`'s id-anchored entries to the sheet's block as a belt (an id-bearing render
this rule wins keeps every deliberate decision), and ordered pre-arrival's fused `Apt ` plain entry
AHEAD of the new name shape (`Apt B` is `<word> <capital>` too — a 26-value alphabet must not hash)
and made its quoted-note entry `plainMask` like the sheet's; round 2 plain-masked `address_line_2`
(city/ST/ZIP, a bounded alphabet) and a NUMERIC Building Name value (a bare-code-shaped plain entry
ahead of the #860 distinctness hash, which an alphabetic complex name keeps) on both rules, and
added the corpus-attested handoff options (`Leave it at the door`, `Hand it to recipient`, id-less)
as a fourth anchor arm. The other dropoff rules carrying the hash-grade `address_line_2` / Building
Name entries are a follow-up (#1126). No committed negative carries the host id, so
`NegativeCorpusStaysUnknownTest` guards future negatives only. Residuals, documented on the rule: a
render whose only stable line is a handoff option outside that vocabulary; the `Apt/Suite`-only
partial is recognized on the device but its envelope loses the label to the #1039 fused entry's
collateral and replays UNKNOWN (intake nuisance, not a leak). Candidate text markers are vetted against the
corpus before joining the runtime set — chrome-ambiguous prefixes ("Return ", "Focus on ",
"Heading to ") are REJECTED because `CaptureBackstopCorpusTest` goes red on a clean corpus,
reasoning recorded in the `CustomerTextMarkers` KDoc; the rule redact is the primary control (#806). Intake-side prefix lists (`SnapshotRedactor.NAME_PREFIXES`,
`PII_ID_SUFFIXES`) are deliberately ASYMMETRIC with the runtime markers — an over-scrub at intake
costs triage text, a runtime false positive scrubs a live envelope — **but that asymmetry has a
floor (#1064): an intake over-scrub that eats a rule's own recognition ANCHOR costs the fixture,
not triage text.** `"Return "` was the receipt (added unconditionally by #994, it masked DoorDash's
`"Return to dash"` button — a `hasText` anchor on four rules — so the 09-05 intake's `on_dash_map`
fixtures re-classified and were set aside), so a chrome-ambiguous intake prefix now carries a
predicate over its tail (`SnapshotRedactor.GATED_NAME_PREFIXES`; `"Return "`'s tests the segment
ahead of the conjugation's own `" to "` against `FIRST_LAST_INITIAL_PATTERN`, the same byte-SSOT
the rule side shares) and `SnapshotRedactor.customerLeadIn` is the ONE owner of "is this a customer
lead-in with a raw tail", shared by the scrubber and the committed-corpus guard. That guard
checks name shape + `ID_MARKERS` ids + lead-in prefixes, with hand-written fixture pseudonyms
exempted by the byte-exact `CorpusDecoys` enumeration rather than by loosening the guard; a
per-folder assertion that a fixture carries a raw pseudonym is pinned to the hand-authored files
BY VALUE, never applied folder-wide, or the folder becomes CLOSED to device captures (which arrive
already edge-masked) — the #995 `pickup_receipt_scan` case, fixed by #1064. Sensitive
rules prefer **view-id anchors** (locale-immune, #938) — e.g. `sensitive.dasher_direct`'s
`dxdr_nav_host_fragment` arm closes DasherDirect's text-free pre-render skeleton at the door (#924).
#1059 blocks three more of the dasher's OWN surfaces on the same id-first pattern, all fielded
2026-08-27/28 reaching UNKNOWN capture: the embedded **Persona selfie / ID-verification** flow
(`sensitive.selfie_verification` — `persona_container` / `personaComposeView` /
`pi2_back_stack_screen_runner`; the camera steps are almost text-free, so the ids are the whole
defence), the **Red Card wallet** screen (`sensitive.red_card` — `virtual_red_card_image` +
the activate/request buttons; its headline "Your Red Card" is deliberately NOT an anchor, since
ordinary shopping-order pickup copy says "pay with your Red Card"), and the **passport** variant of
the ID-scan camera (`id_type_selector` joined `sensitive.id_verification`, whose anchors were all
driver's-licence specific). `SensitiveSurfaceBlockTest` pins the stronger bar the SENSITIVE golden
arm does not — claimed BY THE RULE, `sensitive.known`, and dropped at the content gate before
`CaptureWriter`.
A rules-independent customer-PII **marker backstop** (`CustomerTextMarkers`, #624/#632/#666/#806 —
distinct from `SensitiveTextMarkers`, which drops the dasher's banking screens) scrubs a node
(screen tree) or whole field (notification, incl. `actionLabels` #666) that ships a customer-PII
marker — on the **recognized** path (a rule forgot to redact) AND, since #806, on the **UNKNOWN
screen / notification / click** envelopes too (fail toward privacy) — before the envelope hits
disk. The marker SSOT is cross-platform DATA ("Deliver to "/"Pickup for "/"Message from " for
DoorDash, "Leave the order at "/"Meet at door for " for Uber pushes, #585), with a documented
residual for shapes a prefix scan can't own (a name-at-start body, store-ambiguous prefixes like
Uber's "Going to ") where the rule-declared `redact` is the primary control. The compiler rejects
branch-level `redact` and skips a file with duplicate rule ids (#624); the multi-file loader skips a
later file that re-declares an id an earlier file already claimed (#633, fail-closed — hardens the
#192/#639 multi-file + CDN path). Field-enumeration SSOTs keep a new model field from silently
missing a scrub site: `RawNotificationData.textFields()` (#666) enumerates the 5 flat notif text
fields (title/text/bigText/tickerText/subText) for
`toFullString`/`contentHash`/`CompiledNotifRedact`/`CustomerTextMarkers`; its screen-node edition is
`UiNodeTextField` + `UiNode.scrubbableStrings()`/`mapScrubbableStrings()`/`allScrubbableText()`
(#835 — `stateDescription` is captured on SDK ≥ R and serialized as `"state"`), iterated by
`CompiledRedact.maskNode`, `CustomerTextMarkers`, `SensitiveTextMarkers.findMarker(tree)`, and the
corpus `SnapshotSecurityScanner`/`SnapshotRedactor`. Recognition is deliberately untouched by #835:
`UiNode.allText` (what rules match on) still excludes `stateDescription` — widening a scrub layer
must never be able to move a classification.

**Node model fields (#1147, the 2026-09-21 TalkBack study win 3).** The mapper now also reads what
TalkBack reads, all optional with the DOMINANT value as the default so `UiNodeSchema`'s
`encodeDefaults = false` omits them on a typical node (every committed fixture re-serializes
byte-identically; a pre-#1147 envelope deserializes to the defaults). Strings — each
`capText()`-bounded (`UiTextBounds.MAX_TEXT_LENGTH`), each a `UiNodeTextField` entry (so
`CompiledRedact.maskNode`, both `CustomerTextMarkers` scans, `SensitiveTextMarkers.findMarker`, the
corpus `SnapshotRedactor`/`SnapshotSecurityScanner` cover it with no edit to those classes), and each
EXCLUDED from `UiNode.allText` and from every content/structural/stable hash: `paneTitle` (wire
`pane`, `getPaneTitle()` — the named scope an id-less sheet carries), `roleDescription` (`role`, the
AndroidX `AccessibilityNodeInfo.roleDescription` extra read off `getExtras()` by key, no androidx
dependency; `getExtras()` allocates/unparcels a Bundle, so it is read ONLY on a node that takes a
click or is focusable/screen-reader-focusable — every other node maps a null role (review W1); an
`Exception` from a hostile Bundle costs only this field, while a fatal `Error` propagates to the
supervised restart like every other read, W7), `hintText` (`hint`), `tooltipText`
(`tooltip`), `errorText` (`error`, `getError()`), `clickActionLabel` (`clickLabel`, the label on the
`ACTION_CLICK` entry of `getActionList()` — materialized ONLY when the `getActions()` bitmask already
says a click action exists, keeping #1149 P6's per-node cost off the common path) and `uniqueId`
(`uid`, API 33+; execution evidence only — no predicate, no rule may name it). Non-strings (not in
the scrub contract; no text): `isVisibleToUser` (`visible`, default true), `isFocusable`,
`isScreenReaderFocusable`, `isCheckable`, `isSelected`, `isHeading`, `liveRegion` (`live`, 0), and
`collectionRows`/`collectionCols`/`itemRow`/`itemCol` (`collRows`/`collCols`/`itemRow`/`itemCol`,
-1 = not a collection/item). `hasClickAction`/`foreignPackage`/`unreadableChildren` predate this
(#1149). Rules reach the new fields only through their own node predicates (see
`02-rule-engine.md`). Two whole-node masks — `CompiledRedact`'s `RegexEvaluationFailed` fallback and
`CustomerTextMarkers.scrubUnknown`'s id hit — now leave an ABSENT (null) field absent instead of
stamping `[redacted]` into it (nothing to leak; no phantom `pane`/`uid`/… keys); every present value
is still masked whole. **Scan order is frozen (review X1):** `allScrubbableText()` returns the PRE-#1147
projection (`UiNodeTextField.LEGACY_SCAN_ORDER` = text, desc, state — every node, DFS) FIRST and the
new fields AFTER it, because `SensitiveTextMarkers` joins the stream and relies on sibling adjacency
(`"Transfer"` | `"$45.66"`); interleaving a role between them split the transfer shape and offered the
capture. **Recognized clicks are text-marker scrubbed too (review X2):** the old premise "a
rule-matched click is an app-vocabulary button whose labels carry no PII" held for text/desc, not for
a Compose button's click-action label / hint / tooltip, so `CaptureWriter.captureClick` now runs the
`CustomerTextMarkers` text scrub on EVERY click envelope (byte-identical unless a marker hits;
`ID_MARKERS` stays UNKNOWN-only), and — review Z1 — the `SensitiveTextMarkers` dasher-banking DROP
too: both backstops now run on every click, recognized included (a click can be classified during
the #1104 stale-screen window on a DasherDirect sheet whose Compose button reads "Transfer $… to
bank" in its action label). A recognized SCREEN is still not dropped on that scan. Both decisions
have one owner each in `CaptureWriter` (`droppedOnSensitiveMarker`, `scrubCustomerPii`, review Z3);
their WARNs (tag `Pipeline`) carry the marker's `MarkerLogId` + the rule id only. **The click label is a bind label (review W3):** `UiLabelNode` and
the fire-time `ownLabelsOf` both add it (one live read, `NodeClick.clickActionLabelOrNull`, no IPC).
The corpus-wide additive claim is pinned by `CorpusNodeFieldsAdditiveTest` (X4). The TalkBack receipt the fields rest on: **no hidden Compose identifier
exists** — TalkBack has no `AndroidComposeView`/`testTag` handling; the best anchors for an id-less
render are a named scope (pane title) + a label/action label + the semantic owner.

**Two #910 additions close the SPLIT-NODE class**
(marker and PII in different nodes — a `user_name_label` reading `"Delivery for"` beside a BARE
`user_name`): (1) a **click envelope inherits the SCREEN rule's `redact`** —
`Observation.Click.screenRuleId` (stamped from the classifier's per-platform screen-context cache)
drives the same `redactFor` lookup, applied to recognized AND UNKNOWN clicks, envelope-only (the
dedup `contentHash` stays on the original node), fail-OPEN; (2) `CustomerTextMarkers.ID_MARKERS` —
an enumerated, cross-platform-DATA list of view-id suffixes whose node VALUE is customer PII
(`customer_name`/`user_name`/`address_line_1`/`address_line_2`, + `arriving_at_title` #993 and
#1058's `address_subpremise_line`/`dasher_instruction_content_{collapsed,expanded}`, and #1107's
`description_text_view` — the 8.97.8 "Drop off steps" wrapper's instruction body, a GENERIC id that
carries app chrome on other surfaces, so listing it is a deliberate UNKNOWN-path over-scrub),
`hasIdSuffix` semantics, scrubbed
on the **UNKNOWN screen + click envelopes only** (a recognized frame keeps its rule's deliberate
decisions — #886 leaves `pickup_navigation`'s MERCHANT address raw — so an id scan there would
fight the ruleset; scrubbing the dasher's own `user_name` greeting on an UNKNOWN frame is the
accepted fail-toward-privacy cost). UNKNOWN frames and UNKNOWN clicks remain the documented
debug-only exception (behind the release `NoOpCaptureBus` #346 + the `SensitiveTextMarkers` drop
backstop + the #806 scrub + the #910 id scan); the residual (a name-at-start body, an id-less
address/gate-code line with no customer lead-in) persists on UNKNOWN frames until the surface is
recognized (#806 direction 1). `PipelineV2.events` is a HOT `shareIn` stream — one upstream pass
feeds all collectors, so side effects (captures, dedup state) can never double-run (#361). The
merged upstream is supervised — a crash logs + counts a restart and resubscribes with backoff
instead of silencing all sensing (#430) — and `PipelineStats` counts every gate decision, mapping
failure, and restart (periodic summary log line). **Every build identifies itself (PR #1066):**
`app/build.gradle.kts` computes `versionName = "<base>+<8-hex git sha>[.dirty]"` at configuration
time (fail-safe to `nogit`; `versionCode` stays a hand-bumped constant because Android refuses a
lower one on install), and that string rides `BuildConfig.GIT_SHA`/`BUILD_TIME_MS`, one INFO
startup line (`DashBuddy <version> starting (built <ISO-8601>)`, tag `App`), and the **head of the
periodic `PipelineStats` summary** as `app=<versionName>` — injected through the same
`@Named("appVersionName")` seam `ReplayMetadataProviderImpl` uses, so `:core:pipeline` never sees
`:app`'s `BuildConfig`. A field-data pull reads the build off any log line instead of inferring it
from which lines are absent.

**Ingestion: envelope, coalescer, window-specific snapshots (#1148).** Receipt: the 2026-09-21
TalkBack study (`~/dashbuddy/research/talkback-study/ASTRA-REPORT.md`, wins 4 + 5 — the first
filing from that study), reworked by review round 1 on PR #1150. Four decisions decide *which
frames the pipeline ever sees*:

- **D1 — immutable event envelope.** The framework owns the `AccessibilityEvent` handed to
  `onAccessibilityEvent` and may reuse it after the callback returns, while our shared flow is
  BUFFERED (`extraBufferCapacity = 64`) and read later. `AccessibilitySource.emit` therefore copies
  the scalars into an `AccEvent` (`type`, `windowId`, `packageName`, `className`,
  `contentChangeTypes`, `eventTimeMs`) at the callback — `AccEvent.from` is the one construction
  site — and `AccessibilitySource.events` is a `SharedFlow<AccEvent>`. A `TYPE_VIEW_CLICKED`
  envelope carries a `SourceNodeRef` holding an OWNED COPY of the event (`AccessibilityEvent(event)`
  on API 33+, `obtain` below), never the resolved node: `event.source` is a binder fetch that can
  block for seconds on an unresponsive target, so the click pipeline calls `resolve()` on its
  collector and `release()` in a `finally` (review F3, `AccEventTest` pins that the callback never
  calls `getSource()`). Every other type carries `source = null`.
- **D2 — listener gate.** `ListenerGate.admit` (pure, unit-tested) admits `TYPE_WINDOWS_CHANGED`
  with ANY package, including null — the system fires the topology event with no package, so the
  old event-package gate meant the windows pipeline never saw it — but only while at least one
  platform is enabled (review G6). Every other handled type keeps the enabled-package gate. Package
  scope for the windows path is enforced on the FETCHED roots (D4); the #4 guard is unchanged.
  **Reach (#1151):** the framework filters by `packageNames` BEFORE `onAccessibilityEvent`, so a
  package-less `WINDOWS_CHANGED` reaches the gate ONLY while the dasher's **event-receipt consent**
  is ALLOWED. `AccessibilityListener` applies the pure `ServiceInfoPolicy.packageNamesFor(consent,
  Platform.watchedPackages)` in `onServiceConnected` and on every change of
  `EventReceiptPreferences.consent` (a service-scoped collector, cancelled in `onUnbind`/`onDestroy`):
  ALLOWED ⇒ `null` (all packages), UNDECIDED/DECLINED ⇒ exactly the watched registry (the XML's
  cold-start default; the pre-load value is UNDECIDED, so the footprint is filtered until the store
  says otherwise). Debug and release are identical since #1151 — the old unconditional debug
  `packageNames = null` is gone; debug still widens `eventTypes` to every type for unhandled-type
  logging. One INFO per apply: `Pipeline` / `Event receipt: wide=<bool>`. `ListenerGate` itself is
  unchanged. **Known limitation — Android 11 (API 30, #1151 review MM2):** the framework may treat
  the dynamically-set package filter as additive, so clearing `packageNames` can leave the manifest
  filter in force (Allow logs `wide=true` but `WINDOWS_CHANGED` may never arrive). The app cannot
  verify enforcement from its side: it still applies, logs one `Pipeline` WARN per process ("wide
  event receipt may not take effect on Android 11"), and the Settings switch carries the caveat.
  The rule has one owner, `EventReceiptConsent.isWideReceiptReliable(sdkInt)` (`:domain`).
  **Debug decline disables the service (#1151, dev re-sequencing 2026-09-30):** the consent is asked
  as the first permission-chain step, BEFORE the accessibility grant; on a DEBUG build that declines,
  the listener calls `disableSelf()` once (`ServiceInfoPolicy.shouldDisableSelf`, INFO "event receipt
  declined on a debug build — disabling the accessibility service"), so recognition and the HUD stop
  even when the service had been granted earlier. A release build never disables itself.
- **D3 — per-key coalescer.** `coalesceByKey(quietMs, maxWaitMs, keyOf, merge, maxKeys,
  leadingEdge)` (`event/coalesce/CoalesceByKey.kt`) replaced `debounceWithTimeout`. Per key, a
  burst opens on the first event, folds every event into an accumulator, and emits when EITHER the
  quiet gap elapses OR the max-wait since the burst OPENED elapses — a SCHEDULED timer that fires
  with no arrival. After a close the next event opens a new burst, so a continuing flood emits at
  most every max-wait and the final quiet always yields a trailing frame. Timing is coroutine
  `delay` only (monotonic, virtual-time tested); the open-burst map is capped at 64 keys, flushing
  (never dropping) the least-recently-touched burst with one WARN. **Backpressure (review F2):** a
  timer takes one of `maxKeys` emission permits BEFORE it removes its burst and holds it through
  `send`, so a stalled consumer leaves bursts OPEN and MERGING instead of spawning fresh timers —
  live coroutines ≤ 4 × `maxKeys`, and the merged burst carries every event once the consumer
  resumes. Each burst has ONE long-lived quiet job (review H8): an event bumps a generation and
  pokes a conflated channel, and the job waits for a poke or the quiet timeout — no coroutine is
  cancelled and relaunched per raw event (a map pan is hundreds a second), and the emission still
  lands exactly quiet-ms after the burst's last event. **Leading edge (review F5/G3/G4/H7, opt-in):** a burst opening on a key with no EMISSION in
  the last max-wait emits its opening event immediately — sent by a launched sender HOLDING an
  emission permit, never on the collector (H7: a collector suspended in `send` stops collection
  and the hot DROP_OLDEST source drops raw events; no free permit → no lead, the burst merges);
  the burst's accumulator then restarts
  empty, so its later flush carries only the post-lead events and emits only if there are any (a
  lone event is ONE frame, and the emitted counts sum to the raw event count). The cooldown is
  anchored on the key's last emission (a lead, an emitting flush, an emitting eviction) — a silent
  close starts none, so a transition 300 ms after a lone lead leads again, as the old operator
  did. **`ContentChangedPipeline` runs ONE burst across all windows** (a constant key, review G2 —
  the resolver reads one window per frame whatever fired, so a per-window key only multiplied maps
  of the same root; the operator stays generic), quiet 150 ms / max 300 ms, leading edge on —
  without it the first frame of every transition was delayed 150–300 ms, widening the #1104
  click-before-screen race and shifting `presentedAt`. Its `CoalescedChange` accumulator owns the
  OR of the `contentChangeTypes` bits (the old `pendingChangeTypes` AtomicInteger logged and
  discarded them); its `windowId` is the last event's window, for the DRIP log only.
  `WindowsChangedPipeline` uses the same shape on one key without a leading edge (quiet 100 ms /
  max 300 ms), replacing `debounce(100)`, which could starve under a continuous topology flood.
  **Correction of the record about the old operator:** its max-wait was checked only when the NEXT
  event arrived (arrival-driven, not scheduled), it read the wall clock
  (`System.currentTimeMillis()`), and it coalesced globally across windows — but its trailing
  emission was NOT lost (the `collectLatest` delay completed after the burst stopped); an earlier
  claim that a stopped burst lost its trailing frame was wrong.
- **D4 — the foreground policy (review F1/G5/G6, narrowed by H1–H6).** The event is a TRIGGER only;
  its window is never the snapshot source (a hidden activity under a watched modal sheet keeps
  firing content changes with ITS window id — snapshotting it interleaved obscured frames with the
  sheet's and flapped R0 `decline_confirm` ↔ `offer_popup`). "Watched" here means the **ENABLED**
  platform packages (`PlatformPreferences.enabledPackages`, injected into all three window
  pipelines), never the static registry alone. One resolver (`snapshotForEvent`) owns the order for
  the content and state pipelines and returns `Resolved(snapshot)` or `Skipped(reason)`:
  (1) ONE `getLiveNativeRoot()` — null → `NO_ACTIVE_ROOT`, never enumerate as a fallback; (2) its
  package enabled → map THAT already-fetched root (`getCurrentRootSnapshot(root)`): the active
  enabled window is the ground truth, a sheet over its activity included; (3) otherwise (our bubble,
  the launcher, system UI active) → `AccessibilitySource.foregroundWindow`,
  **readable-top-or-refuse** over **`TYPE_APPLICATION` windows only** (H1 — a platform's own
  transient system-layer toast would otherwise hijack frames, and every SystemUI window would cost a
  root fetch per frame; system-layer windows are never candidates and never have their root
  fetched), skipping our OWN package and **picture-in-picture** windows (H2 — a Google Maps PiP
  above DoorDash would otherwise refuse every bubble-active frame). The FIRST candidate by `layer`
  decides: a null root → `FRONT_UNREADABLE` (never fall through to a lower readable window); a
  non-enabled package → `FRONT_NOT_ENABLED`; else that window is mapped with
  `getWindowSnapshot(window, root, total)` (one enumeration, one root fetch per inspected window);
  no candidate → `NO_CANDIDATE`; a map failure → `MAP_FAILED`. Platform offer overlays that surface
  as accessibility `TYPE_SYSTEM` are candidates too since **#1152** (next paragraph). (4) A post-map check of the snapshot's package against the
  enabled set stays as a belt-and-braces INVARIANT (H5): both builders derive the package from the
  same already-fetched root, so it cannot catch a read-to-read swap and only fires on a programming
  error — which is why it has its OWN reason, `POST_MAP_MISMATCH`, plus a `Pipeline` WARN (review
  round 4), never the legitimate `FRONT_NOT_ENABLED` count. **Every skip is counted** (H3): both pipelines call `PipelineStats.onForegroundSkip(reason)`,
  rendered as `foregroundSkip{…}` (enum names + counts) on the periodic summary — a disabled
  platform's active frame now dies here, before `disabledPlatformDropped` could see it.
  Accepted residuals (round 4): a TRANSIENT null-root application window on top (our bubble
  finishing, an app's starting window) refuses frames for its few milliseconds, counted
  `FRONT_UNREADABLE`; and downstream ORDER between a burst's flush and the next event's leading
  emission is not guaranteed across a burst boundary (two senders) — harmless, since every
  snapshot re-reads the live tree, but `firstEventTimeMs` is not monotonic across DRIP lines.
  Every log site in the ingestion path is tagged `Pipeline` (principle 7); the windows-list DEBUG
  line logs the title's LENGTH only.
  **The windows pipeline emits true overlays only** (G6/H6): enabled-package `TYPE_APPLICATION`
  (non-PiP) windows above a cutoff, never one beneath it. The cutoff is the ACTIVE window's layer
  when the active window is not ours; when OUR bubble is active (its layer would suppress
  everything), the window `foregroundWindow` reads is emitted itself, and nothing if the foreground
  is refused. No active window → nothing (accepted — the event-driven pipelines cover that case).
  It maps through the same builder (F6). `TreeSnapshot.WindowContext` is filled only where the
  window object is already in hand — the foreground and windows paths (one private builder,
  `contextOf`); the active-root path carries none (H4: locating it cost a `service.windows`
  enumeration per frame for metadata nothing persists). `TreeSnapshot.trigger` records the reason
  (`CONTENT`/`STATE`/`WINDOWS`), OR-ed change bits, coalesced-event count and span — consumed by
  the `💧 DRIP` DEBUG log and available to the census (#1138); it is deliberately NOT in the capture
  envelope. The envelope `windowContext` title is **never persisted** (written null on every path —
  review G1: a name- or address-shaped title passes every marker scan); the hashed form arrives
  with #1145. The one kept control: an UNKNOWN frame whose title carries a sensitive marker is
  dropped (`WindowTitleScrubTest`).

**Platform offer overlays (#1152) — the one system-layer window that is read.** Ground truth (the
#248 record, verified on-device 2026-05-15): the Uber offer "popup" is a `SYSTEM_ALERT_WINDOW` from
`com.ubercab.driver`, reported to accessibility services as `AccessibilityWindowInfo.TYPE_SYSTEM` —
the same type as the status bar, nav bar, notification shade, heads-up notifications and custom
toasts. It covers ~full screen below the status bar (`Rect(0,136 – 1080,2347)` on the Pixel 7); the
same package keeps a persistent 142×142 `TYPE_SYSTEM` puck. The overlay can have focus (the six
committed `offer/*__uber__*` fixtures came through the ACTIVE-ROOT path) or not (its content events
then fired, and were answered with the window beneath). The shipped rules:
- **D1 — registry data, not a `when`.** `Platform.offerOverlay` (Uber `true`, every other entry
  `false`) and the derived `Platform.overlayPackages`. `:core:pipeline` names no platform; adding an
  overlay platform is flipping one flag (principle 8).
- **D2 — candidacy is TYPE → SIZE → PACKAGE, cheapest first** (`AccessibilitySource.overlayProbe`, the
  ONE seam — the Boolean `isOverlayCandidate` was deleted, review CC11, since it lost the
  unreadable distinction): `TYPE_SYSTEM` and not PiP; bounds ≥ `MIN_OVERLAY_AREA_FRACTION` (0.25) of
  the display area — the service's display metrics ONLY (review BB4: no window-bounds fallback — the
  largest application window can be a small PiP, which would make the puck "half the display"),
  read once per RESOLUTION (review DD5 replaced CC9's per-generation memo: a rotation need not fire a
  topology event, and release never bumps the generation until #1151); an unknown area admits NOTHING (`NO_DISPLAY_AREA`,
  checked before any memo). The puck (~0.8 %), status bar (~5 %), heads-up notifications and toasts
  fail on size WITHOUT a root fetch; then the root's package ∈ `overlayPackages` — the expanded shade
  passes on size and fails here. The outcome is sealed (review BB1/CC5): `Candidate` /
  `NotCandidate` / `Unreadable` (a LARGE system window whose root cannot be read — its owner is
  unverifiable, and it may be an offer overlay) / `BudgetExhausted` / `NoDisplayArea` (review DD8: an
  unknown display is INCONCLUSIVE, never "not a candidate" — the bubble path refuses
  `NO_DISPLAY_AREA` rather than walk past a possibly-full-screen overlay; the event path reads the
  active root, the pre-#1152 behaviour, counted once per resolution). Refusals are counted once per
  DECISION, by first failed check,
  `overlayRejected{NO_DISPLAY_AREA,TOO_SMALL,UNREADABLE,NOT_OVERLAY_PLATFORM,PACKAGE_CHANGED}`. A
  candidate is READ only if its package is ENABLED, and the package is re-verified on the
  freshly-fetched root that is mapped; a memoized CANDIDATE whose fresh root names another package is
  CORRECTED in the memo and counted `PACKAGE_CHANGED` (review CC10; a fresh root with NO package
  counts `UNREADABLE`, review FF4) — never a `FRONT_NOT_ENABLED`
  refusal — and USED at once when the fresh package is itself an enabled overlay platform's (review
  DD10: never read beneath a live overlay). A **DISABLED** overlay platform's overlay is NOT a candidate (review BB6) — skipped on
  every path, so an ignored platform's offer never blanks the enabled window beneath it.
- **D3 — `WindowVerdictCache`** (review BB7/CC1; `windowId → (packageName?, verdict, bounds)`, LRU 64,
  package names, enums and four ints only — never a node): the package is filled on every root fetch
  that resolves one (application AND system windows); a DECIDED system-window verdict (`CANDIDATE`,
  `TOO_SMALL`, `NOT_OVERLAY_PLATFORM`) is memoized so each status bar / nav bar / puck costs no root
  fetch and is counted ONCE per window id; `UNREADABLE`, `NO_DISPLAY_AREA` and budget exhaustion are
  never memoized (retried). The memo is **geometry- and generation-checked** (review CC1/DD5): EVERY
  memoized verdict stores the bounds AND the display area it was decided on and is honoured only while
  the window's CURRENT bounds and the CURRENT display area are both equal (a resized window keeps its
  id — a puck that grew into a card is re-probed; a rotated display re-probes everything); `AccessibilitySource.emit` CLEARS the cache on every `TYPE_WINDOWS_CHANGED` and bumps a
  topology generation, and every write carries the generation its caller read BEFORE probing — a
  collector paused in a root fetch (or iterating an older window list) across a clear has its write
  DISCARDED. `foregroundWindow` reads it before fetching a root (a cached own/non-enabled application
  window decides without a fetch), and both paths classify the active window through ONE owner —
  `AccessibilitySource.resolveActive` (a fresh `rootOf` the enumerated window) and `classify` (which
  records the package; review KK3) — never through a memoized package (reviews GG1/HH1). Every caller
  reads the generation BEFORE its enumeration and passes it to every walk, probe and fetch, so a clear
  landing between `getWindows()` and the walk discards the verdicts decided on the old list (review
  KK1). Declined (review DD2): revalidating a memoized
  `NOT_OVERLAY_PLATFORM` against an owner change under a live id — window ids are allocated per
  accessibility connection and a connection belongs to ONE package, so `windowId → package` is
  stable by framework construction; only geometry moves under a live id, which the bounds check
  covers. **Release note (review DD12):** in release the verdict cache NEVER clears (no
  `TYPE_WINDOWS_CHANGED` reaches the service until #1151), so the refusal-without-fetch paths — a cached
  own / non-enabled application window deciding without a root fetch, a memoized
  `NOT_OVERLAY_PLATFORM` / `TOO_SMALL` — rely on that same id → package stability (a window id is
  allocated per accessibility connection); geometry and display area are re-checked every frame, and
  a probe writes under its WALK's generation (review DD9), so a probe on a window from an older list
  after a mid-walk clear never writes under the new generation. Area has ONE definition,
  `WindowVerdictCache.Bounds.area()`, used by the size rule and the `area%` log (review DD11); the
  display is read at most once per resolution and passed down.
- **D4 — the front window: ONE walk, reported as data** (`FrontWindowWalk.walk` → `WalkStop`: the
  window it stopped at — application or system candidate, readable or not, its package/root — or
  `Exhausted` / `NoDisplayArea` / `NoCandidate` / `Failed`; review JJ10). Each caller applies its one-line
  policy over it: `foreground` (readable-top-or-refuse, behind `foregroundWindow` and `frontAbove`) and
  `overlayFront` (only an overlay winner) — no policy flag in, no reason-sniffing out. Candidates are the application windows (as shipped, own and PiP skipped) ∪ ENABLED
  overlay candidates, over EVERY window type by `layer`; the first decides. A LARGE unreadable system
  window refuses `FRONT_UNREADABLE` (review BB1 — never read the window beneath what may be an offer
  overlay; a small one is skipped without a fetch); a cached overlay whose root vanished →
  `FRONT_UNREADABLE`. **Root-fetch budget** (review CC5/DD4): at most `MAX_SCAN_ROOT_FETCHES` = 8 root
  fetches per walk — EVERY fetch is charged, including a memoized CANDIDATE's revalidation fetch; exhaustion refuses `SCAN_BUDGET`, never falls through — dozens of large readable non-overlay system
  windows cannot make every frame fetch them all and thrash the 64-entry cache. On the overlay walk a
  CACHED enabled application window decides "no overlay in front" without a root fetch (review FF3 —
  its root would never be mapped). A
  DoorDash toast is never a candidate (DoorDash has no `offerOverlay`); an Uber toast/puck fails size.
- **D5 — an enabled overlay on top IS the frame (review BB5).** **ONE three-valued active resolution**
  (review HH1, replacing the FF1/GG1 clauses; the #1149 N3/U2 pattern): when any overlay platform is
  enabled, `snapshotForEvent` calls `getWindows()` ONCE (a THROWING enumeration is no enumeration —
  review HH4 — not an empty list) and `AccessibilitySource.resolveActive` — shared VERBATIM with the
  topology path — answers over that one list: **Enabled / NotEnabled** — the single flagged window
  (`activeFromEnumeration`, review FF2, the ONE owner of "which window is active") whose FRESH root
  (`rootOf`, never a memoized package) is readable with a non-null package, split by enablement;
  **Unknown(window)** — flagged but its root is unreadable or package-less (never "not enabled");
  **Unknown(none)** — none flagged, or ≥ 2 mid-transition. The overlay scan runs off the active
  WINDOW's layer — it never needs the root — so it runs for Enabled AND Unknown(window): an enabled
  overlay in front is the frame either way. When it finds none: Enabled → the active window is mapped
  through the one window builder (`getWindowSnapshot` — a focused overlay, the card IS the active
  window, is therefore counted and carries its `WindowContext`, review HH5); Unknown(window) → ONE
  `rootInActiveWindow` read stands in only if it provably IS that window (same non-negative window id)
  and then follows EXACTLY the resolution its package calls for — enabled → mapped through the one
  window builder, not enabled (our bubble touched while its enumerated root is null) → the front-window
  read (reviews II1/JJ1: the frames the pre-#1152 code read); a null, negative or mismatched id → the
  frame is SKIPPED `FRONT_UNREADABLE` (the topology path
  emits nothing for Unknown(window), `topologySkip{FRONT_UNREADABLE}`); Unknown(none) (zero or ≥ 2 flagged — e.g. an overlay taking focus mid-touch) → the
  native root's OWN window is located in the list BY ITS ID and resolved as the active one, overlay
  scan included (review JJ2); only a native root not provably in the list (negative id, absent) takes
  the pre-#1152 read (the topology path emits nothing for Unknown(none)); NotEnabled → the front-window read (D4) over the same list. With no overlay
  platform enabled the #1148 path is unchanged (no enumeration, H4). **Taps keep #1149 U2's fail-closed
  rule** (review HH2): ≥ 2 flagged active windows give `getLiveWindowRoots` NO active root (keep-all
  scoping), never a trusted `rootInActiveWindow`. A root fetch that THROWS on a size-passing system
  window is a possible overlay exactly like a null root (review HH3). The walk covers the windows ABOVE
  the active one (`FrontWindowWalk.overlayFront` → `frontAbove`, every window type —
  review CC4: an application window, not ours and not PiP, above the overlay means the overlay is not
  frontmost and the active root is read) — whichever window fired. While an enabled overlay is the
  front, EVERY content/state resolution reads it: the covered window's bursts and the overlay's can
  never alternate and flap R0 offer ↔ map (the #1148 F1 class); `FrameGate`'s identity dedup
  collapses the repeats. On THIS path only an unreadable window that may BE an overlay refuses the
  frame `FRONT_UNREADABLE` — a LARGE system window, or a selected overlay whose root vanished (review
  CC7 as narrowed by DD6; otherwise the covered window would interleave with the overlay across its
  animate-in / tear-down frames). An unreadable APPLICATION window above (our bubble tearing down, a
  platform popup, a foreign panel) cannot be an offer overlay and is no barrier here — the active
  enabled root stays the ground truth (#1148); the bubble path keeps refusing on ANY unreadable window
  on top. An exception during the scan means "no overlay" (review DD7). Budget exhaustion refuses
  `SCAN_BUDGET`. Safe by construction: the overlay is ON TOP, so this is never the hidden-activity
  shape F1 removed. (Review EE1's refusal on an unreconciled active identity was SUPERSEDED by FF1:
  refusing whenever two unsynchronised reads disagree dropped every frame while the list was empty or
  stale — service just bound, a window transition, a finger on the bubble.)
  An overlay that IS the active window is the Enabled active window (HH5, mapped through the window
  builder). A SELECTED overlay that fails
  to map is skipped `MAP_FAILED`, retried on the next frame (review DD1, reversing BB9: a map failure
  does not prove the overlay left, and reading the covered window re-opens the interleave) — only "no
  overlay selected" reads the active root. When the active
  window is NOT enabled (our bubble, the launcher), the overlay is reached by step 3's
  `foregroundWindow` (D4). Every overlay frame is counted, `overlaySnapshots=n`, in ONE place — the
  shared `getWindowSnapshot` builder (review FF6) — and the count MEANS "an offer overlay was read": a
  mapped `TYPE_SYSTEM` window counts only when it passes the overlay probe (review JJ3, memoized — no
  fetch for the walk's own candidates); an active system window that is not one (a dragged puck) is
  still mapped, as before #1152, but not counted; every scan is counted,
  `overlayScans=n` (review CC9). **Cost, accepted (CC9):** with an overlay platform enabled, every
  event-path resolution pays one `getWindows()` enumeration (memoized verdicts keep root fetches
  out of it). There is no safe pre-gate: in release the verdict cache never clears until #1151 lands
  the topology stream, so a cached "no overlay above" verdict could hide a new card. The content
  coalescer stays ONE burst (review CC8 reverted BB3's per-platform key: the resolver never reads
  the burst's package/window, so a second key only mapped the same overlay twice per burst pair).
  **State-machine consequence, accepted (review CC12):** while an enabled CROSS-platform overlay is
  on top (an Uber card over a DoorDash dash), R0 follows the overlay's platform — the covered dash's
  transitions are unobserved for the card's lifetime and its settle park (§3) is dropped when R0
  leaves its surface. Two platforms visible at once is the multiplatform problem tracked in
  [#251](https://github.com/sjtrotter/DashBuddy/issues/251) /
  [#826](https://github.com/sjtrotter/DashBuddy/issues/826), deliberately not solved here.
- **D6 — topology emits at most ONE window, by the event path's rules** (review CC2/CC3/DD3). With the
  active window ENABLED, the event path owns it and never reads a non-active application window above
  it, so `WindowsChangedPipeline` emits ONLY an overlay winner — via `AccessibilitySource.overlayFront`,
  the SAME helper the event path calls (activity 2 / overlay 5 / non-active DoorDash sheet 9 → the event
  path reads the activity and the topology path emits nothing; a non-active sheet above its activity
  is no longer emitted either — that was the #1148 G6 shape, and it interleaved with the activity).
  With the active window NOT enabled (and not ours) it emits `frontAbove`'s single winner. Either way
  an unreadable window above is a BARRIER (nothing beneath it is emitted) and a foreign application
  window on top emits nothing; an overlay over a covered sheet emits the overlay only. The bubble-active branch emits the
  `foregroundWindow` result. The topology path takes its active window from the SAME enumeration
  through the SAME `resolveActive` (review HH1): Unknown(none) → nothing (`topologySkip{NO_ACTIVE_ROOT}`);
  Unknown(window) → the overlay scan, and nothing (`topologySkip{FRONT_UNREADABLE}`) when no overlay is
  in front; Enabled → an overlay winner only; NotEnabled → our bubble's front window or `frontAbove`'s
  single winner. A null package is never "not enabled"; a memoized package never bypasses the fresh-root
  check. (Declined, review HH8: a `windowId → root` memo to save the one per-burst `rootOf` — a cached
  node handle across bursts is a staleness hazard, for a one-fetch saving on a debug-only path, #1151.) Its refusals are counted in their OWN census, `topologySkip{…}` (review FF4 —
  `foregroundSkip{…}`'s contract is event-path frame loss). The DEBUG window-list line
  adds `area%=<int>` (the window's share of the display; `-1` when unknown) beside `titleLen` — no
  text.
- **Not in #1152:** release `packageNames` consent (#1151 — the topology path stays debug-only until it
  lands; D5 works in release because the overlay's own package is on the framework list);
  state-machine acceptance of a background-platform offer (#251/#826); any rule change. Nothing new
  is persisted — the window title stays unpersisted.

Not in #1148: `notificationTimeout` (stays 100 ms), TalkBack's subtree-only / focus-gated filters
(they discard observer evidence), `TYPE_ANNOUNCEMENT`/text events, and any change to `FrameGate`,
`Observation.identity()`, the classifier or the capture envelope schema.

**Census skeleton (M1a, #1145; hardened over four review rounds of PR #1160) — pure, not yet wired.**
The UNKNOWN-screen census (Epic #1138) is specified by ADR-0011; this layer holds its first,
side-effect-free half.

- *Wire contract* — the Apache-2.0-headed package `domain.census.contract`: `UiSkeletonDto` /
  `UiSkeletonNodeDto` / `TextSlot` + `SkeletonSchema` (`uinode.skeleton.v1`, ADR §1 — no plaintext slot,
  no bounds; a per-node `text` map keyed by `UiNodeTextField.wire`, each value `{h?, kind}`, `h` present
  iff `kind` is `words:1..8`; invariants checked at construction AND on decode, incl. well-formed UTF-16
  without U+0000 for class/id/stamps (`WireStrings`) and a real calendar `day`; 64 KB item cap measured
  once by `SkeletonSchema.measure`); `CensusHash` (§3, `sha256("census.v1:" + canonical)` → 16 hex,
  fail-closed to `withheld`, where `CensusHash.canonical` — a fixed point — is the value after the census glyph
  fold `TextFold.foldForCensus` (FORMAT strip by code point, then NFKC, then dash fold; `TextFold` also
  owns the sensitive scan's boundary-preserving form `foldGlyphsPreservingSupplementary`, byte-for-byte
  the pre-#1160 normalizer; `SensitiveTextMarkers.findMarker` scans it AND the STRIPPED form — that same
  preserving output minus its supplementary-plane FORMAT code points (`TextFold.stripSupplementaryFormat`),
  never `foldForCensus`, which is census-only (WW1) — the second only when the preserving OUTPUT carries a
  supplementary FORMAT code point (judged on the emitted string, AG1), and drops on either hit, #1160
  reviews RR1/UU2/UU8/WW1), trimmed, with every
  census-whitespace run collapsed to one ASCII space; the builder hashes the judged string via
  `CensusHash.ofCanonical`); `KindClassifier` (§1's two-stage grammar, code-point based);
  `CensusFingerprint` (§8: wrapper-to-forest over a synthetic root, every string LENGTH-PREFIXED so the
  encoding is injective, digested through `sha256OrNull(ByteArray)`); and the static gates
  `ResourceIdGrammar.isStaticShape` / `ClassNameGrammar` (the DTO enforces `ResourceIdGrammar.isWireId` —
  a static shape or the reserved sentinel `~` — and `ClassNameGrammar` at construction and decode) — a
  dynamic id (a per-frame-UUID Compose test tag) or a non-static class is ABSENT (null) on the wire and in
  the fingerprint; an id `IdPathJudgement.isStaticId` rejects because its name part trips a
  frame-free customer-PII predicate (`row_Deliver_to_Sam`) is emitted as the sentinel `~` (only a
  grammar-dynamic id is null); and the frame-level containment rule likewise EMITS the sentinel `~` (`ResourceIdGrammar.FRAME_WITHHELD_ID`) in place of a static id carrying an identity
  run of the same frame (`chip_Adam` beside `customer_name` "Adam"), so the node keeps its place in the
  fingerprint's structure, and makes absent a non-wrapper class carrying a customer-name run. The golden vectors live with the
  contract's tests (never in the APK).
- *Shared vocabulary* — the anonymous-wrapper predicate (`domain.model.accessibility.AnonymousWrappers`,
  owned by the core model and imported by the contract) is the one `UiNode.stableHash`
  also uses (algorithm unchanged, pinned by `UiNodeStableHashPinTest`); the customer-PII shapes moved
  byte-for-byte from the test-only `SnapshotRedactor` to `domain.privacy.PiiShapes` (app licence;
  `SnapshotRedactor` delegates, `PiiShapesParityTest` pins it; `PiiShapesIcuGuardTest` applies the ICU
  bare-`}` rule to every compiled pattern); `CustomerTextMarkers.ID_MARKER_TABLE` is the ONE owner of
  "what kind of value an id carries" — `IdMarker(suffix, kind, runtimeScrub)`, kind NAME / ADDRESS /
  EXACT / PERSON_OR_MERCHANT / CONTENT, runtime scrub ALWAYS / NEVER — and `ID_MARKERS` is its ALWAYS
  projection: #1160 added `order_cx_name`, `tvTitle` and `tvLastMessage` to the runtime UNKNOWN scrub,
  all ALWAYS on the id alone (review round 11: no value-shape gate — "李明", "de la Cruz" read as chrome to
  any shape — so an UNKNOWN sheet title under `tvTitle`, "Pick up order", loses its X-Ray line, ADR residual
  11); the recognized `doordash.screen.chat` / `chat_conversation` rules
  redact `tvTitle` (customer-name normalized) and `tvLastMessage` (plain); the corpus intake (`PII_ID_SUFFIXES`) holds every table suffix. The frame-rule
  off-switch exists only as `census.diagnostics.DiagnosticSkeletonBuilder` in `:core:pipeline`'s TEST
  FIXTURES (`src/testFixtures`, consumed by `:app` tests via `testFixtures(project(":core:pipeline"))`) —
  never in `main`, guarded by `DiagnosticsNotInMainTest`.
- *The filter* — `core.pipeline.census.SkeletonBuilder` (typed API: `Platform`, `LocalDate`; build/outcome/
  refusals/envelope), with the per-frame state in `FrameFilter`, the run vocabulary in `LetterRuns` and the
  frame-free id judgement in `IdPathJudgement` (split by #1160 review round 12). A
  `SensitiveTextMarkers` hit on the raw tree or title yields no skeleton (a caller may pass the verdict
  it already computed, bound to that exact tree instance; a mismatched one is ignored); a FAILED marker
  scan is `BUILD_FAILED`, not a sensitive frame. Per field: step 1 is the node's own RAW id
  (`ID_MARKER_TABLE` ∪ `PII_ID_SUFFIXES`, via `IdClass`); steps 2–8 judge the CANONICAL form (a fixed point
  or none — a value with no canonical form is withheld; the canonical form alone decides the 40-char cap,
  and a value with more than 4 × 40 non-FORMAT, non-whitespace code points is capped WITHOUT folding —
  4 is the longest canonical decomposition, checked exhaustively by `SkeletonLengthBoundTest`)
  and, when it differs and is itself within the cap, the RAW trimmed form — either hit withholds; only a
  `words:1..8` survivor hashes, on the judged canonical form. The FRAME-LEVEL duplicate rule then
  withholds (a) any field whose canonical value is a seeded EXACT value — value-judged anywhere in the
  frame, or the text/desc of a NAME, ADDRESS or EXACT id — and (b) any field containing a letter run
  (≥ 2 letters in code points, `CaseFold`-folded) of a NAME id's text (else its desc). NAME run-seeding
  is reserved for ids whose value is ONLY ever a person's name (`customer_name`, `order_cx_name`); a
  reused id that is a person or a merchant (`user_name`) is PERSON_OR_MERCHANT — exact-seeded, plus letter
  runs (≥ 3 letters) only when its value reads as a person's name (≤ 2 letter-only Capitalized tokens,
  optionally plus one trailing initial — "Riley S"; never "In-N-Out Burger", "7-Eleven" or "Jack in the
  Box") —
  and a value that may be chrome (`tvTitle`, `tvLastMessage`) is EXACT — exact-seeded only (what a kind
  seeds is the kind table's `seedsExactValue` / `maxRunSeedTokens` / `minRunLetters` /
  `personNameShapeOnly` / `runsGuardClasses`; its source is `nameRunSource` — usable text, else desc); the `idProtect` rows (`customer_name`, `order_cx_name`,
  `user_name`, `tvTitle` — not `tvLastMessage`) also add their WHOLE value (≥ 3 code-point letters, a
  single token included, no name-shape gate) as an id-only run, matched across id separators; the address ids are ADDRESS (exact only — address vocabulary
  is common English); CONTENT ids (`description_text_view`, the instruction bodies) and masks seed
  nothing. The same containment, over every contiguous join of camel segments ACROSS separators
  (`row_mc_kenna`), replaces a node's static id with the reserved sentinel `~` (`chipAdam` beside
  `customer_name` "Adam", `search_bar` beside `tvTitle` "Search") so the fingerprint's STRUCTURE never
  depends on the customer; a class carrying a customer-NAME run is absent (`com.x.RileyButton`,
  `androidx.RileyButton`) unless it is a KNOWN framework class (`FrameworkClasses.KNOWN`, exact names: the
  classpath-generated inventory `census/framework-classes.txt.gz` (gzip, #1160 AI2), diffed on its decompressed content by `:app`'s
  `FrameworkClassInventoryTest` — regenerate with `-DupdateFrameworkClasses=true` — ∪ the corpus set). `IdPathJudgement.isStaticId` is
  memoized process-wide in a bounded LRU (512). A malformed (NUL / lone-surrogate) CLASS is emitted absent and counted
  (`Outcome.Built.malformedClass`); a malformed VIEW ID refuses the frame (`INVALID_TREE` — its identity
  classification cannot be verified, #1160 round 13). On the id path the PII judgement is stricter-to-trigger: a
  lead-in withholds only before a Capitalized token and the name shape needs an uppercase-led
  (Capitalized or all-caps) first token and an uppercase initial (`deliver_to_label`, `tabB` travel;
  `chip_RILEY_S` and — the recall cost — `TAB_B` are absent). Each value is
  judged once per frame (memoized). `outcome()` never throws: every failure is `Refusal.BUILD_FAILED`
  (the #909 inertness rule); refusals are reasons, never text.
- *Tests* — `SkeletonCorpusTest` asserts ADR §7 (a)–(f) over the whole committed corpus (full-tree
  redactor parity with exactly one stated exemption, plus a negative control) and a seeded property.

Nothing calls the builder at runtime yet: the publisher stage, `CensusSink` and `PipelineStats` counters
are #1146 (M1b); upload is M3.

**The whole recognition + text-scrub layer assumes an ENGLISH device (#938).** Rule anchors and
BOTH text-marker SSOTs (`SensitiveTextMarkers.KEYWORDS`, `CustomerTextMarkers.MARKERS`) are literal
English strings, so on a non-`en` device recognition drops toward zero (survivable — everything
falls to UNKNOWN, and release binds `NoOpCaptureBus`) **but the Pledge layers thin**: the
sensitive-screen backstop, the UNKNOWN-capture PII scrub, and the shareable-log scrub all weaken.
`CustomerTextMarkers.ID_MARKERS` (#910) is the one **locale-immune** defence — view ids don't
localize — and is the pattern any future translation work should prefer. #938 makes the boundary
*loud, not fixed*: `RecognitionLocale` (`:domain`, pure) decides, and the `:app`
`LocaleBoundaryNotifier` (bound to the `:domain` `LocaleBoundaryReporter` contract that
`AccessibilityListener.onServiceConnected` calls — recognition's own go-live edge, fail-OPEN)
emits one WARN per sensor start (ISO-639 code only, principle 7) plus a **once-per-install**
dasher-visible notice on its own `app_notice_channel`, remembered by the `app_state` DataStore flag
`locale_boundary_notice_shown`. **Translating anchors/markers is deliberately NOT done** — it needs
a non-English corpus, and until that exists a non-`en` device is a documented degraded mode, not a
supported one. (Distinct from #428, which bounds the app's OWN copy/TTS to en/es/fr — a different
assumption; the #938 notice copy itself IS translated into `values-es` since a Spanish dasher is
exactly its audience.)

**Recognition has a liveness signal (#937)** — the fourth member of the #909 silent-death family
(effect engine #914, bubble #916, odometer #917; the offer voice joined as the fifth in #991, §4).
Two pieces, both fail-OPEN and both inert to frame processing. (1) **Version stamping:**
`PlatformAppVersions` resolves the OBSERVED app's `versionName` (`CachingPlatformAppVersions` — one
`PackageManager` lookup per package per process, negative results cached too, `catch (Throwable)`;
the `PackageManager` call is a lambda injected at the `PipelineModule` DI edge so the caching logic
is a plain unit test). `ObservationClassifier` stamps it onto every observation's
`ReplayMetadata.platformAppVersion` — classification is the one point that always runs AND knows the
package, since the capture stage is skipped wholesale on a disabled bus — from where it rides into
the capture envelope for free. Additive + nullable, and `Json.encodeDefaults` is false, so an
unstamped envelope is byte-identical to a pre-#937 one: the committed corpus and the parse golden
carry no such key and no test may require it. The cache is deliberately NOT invalidated on a package
update (a process restart follows one in practice; a stale diagnostic stamp is a nuisance, never a
correctness problem). The resolved `package@version` pairs also ride the periodic `PipelineStats`
INFO summary — platform-app facts, PII-free. (2) **UNKNOWN-rate alarm:** `RecognitionHealth` (pure,
per-platform rolling window of the last `WINDOW_SIZE`=50 **admitted** screen frames —
post-`FrameGate`, so a dasher parked on one unruled screen contributes a couple of samples, not a
thousand) trips when the window is FULL and ≥`UNKNOWN_RATIO_THRESHOLD`=0.80 of it classified
UNKNOWN. Field-derived: a healthy dash measured near 16 % UNKNOWN on that same stream, so the
threshold sits 5× clear of the noise while a real anchor break drives the ratio toward 1.0.
`RecognitionHealthMonitor` owns the edges — package→platform via the registry (an `Unknown` package
is ignored; no platform literal anywhere), one WARN + one `RecognitionHealthReporter` call per
platform per **process** (per-*dash* would need `:core:state`, which depends on `:core:pipeline` and
not the reverse; recovery deliberately does not re-arm). The `:domain` reporter contract is #938's
inversion reused: the `:app` `RecognitionHealthNotifier` posts on the shared `app_notice_channel`,
whose id + copy live in one owner (`AppNoticeChannel`, ids 102 locale / 103 recognition / 105 TTS
health — 104 is the separate `weekly_plan_channel`; `AppNoticeChannel.postNotice` owns the
ensure+PendingIntent+Builder posting shape all three notifiers call).

**#1036 adds the alarm's other half: matched-but-parsed-nothing.** #937 measures rules that stopped
MATCHING; DoorDash 8.93.7 removed the view ids every money parse anchored on while the text
`require` anchors kept matching, so recognition looked healthy for weeks as every parse died, and
the frozen corpus structurally cannot see that (#1029, re-anchored in §2). `Ruleset.matchFirst`
reports a `:domain` `ParseShortfall` for every branch that MATCHED while its parse yielded nothing
usable, on **two** triggers: every **evidence** field unresolved (total rot), or any
**shape-required** field null while others parsed (partial rot —
`ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`). "Unresolved" is judged per field by the
`ParseFieldKind` the parse compiler emits in the same dispatch that builds the extractor
(`CompiledBranch.parseEvidenceFields`): `CONSTANT` (a literal, a `presence` check, an
`else`-bearing `conditionalEnum`, any `fallback`-bearing extraction) is excluded entirely — it can
neither evidence rot nor MASK a dead extraction beside it — `NULLABLE` counts when null, and
`COLLECTION` (`each`/`findAll`) counts when the list is **empty**, which is what a plain null check
missed on exactly the money surfaces (`payLineItems`, `orders`, `tasks`). Measured pre-validate (a
`DropParsed` validator is a declared decision, not rot) and reported even when a `Skip` validator
discards the branch — the case where a lower-priority text rule claims the frame and the rot leaves
no other trace. The classifier only CARRIES the shortfalls (on `Observation.Screen`/`Notification`);
both pipelines count them **post-admission**, beside the #937 sample, so debounced duplicates and
disabled platforms never enter the census. `PipelineStats.onParseShortfall(shortfall)` owns both
grains: a per-rule count rendered as `parseShortfall{<ruleId>=n,…}` on the periodic summary (loudest
8, `+k more`, rule ids and counts only, P7) and one WARN per rule per process under the
`ParseHealth` tag. Keyed by rule id ONLY (P8), and inert. Deliberately **no** dasher-visible notice:
escalation is a later decision. A rule whose one evidence field is legitimately optional trips
benignly (`dash_along_the_way`, `idle_map`, `set_dash_end_time` in the committed corpus; in the field
(09-06/09-07 pulls, DoorDash 8.95.6) also `waiting_for_offer` — its `earnings_pill` is a CAROUSEL that
alternates the dash total with a `Weekly goal` render, on which `sessionPay` is correctly null — and
`pickup_shopping` on a pre-render frame), which is
why the WARN is a once-per-process breadcrumb rather than an alarm; **#1093 added the bind half**:
`Ruleset.shortfallOf` also lists every OPTIONAL `bind` target that resolved null on the matched
branch (`ParseShortfall.unresolvedOptionalBindings`, sorted bind names — a mandatory miss already
skipped the rule), and `PipelineStats.onParseShortfall` counts those under their own
`bindShortfall{<ruleId>.<bind>=n}` suffix with one WARN per rule+bind per process, leaving the parse
count untouched (`ParseShortfall.hasParseTrigger` is the split). Two sibling censuses joined in #1149, both for
`RuleAction` target binds that DID resolve a node, computed from the returned branch only:
`bindUnprovable{<ruleId>#<bind>=n}` (`ParseShortfall.unprovableBindings` + `unprovableReasons` — unreadable
children, more than 6 labels, or no letter-bearing label: the executor's label re-find can never run) and
`bindRefused{<ruleId>#<bind>=n}` (`ParseShortfall.refusedBindings` — no clickable owner in the package, or a
foreign bound node / owner walk: no `NodeRef` is emitted, the target is withheld); one WARN per rule+bind per
process each, tag `ParseHealth`, bind names and our own reason wording only. The receipt is the receipt: DoorDash
8.93.7 removed `expandable_view`, `delivery_summary_collapsed`'s optional `expandButton` resolved
nothing, and `EffectMap.diffExpandAction` — which emits only on a bound target — went silent for
weeks with no line of any kind (the dev noticed the manual tap). The rule now carries a second,
id-less arm (a clickable `hasNoId` row whose subtree says `This offer`), and
`UiInteractionHandler.findNodeByBounds` (the only strategy that can re-find an id-less, text-less
container) accepts a clickable same-class node overlapping the ref by ≥ `RELAXED_BOUNDS_IOU` (0.5,
sharing `ClickCandidateRanker.boundsIoU`) beside the exact match, descending past it so a wrapper
cannot hide the tighter child. **Geometry is not identity** — the guards that came out of three
adversarial rounds: (1) EVERY bounds-derived candidate (exact rect or overlap) must carry the bind's
own subtree labels — `NodeRef.labelHintHashes` (sha256s of the bound node's letter-bearing
`allText` entries, ≤ 6, normalized by the one `NodeRef.hintKeyOrNull`; amounts and counts are never
hints because they repeat across a surface; HASHES because the ref rides `DeferredAction` into the
journal and snapshots and a subtree label can be anything the platform renders) and ALL of them must
appear among the candidate's live `collectLabels` (one shared label is not identity); a hint-less
pre-#1093 ref admits an exact clickable match only. Without this a row captured 400 px low
mid-animation, or a control sitting at the exact captured rect, would take a label-free tap. (2) A
zero-area ref rect skips the walk (no evidence → manual). (3) `ClickCandidateRanker` requires a
UNIQUE best overlap — a wrapper and its child at the same IoU are a tie, and a tie aborts (#734).
(4) The walk decides NOTHING about identity: every clickable same-class node at the rect or
overlapping it is a hit, an exact NON-clickable match is skipped and descended (the strict click
climbs to the nearest clickable ANCESTOR, so ranking a shell above its clickable child would tap
outside the row), the walk ALWAYS descends (pruning at an exact clickable wrapper handed the tap to
the wrapper), and each hit records the hits it is nested inside; verification then rules — an
UNVERIFIED descendant says nothing about its parent (a stray child with one label must not evict the
row), and two VERIFIED candidates nested in each other (a clickable wrapper inheriting the row's
labels) are undecidable and ABORT to manual (round 4; a max-overlap pick chose the wrapper,
supersession guessed the row) — checked AFTER the #788 active-window scoping, among the retained
candidates only, so a nested pair in a background window cannot abort an unambiguous foreground tap
(round 5; `UiInteractionHandlerTieTest` runs the two-root sequence through the real handler). (5) The `bindShortfall` census is keyed structurally by (rule, bind) —
a dotted string merged `(a.b, c)` with `(a, b.c)` — and rendered `rule#bind` with `#`/`%` escaped in
each component so the render cannot merge two pairs either. The sha256 helper moved to `:domain` (`domain.util.sha256OrNull`) so `NodeRef` can hash
without a second digest site; `:core:pipeline`'s `sha256OrNull` delegates to it. The
rule's id-less arm requires the chevron's `Expand` contentDescription too, since `find` visits
ancestors first and an id-less clickable ancestor containing `This offer` (the 07-17 frames) would
otherwise be bound ahead of the still-present id-bearing pay node (`ActuationBindingResolutionTest`
pins that the id-less arm never steals an id-bearing target, mirrors the walk's pruning exactly, and
replays the shifted-ref sequence). Re-anchoring changed the bind's content-pinned capability key (#422), so
consent is re-asked; the same corpus shows the real
finds (`delivery_summary_expanded`/`_collapsed`, `waiting_for_offer`, `timeline`).
