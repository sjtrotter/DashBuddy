# ADR-0011: UNKNOWN-Screen Census Privacy Model (hash-only skeletons, k-anonymous vocabulary, trusted installs)

**Status:** Accepted — design record for Epic #1138; the invariants below are the specification the
M1 builders (#1145, #1146) and the server (#1157) implement. Nothing leaves the phone until M3.
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

### 6. **Trusted installs** are the unblinding source (dev amendment 2026-09-29)

A trusted install is a device the operator owns (the dev's phone; later, any self-hoster's phone
against their own server) **running a capture-enabled build** — the redacted envelope it uploads
exists only where a real `CaptureBus` is bound, and every release build binds `NoOpCaptureBus`
(`captureScreen` returns before building anything). A release build therefore cannot be enrolled as
trusted; the server refuses a trusted enrolment that does not declare the capture capability, and a
self-hoster runs the same debug/dev flavour the developer does. It is enrolled explicitly — a per-install key marked trusted server-side
and a dev-settings switch on the client that is never reachable from the consent screen. A trusted
install uploads the **existing redacted capture envelope** for UNKNOWN frames (today's
`captureScreen` order: sensitive drop → rule redact → customer text + id scrub) beside the skeleton
so the two can be paired by the envelope's `captureId` (unique per capture — the census `fingerprint`
is a CLUSTER key shared by many frames and must never be the pairing key, or one frame's hashes would
resolve against another frame's text);
a trusted envelope or a trusted capture the operator already holds locally are the only sources from
which a hash is ever resolved to text. For a trusted install k = 1 by definition, so the whole loop
runs on one phone with zero community contributors — the fleet adds speed and coverage, never a
precondition. Community installs never carry plaintext; the server rejects a clear-text envelope from
a non-trusted key.

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
64 KB size cap per skeleton (over → no skeleton, counted). Clicks and notifications are OUT of v1
(the click envelope is the #919 leak class; a notification body is free text).

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
by the core model (`domain.model.accessibility.AnonymousWrappers`, which the contract imports).
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

## Server-side commitments (implemented by #1157, stated here so the client's disclosure can cite them)

Where this table differs from `~/dashbuddy/design/2026-09-30-census-server/PLAN.md` §2.2 (unresolved
clusters kept indefinitely, rejected hashes kept permanently, no sample-hash rewriting), **this ADR
supersedes the plan** and #1157 updates its table and purge contract, including queued or unblinded
vocabulary rows that lose eligibility.

| Data | Retention |
|---|---|
| Sub-k token hash | 30 days after last sighting, then deleted from EVERY persisted copy — the sightings table, the `h` values inside stored cluster samples (replaced by the SERVER-ONLY value `expired`, distinct from the client's `withheld`; the server rejects `expired` inbound), and rejected vocabulary rows (which keep neither hash nor text past the TTL); never listed, exported or logged in bulk. A community sample is rendered or exported only with its sub-k hashes suppressed |
| Cluster samples | at most 5 skeleton bodies per (cluster, app version), no install id; an UNRESOLVED cluster's samples expire 90 days after the cluster's last sighting; purged 30 days after the cluster resolves |
| Per-install cluster sightings | 90 days rolling (distinct from the 30-day TOKEN sightings above) |
| Install records | deleted on "delete my census data", else 365 days after last activity; ingest ledger 7 days; server logs (install-id PREFIX, counts, reason codes — never bodies) 14 days |
| Trusted-install envelopes | 30 days, earlier once a fixture/rule exists |
| Per-install health rows | 180 days; fleet rollups indefinitely without install ids |
| Backups | encrypted, **14 days** — shorter than the longest TTL, so the worst-case life of a purged sub-k row is TTL + 14 days |
| Text | a community skeleton has no text field to store; the ONE exception is a trusted install's redacted envelope (§6), which can carry text the redact layers missed, kept 30 days |
| Precise timestamps, device identifiers, location | never stored (hour bucket in flight, day at rest; a coarse metro cell only under the #1137 dual opt-in); request bodies are never logged on either side, not even on a parse failure |
| IP addresses | not stored and not logged by default; ONE disclosed exception — during an abuse incident the operator may turn on a proxy access log capped at 72 hours, and `GET /v1/policy` reports `ipLogging: true` for as long as it is on |

The **operator-trust statement** (the residual no design removes): the official server is one
machine operated by the DashBuddy maintainer running the published image digest of an AGPL-3.0
repository; `GET /v1/policy` reports the digest and the retention numbers. The operator can see
shapes, counts, the text of their OWN trusted devices' redacted envelopes (`k_unblind`), and — once
a token entered the shipped chrome vocabulary by one of §4's two routes — that vocabulary; for a community install the
operator cannot see what the dasher typed, whom they delivered to, or what they earned, subject to
the dictionary-linkage residual on low-entropy hashes (risk 1). The endpoint is user-selectable
(the #193 pattern), so a dasher can point the app at any server running this code.

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
  requires fresh trusted enrolment.

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
  recorded here, because nothing uploads before M3.
- **Sequencing:** the TalkBack issues (#1147, #1148, #1149 — and #1151/#1152 from their review) shipped
  FIRST by dev decision (2026-09-29) so the census sees the richer, window-correct tree; the census
  chain is `#1144 → #1145 → #1146`, with #1157 in parallel from #1144/#1145.
- **M2:** the bounded local queue and the in-app viewer (`:core:data` + `:app`).
- **M3 (client, #1138) + #1157 (server):** `census_prefs`, consent + disclosure copy + LEGAL.md +
  Data Safety delta, the uploader in `:core:data` behind `:core:network` (the pipeline never performs
  network I/O — the `CaptureBus`/`LogScrubber` precedent), the trusted-install transport (envelope +
  skeleton pairing, metadata stripped as §6 says), server v0.
- **M4:** unblinding tool, `skeleton.v2`, the working-vocabulary → shipped-allowlist promotion gate.
- **Contract placement (open question 1) — the default until the dev decides otherwise:** an
  Apache-2.0-headed package `cloud.trotter.dashbuddy.domain.census.contract` inside `:domain`, with
  no dependency on anything outside the JDK, kotlinx-serialization, `domain.util.sha256OrNull` and
  `domain.model.accessibility.AnonymousWrappers` (the four-class wrapper set, owned by the core model so
  `UiNode` never imports the contract; amended in #1160); `sha256OrNull` STAYS where it is —
  it is the #362 recognition-side SSOT the parse transform and the redact masks share; on extraction
  the contract module carries its own ten-line copy or depends on `:domain`), so extraction to a
  `census-contract/` included build is a move, not a rewrite.

## Consequences

**Positive.** A leak of text is a type error. The k rule protects contributors while the operator's
own device keeps the loop working at k = 1. The filter and the corpus intake share one pattern owner,
so they cannot drift. The skeleton enumerates the scrub contract, so #1147's fields and any later one
are covered without a census change. Client and server share `CensusFingerprint`; `FrameGate`'s and the librarian's identities are unchanged.

**Negative / tradeoffs.** Chrome that is rare (a screen only one market sees) never crosses k from
community installs alone — the trusted install must see it. The filter over-withholds by design
(`mixed` never hashes, so "Order #1234" contributes only a kind; a symbol-only run is dropped
before counting so `Pickup & delivery` still hashes as `words:2`). The two hash domains (census vs parse)
never produce equal digests, so nothing in the system joins a census cluster to a customer hash by
key — and the census cannot help attribute a delivery; the operator-side dictionary linkage residual
(§3, risk 1) is the honest limit of that statement and the M3 disclosure must not overclaim it. Promoting `PiiShapes`
to `:domain` moves test-only regexes into a shipped module; `IcuRegexGuardTest` and the byte-SSOT pins
must stay green.

## Residual risks (stated, not hidden)

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

## Open questions (dev decisions; the same items appear in #1157's plan §10 under its own numbering — this list is the ADR's reference)

1. Wire-contract licence and location: an Apache-2.0 `census-contract/` included build, or an
   Apache-headed package inside `:domain` for now. Changes #1145's file placement.
2. Whether trusted installs are excluded from `k_ship` (this ADR assumes yes).
3. Backup retention of 14 days.
4. What "contributing" means for the #1137 promo (decides whether the server keeps a metro cell at all).

## References

- #1138 (epic, D1–D7 and the 2026-09-29 trusted-installs amendment), #1144, #1145, #1146, #1157
- `~/dashbuddy/design/2026-09-30-census-server/PLAN.md` (server plan; outside the repo)
- CLAUDE.md § Pledges, § Development Principles 6–7, §1 Redaction invariants / Backstops
- ADR-0009 amendment (same PR): authoring model — drafted and reviewed, not hand-authored
