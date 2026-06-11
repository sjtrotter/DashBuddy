# 2026-06-11 — Rule engine security audit

Security review of the JSON rule engine (`core/pipeline/.../rules/` + the
asset rule files), prompted by the new **Security & Privacy First** principle
(CLAUDE.md #6) and the planned matchers split (#192 — recognition rules
published to a separate repo and delivered to running instances over CDN).

**Framing.** The split isn't built. Today the engine loads only **bundled,
trusted** rule files from `assets/rules/`. This audit reads the engine against
the threat model it is *heading toward*: the moment rule JSON arrives from a
forkable, network-delivered source, every rule file becomes **untrusted
input**. The findings are split into "live today" and "must-fix-before-remote."
None is currently exploitable by a third party, because no untrusted rule path
is wired — but several are load-bearing prerequisites for #192, and one
(ReDoS) can be tripped by a merely *careless* rule author even while rules stay
bundled.

## What's already strong (verified, not assumed)

- **Bounded ingestion of the third-party UI tree** (#363): `TreeBudget` caps
  depth (60) and node count (4 000); every `getChild` is a binder IPC and the
  tree is serialized into captures, so this is a real DoS boundary.
- **Compile-time rule validation** (#362): unknown transforms / navigation
  verbs / `onFail` values / malformed transform params all fail at load, not at
  match time; regex length capped (200); predicate nesting capped (`MAX_DEPTH`
  20); file size capped (1 MB).
- **Transforms are a closed, pure vocabulary** — string→string functions, no IO,
  no reflection into arbitrary types, no `eval`. There is no deserialization
  gadget surface: kotlinx `Json` decodes into known `@Serializable` types only.
- **Fail-closed privacy hash** (#362): `sha256OrNull` returns null (never the
  plaintext) on failure; PII is hashed at the edge.
- **Sensitive screens blocked in both pipelines** (#399), **captures debug-only**
  (#346), **api_key redacted from logs** (#348).
- **Unique-priority compile check**: two rules with the same priority in one
  context is a compile error, so within a bundle the priority-0 sensitive rule
  can't be shadowed by a sibling.

## Findings

### S1 — No signature / integrity verification on the rule-load path (HIGH, gates #192)
`JsonRuleInterpreter.loadSingle()` / `load()` accept and compile **any** JSON
string; `load()` is documented "for CDN hot-reload" and **replaces the entire
ruleset**. There is no signature check, no pinned public key, no checksum. The
matchers pillar's promise is "**signed** JSON over CDN" — that verification gate
does not exist. Consequence once remote loading is wired: a tampered/MITM'd or
malicious-fork bundle can silently **drop the sensitive-screen rules** (so
banking screens classify as benign and reach capture/state) or redefine
recognition wholesale.
**Fix (prereq for #192):** verify a detached signature against a pinned/
configured public key *before* `loadSingle` compiles anything; reject unsigned
or mismatched bundles and keep the last-good ruleset. The "switch sources via
configuration" forkability must verify against the *configured* source's key,
not skip verification.

### S2 — `isPermissionGranted` is a hardcoded `true` (HIGH, gates #192 / multi-user)
`SideEffectEngine.isPermissionGranted(tier)` returns `true` for every
`PermissionTier`. The tier system is fully built (verbs carry tiers; the engine
checks before dispatch) but **inert** — so every rule-declared effect fires,
including `CLICK` (ACCESSIBILITY) and `SPEAK` (AUDIO). Intended for the
single-user bundled-rule build (the code comment says so). But a rule can
request an auto-click on an arbitrary target; once rules are remote, that's
arbitrary UI actuation (accept offers, tap through dialogs) driven by untrusted
input.
**Fix (prereq for #192 / the multi-user work):** make the grant check real —
back it with actual granted permissions / a user-confirmed capability set — so a
denied tier genuinely blocks the effect. This is the "capability gates fail
closed" half of principle 6.

### S3 — ReDoS: regex is length-capped but not backtracking-bounded (MEDIUM, live today)
`compileRegex` caps pattern length (200) and catches construction errors, but
Java/Kotlin `Regex` has **no match timeout** and the cap doesn't prevent
catastrophic backtracking — a ≤200-char pattern of the `(a+)+$` family, run
against attacker-influenced UI text, hangs the classification thread.
`hasTextMatchesRegex` / `titleMatchesRegex` / `anyFieldMatchesRegex` and the
notification-parse `find` regex all execute **per event** on the hot path. A
*non-malicious but careless* rule author can hang the HUD today; a malicious
remote rule weaponizes it.
**Fix:** at compile time, reject patterns with nested unbounded quantifiers via
a complexity heuristic, OR run matches under a watchdog/`interrupt`-able
`CharSequence`, OR restrict rule regex to a vetted linear-time subset. Cheapest
first step: a compile-time structural check + a documented "no nested
quantifiers" rule constraint.

### S4 — No rule-count / per-rule cap; wholesale-replace validation is thin (MEDIUM, gates #192)
Only the 1 MB file size bounds the rule set. `RulesetLoader.validate` checks
`format_version` + `platform_id` and nothing else (flow/mode vocab is deferred
to per-rule compile, which is fine). A 1 MB bundle can carry tens of thousands
of trivial rules → `matchFirst` is a linear scan **per accessibility event**, so
this is a sustained-CPU DoS, not a one-shot. Separately, because `load()`
replaces everything, validation must eventually assert the **sensitive rules are
present and intact** — you cannot trust a remote bundle to include them.
**Fix:** cap rule count (and branches/effects per rule) at load; for the remote
path, validate that the required sensitive-screen rules exist (or layer them
from a non-overridable local set, see S5).

### S5 — `overrideable` is inert metadata (LOW, design clarity)
CLAUDE.md describes sensitive rules as "`overrideable: false`, blocking all
further processing." In code, `overrideable` is parsed and stored on
`CompiledRule` but **not consulted in `matchFirst`** — the sensitive block works
via priority-0-first + unique-priority + first-match-wins, not via the flag. It
holds for the bundled case, but the documented mechanism isn't the real one, and
the flag offers no protection against a remote bundle that omits/reorders the
sensitive rules.
**Fix:** either enforce `overrideable` (a non-overrideable rule's classification
can't be pre-empted/replaced), or keep sensitive rules in a **local, non-remote**
layer that always evaluates first regardless of the downloaded bundle, and
update the CLAUDE.md wording to match reality.

### S6 — Reflection on the gate hot path (INFO)
`EffectMap.parsedFieldsToMap` uses `declaredFields` + `isAccessible = true` over
`ParsedFields` subtypes. The types are a sealed, app-owned hierarchy (not
attacker-controlled) and the path is fail-closed (#345), so this is not a
security issue — noted only as a reflection-on-hot-path smell to retire if the
gate is ever reworked.

## Posture summary

For the **current bundled-rule build**, the engine is sound: the input is
trusted, the dangerous capabilities are stubbed-open by deliberate single-user
choice, and the genuine remaining live risk is S3 (a careless regex can hang the
HUD). For the **matchers split (#192)**, S1 (signature verification) and S2 (real
capability gates) are non-negotiable prerequisites — without them, "delivered to
running instances over CDN" means "arbitrary recognition + arbitrary UI
actuation from a network source" — and S4/S5 are the supporting hardening
(resource caps + guaranteeing the sensitive block survives a wholesale
ruleset replacement). Suggested issues: one per S1–S5; S6 is a note.
