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
only when invariant 3 admits a hash. **The type has no plaintext slot**: a leak of a text value is a
type error. **The window title is the ONE explicit non-node text field** (it lives on
`windowContext`, outside `UiNodeTextField`) and goes through the same `{h?, kind}` rule; it is the
single named exception to "enumerate the enum", and `SkeletonCorpusTest` names it. `deviceFingerprint`
is dropped from skeleton metadata.

**Bounds are carried only by nodes with NO text-field value, quantized to a 16 × 32 grid of the
window.** A `wrap_content` text node's width is a function of its rendered text — a withheld name
would still leak its length class through its right edge, and raw pixels also encode screen size and
density — so text-bearing nodes carry no bounds at all, and the layout a human needs to read a
cluster comes from the container nodes' coarse cells. Nothing ever clusters or keys on bounds.

The permitted string fields, at both levels, are the test allowlist (§7a): per node `class`, `id`,
`kind`, `h`; per envelope `schemaId`, `fingerprint`, `platform`, `platformAppVersion`, `appVersion`,
`rulesetVersion`, `engineVersion`, and a `day` bucket (an `hour` bucket in flight only). The install
id is added by the M3 uploader at the transport layer, never inside the skeleton.

`kind` grammar: `empty | digits | money | time | words:N (N ≤ 8) | mixed`. `mixed` (letters +
digits) covers unit numbers, gate codes, order ids and licence plates in one class.

### 2. The **chrome-likely filter** decides whether a token may even be hashed (D2)

Hash-only is necessary, not sufficient: a hash of a low-entropy value (a first name, a street) is
dictionary-attackable. Before hashing, `SkeletonBuilder` runs every text field through the SAME
SSOTs the redact side uses, in this order, and **any hit withholds the hash**:

1. the node id is in `ID_MARKERS ∪ PII_ID_SUFFIXES` (a view id whose value is PII by construction;
   the two sets are deliberately NOT the same set — the union is used);
2. length > 40 characters — **this cap runs before any pattern in this list**, so every step below
   runs on bounded input on the device (the #803 instruction-body class never hashes);
3. the value contains `[redacted` (already masked upstream — never re-hash a mask);
4. `CustomerTextMarkers.unredactedMarker` hits;
5. `PiiShapes.customerLeadIn` hits (the intake prefix rule, incl. `GATED_NAME_PREFIXES`);
6. `kind` is not `words:N` — **only `words:N` tokens are ever hashed**. The `kind` grammar is the
   digit backstop: any token containing a digit classifies as `digits` or `mixed` and can never be
   `words:N`, so gate codes, PINs, unit numbers and phone fragments emit `kind` only by construction
   (a future grammar change that lets a digit into `words:N` must re-add an explicit digit rule);
7. `PiiShapes.FIRST_LAST_INITIAL_PATTERN` (the id-less name shape, byte-SSOT with the redact side);
8. any other promoted `PiiShapes` pattern (street, city/state/ZIP, full address, bare street,
   apartment, PIN, quoted note, phone, email, card).

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

**A withheld field emits `kind` only — no length, no hash.** A length is a small leak on a name and
would break invariant 7 (pseudonym invariance); the bounds rule in §1 closes the same leak through
geometry. Because `PiiShapes` is the one owner on both sides, the census filter and the corpus intake
can never drift apart.

### 3. Hashing: unsalted sha256 with a domain-separation prefix

`CensusHash.of(text) = sha256("census.v1:" + trimmed)`, first 16 hex, through the fail-closed
`sha256OrNull` (a failure withholds; plaintext is never echoed, #362). Unsalted because
**cross-install equality is what k-anonymity needs**; the prefix keeps census hashes unjoinable
against the parse-side customer hashes (`customerNameHash`) and the redact masks (`[redacted:<4hex>]`
hashes the stripped token with no prefix).

### 4. **k-anonymity on strings** is a read-time gate on the server (D3)

A token that k = 10 distinct installs rendered is chrome by definition; a token one install rendered
is somebody's customer. There are TWO named predicates, and every read says which it uses:

- **`k_unblind`** — a hash may be resolved to text only from a **trusted install's own envelope**
  (invariant 6) or the operator's local corpus, never by asking a contributor. For a trusted install
  this is k = 1: the operator's phone alone resolves every hash it has itself rendered, which is
  what lets the whole loop run on one phone. The resolved text is visible to the operator only.
- **`k_ship`** — a resolved token is promoted to the SHIPPED chrome vocabulary (the allowlist that
  rides the signed bundle and permits clear-text sending in `skeleton.v2`) only once **≥ 10
  distinct community installs, past a 7-day quarantine and excluding trusted installs** (open
  question 2), have shown the same hash.

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
DataStore, `census_prefs`. The disclosure copy, the LEGAL.md paragraph and the Data Safety form
delta are written in M3 against the screen they belong to (moved out of M0 by dev decision
2026-09-29) — nothing leaves the phone before then, so nothing is disclosed before then.

### 6. **Trusted installs** are the unblinding source (dev amendment 2026-09-29)

A trusted install is a device the operator owns (the dev's phone; later, any self-hoster's phone
against their own server). It is enrolled explicitly — a per-install key marked trusted server-side
and a dev-settings switch on the client that is never reachable from the consent screen. A trusted
install uploads the **existing redacted capture envelope** for UNKNOWN frames (today's
`captureScreen` order: sensitive drop → rule redact → customer text + id scrub) beside the skeleton
so the two can be paired; that envelope is the only source from which a hash is ever resolved to
text. For a trusted install k = 1 by definition, so the whole loop runs on one phone with zero
community contributors — the fleet adds speed and coverage, never a precondition. Community installs
never carry plaintext; the server rejects a clear-text envelope from a non-trusted key. The residual
is the one the project already accepts on disk (a marker backstop can miss an UNKNOWN-path value),
so trusted envelopes are swept manually and stored under the same retention the pull directories get
(30 days, earlier once a fixture or rule exists).

### 7. **Pseudonym invariance** is the structural property the tests pin

For every fixture with a hand-pseudonymized twin, `skeleton(raw) == skeleton(pseudonymized)`: a PII
token can never change what leaves the phone. `SkeletonCorpusTest` (#1145) walks the ENTIRE corpus
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

**Cluster fingerprint** = sha256 over the same pre-order `(class, id)` key sequence
`UiNode.computeStableHash` hashes, refactored to expose the sequence (`stableKeySequence()`) so
`stableHash` — today's `UnknownSuppressor` identity and the capture `contentHash` — and the census
cluster key share ONE owner. (The corpus librarian's variant check is a TEXT fingerprint and
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
| Sub-k token hash | 30 days after last sighting, then deleted; never listed, exported or logged in bulk |
| Cluster samples | at most 5 skeleton bodies per (cluster, app version), no install id; purged 30 days after the cluster resolves |
| Per-install sightings | 90 days rolling |
| Trusted-install envelopes | 30 days, earlier once a fixture/rule exists |
| Per-install health rows | 180 days; fleet rollups indefinitely without install ids |
| Backups | encrypted, **14 days** — shorter than the longest TTL, so the worst-case life of a purged sub-k row is TTL + 14 days |
| IP addresses, raw text, precise timestamps, device identifiers, location | never stored (hour bucket in flight, day at rest; a coarse metro cell only under the #1137 dual opt-in) |

The **operator-trust statement** (the residual no design removes): the official server is one
machine operated by the DashBuddy maintainer running the published image digest of an AGPL-3.0
repository; `GET /v1/policy` reports the digest and the retention numbers. The operator can see
shapes, counts and — once k installs have shown a token — the chrome vocabulary; the operator cannot
see what a dasher typed, whom they delivered to, or what they earned. The endpoint is user-selectable
(the #193 pattern), so a dasher can point the app at any server running this code.

## Consequences

**Positive.** A leak of text is a type error. The k rule protects contributors while the operator's
own device keeps the loop working at k = 1. The filter and the corpus intake share one pattern owner,
so they cannot drift. The skeleton enumerates the scrub contract, so #1147's fields and any later one
are covered without a census change. The fingerprint has one owner across FrameGate, the librarian
and the server.

**Negative / tradeoffs.** Chrome that is rare (a screen only one market sees) never crosses k from
community installs alone — the trusted install must see it. The filter over-withholds by design
(`mixed` never hashes, so "Order #1234" contributes only a kind). Two hash domains (census vs parse)
are deliberately unjoinable, so a census cluster can never be correlated with a customer hash — that
is the point, and it also means the census cannot help attribute a delivery. Promoting `PiiShapes`
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
5. **Dictionary attack by the operator on sub-k hashes.** Sub-k hashes are never listed, but the
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
