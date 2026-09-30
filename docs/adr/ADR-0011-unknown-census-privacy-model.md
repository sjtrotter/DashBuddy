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
chrome by construction), the three flags (`isClickable`/`isEnabled` as booleans, `isChecked` as the
`UiNode` tri-state `Int` 0/1/2 — wire types stated so the shared vectors cannot disagree), and
`children`. Per text field — enumerated from **`UiNodeTextField`**, the #835 scrub contract, never a
hand-list, so #1147's strings (`paneTitle`, `hintText`, `clickActionLabel`, …) and any entry added
later are covered automatically; the node's text fields travel as a MAP keyed by the enum's wire key,
and the server accepts ANY key in that map (every value has the same `{h?, kind}` shape, so a new key
is not a privacy change) while rejecting unknown fields everywhere else — an object `{h?, kind}`: `kind` is a coarse shape class and `h` is present
only when the §2 filter admits a hash (§3 only defines how the admitted token is hashed). **The type has no plaintext slot**: a leak of a text value is a
type error. **`uniqueId` (`uid`) is the one enum entry treated as an IDENTIFIER, not a text slot**:
it is an app-assigned identifier (a Compose test tag — the only stable handle on an id-less Compose
card, the #1114 class), so it travels in the CLEAR beside `id` when it passes the identifier gate
(≤ 40 chars, only `[A-Za-z0-9_.:/-]`, no run of 3+ digits, no `CustomerTextMarkers` marker, not a
mask) and is `withheld` otherwise; it is never hashed. **The window title is the ONE explicit non-node
text field** (it lives on
`windowContext`, outside `UiNodeTextField`) and goes through the same `{h?, kind}` rule; it is the
single named exception to "enumerate the enum", and `SkeletonCorpusTest` names it. `deviceFingerprint`
is dropped from skeleton metadata.

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
be hashed), first match wins. It is computed in two stages so that classification and filtering are
not circular: the grammar (rows 2–4 below) yields an intermediate `shapeKind`; §2's filter then
consults `shapeKind` (step 6) and decides; the EMITTED `kind` is `withheld` when a withholding step
fired, otherwise `shapeKind` — so `digits` and `mixed` ARE emitted (hash refused), and `words:N` is
emitted only together with its hash.

| Precedence | `kind` | Rule |
|---|---|---|
| 1 | `withheld` | a WITHHOLDING filter step in §2 (steps 1–5, 7, 8) caught the field — a CONSTANT, so a caught PII slot reveals nothing, not even its word count. Step 6 is NOT a withholding step: it only refuses the HASH, and the token keeps its `digits`/`mixed` kind |
| 2 | `digits` | every non-whitespace character is a Unicode decimal digit (`Character.isDigit`) |
| 3 | `words:N` | split on whitespace runs; DROP any run that contains no letter and no digit (`&`, `→`, `-`, emoji-only — so `Pickup & delivery` is `words:2`); strip LEADING and TRAILING punctuation (Unicode general category P*) from each remaining run (interior apostrophes and hyphens are kept, so `O'Brien` and `Drop-off` are one run each); every remaining run must contain at least one Unicode letter and no digit; N = the run count, `words:8+` when N > 8 |
| 4 | `mixed` | everything else — money (`$45.66`), clock times, unit numbers, gate codes, order ids, plates, symbols, emoji |

A null or blank field is OMITTED from the skeleton before any classification, never emitted —
including on a node whose id would withhold it. Money and time are deliberately
NOT classes of their own: `CurrencyShape` lives in `:core:pipeline` and the contract module may not
depend on it, and a second currency regex would be exactly the SSOT drift `CurrencyShapePinTest`
exists to prevent; a money or time slot is anchored by its id/class and siblings when a rule is
drafted. Shared client/server golden vectors for this table live in the contract module; the server
verifies `kind` where it has text — on trusted envelopes (paired plaintext) and on the vectors — and
cannot re-classify a community skeleton, which carries no text. A skeleton item is capped at **65 536
uncompressed UTF-8 bytes of its serialized JSON** (the whole item, metadata included); over the cap
→ no skeleton, counted.

### 2. The **chrome-likely filter** decides whether a token may even be hashed (D2)

Hash-only is necessary, not sufficient: a hash of a low-entropy value (a first name, a street) is
dictionary-attackable. Before hashing, `SkeletonBuilder` runs every text field through the SAME
SSOTs the redact side uses, in this order, and **any hit withholds the hash**:

1. the node id is in `ID_MARKERS ∪ PII_ID_SUFFIXES` (a view id whose value is PII by construction;
   the two sets are deliberately NOT the same set — the union is used);
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
6. `shapeKind` is not `words:N` — **only `words:N` tokens are ever hashed**. This step SHORT-CIRCUITS:
   a `digits`/`mixed` token is emitted with that kind and no hash and steps 7–8 do not run on it (there
   is nothing left to protect); steps 7–8 run only on `words:N` survivors. The `kind`
   grammar is the digit backstop: any run containing a digit makes the token `digits` or `mixed`, never `words:N`, so gate codes, PINs, unit numbers and phone fragments emit `kind` only by construction
   (a future grammar change that lets a digit into `words:N` must re-add an explicit digit rule);
7. `PiiShapes.FIRST_LAST_INITIAL_PATTERN` (the id-less name shape, byte-SSOT with the redact side —
   the existing match mode, `matches` with `IGNORE_CASE`, is preserved);
8. any other promoted `PiiShapes` pattern — each keeps the match mode `SnapshotRedactor` uses today
   (`BARE_STREET` whole-value, the others substring), pinned by the byte-SSOT tests. Because only
   `words:N` tokens reach this step, the digit-bearing shapes (street number, ZIP, apartment, PIN,
   phone, card) cannot fire here and are covered by step 6; the letter-only shapes (quoted note,
   city/state, email's local part) are the ones this step exists for. The list stays the one
   `PiiShapes` owner — no census-specific subset is hand-maintained.

**What the builder consumes.** `SkeletonBuilder` takes the RAW admitted `UiNode` tree plus the
window title. #1146's publisher sits on the UNKNOWN SCREEN BRANCH of `AccessibilityPipeline.output()`
— after the rulesets-loaded / sensitive / disabled-platform gates and `FrameGate.admit`, and BEFORE
the terminal filter that keeps UNKNOWN observations away from the state machine (placing it after
that filter would receive nothing). It never consumes the capture DTO — the release build binds
`NoOpCaptureBus` and `captureScreen` returns before its scans run, so the census must work in the only
build that uploads. The
corpus tests feed fixture trees that ARE masked captures; that is a superset condition (every mask
token is caught by step 5 and emits `withheld`), not the runtime shape.

**Inputs and predicates, exactly.** Every step sees the TRIMMED canonical value — the same bytes
`CensusHash` would hash — so a leading space cannot slip a prefix past a `startsWith`. Step 1 uses the
two EXISTING predicates as they are: `ID_MARKERS` is a case-insensitive SUFFIX match on the full
resource id (`ID_MARKERS.any { id.endsWith(it, ignoreCase = true) }`, today inline in
`CustomerTextMarkers.unredactedIdMarker`; #1145 extracts it as the shared helper both call),
`PII_ID_SUFFIXES` is exact membership of the id's part after the last `/` (the `SnapshotRedactor`
rule); a hit on either withholds.

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
`FIRST_LAST_INITIAL_PATTERN`, the shape patterns, `NAME_PREFIXES`, `GATED_NAME_PREFIXES`,
`PII_ID_SUFFIXES` and `customerLeadIn()`; `SnapshotRedactor` delegates to it with byte-SSOT pins.

**A withheld field emits the constant `kind: withheld` — no length, no word count, no hash.** A
length or a word count is a small leak on a name and would break invariant 7 (pseudonym invariance); §1 carries no geometry at all for the same reason. Because `PiiShapes` is the one owner on both sides, the census filter and the corpus intake
can never drift apart.

### 3. Hashing: unsalted sha256 with a domain-separation prefix

`CensusHash.of(text) = sha256("census.v1:" + trimmed)`, first 16 hex, through the fail-closed
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
against their own server). It is enrolled explicitly — a per-install key marked trusted server-side
and a dev-settings switch on the client that is never reachable from the consent screen. A trusted
install uploads the **existing redacted capture envelope** for UNKNOWN frames (today's
`captureScreen` order: sensitive drop → rule redact → customer text + id scrub) beside the skeleton
so the two can be paired (the trusted transport stamps the envelope with the same census
`fingerprint` it computed for the skeleton);
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
the residual for an uncaught value is stated in risk 6. `SkeletonCorpusTest` (#1145) walks the ENTIRE corpus
including `SENSITIVE/` and `UNKNOWN/negative/` and asserts (a) no string field outside the §1
allowlist (per node `class`/`id`/`kind`/`h`; per envelope the enumerated metadata) and no bounds on
any node; (b) invariance under two shape-matched pseudonym
substitutions; (c) redactor parity — any value `SnapshotRedactor.redact` changes has no `h` (load-bearing only on
the pseudonym and decoy fixtures: on an already-redacted committed fixture `redact` is idempotent); (d)
every `SENSITIVE/` fixture the markers catch yields no skeleton; (e) no `h` equals `CensusHash.of(x)` (trimmed, as the builder hashes) for any PII-VALUED
`CorpusDecoys` entry (pseudonym names, addresses, notes — not retained chrome labels such as
`"Hand it to me: "`, which are legitimately hashed) or for any mask token; (f) determinism and idempotence. A seeded property (#878) adds:
no string matching any `PiiShapes` pattern ever hashes, and no output contains an input token
verbatim outside `class`/`id`.

### 8. Bounded, budgeted, deduplicated (D5)

Only frames that pass today's gates reach the census: rulesets loaded, sensitive/noise dropped,
disabled platform dropped, `UnknownSuppressor` dedup. On top: a per-install daily budget (300
skeletons), a per-cluster cap, a bounded on-disk queue (drop-oldest), batch upload with backoff, a
64 KB size cap per skeleton (over → no skeleton, counted). Clicks and notifications are OUT of v1
(the click envelope is the #919 leak class; a notification body is free text).

**Cluster fingerprint** (`CensusFingerprint`) is a NEW function in the contract module, not
today's `stableHash`: `stableHash` is a 32-bit `Int` (`31 * h + child`, collidable, and the type of
`FrameGate.admit(contentHash)` and of every fixture's `contentHash`), which the server cannot
recompute safely and which must not change type. The fingerprint is a full sha256 (**64 hex**) over ONE canonical byte form, pre-order: per node
`"C"` + class (UTF-8, `""` when null) + `0x00` + (`"I"` + id UTF-8, or the single byte `"N"` when the
id is null — so a null id and an empty id differ) + `0x00` + the spliced child count as ASCII decimal
+ `0x00`, then the children in order. Transparent wrappers are removed first (wrapper-to-forest
normalization): a wrapper's children are spliced into its parent, an EMPTY wrapper contributes
nothing (the parent's count drops), and when the ROOT itself is a wrapper the forest hangs under a
synthetic root with class `""`, null id and the spliced count. The contract module publishes vectors
for: null vs empty id, an empty wrapper, a multi-child wrapper, a wrapper root, and the two nesting
cases below. It keeps ONE
structural rule of `stableHash` deliberately: an **anonymous wrapper** (no id AND a class in
`{android.view.View, android.view.ViewGroup, android.widget.FrameLayout, android.widget.LinearLayout}`)
is TRANSPARENT: it contributes no node of its own and its children are SPLICED into its parent's
child sequence in order (`A(W(C1, C2)) == A(C1, C2)`; the parent's child count is the spliced count),
because a Compose recomposition adds and removes such wrappers and the cluster must not split on
them; every other boundary is preserved (`A(B(C)) ≠ A(B, C)`). This is the census's OWN rule — today's
`computeStableHash` folds a wrapper's children as a nested group and never splices, so the two
algorithms are deliberately different; only the wrapper CLASS SET is shared through one constant.
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
  skeleton together with the census `fingerprint` and, when a capture envelope exists, that envelope's
  `captureId`, so a trusted transport can pair the two with no new redaction code. Nothing leaves the
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
  no dependency on anything outside the JDK and `domain.util.sha256OrNull` (which STAYS where it is —
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
