# ADR-0011: UNKNOWN-Screen Census Privacy Model (hash-only skeletons, k-anonymous vocabulary, trusted installs)

**Status:** Accepted — design record for Epic #1138; the invariants below are the specification the
M1 builders (#1145, #1146) and the server (#1157) implement. Nothing leaves the phone until M3.
**Amended 2026-11-13 (#1618, epic #1617):** skeletons from the Play and sideload builds, a contributor tier, shape
templates, probes, shadow and canary, and a dasher-privacy model. The decisions are in §12 to §21 at the end; the
sections they change carry an "Amended 2026-11-13" note in place. Decided, not built, on that date.
**Issue:** #1144 (census M0), sub-issue of #1138
**Date:** 2026-09-30
**Builds on:** ADR-0003 (versioning and API contracts), ADR-0004 (canonical pipeline architecture),
ADR-0007 (canonical domain schema), ADR-0009 (rule distribution channels — amended in the same PR)
**Related:** #192 (the downstream OTA half), #193 (self-hostable aggregation server, URL sovereignty),
#194 (attestation / poisoning resistance), #1137 (k-promo), #1157 (census server v0),
#835 (`UiNodeTextField` scrub contract), #1147 (richer node fields — SHIPPED in PR #1158, so its seven
strings incl. `uniqueId` are already in the enum), #246 (counsel review)

---

## Context

Recognition is data (ADR-0001) and the ruleset is repaired from UNKNOWN captures. Today those
captures are pulled by USB from ONE phone, days after a platform release moved an anchor; September
2026 alone brought four DoorDash versions that each moved anchors, and a server-side Compose offer
card that blanked two dashing days of offers (#1114). The developer's ask (#1138): let opted-in
phones tell us which screens the ruleset does not recognize, so a fix can be drafted within hours
instead of after the next pull.

The constraint that shapes everything: **an UNKNOWN frame is the least-protected frame in the
system.** No rule claimed it, so no rule `redact` ran, and every Pledge leak of September (#1116,
#1122, #1127, #1128) was an UNKNOWN frame on which a marker backstop missed a name, an address or
a gate code. The existing debug capture envelope (`uinode.v1`, plaintext `text`/`desc`/`state`,
`NoOpCaptureBus` in release) is therefore NOT the thing to upload. The census needs a payload that
cannot carry a customer's details even when every filter fails, and a server that cannot learn a
string from one person.

Framing (CLAUDE.md): this is **recognition coverage of the visible UI surface** — measuring which
screen shapes the ruleset fails to recognize so the ruleset can be maintained. It is not
reverse-engineering, model recovery or algorithm characterization, and public text about it must
not suggest otherwise.

## Decision

The census is defined by nine invariants. Each names its owner (the type, class or table that makes
it true by construction) so a violation is a compile error or a failing test, not a policy breach.

### 1. What leaves the phone is a **skeleton**, never an envelope (D1)

A new versioned schema, `uinode.skeleton.v1` (`UiSkeletonDto` + `SkeletonSchema`, beside
`UiNodeSchema`, ADR-0003 rules apply). Per node: `class`, `id` (the platform's own resource name —
chrome by construction WHEN it matches the static resource-name grammar, `ResourceIdGrammar`: an optional
`<package>:id/` prefix (`[A-Za-z][A-Za-z0-9_.]*`), a name `[A-Za-z_][A-Za-z0-9_.-]*` with at most one
internal space, ≤ 64 characters, no UUID-shaped token (dynamic whatever its digits, review round 17), no other run of 8+ hex characters containing a decimal digit (a letter-only hex run is a word — `AddedBadgeView`, review round 16) and no run of 4+ decimal digits — residual: an opaque per-frame id made only of letters, outside the UUID shape, reads as static; a dynamic id — a
per-frame UUID in a Compose test tag, three committed frames `PRIMARY_BUTTON_<uuid>` — is treated as
ABSENT for both the wire and the fingerprint, never rewritten; the §2 PII-id step still runs on the raw
id; amended in #1160. The SHAPE is the contract's (`ResourceIdGrammar.isStaticShape`) and is enforced by
the DTO at construction and decode, and by the server; in addition the CLIENT judges the id's name part —
separators read as spaces, every token start — through the frame-free customer-PII predicates (marker,
lead-in, mask, name shape), and a hit replaces the id with the sentinel `~` (§8, review round 14 — a null
would splice a wrapper-class node for one customer and keep it for the next): `row_Deliver_to_Sam` and `chip_Adam_S` do not
travel; the id's camelCase segments count as words too (`deliverToSam`), and only the name shape with a
CASE-SENSITIVE initial runs on the id path, so `option_a`/`tab_b` chrome ids travel (review round 7).
Those predicates live in `:core:pipeline`, so that judgement is client-side only; a bare name
with no marker, lead-in or initial (`chip_Adam`, `Adam Smith`) is indistinguishable by shape from chrome
(`chip_Gold`, `Artwork Image`) — it is replaced by the sentinel `~` when an identity id on the same
frame carries that name (§2 frame-level rule, §8), and travels in the clear otherwise — residual risk 10. On the id path the PII
judgement is deliberately harder to trigger than on text: a marker or lead-in withholds only when the
token after it is Capitalized (`deliver_to_Sam` is absent, `deliver_to_label` travels), and the name shape
needs an UPPERCASE-led first token — Capitalized or all-caps — and an uppercase initial (`chip_Adam_S` and
`chip_RILEY_S` are absent; `tabB`, `optionA`, `tab_B` travel) — review rounds 8 and 11. The all-caps arm
(review round 11) costs recall: SCREAMING_SNAKE constants ending in a one-letter segment (`TAB_B`,
`SECTION_C`, `PRIMARY_BUTTON_A`) are absent too — fail closed. `class` likewise travels only when it matches `ClassNameGrammar` — a Java binary
class name, ≤ 128 characters, the same digit-run rule — else it is absent (null on the wire, `""` in the
fingerprint), because Compose/Flutter/WebView/custom views can report any string as their class), the three flags (`isClickable`/`isEnabled` as booleans, `isChecked` as the
`UiNode` tri-state `Int` 0/1/2 — wire types stated so the shared vectors cannot disagree), and
`children`. Per text field — enumerated from **`UiNodeTextField`**, the #835 scrub contract, never a
hand-list, so #1147's strings (`paneTitle`, `hintText`, `clickActionLabel`, …) and any entry added
later are covered automatically; the node's text fields travel as a MAP keyed by the enum's wire key,
and the server accepts any key OF THE WIRE-KEY SHAPE `^[a-z][A-Za-z0-9]{0,15}$` in that map — a field
name, never free text; a new enum entry must use a wire key of that shape (every value has the same
`{h?, kind}` shape, so a new key
is not a privacy change) while rejecting unknown fields everywhere else — an object `{h?, kind}`: `kind` is a coarse shape class and `h` is present
only when the §2 filter admits a hash (§3 only defines how the admitted token is hashed). **The type has no plaintext slot**: a leak of a text value is a
type error. **`uniqueId` (`uid`) is a text slot like every other enum entry** — hashed through the filter, never
sent in the clear. It is the optional API-33 `AccessibilityNodeInfo.getUniqueId()` value; whether the
platforms set it, and whether it is stable, is UNVERIFIED (#1147 shipped it as capture evidence only
and the rule compiler deliberately exposes no predicate for it), so it cannot anchor a rule today. A
clear-text gate was considered and REJECTED: no shape rule can tell `store_Chipotle` or
`customer_row_JaneS` from chrome, and if it ever proves useful the operator's trusted phone resolves
the hash under `k_unblind` anyway. **The window title is the ONE explicit non-node
text field** (it lives on
`windowContext`, outside `UiNodeTextField`) and goes through the same `{h?, kind}` rule; it is the
single named exception to "enumerate the enum", and `SkeletonCorpusTest` names it. `deviceFingerprint`
is dropped from skeleton metadata.

> **Device-API residual (#1163):** `isChecked` is the platform tri-state only on API 36+ (`getChecked`); below 36 the mapper can observe only the boolean, so a PARTIAL control reads `1` there and `2` on 36 — the same UNKNOWN screen fingerprints differently across OS versions when it carries a partially-checked control. #1146 (the publisher) decides whether the fingerprint folds `2 → 1`; until then this is a known cohort-split source, not a privacy change.

**No bounds leave the phone in v1.** A `wrap_content` text node's width is a function of its rendered
text, a content-sized container mirrors its child's rectangle exactly (the corpus has textless
`LinearLayout`s with precisely their redacted child's bounds), and raw pixels encode screen size and
density — so quantizing or dropping text-node bounds alone does not close the channel. v1 carries
tree ORDER only; the layout a human needs to read a cluster comes from class/id nesting and sibling
order. Bounds are a v2 candidate only with a leak analysis that covers containers and positioned
siblings.

The permitted fields, at both levels, are the test allowlist (§7a): per node `class`, `id`, `kind`,
`h` (strings) plus the three typed flags — no bounds; per envelope the `windowTitle` `{h?, kind}`
object (§1's one non-node text field), the strings `schemaId`,
`fingerprint`, `platform`, `platformAppVersion`, `appVersion`, `rulesetReleaseTag`, `day` (an `hour`
bucket in flight only) and the integers `filterRev`, `hashDomain`, `engineVersion`,
`rulesetFormatVersion`. Five of these are `ReplayMetadata`'s own names (`platformAppVersion`,
`appVersion`, `rulesetReleaseTag`, `engineVersion`, `rulesetFormatVersion`); the other six
(`schemaId`, `fingerprint`, `platform`, `day`, `filterRev`, `hashDomain`) are census-own fields, and
the §7(a) test enumerates both groups explicitly. The install id is added by the M3 uploader at the transport layer,
never inside the skeleton.

`kind` is a NORMATIVE classifier, evaluated on the trimmed canonical value (the same bytes that would
be hashed) AFTER the 40-character cap (§2 step 2 runs before the grammar, so the split and every
category test see bounded input), first match wins. It is computed in two stages so that
classification and filtering are not circular: the grammar (rows 2–4 below) yields an intermediate `shapeKind`; §2's filter then
consults `shapeKind` (step 6) and decides; the EMITTED `kind` is `withheld` when a withholding step
fired, otherwise `shapeKind` — so `digits`, `mixed` and `words:8+` ARE emitted (hash refused), and `words:1` …
`words:8` are emitted only together with their hash.

| Precedence | `kind` | Rule |
|---|---|---|
| 1 | `withheld` | a WITHHOLDING filter step in §2 (steps 1–5, 7, 8) caught the field, OR `sha256OrNull` returned null for a `words:N` survivor (emit `{kind: withheld}` with no `h`) — a CONSTANT, so a caught PII slot reveals nothing, not even its word count. Step 6 is NOT a withholding step: it only refuses the HASH, and the token keeps its `digits`/`mixed` kind |
| 2 | `digits` | every non-whitespace character is a Unicode decimal digit (`Character.isDigit`) |
| 3 | `words:N` | split on whitespace runs; DROP any run that contains no letter and no digit (`&`, `→`, `-`, emoji-only — so `Pickup & delivery` is `words:2`); strip LEADING and TRAILING punctuation (Unicode general category P*) from each remaining run (interior apostrophes and hyphens are kept, so `O'Brien` and `Drop-off` are one run each); every remaining run must contain at least one Unicode letter and no digit (letter/digit tests are CODE-POINT based — `Character.isLetter(int)`/`isDigit(int)` — so supplementary-plane scripts count); N = the run count and must be ≥ 1 (all runs dropped → `mixed`); `words:8+` when N > 8 |

`words:1` … `words:8` are emitted only together with a hash; `words:8+` is emitted WITHOUT a hash (nine-plus
runs is body text, not chrome — step 6 refuses it) and still passes through steps 7–8, which may override
it to `withheld`.
| 4 | `mixed` | everything else — money (`$45.66`), clock times, unit numbers, gate codes, order ids, plates, symbols, emoji |

A null or blank field is OMITTED from the skeleton before any classification, never emitted —
including on a node whose id would withhold it. Money and time are deliberately
NOT classes of their own: `CurrencyShape` lives in `:core:pipeline` and the contract module may not
depend on it, and a second currency regex would be exactly the SSOT drift `CurrencyShapePinTest`
exists to prevent; a money or time slot is anchored by its id/class and siblings when a rule is
drafted. Shared client/server golden vectors for this table live with the contract's tests (published to
the server as a test-fixtures artifact, never shipped in the APK — amended in #1160); the server
verifies `kind` where it has text — on trusted envelopes (paired plaintext) and on the vectors — and
cannot re-classify a community skeleton, which carries no text. A skeleton item is capped at **65 536
uncompressed UTF-8 bytes of its serialized JSON** (the whole item, metadata included); over the cap
→ no skeleton, counted.

> **Amended 2026-11-13 (#1618).** Money, clock times, distances, minutes and item counts gain a `shape` slot kind
> from a closed list (§13, #1619); a value outside the list goes out as it does above. The objection to a second
> currency regex still holds, so the shape classifier reuses the one money definition or is pinned to it. Nodes gain
> click and visibility flags (§13). The skeleton type still has no plaintext slot; §15 adds the one path by which a
> community install sends a word in plaintext, a contributor's confirmed word on a constant slot after its gate opens.

### 2. The **chrome-likely filter** decides whether a token may even be hashed (D2)

Hash-only is necessary, not sufficient: a hash of a low-entropy value (a first name, a street) is
dictionary-attackable. Before hashing, `SkeletonBuilder` runs every text field through the SAME
SSOTs the redact side uses, in this order, and **any hit withholds the hash**:

1. the node id ends with a row of `CustomerTextMarkers.ID_MARKER_TABLE` (a view id whose value is PII by
   construction) — ONE list since review round 16: the runtime rows (`ID_MARKERS`, `runtimeScrub =
   ALWAYS`) and the former intake-only ids as CONTENT rows with `runtimeScrub = NEVER`;
2. length > 40 characters — **this cap runs before any pattern in this list**, so every step below
   runs on bounded input on the device (the #803 instruction-body class never hashes);
3. `CustomerTextMarkers.unredactedMarker` hits;
4. `PiiShapes.customerLeadIn` hits (the intake prefix rule, incl. `GATED_NAME_PREFIXES`);
5. the value CONTAINS a mask ANYWHERE — `PiiShapes.containsMask`, one shared predicate covering every
   mask the redact side and the corpus intake emit, matched as a substring so composite values such
   as `Apt [redacted]` or `For [redacted:eacf]` (both `words:N` after punctuation stripping) are
   caught — the corpus has both shapes and they are vectors (`[redacted…]`, `[address]`, `[email]`, `[phone]`, `[card]`,
   `[note]`, …) → `withheld`; a mask must never be hashed (`[address]` would otherwise strip to
   `words:1`), and the predicate is what §7(e) asserts against;
6. `shapeKind` is not `words:N` — **only `words:N` tokens are ever hashed**. This step disables the
   hash and CONTINUES: steps 7–8 still run on a `digits`/`mixed` token, and any withholding match there
   overrides the emitted kind to `withheld` (an id-less `Apt 12` is `mixed` by shape but the `APT`
   pattern withholds it — a required vector). A `digits`/`mixed` token that no later step catches is
   emitted with that kind and no hash. The `kind`
   grammar is the digit backstop: any run containing a digit makes the token `digits` or `mixed`, never `words:N`, so gate codes, PINs, unit numbers and phone fragments emit `kind` only by construction
   (a future grammar change that lets a digit into `words:N` must re-add an explicit digit rule);
7. the id-less name shape. `PiiShapes` owns ONE pattern BODY (`FIRST_LAST_INITIAL_BODY`) and derives
   two variants from it: the existing ANCHORED whole-value pattern (`^…$`, `FIRST_LAST_INITIAL_PATTERN`,
   byte-SSOT with the redact side and unchanged) and a BOUNDARY-DELIMITED substring variant
   (`(?<![\p{L}])…(?![\p{L}])`, `FIRST_LAST_INITIAL_EMBEDDED`). Step 7 withholds when the anchored
   pattern matches the WHOLE value OR the embedded one occurs anywhere (`PiiShapes.hasNameShape`,
   `IGNORE_CASE`); in the embedded variant the INITIAL alone is case-sensitive (`(?-i:[A-Z])`), because a
   case-insensitive initial reads the English words "a"/"i" as initials and withheld every `<word> a
   <word>` chrome phrase ("Take a photo", "Report a problem") — amended in #1160; a lowercase whole-value
   name ("jordan t") is still caught by the anchored arm — merely searching the anchored pattern would still reject
   `Jane S is waiting at the door` (the anchors survive `containsMatchIn`). That sentence, with no
   lead-in prefix, must emit `{kind: withheld}` — a required vector; over-withholding a chrome sentence
   is the accepted cost;
8. any other promoted `PiiShapes` pattern — each keeps the match mode `SnapshotRedactor` uses today
   (`BARE_STREET` whole-value, the others substring), pinned by the byte-SSOT tests. It runs on every
   token that reached it (step 6 does not terminate), so the digit-bearing shapes (street number, ZIP,
   apartment, PIN, phone, card) withhold a `digits`/`mixed` token and the letter-only shapes (quoted
   note, city/state, email's local part) withhold a `words:N` one. The list stays the one `PiiShapes`
   owner — no census-specific subset is hand-maintained.

**Frame-level duplicate rule** (amended in #1160). The corpus intake's replacements are DOCUMENT-WIDE
(`SnapshotRedactor.redact` rewrites every occurrence of a value it masks), so the per-field filter alone
would under-withhold: a customer name withheld on the node whose id marks it would be hashed on an
id-less parent that repeats it. `SkeletonBuilder` therefore runs two passes. Pass 1 runs the per-field
filter over every text field of the frame (tree + window title) and SEEDS:

- the exact canonical value of any field a VALUE-judging step caught (3, 4, 7, 8 — not the length cap,
  whose duplicate is itself over-length);
- from step 1, ONLY on an identity id's TEXT / CONTENT_DESCRIPTION (never its
  role/hint/tooltip/click-label/uid/pane), by the id's KIND in `CustomerTextMarkers.ID_MARKER_TABLE`:
  a **NAME** id — reserved for ids whose value is ONLY ever a person's name (`customer_name`,
  `order_cx_name`) — seeds its exact value AND its maximal letter runs of at least 2 letters
  (counted in code points, case-folded with the one `CaseFold`: `ẞ` → `ß`, then upper- and lower-case
  ROOT, final sigma to medial) — from its TEXT when the text is non-blank, otherwise from its
  CONTENT_DESCRIPTION (review round 7); an **ADDRESS** id (`address_line_1/2`,
  `arriving_at_title`, `address_subpremise_line`) seeds its exact value ONLY — address vocabulary
  ("Road", "View", "San", "Lane", "Way") is common English, and seeding its runs would withhold "View
  details" or "Road closed" chrome, and camelCase ids like `roadNameLayout`, on every frame with an
  address, while a person's name rarely collides with chrome; a **CONTENT** id (the free-text
  instruction bodies; `description_text_view`, generic DoorDash chrome such as "Raise to 50%") seeds
  nothing; an **EXACT** id — a value that may be chrome (`tvTitle`, `tvLastMessage`: the chat header is a
  customer's name and the preview their text, but the same generic id titles other sheets, "Pick up
  order") — seeds its exact value only, so a duplicated first name is still caught (review rounds 8,
  10); a **PERSON_OR_MERCHANT** id — a REUSED id that is a person or a merchant but never chrome
  (`user_name`, which also carries the merchant's and the dasher's own name) — seeds its exact value, and
  its letter runs (≥ 3 letters) only when the value READS AS A PERSON'S NAME: at most TWO tokens, each a
  letter-only Capitalized word of ≥ 2 letters (an apostrophe allowed; no hyphen or digit), optionally
  followed by ONE trailing capital initial ("Riley S", "Mary Jo S." — the run floor keeps the initial
  itself from seeding). "Text Riley" beside `user_name` "Riley" is withheld exactly as beside `customer_name`, while
  "In-N-Out Burger", "Sonic Drive-In", "7-Eleven", "Jack in the Box" and "The Home Depot" seed their
  exact value only (never `in`/`out`/`the`, which would withhold "Sign in" and turn `sign_in_button`
  into `~` per merchant). A name-shaped merchant ("Wing Stop") seeds runs too — accepted: a merchant word
  is never a recognition anchor, and its collision with chrome is residual risk 9 (review rounds 12, 13). A NAME seeds runs from its text, so a TalkBack-style desc "Customer name Adam" beside text "Adam"
  seeds no `customer`/`name`, while a name rendered only in a desc still propagates — and a NAME whose
  text is a mask or has no canonical form takes its runs from the desc. What a kind seeds is owned by
  the kind table itself (`IdentityKind.seedsExactValue` / `maxRunSeedTokens` / `minRunLetters` /
  `personNameShapeOnly` / `runsGuardClasses`), and the source rule (usable text, else usable desc) by
  `SkeletonBuilder.nameRunSource`, which the builder and the test mirrors both call — it feeds BOTH the
  letter runs and the whole-value id seed, so a TalkBack label-only desc ("Customer name") beside text
  "Adam" never seeds `customername` (review rounds 11–13). For the ID check only, a WHOLE-value run (case-folded, code-point
  letters only, ≥ 3 letters) is matched against every contiguous join of the id's camel segments ACROSS
  separators (`row_mary_jo`, `chip-mary-jo`, `rowMaryJo` beside "Mary Jo"), contributed only by the
  `idProtect` rows — values a test tag can plausibly embed (review rounds 10, 11 — no name-shape gate:
  fail closed):

  | Row | Whole-value run for ids |
  |---|---|
  | `customer_name`, `order_cx_name` (NAME) | always ("Mary Jo" nulls `chipMaryJo`), beside its letter runs |
  | `user_name` (PERSON_OR_MERCHANT) | always, a single token included: "Riley" nulls `chipRiley`; "Jack in the Box" nulls `jackInTheBoxLogo` (recall cost), never `boxView` |
  | `tvTitle` (EXACT) | always: "Riley Smith" nulls `chipRileySmith`; a one-word chrome title "Search" nulls `search_bar` on its frame (recall cost) |
  | `tvLastMessage` (EXACT), every ADDRESS and CONTENT row | none — a chat reply "Ok" must not null `ok_button` or move the fingerprint per message; street vocabulary is common English |

  Text slots never use the whole-value rule. A CLASS name is third-party-set (the mapper copies it and the
  grammar checks only its syntax), so it is checked against customer-name runs ONLY — the runs of a
  class-guarding kind (NAME: `com.x.RileyButton` beside `customer_name` "Riley" is absent) — never a
  title or merchant word (a `SearchView` class beside `tvTitle` "Search" stays), and never a KNOWN
  framework class — an exact binary name in `FrameworkClasses.KNOWN`: the pinned inventory
  `core/pipeline/src/main/resources/census/framework-classes.txt` (review rounds 15–16: every public
  `android.view.View` SUBCLASS — the `super_class` chain followed across the classpath — under
  `android.view.`/`android.widget.`/`android.webkit.` in the SDK `android.jar` and under `androidx.` /
  `com.google.android.material.` in the RELEASE runtime classpath (`:app:censusReleaseClasspath` —
  debug-only artifacts never exempted); 233 names, ~10 KB, plain text under a `#sha256=` header; any
  name the class grammar rejects excluded — regenerated and diffed by `FrameworkClassInventoryTest`; the
  loader is strict (malformed UTF-8, a header/body sha256 mismatch such as truncation, or an invalid entry
  discards the whole resource, which only shrinks the set)) ∪ every framework-prefixed class the committed corpus renders (pinned by a corpus
  guard) ∪ the wrapper set and Material's `Chip`, so
  "Chip" never nulls `…material.chip.Chip` and forks the fingerprint per customer, and wrapper
  eligibility cannot depend on the customer. A framework PREFIX is not proof: an app can name its own
  class `androidx.RileyButton`, and any unlisted class is judged like an app class (review rounds 11–14).
  The commit-path intake list IS this table (`ID_MARKER_SUFFIXES`, review round 16): the intake-only
  ids (message bodies, maneuver/road text, instruction bodies) are its CONTENT / `NEVER` rows (review
  rounds 6–9, 16);
- a MASK never seeds anything (`[redacted…]`, `[address]`, …): it is not identity, and its word would
  collide with chrome and with address-block ids.

Pass 2 emits the constant `withheld` for every field whose canonical value is a seeded exact value,
wherever it sits, and — by TOKEN CONTAINMENT — for every field containing a NAME run: `customer_name`
"Adam" withholds an id-less "Adam's order" or "Adam, 2 items" (which pass steps 3–8), while "Add a tip"
beside it still hashes. The containment rule applies to text slots AND to the id of every node on the
frame (and, for customer-name runs only, its class — see above); ids and classes are split into runs ALSO at camelCase boundaries (lower→Upper, and
Upper→Upper+lower: `XMLAdam` → `XML` + `Adam`), because Compose test tags are usually camelCase, while
text slots keep the plain letter-run split; a seed is tested against every CONTIGUOUS concatenation of
the camel segments ACROSS separators (the singles, the joins, the whole name part), so a name with
internal capitals or split by a separator still matches — `chipMcKenna`, `row_mc_kenna` → `mckenna`
(amended in #1160 review rounds 6, 12). A static id carrying a seeded run is replaced by the reserved
sentinel id `~` (`ResourceIdGrammar.FRAME_WITHHELD_ID`, §8) on the wire and in the fingerprint — `chipAdam` / `chip_Adam` beside `customer_name`
"Adam" does not travel, `chipGold` and `chipAdamant` (whole-run equality) do (one owner,
`FrameFilter.containsIdentityRun`). A CONTENT id (including the intake-only rows, which cover
instruction BODIES — `step_description`, `instruction_text`) withholds its OWN field but
seed nothing, so the chrome vocabulary the census exists for is not withheld frame-wide (amended in #1160
review rounds 2–5). An EXACT id (`tvTitle`, `tvLastMessage`) DOES seed its exact value, so an id-less
duplicate of a chrome sheet title ("Pick up order") on the same frame is withheld too — duplicate-chrome
suppression is the accepted trade, because exempting chrome-shaped values would also exempt a bare
customer name ("Adam") in the same slot (review round 8). §7(c) compares the skeleton against FULL-TREE redaction for this
reason, with exactly that one stated exemption: a value the intake rewrites document-wide only because a
non-seeding PII-id field carries it elsewhere, which survives redaction in isolation and has no other
withholding cause anywhere in the frame.

**What the builder consumes.** `SkeletonBuilder` takes the RAW admitted `UiNode` tree plus the
window title. #1146's publisher sits on the UNKNOWN SCREEN BRANCH of `AccessibilityPipeline.output()`
— after the rulesets-loaded / sensitive / disabled-platform gates and `FrameGate.admit`, and BEFORE
the terminal filter that keeps UNKNOWN observations away from the state machine (placing it after
that filter would receive nothing). It never consumes the capture DTO — the release build binds
`NoOpCaptureBus` and `captureScreen` returns before its scans run, so the census must work in the only
build that uploads. The
corpus tests feed fixture trees that ARE masked captures; that is a superset condition (every mask
token is caught by step 5 and emits `withheld`), not the runtime shape.

**Inputs and predicates, exactly.** Every step sees the CANONICAL value — glyph-folded, trimmed and
whitespace-normalized (`CensusHash.canonical`: first the census glyph fold `TextFold.foldForCensus` —
`Character.FORMAT` stripped by code point FIRST, then NFKC, then the Unicode-dash fold — so a zero-width
or tag character inside a marker, or a fullwidth letter, cannot defeat steps 3/4/7, and a fullwidth
chrome word hashes equal to its plain twin (amended in #1160 review rounds 6–7); then every run of code points the classifier treats as whitespace,
`Character.isWhitespace || isSpaceChar`, collapsed to one ASCII space. The canonical form is a FIXED
POINT — `canonical(canonical(x)) == canonical(x)`; stripping FORMAT before NFKC lets a combining mark
hidden behind a zero-width joiner compose in the first pass; the pass is applied at most 3 times and the
value is canonical when a pass leaves it unchanged; otherwise it has NO canonical form (`canonical`
returns null) and is withheld, and an id without one is not static (review round 8). The builder HASHES the canonical string it JUDGED
(`CensusHash.ofCanonical`), never a re-canonicalized one (review round 7). Amended in #1160 because the JVM's
regex `\s` excludes NBSP/thin space while ICU's includes `\p{Z}`, so the decision was engine-dependent) —
the same bytes `CensusHash` hashes — so a leading space cannot slip a prefix past a `startsWith`. Every
value-judging step (3, 4, 5, 7, 8) runs on the canonical form AND — when the raw trimmed value differs
and is itself within the 40-character cap — on the raw trimmed value; either hit withholds. The cap
(step 2) is decided by the CANONICAL form alone, so wide-spaced chrome is not capped on its padding and a
padded "Deliver  to  Sam" is still caught (and seeds); the raw pass keeps every pattern on bounded input.
A value PROVABLY over the cap — more than 4 × 40 non-FORMAT, non-whitespace code points, 4 being the
longest canonical decomposition NFKC can compose into one code point — is capped without folding at all,
so a multi-kilobyte body never runs the fixed-point fold (review round 11; both premises of the bound are
checked over every code point by a unit test).
Classification and hashing use the canonical form, and the frame-wide duplicate set is keyed by it
(amended in #1160 review round 4 — canonicalization alone can shrink a value below a pattern's minimum:
`"ab  cd"` is a quoted note raw, `"ab cd"` is not). Step 1 uses the
ONE predicate: `CustomerTextMarkers.idMarkerFor`, a case-insensitive SUFFIX match on the full
resource id against the whole table — the runtime scrub, the census and the `SnapshotRedactor` intake
all use it (review round 16 retired the intake's separate exact-last-segment list, a widening toward
privacy that moved no committed fixture).

A `SensitiveTextMarkers` hit anywhere on the frame → **no skeleton at all** (the dasher's banking
surfaces are blocked, never described). `SkeletonBuilder` invokes the shared sensitive-marker scans
ITSELF — on the raw tree (`findMarker(tree)`) and on the window title — regardless of the capture
bus's state, because `captureScreen`'s scans do not run in release; the bounded-input claim above is
about the per-field census filter, not this whole-frame scan.

**Module homes.** `SkeletonBuilder` lives in `:core:pipeline`, because steps 1, 3 and the frame drop
need `ID_MARKERS`, `CustomerTextMarkers` and `SensitiveTextMarkers`, which live there and which
`:domain` may not depend on. The wire contract — `UiSkeletonDto`, `SkeletonSchema`, `CensusHash`,
the fingerprint — lives in `:domain` or the Apache-2.0 contract module (open question 1). The
promotion to `:domain` (`privacy/PiiShapes.kt`) covers EVERY test-only pattern the filter uses:
`FIRST_LAST_INITIAL_PATTERN`, the shape patterns, `NAME_PREFIXES`, `GATED_NAME_PREFIXES` and
`customerLeadIn()`; `SnapshotRedactor` delegates to it with byte-SSOT pins (its PII-id list is the
`ID_MARKER_TABLE`, review round 16).

**A withheld field emits the constant `kind: withheld` — no length, no word count, no hash.** A
length or a word count is a small leak on a name and would break invariant 7 (pseudonym invariance); §1 carries no geometry at all for the same reason. Because `PiiShapes` is the one owner on both sides, the census filter and the corpus intake
can never drift apart.

### 3. Hashing: unsalted sha256 with a domain-separation prefix

`CensusHash.of(text) = sha256("census.v1:" + canonical)`, first 16 hex, where `canonical` is the
trimmed, whitespace-normalized value (§2 "Inputs and predicates"), through the fail-closed
`sha256OrNull` (a failure withholds; plaintext is never echoed, #362). Unsalted because
**cross-install equality is what the k rule needs**. The `census.v1:` prefix separates digest
domains — a census hash can never EQUAL a parse-side `customerNameHash` (sha256 of the normalized
`customerNameKey`) or a redact mask (`[redacted:<4hex>]`, the stripped token, optionally
`normalize: customerName`-normalized, no prefix). It does NOT make the domains unjoinable: an operator
holding both can enumerate candidate strings, hash them in every domain and link low-entropy values
through that dictionary, including after unblinding. That is residual risk 1, and it is why the
filter withholds low-entropy PII shapes before hashing rather than relying on the hash.

**Amended 2026-11-13 (#1618): keyed at rest.** The phone's hash is unchanged: unsalted, so every install agrees. The
server replaces every word hash with `HMAC(pepper, h)` at ingest, before it writes it anywhere (token sightings,
stored skeleton samples, constancy tables, confirmations); see §21 (sjtrotter/dashbuddy-census#85). Values derived
from word hashes are keyed the same way wherever they are stored: today the notification fingerprint folds the title and
text hashes into a public digest (`CensusFingerprint.kt:63`), so a stored notification fingerprint, and every reference
to it, is keyed at rest too. Only when every stored text-derived value is keyed can a stolen database or backup no
longer be guessed against offline. The live operator holds the pepper, so residual risks 1 and 7
stand against the operator.

### 4. **k-anonymity on strings** is a read-time gate on the server (D3)

A token one install rendered may be somebody's customer; a token many installs rendered is a
CANDIDATE for chrome — frequency is evidence, not proof. There are TWO named predicates, and every
read says which it uses:

- **`k_unblind`** — a hash may be resolved to text only from a **trusted install's own envelope**
  (invariant 6) or the operator's locally held trusted captures, never by asking a contributor. For
  a trusted install this is k = 1: the operator's phone alone resolves every hash it has itself
  rendered, which is what lets the whole loop run on one phone. The result is the operator's
  **working vocabulary**: visible to the operator only, usable for drafting and reviewing a rule,
  NOT the shipped allowlist.
- **`k_ship`** — a working-vocabulary token is promoted to the SHIPPED chrome vocabulary (the
  allowlist that rides the signed bundle and permits clear-text sending in `skeleton.v2`) only once
  **≥ 10 distinct community installs, past a 7-day quarantine and excluding trusted installs** (open
  question 2), have shown the same hash AND a human has classified it as chrome. k = 10 is a
  distinct-install FREQUENCY threshold, not proof of ten independent people and not proof that a
  token is chrome (a common first name recurs across ten honest installs; one party can age ten
  client-minted identities through the quarantine — residual risk 5). Crossing k creates a review
  candidate; the human classification is the gate.

**Publication is irreversible for a bundle.** A read-time k gates community observations and NEW
promotions; it cannot retract a token already shipped in a signed bundle — a client sends clear
tokens according to the list it holds, and the server accepts a clear token that is on the list of
ANY bundle version it still supports. A later cohort decline therefore stops new promotions and
stops serving sub-k aggregates; it does not un-ship a token. Removing a shipped token is a bundle
release (the same path as removing a rule).

The server keeps per-hash distinct-install counts with a rolling TTL. Sub-k hashes are counted for
**30 days after their last sighting** and then deleted from every persisted copy; they are **never
listed, never exported, never logged in bulk**. Each predicate is evaluated on every read that uses it,
never stored as a flag, so a cohort that shrinks below k simply stops being served — a dip degrades
availability, never privacy. Counting is per `(hash, install)` row: **every token-sighting row expires
30 days after THAT install's last observation of the token, regardless of the hash's current k**, and
reads exclude expired rows before counting, so one continuing install cannot keep nine stale
contributors alive.

Unblinding populates the operator's **working vocabulary** only. The **shipped chrome vocabulary**
— the allowlist that rides the signed rule bundle (#641) and, from `uinode.skeleton.v2` on, lets a
client send an allowlisted token in the clear (enforced client-side by the bundle's list and
server-side by rejecting any clear token not on it) — admits a token by exactly two routes: `k_ship`
above, or the token appearing VERBATIM as an anchor in a rule the bundle already ships (already public
by construction). An operator-attestation route into the shipped list is rejected (it would bypass
k); #1157's plan §4.3 is superseded where it differs. Trusted drafting reads use `k_unblind`;
community observation reads (dashboard, export) require the live community threshold.

**Amended 2026-11-13 (#1618).** "Never by asking a contributor" is superseded. A hash may also be resolved to text
by the contributor word gate (§15): a contributor's confirmed word on a constant slot, sent only after at least 2
independent confirmations and k installs. A word that passes the gate joins the working vocabulary ("approved
chrome", #1628); it does not enter the shipped vocabulary, which still needs `k_ship` and a human classification. The
trusted-envelope source of `k_unblind` closes on the official server once #1633 ships (§6, §12).

### 5. Consent is a per-feature switch, default OFF, with a ledger (D4)

Census upload is its own feature consent (the #1151 pattern — a preference, never a capability
grant): default off, a prominent in-app disclosure that is also the Play compliance artifact, a live
counter ("N skeletons sent, last at …"), a local viewer of the last N skeletons exactly as sent, a
Wi-Fi-only option, per platform, and a "delete my census data" action. Its store is the ninth
DataStore, `census_prefs`.

**Withdrawal and deletion contract** (epic acceptance criterion 4): turning the switch off cancels
pending upload work, stops further sends within one upload cycle, and purges the local queue and the
local viewer's copies. "Delete my census data" is an authenticated request that removes every
install-keyed row (installs, sightings, health, ingest ledger, trusted envelopes) within 24 hours;
what survives is disclosed on the same screen: cluster samples and fleet aggregates carry no install
id and cannot be attributed back, and the 14-day encrypted backups age out on their own — a restore
re-runs the deletion log before serving. The disclosure copy, the LEGAL.md paragraph and the Data Safety form
delta are written in M3 against the screen they belong to (moved out of M0 by dev decision
2026-09-29) — nothing leaves the phone before then, so nothing is disclosed before then.

**Amended 2026-11-13 (#1618).** The Wi-Fi-only option was promised here and never built: the upload worker's only
network constraint is `NetworkType.CONNECTED` (`CensusWorkScheduler.kt:59-62`). #1620 builds it, on by default.
"Delete my census data" was not built either: the phone sends no deletion request today, and resetting the census
identity orphans the old server install (`CensusIdentityResetter.kt:25`). It is built as its own button, separate
from stopping, by #1622 (server: sjtrotter/dashbuddy-census#79), and the on-phone wipe calls it (#1579). The local
viewer is #1622's "What gig.log sent" screen. The disclosure for the Play and sideload builds is #1621, the LEGAL.md
paragraph is written by #1618, and the Data safety answers are #1623. The deletion contract above extends to
confirmations, grants, the quarantine and epoch-tagged rows (§15, §21).

### 6. **Trusted installs** are the unblinding source (dev amendment 2026-09-29)

A trusted install is a device the operator owns (the dev's phone; later, any self-hoster's phone
against their own server) **running a capture-enabled build** — the redacted envelope it uploads
exists only where a real `CaptureBus` is bound, and every release build binds `NoOpCaptureBus`
(`captureScreen` returns before building anything). A release build therefore has no envelope to upload
even if its key were trusted. Trust is the operator's setting on the server, made with the operator's TOTP code;
enrolment declares no capture capability and the server checks none (corrected by #1522). Capture upload is gated
in the app: only a debug build binds the envelope sink, and release binds the no-op. A
self-hoster runs the same debug/dev flavour the developer does. It is enrolled explicitly — a per-install key marked trusted server-side
and a dev-settings switch on the client that is never reachable from the consent screen. A trusted
install uploads the **existing redacted capture envelope** for UNKNOWN frames (today's
`captureScreen` order: sensitive drop → rule redact → customer text + id scrub) beside the skeleton
(superseded by the 2026-10-04 amendment below) so the two can be paired by the envelope's `captureId` (unique per capture — the census `fingerprint`
is a CLUSTER key shared by many frames and must never be the pairing key, or one frame's hashes would
resolve against another frame's text);
a trusted envelope or a trusted capture the operator already holds locally are the only sources from
which a hash is ever resolved to text. For a trusted install k = 1 by definition, so the whole loop
runs on one phone with zero community contributors — the fleet adds speed and coverage, never a
precondition. Community installs never carry plaintext; the server rejects a clear-text envelope from
a non-trusted key.

**Amendment 2026-10-04 (#1200):** The shipped trusted envelope is paired to its CLUSTER by the
skeleton `fingerprint` the phone computed for the same frame. The server presents it ONLY as one
sample frame of that cluster: its own text in its own boxes (the wireframe). It is NEVER used to
resolve another frame's withheld slots to text: two frames in one cluster can differ exactly in
their withheld customer slots, which is the misattribution the original sentence guards against.
`captureId` stays in the envelope so per-frame pairing remains possible. The transport projection
drops `deviceFingerprint` and `rulesetSignature` and coarsens the timestamp to the hour. Node
`bounds` remain and imply the screen resolution — a device-class signal the skeleton omits,
accepted for the operator's own device.

The trusted path reuses the existing scrubbed PAYLOAD, not the existing metadata:
`ReplayMetadataProviderImpl` supplies `deviceFingerprint` (`Build.FINGERPRINT`) and `EnvelopeBuilder`
copies it and stamps a millisecond timestamp, so the trusted transport (M3) applies ONE projection at
the transport boundary — drop the device fingerprint, reduce the timestamp to the documented
precision (hour in flight, day at rest) — before anything persists; the on-device capture path is
unchanged. And the promise is scoped honestly: **the
operator CAN read a trusted envelope's text, including any customer detail the redact and marker
layers missed** — that is the operator's own device and the same residual the pull directories carry
today. The "operator cannot see what you typed or whom you delivered to" statement applies to
community installs, whose payload has no text slot. The residual
is the one the project already accepts on disk (a marker backstop can miss an UNKNOWN-path value),
so trusted envelopes are swept manually and stored under the same retention the pull directories get
(30 days, earlier once a fixture or rule exists).

**Amendment 2026-11-13 (#1618, #1633).** The trusted tier stays the operator's own phone on the debug build, and it
keeps the envelope path above for bring-up only until #1633 ships the first rule drafted from release-build evidence.
After that the official server accepts no plaintext envelope from any build, so all three builds (Play, sideload,
debug) share one collection floor, and the developer's own diagnosis of the debug build stays local, over adb.
"The whole loop runs on one phone" then no longer holds on the official server: drafting reads skeletons, approved
chrome, shapes and role tags (§13 to §15). A self-hoster's own server is not bound by this. The contributor tier
(§15) is separate from trusted: a contributor never sends an envelope or screen text, and contributor status never
grants either. "Community installs never carry plaintext" now has one exception, the gated contributor word (§15).

### 7. **Pseudonym invariance** is the structural property the tests pin

For every fixture with a hand-pseudonymized twin, `skeleton(raw) == skeleton(pseudonymized)`: a PII
token the filter catches can never change what leaves the phone — the guarantee is equality under
**shape-preserving substitution in known PII slots** (those slots emit the constant `withheld`), and
the residual for an uncaught value is stated in risk 6. The equality holds for the PII slots
THEMSELVES; a CHROME slot that shares a letter run with an identity value is withheld only while that
value is present — identity-dependent chrome suppression, the cost of the §2 token-containment rule, and
why a common first name such as "May", "June" or "Will" suppresses same-word chrome frame-wide
("May need returns" is withheld beside `customer_name` "May" and hashes beside "Sam"; risk 9, amended in
#1160 review round 5). The corpus substitutions of test (b) use pseudonyms that share no run with the
chrome, so (b) asserts whole-skeleton equality there. `SkeletonCorpusTest` (#1145) walks the ENTIRE corpus
including `SENSITIVE/` and `UNKNOWN/negative/` and asserts (a) no string field outside the §1 allowlist (per node `class`/`id`/`kind`/`h`; per envelope the
enumerated metadata) and no bounds on any node; (b) invariance under two shape-matched pseudonym
substitutions; (c) redactor parity — any value `SnapshotRedactor.redact` changes has no `h` (load-bearing only on
the pseudonym and decoy fixtures: on an already-redacted committed fixture `redact` is idempotent); (d)
every `SENSITIVE/` fixture the markers catch yields no skeleton; (e) no `h` equals `CensusHash.of(x)` (canonical, as the builder hashes) for any PII-VALUED
`CorpusDecoys` entry (pseudonym names, addresses, notes — not retained chrome labels such as
`"Hand it to me: "`, which are legitimately hashed) or for any mask token; (f) determinism and idempotence. A seeded property (#878) adds:
no string ever hashes whose canonical form — or whose raw trimmed form, when that is within the
40-character cap (the bounded raw pass, §2) — matches any `PiiShapes` pattern in substring mode (as steps
7/8 run them; a raw match beyond the cap does not prevent hashing by design — amended in #1160), and
no output contains an input token verbatim outside `class`/`id`.

### 8. Bounded, budgeted, deduplicated (D5)

Only frames that pass today's gates reach the census: rulesets loaded, sensitive/noise dropped,
disabled platform dropped, `UnknownSuppressor` dedup. On top: a per-install daily budget (300
skeletons), a per-cluster cap, a bounded on-disk queue (drop-oldest), batch upload with backoff, a
64 KB size cap per skeleton (over → no skeleton, counted). Clicks and notifications were OUT of v1
(the click envelope is the #919 leak class; a notification body is free text) — §10 (amendment
2026-10-06, #1189) brings NOTIFICATIONS in under the same grammar and keeps clicks out until a reliable
screen-fingerprint association exists.

**Amended 2026-11-13 (#1618).** On top of the budgets above: one skeleton per layout and format variant per UTC day,
kept as presence, with a local count that never leaves the phone, and never de-duplicated by text hashes, which
would sample per customer (#1620). The skeleton `day` becomes a UTC day; today it is the phone's local date
(`SkeletonPublisher.kt:67`), a slow time-zone signal (#1619). Uploads run once a day at a random time, never on a
recognition trigger (§21). Clicks come in as references into a screen's skeleton (§19, #1634).

**Cluster fingerprint** (`CensusFingerprint`) is a NEW function in the contract module, not
today's `stableHash`: `stableHash` is a 32-bit `Int` (`31 * h + child`, collidable, and the type of
`FrameGate.admit(contentHash)` and of every fixture's `contentHash`), which the server cannot
recompute safely and which must not change type. The fingerprint is a full sha256 (**64 hex**) over ONE canonical byte form, pre-order, every string
LENGTH-PREFIXED so the encoding is prefix-free and therefore injective (amended in #1160 — a
delimiter-only form let a value that mimics the framing collide two different trees): per node `"C"` +
the class's UTF-8 byte length as ASCII decimal + `0x00` + the class UTF-8 (`""` when null) + (`"I"` + the
id's byte length as ASCII decimal + `0x00` + the id UTF-8, or the single byte `"N"` when the id is null —
so a null id and an empty id differ) + the spliced child count as ASCII decimal + `0x00`, then the
children in order. class/id must be well-formed UTF-16 with no U+0000 (`WireStrings.isWellFormed` — a
lone surrogate would otherwise UTF-8-encode as `?` and collide); a violating class or id is refused at
construction and on decode. In the builder the two differ (review rounds 12, 13): a malformed CLASS
carries no identity semantics, so it is emitted ABSENT (null never enters the byte form, so the encoding
stays injective) and counted (`Outcome.Built.malformedClass`) — one bad node must not blind a surface;
a malformed VIEW ID refuses the frame (`INVALID_TREE`), because its identity classification cannot be
verified (`customer_name` + U+0000 misses every suffix lookup and would ship the name it marks). The id is a static resource name
OR the one reserved non-grammar value `~` (`ResourceIdGrammar.FRAME_WITHHELD_ID`): a static-shaped id the
builder withheld — by the §2 FRAME rule or by the frame-free PII judgement of the id itself (§1) — it keeps the node a non-wrapper, so the tree STRUCTURE never
depends on the customer on the frame; a grammar-rejected (dynamic) id stays null, since it is rejected
identically on every frame. The server accepts `~`. Fingerprinting happens AFTER that frame-level
withholding, so a colliding id (`mark_read_button` beside `customer_name` "Mark") still moves the
cluster key by that one id — fingerprinting the pre-withholding structure would break the server's
recompute-from-the-wire rule; the sentinel keeps the structure stable, and the single-id difference is
residual risk 9 (review round 12).
Wrapper transparency is judged on the WIRE id: a container whose only id was dynamic (or empty) arrives
with a null id and IS spliced — a deliberate divergence from `stableHash`, which sees the raw id; the
server can only recompute from the wire tree (amended in #1160). Transparent wrappers are removed first (wrapper-to-forest
normalization): a wrapper's children are spliced into its parent, an EMPTY wrapper contributes
nothing (the parent's count drops), and the normalized forest ALWAYS hangs under one synthetic root
(class `""`, null id, the spliced count) — whether or not the original root was a wrapper — so
`fingerprint(A) == fingerprint(W(A))` holds; that equality is a required vector. The contract's tests hold vectors
for: null vs empty id (on the pure byte function — an empty id can no longer enter a DTO), an empty wrapper, a multi-child wrapper, a wrapper root, and the two nesting
cases below. It keeps ONE
structural rule of `stableHash` deliberately: an **anonymous wrapper** (no id AND a class in
`{android.view.View, android.view.ViewGroup, android.widget.FrameLayout, android.widget.LinearLayout}`)
is TRANSPARENT: it contributes no node of its own and its children are SPLICED into its parent's
child sequence in order (`A(W(C1, C2)) == A(C1, C2)`; the parent's child count is the spliced count),
because a Compose recomposition adds and removes such wrappers and the cluster must not split on
them; every other boundary is preserved (`A(B(C)) ≠ A(B, C)`). This is the census's OWN rule — today's
`computeStableHash` folds a wrapper's children as a nested group and never splices, so the two
algorithms are deliberately different; only the wrapper CLASS SET is shared, through one constant owned
by the `census-contract/` build (`cloud.trotter.census.contract.AnonymousWrappers`, #1173).
`stableHash` and `UnknownSuppressor` are untouched. (The corpus librarian's variant check is a TEXT fingerprint and
`FrameGate`'s identity is `Observation.identity()`; neither is touched — a structural key would
collapse the librarian's store-distinct variants.) The server RECOMPUTES the fingerprint from the
skeleton it received rather than trusting the client's (#1157), which is why the wire contract must
be consumable by an AGPL server (open question 1).

### 9. Rules are **proposed**, never auto-pushed (D7)

A cluster that crosses a threshold yields a shape bundle → a drafted rule + synthetic fixture → a
matchers PR → the existing CI gates (`AllMatchersSuite`: golden, negative corpus, sensitive-screen
invariant, redact parity) → **human approval** → signing (the runtime signature gate exists today —
`RulesetVerifier`, #416; #641 adds the CI signing step) → OTA fetch (#640). Over-match forges
state and a wrong redact ships PII to every phone; both are silent in the field and caught only by
the corpus plus a reader, so the human gate is structural. The authoring-model consequence is
recorded as the ADR-0009 amendment.

### 10. Notification skeletons (amendment 2026-10-06, #1189 — APPROVED)

**Why.** UNKNOWN notifications carry lifecycle signal the screen census never sees (an offer push, an
arrival, a pause, an earnings deposit) and are the cheapest surface to keep rules current on — but v1
excluded them because a body is free text. Fielded evidence (local pulls May→Oct 2026, 691 UNKNOWN
notification envelopes, 597 DoorDash / 94 Uber): the posting channel is STABLE CHROME — 14 distinct
`(package, channelId)` pairs in five months, the main one (`dasher-notification-channel-dash-update`)
seen on 82 distinct days; titles are short (median 16 chars, 12 of 591 over the 40-character cap);
bodies are long (text / bigText / ticker median 75 chars, ~85 % over the cap). So under the EXISTING
§1–§2 grammar a notification skeleton is: a platform token, a channel id, a title that mostly hashes
as `words:N`, and a body that mostly becomes `withheld` at the 40-character gate — the same
"shape, not content" posture as a screen skeleton. No new hash grammar, no new unblinding path.

**Skeleton (the §7(a) allowlist for notifications).**
- `kind = "notification"` — a wire discriminator; the server clusters notification skeletons apart
  from screen skeletons and applies the unchanged §4 k-gate, §5 consent, §8 budgets/retention.
- `platform`: the registry token from `Platform.fromPackage(packageName)` — never the package string.
- `channelId`: the posting channel id, RAW but GRAMMAR-BOUNDED (`[A-Za-z0-9_.-]{1,64}`, else the skeleton
  is refused and counted): a channel id is an app-defined constant (chrome), the primary clustering key,
  and the one field that may be read in clear. The evidence above is the basis for calling it chrome;
  the bound is the enforcement.
- One `TextSlot` per field of `RawNotificationData.textFields()` — title, text, bigText, tickerText,
  subText — through the SAME `KindClassifier` grammar (the five-field enumeration is the SSOT, #666):
  `words:1..8` hashes with the §3 domain prefix, every other kind is a coarse kind or `withheld`.
  `actionLabels` are OMITTED in this slice (not even hashed) — a button label is where a customer name
  rode in the #1147 fielded shape.
- Fingerprint: `CensusFingerprint` over `(kind, platform, channelId, the five slot kinds + hashes)` —
  structural identity, so a title that differs only by a customer name (its slot `withheld`) still
  clusters with its siblings.

**Wire encoding (Slice 1 contract).** `notification.skeleton.v1` (schema version 1) requires the literal
`kind: "notification"`. `uinode.skeleton.v1` retains its exact byte shape with no wire `kind`; its
computed internal kind is `SCREEN`, and adding a wire kind remains an unknown-field error. Both use the
same envelope validation, strict JSON settings and 65,536-byte item limit. The common envelope is
`schemaId`, `hashDomain`, `filterRev`, `fingerprint`, `platform`, `platformAppVersion`, `appVersion`,
`rulesetReleaseTag`, `engineVersion`, `rulesetFormatVersion`, and `day`. The three version strings and
`rulesetFormatVersion` are optional. Notification fields are declared in order: `kind`, that envelope,
`channelId`, then `slots`. Defaults/nulls are omitted; the mandatory kind has no default.

The channel regex matches the **whole** value. Missing/null, empty, overlong or malformed channels
refuse the item; never trim, truncate, normalize or hash them into acceptance. `slots` contains exactly
`title`, `text`, `bigText`, `tickerText`, `subText`, serialized and fingerprinted in that SSOT order
regardless of map insertion order. Missing/blank source values become `TextSlot.WITHHELD`; there is no
empty kind. Slot kinds remain `withheld`, `digits`, `mixed`, `words:1`…`words:8`, `words:8+`, with `h`
present iff `words:1..8`, as 16 lowercase hex. Unknown keys, including `actionLabels`, fail decoding.
The existing `hashDomain = 1`, `census.v1:` token prefix, filter revision and normalization stay intact.

For notifications, define `LP(s)` as ASCII decimal UTF-8 byte length, one `0x00`, then UTF-8 bytes.
Fingerprint bytes are `ASCII("N1") + LP("notification") + LP(platform) + LP(channelId)`, followed for
each field in SSOT order by `LP(field.wire) + LP(slot.kind)` and either `ASCII("N")` for null `h`, or
`ASCII("H") + LP(h)`. One SHA-256 yields 64 lowercase hex. Metadata/day are excluded; platform,
channel case and every slot kind/hash participate. `N1` separates notifications from screen tree
bytes; all existing screen fingerprints remain unchanged. The inbound rejection vocabulary adds
`unknown_skeleton_kind`, `kind_schema_mismatch`, `bad_channel`; `bad_kind` still denotes a TextSlot
kind, and `expired` remains invalid inbound. Production server policy stays screen-only until Slice 2
storage/ops support lands.

The conformance manifest has `formatVersion: 1`, `totalRecords`, `builtBySchema`, `refusedByReason`, and
`sha256` (lowercase SHA-256 of the uncompressed, file-sorted JSONL including its final LF). Synthetic
notification wire rows use the `contract/notification/` namespace. File keys are unique; refusal rows
contain only `file` and `refused` and are never uploadable payloads.

**Refusal (fail-closed, independent of capture).** `SensitiveMarkerScan` runs over the five fields AND
the action labels (the #666 item 2c lesson: a banking marker ONLY in an action label must still refuse);
any hit refuses the whole skeleton and is counted. The scan is a publication gate of its own — it does
not rely on `CaptureWriter`, which skips a sensitive UNKNOWN capture without returning a verdict. The
`CustomerTextMarkers` lead-in scrub applies to the five fields before kind classification, as on screens.

**Where it publishes.** `NotificationPipeline`, after admission + dedup + capture and BEFORE the UNKNOWN
rejection — the same slot `SkeletonPublisher.publish` occupies on the screen path; UNKNOWN platform
notifications only; fail-open to the pipeline; debug `HttpCensusSink` only; release `NoOpCensusSink`
(inert by construction). The 300-per-day budget is SHARED with screens (one budget, one spool).
Amended 2026-11-13: release and sideload bind the real sink once #1620 ships (§12); #1619 adds the action list to
notification skeletons, and nothing in it travels in clear.

**Clicks stay OUT.** A click skeleton must cluster under the SCREEN it happened on, which needs the
screen's census fingerprint at click time; today `Observation.Click` carries only `screenRuleId`, and the
classifier caches rule/target per platform — not a fingerprint — so an association could be stale across
a window change. Clicks return as their own amendment once the app computes and carries that fingerprint.
Superseded 2026-11-13 by §19: click evidence travels with the skeleton of the screen it happened on, as a reference
into it (#1634).

**Residuals (added to the list below).** A channel id is read in clear: a platform could, in principle,
encode a per-user value in a channel id — the grammar bound and the k-gate (a singleton channel never
unblinds) are the controls, and the evidence shows app-constant ids. Bodies over the cap lose their
distinction (`withheld`, because the length gate runs before classification) — accepted: the channel +
title carry the clustering signal.

**Contract / server / app order.** This section was approved and merged first; next a versioned
`NotificationSkeletonDto` + fingerprint in `census-contract/` with a conformance golden; then the server's
`kind`-aware ingestion/clustering (k/retention unchanged); then the app publisher. Trusted notification
envelopes (unblinding) are a later slice.

## Server-side commitments (implemented by #1157, stated here so the client's disclosure can cite them)

Where this table differs from `~/dashbuddy/design/2026-09-30-census-server/PLAN.md` §2.2 (unresolved
clusters kept indefinitely, rejected hashes kept permanently, no sample-hash rewriting), **this ADR
supersedes the plan** and #1157 updates its table and purge contract, including queued or unblinded
vocabulary rows that lose eligibility.

| Data | Retention |
|---|---|
| Sub-k token hash | 30 days after last sighting, then deleted from EVERY persisted copy — the sightings table, the `h` values inside stored cluster samples (replaced by the SERVER-ONLY value `expired`, distinct from the client's `withheld`; the server rejects `expired` inbound), and rejected vocabulary rows (which keep neither hash nor text past the TTL); never listed, exported or logged in bulk. A community sample is rendered or exported only with its sub-k hashes suppressed. Amended 2026-11-13: every stored word hash is keyed, `HMAC(pepper, h)` (§3, sjtrotter/dashbuddy-census#85) |
| Cluster samples | at most 5 skeleton bodies per (cluster, app version), no install id; an UNRESOLVED cluster's samples expire 90 days after the cluster's last sighting; purged 30 days after the cluster resolves |
| Per-install cluster sightings | **30 days, presence only** (no counts per install), keyed by a 30-day epoch pseudonym (amended 2026-11-13: sjtrotter/dashbuddy-census#79 and sjtrotter/dashbuddy-census#86; was 90 days rolling with counts) |
| Install records | deleted on "delete my census data", else 365 days after last activity; `enrolled_at` stored as a day; ingest ledger 7 days; server logs and alarms (counts and reason codes, no install id or prefix, never bodies) 14 days (amended 2026-11-13, sjtrotter/dashbuddy-census#79; logs carried an install-id prefix before). The `installs` table is the one place the install id stays (authentication, quotas, contributor grants, withdrawal; §21) |
| Trusted-install envelopes | 30 days, earlier once a fixture/rule exists. None accepted by the official server after #1633 (amended 2026-11-13, §6) |
| Per-install health rows | **30 days, then folded into fleet totals**; fleet rollups indefinitely without install ids (amended 2026-11-13: sjtrotter/dashbuddy-census#79 and sjtrotter/dashbuddy-census#64; was 180 days). Diagnostics only, no count that reads as work done (§21, #1571) |
| Contributor grants, confirmations, role tags | a grant expires after 30 days and can be revoked; confirmations are stored as keyed hashes and role tags as role names, both under epoch pseudonyms, never an install id; the rows of the two live epochs are deleted on request, and rows under a destroyed epoch pepper can no longer be tied to anyone (§15, §21, sjtrotter/dashbuddy-census#81 and sjtrotter/dashbuddy-census#86) |
| "Ask the developer" quarantine | 72 hours, encrypted to the operator key, never backed up, invisible to clusters, counts, drafting and the agent until the operator approves a sanitized copy (§15, sjtrotter/dashbuddy-census#81) |
| Click evidence | as the skeleton it points into: same retention, epoch pseudonyms and keyed hashes (§19, sjtrotter/dashbuddy-census#87) |
| Backups | encrypted, **14 days** — shorter than the longest TTL, so the worst-case life of a purged sub-k row is TTL + 14 days |
| Text | a community skeleton has no text field to store; the ONE exception is a trusted install's redacted envelope (§6), which can carry text the redact layers missed, kept 30 days. Amended 2026-11-13: a contributor's gated word (§15) is text, the app's own wording on a constant slot, and is kept as working vocabulary under §4 |
| Precise timestamps, device identifiers, location | never stored (hour bucket in flight, day at rest); the census stores no location or region of any kind (amended 2026-11-13, §20: the earlier "coarse metro cell only under the #1137 dual opt-in" clause is withdrawn for the census); request bodies are never logged on either side, not even on a parse failure |
| IP addresses | **never stored and never logged — no exception** (#1180, 2026-10-05: the earlier "72 h abuse log, advertised as `ipLogging`" clause is withdrawn; the server never implemented it and `GET /v1/policy` has no such field). The Caddy edge rate limiter retains the peer address only in process memory for a single window (no access log, forwarded-IP headers stripped); client IPs are never persisted or logged |

The **operator-trust statement** (the residual no design removes): the official server is one
machine operated by the gig.log maintainer running the published image digest of an AGPL-3.0
repository; `GET /v1/policy` reports the digest and the retention numbers. The operator can see
shapes, counts, the text of their OWN trusted devices' redacted envelopes (`k_unblind` — for a trusted install, i.e. the operator's own device, k = 1 by definition; the **k = 10** `k_ship` gate in §4 is the COMMUNITY-install gate and explicitly excludes trusted installs), and — once
a token entered the shipped chrome vocabulary by one of §4's two routes — that vocabulary; for a community install the
operator cannot see what the dasher typed, whom they delivered to, or what they earned, subject to
the dictionary-linkage residual on low-entropy hashes (risk 1). "Only credential digests are stored" is true of CLIENT bearers (`installs.key_hash`); the host itself holds RECOVERABLE operator secrets — the TOTP seed and the database password — as an SSM SecureString rendered into a root-only `.env` (encrypted at rest, not hashed), which the operator-trust statement discloses rather than papers over (#1180). The endpoint is user-selectable
(the #193 pattern), so a dasher can point the app at any server running this code.

**Amended 2026-11-13 (#1618).** The operator also sees contributor role tags and, once a slot's gate opens, the
contributor's confirmed word (§15). A shape template shows a value's format, never its digits (§13). The census is
pseudonymous, not anonymous: until #1635 the operator sees each request's install credential and IP address in
flight, and holds the peppers that make the at-rest protections of §21 work, so those protect against a stolen
database or backup, never against the operator. §21's threat model states what each party can learn about one dasher.

## Lifecycle: schema, filter and identity evolution

- **Schema versions** are explicit wire fields (`schemaId`); the server negotiates accepted versions
  through `GET /v1/policy` and rejects the rest. A new `UiNodeTextField` entry is NOT a schema bump —
  it is a new key in the per-node text map, which the server accepts by construction (§1); any other
  new field (node-level, envelope-level) IS a schema bump, and the server rejects unknown fields there.
- **Hash versions** are the domain prefix (`census.v1:`) AND an explicit integer `hashDomain` in the
  envelope allowlist (1 for `census.v1:`) — the prefix is inside the digest and `h` is 16 hex, so the
  wire needs its own discriminator; sightings and vocabulary are keyed by `(hashDomain, h)` and counts
  are NEVER pooled across hash versions, so a normalization change starts a new count.
- **Filter revisions** ride every item as `filterRev` (mandatory; the server rejects items below the
  minimum accepted revision published in `GET /v1/policy`, not only the client's bundle). Token and
  cluster sighting rows and vocabulary rows carry the `filterRev` that produced them, so when a
  revision is found unsafe (a shape the filter should have withheld) the server can purge or recompute
  every derived row from it — counts, samples, queued and unblinded vocabulary — and the purge is
  replayed after a backup restore (the deletion log).
- **Vocabulary removal** is a bundle release (see §4); a token found to be PII after shipping is
  removed from the allowlist, purged server-side, and — a PRIVACY revocation, not a version bump —
  rejected as a clear token across EVERY accepted bundle version immediately, so a client still
  holding the old list cannot re-ingest it. Previously published bundles stay irreversible on the
  device; they never again authorize server ingestion of a revoked token. The revocation is a DURABLE
  TOMBSTONE — the one record exempt from ordinary hash deletion — kept for the lifetime of every
  accepted bundle that carried the token and replayed before a restored server accepts traffic.
- **Install identity**: secret rotation preserves the install id and its counts; a reset deletes the
  old identity first (the deletion contract), enrols anew, restarts the 7-day quarantine, and
  requires fresh trusted enrolment. **A withdrawal survives a restore by construction (#1192,
  dashbuddy-census PR #47 — the release after v0.11.1):** the withdrawal transaction writes a hashed tombstone (`sha256` of the install id —
  a record that a withdrawal happened, never of who) that is outside `WITHDRAWAL_TABLES`; the server
  replays tombstones at EVERY startup (deleting any install the restore resurrected, fail-closed: a
  replay failure refuses to start) and `restore.sh` merges the off-host journal first. Off-host
  durability goes through the host, because the census container cannot reach the instance role: a
  spool notice the host publishes to SNS within minutes (its two lines are a journal row) and a CSV
  export written beside every backup and uploaded with it. A re-enrolment after the tombstone is never
  erased. The stated residual: a withdrawal in the window between the last journal export / SNS publish
  and a total host loss is replayable only from the SNS email.

## Ownership by milestone (the seams the builders keep)

- **#1145 (M1a, pure):** the wire contract (`UiSkeletonDto`, `SkeletonSchema`, `CensusHash`,
  `CensusFingerprint` + vectors, the `kind` classifier + vectors, the shared anonymous-wrapper class
  constant) and `SkeletonBuilder` with the §2 filter; the `PiiShapes` promotion. No wiring, no I/O.
- **#1146 (M1b, inert):** the `CensusSink` interface in `:domain`, a `NoOp` binding, the publisher
  stage on the UNKNOWN screen branch in `:core:pipeline`, `PipelineStats` counters — and the
  trusted-install PAIRING the 2026-09-29 amendment assigned to M1: the publisher hands the sink the
  skeleton together with the census `fingerprint` (its cluster key) and, when a capture envelope
  exists, that envelope's `captureId` (the PAIRING key), so a trusted transport can pair the two with
  no new redaction code. Nothing leaves the
  device. The trusted TRANSPORT itself (upload, metadata projection) is M3's — a deliberate deferral
  recorded here, because nothing uploads before M3. — **shipped 2026-10-01** (PR for #1146): `CensusSink` + `CensusRecord` in `:domain`, `NoOpCensusSink` bound in every variant, `SkeletonPublisher` + `census{…}` counters.
- **Sequencing:** the TalkBack issues (#1147, #1148, #1149 — and #1151/#1152 from their review) shipped
  FIRST by dev decision (2026-09-29) so the census sees the richer, window-correct tree; the census
  chain is `#1144 → #1145 → #1146`, with #1157 in parallel from #1144/#1145.
- **M2:** the bounded local queue and the in-app viewer (`:core:data` + `:app`).
- **M3 (client, #1138) + #1157 (server):** `census_prefs`, consent + disclosure copy + LEGAL.md +
  Data Safety delta, the uploader in `:core:data` behind `:core:network` (the pipeline never performs
  network I/O — the `CaptureBus`/`LogScrubber` precedent), the trusted-install transport (envelope +
  skeleton pairing, metadata stripped as §6 says), server v0.
- **M4:** unblinding tool, `skeleton.v2`, the working-vocabulary → shipped-allowlist promotion gate.
- **Contract placement (open question 1, resolved 2026-10-01):** the Apache-2.0
  `census-contract/` included build (#1173), package `cloud.trotter.census.contract`, depends only
  on the JDK and kotlinx-serialization. Both the PolyForm-Shield app (`:domain` via `api`) and
  the AGPL `dashbuddy-census` server consume it by `includeBuild`. It owns the ONE `sha256OrNull`
  (#362 SSOT), `AnonymousWrappers`, and the sensitive-marker DATA (`SensitiveMarkerData`);
  the phone's scan logic stays in `:core:pipeline` and `PiiShapes` stays in `:domain`.
  `UiNode` never imports the contract's WIRE types; it imports the anonymous-wrapper predicate
  from the contract build, which owns it (#1173).

## Consequences

**Positive.** A leak of text is a type error. The k rule protects contributors while the operator's
own device keeps the loop working at k = 1. The filter and the corpus intake share one pattern owner,
so they cannot drift. The skeleton enumerates the scrub contract, so #1147's fields and any later one
are covered without a census change. Client and server share `CensusFingerprint`; `FrameGate`'s and the librarian's identities are unchanged.

**Negative / tradeoffs.** Chrome that is rare (a screen only one market sees) never crosses k from
community installs alone — the trusted install must see it. (Amended 2026-11-13: once the trusted path closes, §12,
such a screen is drafted from ids, structure, shapes and role tags, and its wording is checked on phones by probes,
§17.) The filter over-withholds by design
(`mixed` never hashes, so "Order #1234" contributes only a kind; a symbol-only run is dropped
before counting so `Pickup & delivery` still hashes as `words:2`). The two hash domains (census vs parse)
never produce equal digests, so nothing in the system joins a census cluster to a customer hash by
key — and the census cannot help attribute a delivery; the operator-side dictionary linkage residual
(§3, risk 1) is the honest limit of that statement and the M3 disclosure must not overclaim it. Promoting `PiiShapes`
to `:domain` moves test-only regexes into a shipped module; `IcuRegexGuardTest` and the byte-SSOT pins
must stay green.

## Residual risks (stated, not hidden)

- **(§10, approved) Notification channel ids are read in clear.** An app-defined constant by evidence (14 ids in five months), bounded by grammar and k-gated; a platform encoding a per-user value in a channel id would be a singleton cluster that never unblinds. Bodies over the 40-character cap become `withheld` before classification — the distinction is lost by design.

1. **A hash of a value that slipped every filter.** A short, marker-free, name-shape-free word that is
   nonetheless personal. Mitigations: the `words:N` restriction, the k gate, the 30-day TTL, the
   never-listed rule, and the fact that the operator already holds the plaintext captures of the only
   device whose hashes are ever resolved.
2. **The trusted install as the sole unblinding source.** Its envelopes carry the same UNKNOWN-path
   residual the project accepts on disk today; the manual sweep and the 30-day server retention are
   the controls.
3. **Server operator trust.** Stated above; reduced, never removed, by AGPL source, a published digest,
   a user-selectable endpoint and the retention table.
4. **Tree shape.** Child counts and sibling order are structure and leave the phone; a longer list
   has more rows. Accepted: rows are not identities and carry no text.
5. **Aged Sybil cohorts.** Enrolment is accountless and client-minted; one party can age ten
   identities through the quarantine and push a chosen hash across `k_ship`. The human classification
   before publication is the control until an independently specified admission control (#194's
   attestation) exists.
6. **Shape information on non-withheld slots.** Pseudonym invariance is guaranteed for
   shape-preserving substitution in slots the filter catches (they emit the constant `withheld`); a
   PII value the filter does NOT catch emits its `kind` (a word count) and, if it is `words:N`, a hash —
   the undetected-PII residual, the same class as risk 1.
7. **Dictionary attack by the operator on sub-k hashes.** Sub-k hashes are never listed, but the
   operator holds the database. The control is the same as 3, plus the 30-day TTL.
8. **The raw-form pass re-admits the JVM-vs-ICU regex divergence** (#1160 review round 4). The canonical
   form closes the `\s` difference (the JVM's excludes NBSP/thin space, ICU's includes `\p{Z}`), but the
   raw-form pass (§2 "Inputs and predicates") still runs the patterns on un-normalized text, where ICU's
   `\s`/`\b`/`\w` are wider than the JVM's. Every divergence found so far withholds MORE on ART, so the
   device withholds a superset of what the corpus tests (JVM) prove hashed: the privacy direction is
   safe, but fixture-derived RECALL is optimistic. The JVM tests remain the oracle until the builder's
   vectors run instrumented on ART (a #1146 follow-up).
9. **Identity-dependent chrome suppression** (#1160 review round 5). The §2 token-containment rule
   withholds any field sharing a ≥2-letter run with an identity value (case-folded), so a customer whose
   first name is also an English word ("May", "June", "Will", "Grace") suppresses same-word chrome on
   that frame, and the skeleton's chrome slots depend on who the customer is: a withheld-vs-hashed
   difference on a chrome slot reveals only that SOME identity value on the frame shares that word —
   never the value — but it is a real one-bit channel and a recall cost, accepted in exchange for
   withholding "Adam's order".
10. **A name-shaped test-tag id on a frame with NO identity id bearing that name** (#1160 review round 5).
    The static id gate cannot tell a Compose test tag built from a customer's name with no marker,
    lead-in or initial (`chip_Adam`, `Adam Smith`) from chrome of the same shape (`chip_Gold`, `Artwork
    Image`). The frame-level containment rule closes the case where an identity id on the same frame
    carries that name (the id is then absent); a name-shaped tag on a frame with no such identity id
    still travels in the clear. The corpus has none (the rejected-id pin lists only the three dynamic
    UUIDs, and no identity seed collides with a committed chrome id); the controls are that pin and the
    k-gated, human-reviewed promotion path.
   The same containment applies to ids (and, for customer-name runs, to non-wrapper classes — review
   round 12), so in the rarer case of a first name equal to an id token (`Star` / `star_rating_bar`,
   `Page` / `page_indicator`, `Dash` / `dash_now_button`) the id becomes the sentinel `~` (the class
   becomes absent) and the CLUSTER KEY itself moves by that one value — never the tree structure (§8): that surface lands in a singleton cluster for that install on
   that frame. Fingerprinting the pre-containment structure would break the server's
   recompute-from-the-wire rule, so this is accepted: the drop costs availability (a cluster that does
   not reach k), never privacy (#1160 review round 6).
11. **Every `tvTitle` line in the runtime UNKNOWN scrub** (#1160 review rounds 10, 11). The runtime UNKNOWN
    id scrub masks `tvTitle` (the chat header — a customer's name) ALWAYS, whatever its value: a
    value-shape gate cannot tell "李明", "محمد" or "de la Cruz" from chrome, so the runtime path fails
    closed on the id alone. The cost: DoorDash reuses the generic `tvTitle` for sheet titles, so an
    UNKNOWN sheet titled "Pick up order" loses that line in the debug X-Ray triage. The census skeleton
    is unaffected — it keeps the node's id and structure (its text slot was already withheld as an
    EXACT id). A recognized chat frame masks through its rule's `redact` (customer-name hash) instead.
    The blast radius is CROSS-PLATFORM (review round 15): the scrub is keyed on generic Hungarian-notation
    suffixes (`tvTitle`, `tvLastMessage`), so any platform's `…:id/tvTitle` node on an UNKNOWN capture — an
    Uber sheet title included — is scrubbed too. Accepted, not scoped by a chat ancestor: the path is
    debug-only (release binds `NoOpCaptureBus`) and the cost is triage text, never privacy.

Added 2026-11-13 (#1618), for §12 to §21:

12. **Constant is not chrome.** A common first name, a store or a local word can carry the same hash on k installs.
    Controls: the regional class (§14), the chrome filter on candidates, two independent confirmations (§15), and the
    human gates of §18. A wrongly gated word reaches the working vocabulary only; the shipped vocabulary still needs
    `k_ship` and a human classification (§4).
13. **Contributor Sybils.** One party with several eligible installs can supply both confirmations. Eligibility is a
    yes/no the phone reports, so it is evidence, not proof. Controls: the k-install requirement on top of the two
    confirmations, expiring and revocable grants, and the human gates. Play Integrity may later weight k; it is never
    a gate, because it would exclude the sideload build.
14. **The anchor check is a dictionary oracle.** Its limits (§16) bind the workbench, the agent token and the
    dashboard. The live operator holds the database and the pepper and can run any lookup outside the tool, which is
    risk 7.
15. **The live operator can link one phone's requests.** Until #1635, every request arrives with the install
    credential and an IP address; within an epoch, stored rows join; the daily upload and the health report show
    which days a phone saw a platform app, for 30 days (§21).
16. **A shape says what a slot holds.** A template tells the server a slot is money, a time, a distance, minutes or
    a count, and its format. Classes leave out what parsing does not need (§13), and a property test proves no name,
    address, phone number or code becomes a shape (#1619).
17. **A probe literal is chosen by the operator.** The phone's chrome filter, no per-install targeting, expiry and a
    client-side budget limit what a probe can ask (§17), but they do not stop it: a common first name can pass the
    filter, and each install's answer arrives in its own health report, readable within the epoch. Answers are
    attributable until they are aggregated before the operator sees them.

## Open questions (dev decisions; the same items appear in #1157's plan §10 under its own numbering — this list is the ADR's reference)

1. **RESOLVED 2026-10-01:** the Apache-2.0 `census-contract/` included build (#1173).
2. Whether trusted installs are excluded from `k_ship` (this ADR assumes yes).
3. Backup retention of 14 days.
4. What "contributing" means for the #1137 promo (decides whether the server keeps a metro cell at all).
   Amended 2026-11-13: the census keeps none (§20); the question remains open only for the aggregation pulse (#193).

## References

- #1138 (epic, D1–D7 and the 2026-09-29 trusted-installs amendment), #1144, #1145, #1146, #1157
- `~/dashbuddy/design/2026-09-30-census-server/PLAN.md` (server plan; outside the repo)
- CLAUDE.md § Pledges, § Development Principles 6–7, §1 Redaction invariants / Backstops
- ADR-0009 amendment (same PR): authoring model — drafted and reviewed, not hand-authored
- 2026-11-13 amendment (§12 to §21): epic #1617, #1618; council rounds 2 and 3 (Claude, Sol, Fable, Astra),
  `~/dashbuddy/design/2026-11-12-census-contributor/synthesis-r2.md` and `synthesis-r3.md` (outside the repo);
  server issues in `sjtrotter/dashbuddy-census` (#64, #69, #78 to #87)

## §11 Region cell (#1194)

The REGION cell grammar is a US Census CBSA (metro or micro statistical area),
with an explicit STATE cell for counties outside every CBSA. Examples:
`metro:12420@2023`, `micro:10100@2023`, `state:ND@2023`. The crosswalk vintage
is pinned to 2023 and embedded in the id; a new vintage must never silently
re-home an install. The non-metro STATE cell is the rural aggregation floor.

Derivation happens entirely on-device from the dasher's own GPS fixes through
the offline 2023 county-subdivision internal points (~36k), with a county→CBSA join: no address, no ZIP, and no
geocoder (including Android's geocoder, which has no offline guarantee).
Nearest internal point is an approximation, not point-in-polygon: a border fix
can select a neighbouring county in another CBSA, and — the known limitation of
this slice — a fix in a border city of a neighbouring country within the 160 km
reach resolves to the adjacent US cell (Windsor 8.5 km → Detroit, Tijuana
27.8 km → San Diego, Toronto 52.4 km → Buffalo): a wrong cell, never a privacy leak. An offline
US-containment guard replaces the distance cutoff before the `region` field
ships. The 160 km reach covers Key West, 33.7 km from its own subdivision point;
the remaining US nulls are the Alaska Arctic/Aleutian boroughs (Utqiagvik 239 km,
Adak 256 km — no delivery market). The primary-cell policy chooses the most distinct
active days over a rolling 28-day window, with ties resolved by the most recent activity
observation; it cannot correct systematic nearest-point errors. Day buckets use the
device's zone offset supplied by the caller (UTC by default): UTC midnight is 17:00 PDT,
so without the offset a Pacific dinner shift spans two "days".

The cell id is the **only location-shaped value that may ever leave the device**;
coordinates, county FIPS, addresses, and ZIPs stay local. With the aggregation
pulse (#193), each install will store **ONE primary cell**. The `region` field
on enrol/me and the metro cohort counter (#1137) wait for that pulse; this first
slice supplies only pure domain derivation and does not wire into the app.

Region cohort counts are reported **ONLY at k ≥ 10 contributors** and are
**suppressed below 10 even to members**: “you're one of 4” is not shown.
Suppression is enforced server-side with the pulse. Enrolment alone never
counts as contributing; observations must come from activity. For Sybil
purposes the cell is a self-reported field, not proof of location, and the
cohort count uses the pulse's attestation weighting (#194).

## Amendment 2026-11-13: skeletons from every build (#1618, epic #1617)

Decided by council rounds 2 and 3 (Claude, Sol, Fable, Astra; `synthesis-r2.md` and `synthesis-r3.md` in
`~/dashbuddy/design/2026-11-12-census-contributor/`) and by the developer on 2026-11-13. The census moves from the
developer's debug build to opted-in Play and sideload installs, and the census server becomes the place where rules
are drafted from what those installs send. No customer text leaves a Play or sideload phone, and the Pledges do not
change.
`census#N` below is an issue in `sjtrotter/dashbuddy-census`. On 2026-11-13 none of §12 to §21 is built: the debug
build's census runs as §1 to §11 describe.

| Section | Mechanism | Built by |
|---|---|---|
| §12 | skeletons from the Play and sideload builds, opt-in | #1620, #1621; census#79, census#85 |
| §13 | shape templates and click flags | #1619; census#78 |
| §14 | per-slot constancy with a regional class | census#80 |
| §15 | the contributor tier and its word gate | #1625, #1626, #1627; census#81 |
| §16 | the anchor check | census#82 |
| §17 | probe rules | #1630 |
| §18 | skeleton drafting, shadow mode, canary, rollback | #1628, #1629, #1630, #1631, #1573, #1632, #1633; census#69, census#83, census#84 |
| §19 | click evidence | #1634; census#87 |
| §20 | no region data | a limit; nothing to build |
| §21 | the dasher's privacy | #1571, #1579, #1620, #1622; census#64, census#79, census#85, census#86; later #1635 |

### §12 Every build may send skeletons, opt-in

- The Play (`release`) and sideload builds bind the real census sink and the health reporter (#1620). Today they
  bind `NoOpCensusSink` (`core/data/src/release/.../di/CensusSinkModule.kt:27`), and the upload worker refuses to
  run outside debug (`CensusUploadWorker.kt:148`).
- Nothing is sent until the dasher opts in through a prominent disclosure (#1621): what a skeleton is, why it is
  sent, who receives it, and how to stop. Off by default, per platform, Wi-Fi only by default. The consent receipt is
  bound to the server endpoint, so a new endpoint asks again. Health reports ride the same consent: no consent, no
  upload of any kind.
  Built by #1621 (2026-11-13): Settings > Data & privacy > Census in every build, its disclosure, a
  `CensusConsentReceipt` bound to `censusBaseUrl` that every upload stage checks (`CensusConsent.of`), and the
  platform and Wi-Fi-only choices as stored data. The upload cadence, the Wi-Fi constraint and the per-platform filter
  stay #1620's; until then the disclosure carries a line for the build it runs in (`PRIVACY.md` §6).
  Built by #1620 (2026-11-13): every build binds `HttpCensusSink` and runs the worker behind that consent, health
  reports included; the release variant keeps `NoOpCaptureBus` and the no-op envelope sink. One collection floor for
  all three builds: one skeleton per variant per UTC day (`CensusSampleKey`: structure, flags, field names and format
  templates, never a word hash, word count or withheld mark), the census choices filtering skeletons and health rows,
  one upload a day at a time drawn on the phone (`CensusUploadPlanner`), never on a recognition trigger, Wi-Fi only by
  default (`UNMETERED`), and an app-wide network security config refusing cleartext. The deletion button (#1622) and
  the server's 30-day pseudonyms (census#86) are not built; the disclosure's per-build line says so.
  Built by #1622 (2026-11-13): "Delete my census data" on the census screen, separate from the switch, with the
  confirmation naming what survives and the 14-day backups; it clears the phone's queue, sends
  `DELETE /v1/installs/me/data` (census#79) and shows the server's confirmation, and a request made offline is sent
  before anything else (`CensusDataDeletion`). "What gig.log sent" summarizes the request bodies the server read,
  from their bytes, for 7 days on the phone. An install the server no longer knows (a restore after a deletion
  erases it whole) re-enrols with consent kept. The server keeps the install record and today's ledger row so uploads
  continue (census API.md), and the confirmation says so with today's quota row; the bullet below that lists "the
  install record" describes withdrawal. Until the live server is deployed with census#79 it answers 404 and the request
  waits on the phone, bound to its install ID, with uploads held; a 401 never counts as deleted. The on-phone wipe
  (#1579) asks the server first; the disclosure's per-build line says both.
- The capture bus stays `NoOpCaptureBus` in the Play and sideload builds. Only skeletons, health counts and, from a
  contributor, the role tags and gated words of §15 leave those phones.
- The debug build keeps the trusted-envelope path until #1633; after that the official server accepts no plaintext
  envelope from any build (§6), and all three builds follow one collection floor (checked by #1624).
- The server goes first: no Play or sideload install uploads before the retention and log cuts, quotas, pause switch
  and deletion (census#79) and keyed storage (census#85) are live. Testers are recruited only after the whole chain
  works end to end, the contributor tier and #1633 included (#1624).
- Counsel (#246) reviews the disclosure wording before recruiting. The disclosure tells the dasher that the delivery
  company's agreement is between them and that company (#1621).
- A census failure never changes recognition (unchanged).

### §13 Shape templates and click flags (#1619, census#78)

A money, time or distance slot goes out today as `mixed` with no hash (§1), so a pay field that moves or re-renders
cannot be studied from census data. A new `shape` slot kind carries a value's format with the digits removed, from a
closed list: money (`$##.##`), a 12-hour clock time, distance (`#.# mi`), minutes (`## min`) and item counts
(`# items`).

- A shape is filled only from the list, through `PiiShapes`. Free text never becomes a shape, and a value that fits no
  template goes out as it does today.
- A class leaves out what parsing does not need: one 12-hour clock class covers AM and PM.
- The phone keeps a local "this slot varied" flag per variant instead of sending every differing hash.
- The server validates shapes against the same list and rejects anything else as `bad_request`. Fingerprints ignore
  the new fields, so existing clusters do not split.
- Property tests: no generated customer name, address, phone number or code produces a shape, and neither does the
  dasher's own name, greeting, account identifier or location-bearing label.
- Screen nodes gain `clickable`, `hasClickAction` and the missing visibility flags. Notification skeletons gain their
  action list; #1619 settles its form, and nothing in it travels in clear.

On the workbench, a shape test runs generated boundary values through the draft's real regex and transforms. A regex
run against a literal `#` proves nothing (census#83).

> **Built by #1619 (2026-11-13).** The new fields ride new schema ids, `uinode.skeleton.v2` and
> `notification.skeleton.v2`; the phone sends v2 only once the server's policy lists it, and v1 otherwise (the v2
> build projected, byte-identical to what v1 sent before). The list is `ShapeTemplate` in `census-contract/`. The node
> flags are `hasClickAction` and `isVisibleToUser`. The action list's form: each label a slot judged by the same
> filter as the five text slots (a hash, `withheld` or a shape), in order, at most 8, and outside the fingerprint. The
> skeleton `day` is the UTC day. The local "this slot varied" flag is left to #1620: it needs per-variant state across
> frames, which arrives with #1620's sampling, and slot identity across frames is not settled (the fingerprint does
> not cover the text map, and a spliced wrapper can carry text).

### §14 Per-slot constancy (census#80)

The server classifies each text slot of a cluster from the hashes it already holds:

- constant: the same hash on many installs, reported as candidate chrome, never as proof;
- varying: a different hash per frame, which is a value (a customer, a store, a time);
- regional: several values, each on several installs (zone and store names); never confirmable and never
  anchor-checked;
- withheld: a backstop fired;
- unknown: fewer than k installs, where k is the live community threshold of §4.

Support is counted per variant and slot within one epoch (§21), not from global token sightings. The phone reads a
slot's class from an endpoint that returns classes only, never another install's hash, and greys varying and regional
slots on the review screen (#1626).

### §15 The contributor tier (#1625, #1626, #1627, census#81)

A contributor is an opted-in dasher, invited after real use, who reviews a screen before it is sent and adds what a
hash cannot give: the app's own words on constant slots, and what a field means. The tier is separate from trusted
(§6). A contributor never sends an envelope or screen text, and contributor status never grants either.

- Eligibility is computed on the phone: census opt-in, an install at least 7 days old, active on at least 3 of the
  last 7 days, at least 2 recorded shifts. Only yes or no is sent. Eligibility never requires a high recognition
  ratio. The invitation comes after a shift ends, never during one; accepting shows a second disclosure and then
  requests a grant; a decline is durable.
- A grant expires after 30 days, can be revoked, and is checked at upload in the same transaction. Expiry, revocation
  or withdrawal stops contributor uploads and clears pending reviews.
- Review happens after the shift, at most 3 screens a day, de-duplicated by layout. Raw text is held in memory only
  and is lost on process death; only the sanitized result is stored. Customer data the app can detect (names,
  addresses, codes, notes, every backstop hit) is masked before the contributor sees the screen, so a careless Send
  is still masked. The dasher's own name, greeting, account details and location-bearing labels are masked the same
  way. Varying and regional slots are greyed and cannot be unmasked. A constant slot shows its word, and the
  contributor may confirm it as the app's own wording. Any field may get a role tag ("total pay", "tip", "deadline",
  "customer info", "store name"), which carries no value.
- **The word gate.** A confirmation is sent as a hash. The confirmed word leaves the phone only after the server
  reports at least 2 confirmations from independent installs and k installs seeing the slot; two confirmations from
  one install count once. The phone holds the word back until then, and the server refuses a plaintext word for a slot
  whose gate is closed; the gate is never only a dashboard filter. A word that passes joins the working vocabulary as
  approved chrome (§4). Two confirmations and k installs are necessary, not sufficient (risk 12).
- Confirmations and role tags are keyed by epoch pseudonyms (§21), never the install id.
- "Ask the developer" sends the outbound artifact the review screen described (byte-identical), with an optional note
  from a fixed list, to a quarantine: encrypted to the operator key, expiring in 72 hours, never backed up, and kept
  out of clusters, counts, drafting and the agent's view until the operator approves a sanitized copy. It never
  sends unmasked text, and it is not a way around the word gate: a word the review screen shows on a constant slot is
  a local display, and in the outbound artifact that slot stays a hash unless its gate has opened.
- Contributor review is evidence. It is never permission to send customer text.

### §16 The anchor check is a dictionary lookup (census#82)

When the developer or an agent drafts an anchor, the workbench checks whether a word is what a constant slot holds by
hashing the candidate and comparing. That is a dictionary lookup, the same operation an attacker would run against
the hashes, so it runs only inside these limits:

- a candidate must pass the chrome filter (no name, address or code shape), and a failing one is refused before it is
  hashed;
- only constant slots seen by at least k installs answer; a regional or below-threshold slot returns "no answer",
  never "no match";
- exact `hasText` only, with the case variants `hasText` accepts, because `hasText` compares case-insensitively and
  the census hash keeps case; "contains" and pattern candidates are answered on phones by probes (§17);
- the candidate is hashed, then keyed with the pepper (§21), then compared;
- each caller has a daily rate limit, and an audit log records every query (caller, slot, candidate, result), from the
  agent and the operator alike.

These limits bind the tool. They do not bind the live operator, who holds the database and the pepper (risk 14).

### §17 Probes, never fragment hashes (#1630)

No word fragment is ever hashed or sent: n-gram, prefix or suffix hashes would put a name inside a phrase on the wire.
A whole-slot hash cannot verify a "contains" or pattern arm, so those arms, and long notification bodies, are checked
on phones by probes. A probe is a shadow rule (§18) with no `state` block, published only to be counted.

- Probes are signed, expire, are common to all phones (no per-install targeting), and run under a client-side budget.
- The phone checks every literal before it evaluates a probe. A literal that fails the chrome filter (a name, address
  or code shape, a mask marker) is refused and counted as `probe_refused`, never evaluated. Like redaction, this
  fails closed.
- Only counts reach the health report. Those counts arrive in the install's own health report, so within an epoch the
  operator can read one install's answer; publishing a probe to every phone does not prevent that. The literal filter,
  no targeting, expiry and the budget limit what a probe can ask; a common first name can still pass the filter
  (risk 17). Answers stay attributable until a mechanism aggregates them before the operator sees them.

### §18 Drafting from skeletons, shadow mode, canary, rollback

§9 stands: every drafted rule reaches `matchers/` as a pull request through app CI and review, and the developer
approves the signing. Drafting from skeletons adds:

- Skeleton mode in `RuleDraft`: anchors come from ids, approved chrome and structure; parse fields from role tags and
  shapes. A slot with no approved word stays unknown and is never shown as observed text. Synthetic fixtures carry
  approved chrome and fictional values in every other slot, several per variant, with independent expected values
  (#1628, census#83).
- Masking obligations that do not come from the drafted rule: role tags, every varying slot, and the rules-independent
  backstops. Deleting any one `redact` entry fails the test, and a node with no stable selector gets a wider mask or no
  draft (#1629). Today `CensusDraftRoundTripTest` takes its obligations from the production redaction, which cannot
  see a new node.
- An agent token reads only approved evidence and writes only a pull request (census#84).
- **Shadow mode** (#1630). A signed rule marked `shadow` is compiled and evaluated beside the live rules, and its result
  never reaches the state machine, a capture, a tap or any effect. The phone counts matches, ties with a live rule,
  empty parse fields, and frames where a name-shaped value survived the masks; only those counts reach the health
  report. Shadow rules are bounded in number and evaluation time, and a failure counts as no match.
- **Canary** (#1631). The signed manifest carries a share per rule. Each phone draws its own bucket once, locally; the
  server never assigns it. Health reports carry the rules generation and whether the phone was in the canary.
  Promotion to every phone is a new signed generation the developer approves.
- **Rollback** is a newer generation with a higher sequence that restores the last good rules; a phone never accepts
  a lower sequence (#1631).
- Matched skeletons (#1573, census#69): while a rule is in a parse-rot incident, the skeleton of a frame it matched,
  at most 1 per rule per day and 10 a day, inside the shared budgets.
- A drafted click rule goes to shadow mode first, and an automated tap still needs the no-auto-grant capability
  consent.
- Proof: #1632 redrafts a shipped id-less rule blind and compares it on the real corpus; #1633 ships a rule through
  shadow and canary to every phone, then closes the trusted path (§6).

### §19 Click evidence as references into skeletons (#1634, census#87)

This supersedes §10's "Clicks stay OUT". When a tap lands on a node no click rule recognizes, or a click rule is in an
incident, the phone records which node of that screen's skeleton was tapped: its path in the tree, its class, id and
click flags, and its word hash or shape, with the screen's fingerprint and the rules generation. The target and its
clickable owner are resolved the way `UiInteractionHandler` resolves them, so the evidence names the node a rule would
bind. It travels with that screen's skeleton under the same caps, sampling, upload window and consent, and carries no
text, no sequence of taps and no time finer than the day. A tap on a blocked screen sends nothing.

### §20 No region data

The census collects no location, region or metro cell. "Installs on platform version V landing in cluster C" stands
in for a market. §11 is unchanged and belongs to the aggregation pulse (#193); a census enrolment or upload carries
no region field.

### §21 The dasher's privacy (council round 3)

Rounds 1 and 2 protected the customer. Round 3 found the dasher was the weak side: every census table joins on a
permanent install id (`V1__census_schema.sql` in the server repository), so the server keeps a months-long
diary of one dasher's work that rule authoring never needs. Rule authoring needs to know that k phones saw something;
it does not need to follow one phone over time. Most of the fix is keeping less.

**Threat model.** What each party can learn about one dasher from the census: today (the debug build, 2026-11-13) and
once §12 to §21 ship.

| Party | Today | After |
|---|---|---|
| The live operator, or whoever controls the running server | each request's install credential and IP address in flight; every table joined on the install id: daily health rows with frame counts for 180 days, cluster sightings for 90 days, token sightings for 30 days, an install-id prefix in request logs for 14 days; upload arrival about 5 minutes after the first unrecognized frame (`HttpCensusSink.kt:53-58`, `CensusWorkScheduler.kt:29-38`), which tracks shift starts; dictionary guesses against stored word hashes | the credential and IP in flight, until #1635; rows from the two live epochs can be tied to an install by recomputing pseudonyms, and dictionary lookups still work with the pepper; older rows cannot be tied to any install; per-install health for 30 days, then fleet totals; which days a phone uploaded, at a random target time; a send can start up to an hour after it, when the phone gets Wi-Fi or a charge (#1620) |
| A thief with a database dump or a backup, without the server's peppers | the same joins at rest, and offline guesses (place, store and first names) against the unsalted word hashes, per install | content rows carry epoch pseudonyms, not install ids; stored word hashes are keyed, so a guess list matches nothing; the `installs` table still holds install ids, enrolment days and grants |
| A network observer on the Wi-Fi or cellular path | content hidden by TLS; the census host, request sizes, and upload times that track shift starts, on any network | HTTPS only, cleartext refused app-wide; one upload a day at a random time, on Wi-Fi by default, so a cellular observer sees no census traffic by default |

**Diagnostics, not work history** (#1571, census#64). No count of shifts, deliveries or active days leaves the phone;
contributor eligibility is a yes or no (§15). The health report carries no count that reads as work done. Its `trips`
field stays: it counts recognition-health alarm trips (`HealthLedger.kt:36`, `RecognitionHealthMonitor.kt:155-157`),
not deliveries. It carries per-rule shortfall counts and
their denominators, clipped to fixed bounds, with the rules generation. Skeletons are sampled once per layout variant
per UTC day, as presence (§8).

**Upload timing and transport** (#1620).

- No upload is triggered by recognition activity. The phone uploads once a day at a random time, independent of shift
  starts and ends. As built (#1620) the random time is a target, not the send: a run sends only if it starts within an
  hour after its target, so a send can be delayed by up to an hour and can then start when the phone gets Wi-Fi or a
  charge (on reaching home after a shift, say); a run held back longer, or one before its target, sends nothing that
  day. The send time is therefore not fully independent of shift ends; the hour bounds how much it can follow them. Fable dissented (arrival times are not persisted, and an hourly cadence speeds fixes); the window
  was adopted because the live operator and a network observer see timing, and drafting, not upload latency, sets how
  fast a fix ships.
- Wi-Fi only, on by default.
- HTTPS only: an explicit network security config refuses cleartext for the whole app, and a test checks that every
  endpoint the app calls is `https` (built by #1620: `res/xml/network_security_config.xml`, `HttpsEndpointGuardTest`).

**Keyed storage** (census#85). At ingest the server replaces every word hash with `HMAC(pepper, h)` before writing it
anywhere. The pepper lives in SSM beside the TOTP seed, never in the database, its backups or the logs, and is backed
up like the TOTP seed. It is not rotated: a new pepper is a new hash domain and needs a migration plan. Equality is
unchanged, so constancy, k counting and the anchor check (hash, key, compare) work as before. The limit: this protects
a stolen database or backup. It does not protect against the live operator, who holds the pepper.

**Epoch pseudonyms at rest** (census#86). Content rows (token and cluster sightings, per-install health, confirmations,
role tags, click evidence) are keyed by an epoch tag computed at ingest, `HMAC(epoch pepper, install id | purpose |
object)`, where an epoch is 30 days. One phone gets the same tag within an epoch and an unrelated tag in the next. The
`installs` table keeps the install id only for authentication, quotas, contributor grants and withdrawal. The server
holds the current and previous epoch peppers and destroys older ones, after which those rows cannot be tied to any
install. k is counted within one epoch, never across two. Deletion recomputes the tags under the live peppers and
deletes those rows; a restore replay skips rows under destroyed peppers. The limit: rows in the two live epochs can be
tied to an install by whoever holds their peppers.

**Retention** (census#79). The Server-side commitments table above holds the numbers, amended in place on this date.

**Deletion** (#1622, #1579, census#79).

- "Delete my census data" is its own button, separate from stopping. It sends a deletion request, shows the server's
  confirmation, and works while an upload is still waiting. Stopping keeps anything new from leaving; deleting removes what the server holds that is tied to this install.
- The server deletes every row it can tie to the install: the install record, sightings, health, confirmations, role
  tags, grants, quarantine items and trusted envelopes, and it re-applies deletions after a restore from backup.
- What survives is stated on the same screen. Data that can no longer be tied to the install: cluster samples
  (stored without an install id, §5), fleet totals, vocabulary already approved at k, rules and synthetic fixtures
  already published, and rows under destroyed epoch peppers. Data that can still be tied to it for a while: the
  encrypted backups taken before the request keep the install's rows until they expire, at most 14 days later; a
  restore from one of them re-applies the deletion before the server serves anything.
- The on-phone wipe (#1579) sends the same deletion request when the install is enrolled and clears everything
  gig.log keeps on the phone once the server confirms. Without a confirmation it deletes nothing until the dasher
  chooses: wipe anyway (the phone loses the ID and can't ask again; the server's copy ages out) or wait while the
  request keeps retrying.

**Later: an independent relay and anonymous credentials** (#1635). Needed before any wide rollout, not before the first
testers. Collection requests go through an Oblivious HTTP relay (RFC 9458) run by an independent operator (a
university host under the academic pillar is the candidate; a relay the census operator runs does not count), and
carry epoch-limited anonymous tokens (Privacy Pass, RFC 9576) instead of the permanent credential. The relay sees the
IP address and not the content; the server sees the content and not the IP address. Until it ships, the records say
the census is pseudonymous.

**Rejected.** An OPRF key service (one operator would hold both halves); phone-side rotating or shared keys (they
break cross-install equality, and a key in the APK is public); STAR or Poplar (frequency is not chrome, Sybils, and it
needs a non-colluding party); local differential privacy now (with a dozen testers the noise swamps the signal);
mandatory Play Integrity (it excludes the sideload build; at most a k-weight later); pull-only collection in place of
uploads (it cannot discover unknown screens); n-gram hashes (§17).
