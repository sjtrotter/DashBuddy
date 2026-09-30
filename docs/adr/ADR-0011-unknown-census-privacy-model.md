# ADR-0011: UNKNOWN-Screen Census Privacy Model (hash-only skeletons, k-anonymous vocabulary, trusted installs)

**Status:** Accepted — design record for Epic #1138; the invariants below are the specification the
M1 builders (#1145, #1146) and the server (#1157) implement. Nothing leaves the phone until M3.
**Issue:** #1144 (census M0), sub-issue of #1138
**Date:** 2026-09-30
**Builds on:** ADR-0003 (versioning and API contracts), ADR-0004 (canonical pipeline architecture),
ADR-0007 (canonical domain schema), ADR-0009 (rule distribution channels — amended in the same PR)
**Related:** #192 (the downstream OTA half), #193 (self-hostable aggregation server, URL sovereignty),
#194 (attestation / poisoning resistance), #1137 (k-promo), #1157 (census server v0),
#835 (`UiNodeTextField` scrub contract), #1147 (richer node fields), #246 (counsel review)

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
chrome by construction), `bounds`, the three flags (`isClickable`/`isEnabled`/`isChecked`), and
`children`. Per text field — enumerated from **`UiNodeTextField`**, the #835 scrub contract, never a
hand-list, so a string field added later (#1147's `paneTitle`, `hintText`, `clickActionLabel`, …) is
covered automatically — an object `{h?, kind}`: `kind` is a coarse shape class and `h` is present
only when the §2 filter admits a hash (§3 only defines how the admitted token is hashed). **The type has no plaintext slot**: a leak of a text value is a
type error. **The window title is the ONE explicit non-node text field** (it lives on
`windowContext`, outside `UiNodeTextField`) and goes through the same `{h?, kind}` rule; it is the
single named exception to "enumerate the enum", and `SkeletonCorpusTest` names it. `deviceFingerprint`
is dropped from skeleton metadata.

**Bounds are carried only by nodes with NO text-field value, quantized to a 16 × 32 grid of the
window.** A `wrap_content` text node's width is a function of its rendered text — a withheld name
would still leak its length class through its right edge, and raw pixels also encode screen size and
density — so text-bearing nodes carry no bounds at all, and the layout a human needs to read a
cluster comes from the container nodes' coarse cells. Nothing ever clusters or keys on bounds.

The permitted fields, at both levels, are the test allowlist (§7a): per node `class`, `id`, `kind`,
`h` (strings) plus the coarse `bounds` cell and the three flags; per envelope the strings `schemaId`,
`fingerprint`, `platform`, `platformAppVersion`, `appVersion`, `rulesetReleaseTag`, `day` (an `hour`
bucket in flight only) and the integers `filterRev`, `engineVersion`, `rulesetFormatVersion` — the
names are `ReplayMetadata`'s own. The install id is added by the M3 uploader at the transport layer,
never inside the skeleton.

`kind` is a NORMATIVE classifier, evaluated on the trimmed canonical value (the same bytes that would
be hashed), first match wins:

| Precedence | `kind` | Rule |
|---|---|---|
| 1 | `withheld` | any filter step in §2 withheld the field — a CONSTANT, so a caught PII slot reveals nothing, not even its word count |
| 2 | `digits` | every non-whitespace character is a Unicode decimal digit (`Character.isDigit`) |
| 3 | `words:N` | split on whitespace runs; strip LEADING and TRAILING punctuation from each run (interior apostrophes and hyphens are kept, so `O'Brien` and `Drop-off` are one run each); every run must contain at least one Unicode letter and no digit; N = the run count, `words:8+` when N > 8 |
| 4 | `mixed` | everything else — money (`$45.66`), clock times, unit numbers, gate codes, order ids, plates, symbols, emoji |

A null or blank field is OMITTED from the skeleton, never emitted. Money and time are deliberately
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
5. (reserved — the former "contains `[redacted`" step is dropped: the builder consumes the RAW
   admitted tree, on which no mask exists; a mask token in a corpus fixture classifies as `mixed`
   and never hashes anyway);
6. `kind` is not `words:N` — **only `words:N` tokens are ever hashed**. The `kind` grammar is the
   digit backstop: any run containing a digit makes the token `digits` or `mixed`, never `words:N`, so gate codes, PINs, unit numbers and phone fragments emit `kind` only by construction
   (a future grammar change that lets a digit into `words:N` must re-add an explicit digit rule);
7. `PiiShapes.FIRST_LAST_INITIAL_PATTERN` (the id-less name shape, byte-SSOT with the redact side —
   the existing match mode, `matches` with `IGNORE_CASE`, is preserved);
8. any other promoted `PiiShapes` pattern (street, city/state/ZIP, full address, bare street,
   apartment, PIN, quoted note, phone, email, card).

**What the builder consumes.** `SkeletonBuilder` takes the RAW admitted `UiNode` tree at the
post-admission stage (#1146's publisher, after the rulesets-loaded / sensitive / disabled-platform /
UNKNOWN gates and `UnknownSuppressor`), never the capture DTO — the release build binds
`NoOpCaptureBus` and produces no DTO, and the census must work in the only build that uploads. The
corpus tests feed fixture trees that ARE masked captures; that is a superset condition (a `[redacted…]`
token is `mixed`), not the runtime shape.

**Inputs and predicates, exactly.** Every step sees the TRIMMED canonical value — the same bytes
`CensusHash` would hash — so a leading space cannot slip a prefix past a `startsWith`. Step 1 uses the
two EXISTING predicates as they are: `ID_MARKERS` is a case-insensitive SUFFIX match on the full
resource id (`CustomerTextMarkers.hasIdSuffix`), `PII_ID_SUFFIXES` is exact membership of the id's
part after the last `/` (the `SnapshotRedactor` rule); a hit on either withholds.

A `SensitiveTextMarkers` hit anywhere on the frame → **no skeleton at all** (the dasher's banking
surfaces are blocked, never described). That frame-level scan is the EXISTING runtime control on the
capture path and runs as it does today; the bounded-input claim above is about the per-field census
filter.

**Module homes.** `SkeletonBuilder` lives in `:core:pipeline`, because steps 1, 4 and the frame drop
need `ID_MARKERS`, `CustomerTextMarkers` and `SensitiveTextMarkers`, which live there and which
`:domain` may not depend on. The wire contract — `UiSkeletonDto`, `SkeletonSchema`, `CensusHash`,
the fingerprint — lives in `:domain` or the Apache-2.0 contract module (open question 1). The
promotion to `:domain` (`privacy/PiiShapes.kt`) covers EVERY test-only pattern the filter uses:
`FIRST_LAST_INITIAL_PATTERN`, the shape patterns, `NAME_PREFIXES`, `GATED_NAME_PREFIXES`,
`PII_ID_SUFFIXES` and `customerLeadIn()`; `SnapshotRedactor` delegates to it with byte-SSOT pins.

**A withheld field emits the constant `kind: withheld` — no length, no word count, no hash.** A
length or a word count is a small leak on a name and would break invariant 7 (pseudonym invariance);
the bounds rule in §1 closes the same leak through geometry. Because `PiiShapes` is the one owner on both sides, the census filter and the corpus intake
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

A token that k = 10 distinct installs rendered is chrome by definition; a token one install rendered
is somebody's customer. There are TWO named predicates, and every read says which it uses:

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
**30 days after their last sighting** and then deleted; they are **never listed, never exported,
never logged in bulk**. Both predicates are evaluated on every read (dashboard, export, promotion),
never stored as a flag, so a cohort that shrinks below k simply stops being served — a dip degrades
availability, never privacy.

The unblinded set is the **chrome vocabulary**; it ships DOWN inside the signed rule bundle (#641).
From `uinode.skeleton.v2` on, a client may send an allowlisted token in the clear, enforced
client-side by the bundle's list and server-side by rejection of any clear token not on it.

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

The trusted path reuses the existing scrubbed PAYLOAD, not the existing metadata: `EnvelopeBuilder`
stamps `deviceFingerprint` (`Build.FINGERPRINT`) and a millisecond timestamp, so the trusted
transport (M3) strips the device fingerprint and reduces the timestamp to the documented precision
(hour in flight, day at rest) before anything persists. And the promise is scoped honestly: **the
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
any text-bearing node; (b) invariance under two shape-matched pseudonym
substitutions; (c) redactor parity — any value `SnapshotRedactor.redact` changes has no `h`; (d)
every `SENSITIVE/` fixture the markers catch yields no skeleton; (e) no `h` equals the hash of any
`CorpusDecoys` value or mask token; (f) determinism and idempotence. A seeded property (#878) adds:
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
recompute safely and which must not change type. The fingerprint is a full sha256 (**64 hex**) over a
published canonical form — UTF-8; per node `class`, `id`, child count, then the children in order;
null represented distinctly from empty — with shared client/server test vectors. It keeps ONE
structural rule of `stableHash` deliberately: an **anonymous wrapper** (no id AND a class in
`{android.view.View, android.view.ViewGroup, android.widget.FrameLayout, android.widget.LinearLayout}`)
is TRANSPARENT (`A(Wrapper(C)) == A(C)`), because a Compose recomposition adds and removes such
wrappers and the cluster must not split on them; every other boundary is preserved
(`A(B(C)) ≠ A(B, C)`). `stableHash` and `UnknownSuppressor` are untouched; the wrapper class set is
shared with `computeStableHash` through one constant so the two rules cannot drift. (The corpus librarian's variant check is a TEXT fingerprint and
`FrameGate`'s identity is `Observation.identity()`; neither is touched — a structural key would
collapse the librarian's store-distinct variants.) The server RECOMPUTES the fingerprint from the
skeleton it received rather than trusting the client's (#1157), which is why the wire contract must
be consumable by an AGPL server (open question 1).

### 9. Rules are **proposed**, never auto-pushed (D7)

A cluster that crosses a threshold yields a shape bundle → a drafted rule + synthetic fixture → a
matchers PR → the existing CI gates (`AllMatchersSuite`: golden, negative corpus, sensitive-screen
invariant, redact parity) → **human approval** → signing (#641) → OTA (#640). Over-match forges
state and a wrong redact ships PII to every phone; both are silent in the field and caught only by
the corpus plus a reader, so the human gate is structural. The authoring-model consequence is
recorded as the ADR-0009 amendment.

## Server-side commitments (implemented by #1157, stated here so the client's disclosure can cite them)

| Data | Retention |
|---|---|
| Sub-k token hash | 30 days after last sighting, then deleted from EVERY persisted copy — the sightings table, the `h` values inside stored cluster samples (rewritten to `withheld`), and rejected vocabulary rows (which keep neither hash nor text past the TTL); never listed, exported or logged in bulk. A community sample is rendered or exported only with its sub-k hashes suppressed |
| Cluster samples | at most 5 skeleton bodies per (cluster, app version), no install id; an UNRESOLVED cluster's samples expire 90 days after the cluster's last sighting; purged 30 days after the cluster resolves |
| Per-install cluster sightings | 90 days rolling (distinct from the 30-day TOKEN sightings above) |
| Install records | deleted on "delete my census data", else 365 days after last activity; ingest ledger 7 days; server logs (install-id PREFIX, counts, reason codes — never bodies) 14 days |
| Trusted-install envelopes | 30 days, earlier once a fixture/rule exists |
| Per-install health rows | 180 days; fleet rollups indefinitely without install ids |
| Backups | encrypted, **14 days** — shorter than the longest TTL, so the worst-case life of a purged sub-k row is TTL + 14 days |
| Raw text, precise timestamps, device identifiers, location | never stored (hour bucket in flight, day at rest; a coarse metro cell only under the #1137 dual opt-in); request bodies are never logged on either side, not even on a parse failure |
| IP addresses | not stored and not logged by default; ONE disclosed exception — during an abuse incident the operator may turn on a proxy access log capped at 72 hours, and `GET /v1/policy` reports `ipLogging: true` for as long as it is on |

The **operator-trust statement** (the residual no design removes): the official server is one
machine operated by the DashBuddy maintainer running the published image digest of an AGPL-3.0
repository; `GET /v1/policy` reports the digest and the retention numbers. The operator can see
shapes, counts and — once k installs have shown a token — the chrome vocabulary; the operator cannot
see what a dasher typed, whom they delivered to, or what they earned. The endpoint is user-selectable
(the #193 pattern), so a dasher can point the app at any server running this code.

## Lifecycle: schema, filter and identity evolution

- **Schema versions** are explicit wire fields (`schemaId`); the server negotiates accepted versions
  through `GET /v1/policy` and rejects the rest. Adding a field on the client (a new `UiNodeTextField`
  entry) is a schema bump, not a silent widening — the server rejects unknown fields.
- **Hash versions** are the domain prefix (`census.v1:`); counts are NEVER pooled across hash
  versions, so a normalization change starts a new count.
- **Filter revisions** ride the envelope as `filterRev`; when a revision is found unsafe (a shape the
  filter should have withheld), the server drops queued and stored items from that revision and the
  client's bundle carries the minimum accepted revision — an old client cannot contribute until it
  updates.
- **Vocabulary removal** is a bundle release (see §4); a token found to be PII after shipping is
  removed from the allowlist and purged server-side, and clear tokens for it are rejected from the
  next accepted bundle version on.
- **Install identity**: secret rotation preserves the install id and its counts; a reset deletes the
  old identity first (the deletion contract), enrols anew, restarts the 7-day quarantine, and
  requires fresh trusted enrolment.

## Ownership by milestone (the seams the builders keep)

- **#1145 (M1a, pure):** the wire contract (`UiSkeletonDto`, `SkeletonSchema`, `CensusHash`,
  `CensusFingerprint` + vectors, the `kind` classifier + vectors, the shared anonymous-wrapper class
  constant) and `SkeletonBuilder` with the §2 filter; the `PiiShapes` promotion. No wiring, no I/O.
- **#1146 (M1b, inert):** the `CensusSink` interface in `:domain`, a `NoOp` binding, the
  post-admission publisher stage in `:core:pipeline`, `PipelineStats` counters. Nothing leaves the
  device.
- **M2:** the bounded local queue and the in-app viewer (`:core:data` + `:app`).
- **M3 (client, #1138) + #1157 (server):** `census_prefs`, consent + disclosure copy + LEGAL.md +
  Data Safety delta, the uploader in `:core:data` behind `:core:network` (the pipeline never performs
  network I/O — the `CaptureBus`/`LogScrubber` precedent), the trusted-install transport (envelope +
  skeleton pairing, metadata stripped as §6 says), server v0.
- **M4:** unblinding tool, `skeleton.v2`, the working-vocabulary → shipped-allowlist promotion gate.
- **Contract placement (open question 1) — the default until the dev decides otherwise:** an
  Apache-2.0-headed package `cloud.trotter.dashbuddy.domain.census.contract` inside `:domain`, with
  no dependency on anything outside the JDK and `sha256OrNull` (which moves into the package), so
  extraction to a `census-contract/` included build is a move, not a rewrite.

## Consequences

**Positive.** A leak of text is a type error. The k rule protects contributors while the operator's
own device keeps the loop working at k = 1. The filter and the corpus intake share one pattern owner,
so they cannot drift. The skeleton enumerates the scrub contract, so #1147's fields and any later one
are covered without a census change. The fingerprint has one owner across FrameGate, the librarian
and the server.

**Negative / tradeoffs.** Chrome that is rare (a screen only one market sees) never crosses k from
community installs alone — the trusted install must see it. The filter over-withholds by design
(`mixed` never hashes, so "Order #1234" contributes only a kind). The two hash domains (census vs parse)
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
4. **Container geometry.** Coarse-grid bounds on non-text containers can still hint at layout
   variants (a taller list = more rows). Accepted: rows are not identities, and no text node carries
   bounds.
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
