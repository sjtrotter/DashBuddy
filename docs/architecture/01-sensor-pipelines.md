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
`:domain`). Per-event-type sub-pipelines (`ContentChangedPipeline` — debounced,
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
brute-recoverable; compile-rejects `plainMask`+`normalize` together — and the rule-INDEPENDENT
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
chat title/body, order-ready customer name via a per-field notif `redact`; store names kept).
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
end-to-end by `FlowlessRecognitionNeutralityTest`. Candidate text markers are vetted against the
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
must never be able to move a classification. **Two #910 additions close the SPLIT-NODE class**
(marker and PII in different nodes — a `user_name_label` reading `"Delivery for"` beside a BARE
`user_name`): (1) a **click envelope inherits the SCREEN rule's `redact`** —
`Observation.Click.screenRuleId` (stamped from the classifier's per-platform screen-context cache)
drives the same `redactFor` lookup, applied to recognized AND UNKNOWN clicks, envelope-only (the
dedup `contentHash` stays on the original node), fail-OPEN; (2) `CustomerTextMarkers.ID_MARKERS` —
an enumerated, cross-platform-DATA list of view-id suffixes whose node VALUE is customer PII
(`customer_name`/`user_name`/`address_line_1`/`address_line_2`, + `arriving_at_title` #993 and
#1058's `address_subpremise_line`/`dasher_instruction_content_{collapsed,expanded}`), `hasIdSuffix` semantics, scrubbed
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
count untouched (`ParseShortfall.hasParseTrigger` is the split). The receipt is the receipt: DoorDash
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
supersession guessed the row). (5) The `bindShortfall` census is keyed structurally by (rule, bind) —
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
