# matchers/ — the JSON5 rule source + canonicalizer (N1, #635)

This directory owns the **single source of truth for DashBuddy recognition rules**. It is an
in-repo stand-in for the future separate, forkable, Apache-2.0 **matchers repo** (Pillar 2 /
Epic #192) — same Gradle topology, so when the repo splits out (licensing settled Apache-2.0 per
#246) this becomes a git **submodule** with no consumer-side change. See
[`docs/adr/ADR-0009-rule-distribution-channels.md`](../docs/adr/ADR-0009-rule-distribution-channels.md).

It is a self-contained plain-JVM Gradle build (`includeBuild`-ed by the app's root
`settings.gradle.kts`) that:

- owns the **JSON5 rule source** (`rules/*.json5` — comments + trailing commas for human editing), and
- **canonicalizes** it to the streamlined JSON the app consumes (`build/canonical/rules/*.json`)
  via the `canonicalizeRules` task, exposing that output as the `rulesElements` outgoing artifact.

`:core:pipeline` resolves that artifact (composite-build dependency substitution) and imports it
into **generated** `assets/rules/*.json` via `importMatchersRules`; both the APK (AGP Variant-API
asset merge) and the unit tests consume the generated output. **There are no committed
`assets/rules/*.json`.** Editing a `rules/*.json5` value flows straight into recognition tests with
**no publish step** — the local dev loop is the default.

```bash
# canonicalize standalone
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :matchers:canonicalizeRules

# canonicalization proof: idempotent + schema-valid (canonicalize(canonicalize(x)) == canonicalize(x))
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :matchers:verifyMatchersCanonical

# import into generated assets/rules/ (runs automatically before :app:testDebugUnitTest and the APK build)
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :core:pipeline:importMatchersRules
```

## Canonicalization

JSON5 → canonical JSON is done with **kotlinx-serialization** (the same JSON library the app
compiles rules with — one canonical parse/serialize definition, not a second copy): parse leniently
enough for JSON5 authoring affordances (`allowComments`, `allowTrailingComma`; `isLenient = false`
keeps unquoted keys / single quotes out), then re-serialize deterministically (pretty, 2-space,
insertion order preserved). This is **semantically inert** — it preserves object key order, array
element order (load-bearing for equal-priority rule match order), and numeric literals verbatim; the
only change vs. a hand-authored file is cosmetic whitespace (inlined arrays expand to one element per
line). The app's `ParseOutputGoldenTest` staying green **without** a regen flag is the standing proof
that the reformat is behaviourally inert.

Full JSON-Schema validation against `docs/rules.schema.json` is deferred to the matchers repo's CI
(ADR-0009); `verifyMatchersCanonical` does a cheap schema-aware structural check (required top-level
keys, read from the schema) plus the idempotency fixed-point assertion.

## Regex pattern language: RE2, not PCRE (#1053)

Every regex a rule carries — `…MatchesRegex` predicates, parse `find`/`regex` patterns, `redact`
`match`, `nextSiblingMatchingRegex`'s argument — is compiled by the app onto **RE2J**, a
non-backtracking engine, so an accepted pattern's match time is linear in `input × pattern` **by
construction** rather than by a watchdog (the previous 200 ms budget could not fire on Android at
all). That matters most for the milestone-2 CDN channel below: an untrusted pattern language must be
one whose worst case is known.

The cost is syntax. **No lookaround** (`(?!…)`, `(?=…)`, `(?<…)`), **no backreferences**, no
possessive or atomic groups. Everything this ruleset uses is supported: character classes,
`\d\s\w\S`, `\b` (ASCII word boundary), `\p{L}`, lazy quantifiers, `(?:…)`, numbered capture
groups, `^`/`$`. Two behaviours differ from the JVM/ICU engines: `$` matches only at end of **text**
(not before a trailing newline), and `\b` is ASCII-only — neither is exercised by this English-only
ruleset. Patterns are case-**insensitive** and capped at 200 chars; an over-long or unsupported
pattern fails the rule LOAD loudly, per file, so a bad pattern can never degrade quietly into one
that just never matches. See `docs/adr/ADR-0010-linear-time-rule-regex.md`.

## Locale scope: this ruleset is English-only (#938)

Every anchor in `rules/` is a **literal English string** — `require` text predicates, `matchesRegex`
shapes, the `keepPrefix` markers a `redact` entry preserves, the tap-label allowlists a `RuleAction`
verifies against. So is the app-side privacy backstop these rules sit behind: both marker SSOTs
(`SensitiveTextMarkers.KEYWORDS`, `CustomerTextMarkers.MARKERS`) are English literals too.

**Consequence on a non-English device.** Recognition drops toward zero — every frame falls to
UNKNOWN, which is survivable (release builds bind `NoOpCaptureBus`, so nothing is written). What is
*not* survivable silently is that the **Pledge layers thin**: the sensitive-screen text backstop,
the UNKNOWN-capture customer-PII scrub, and the shareable-log scrub all weaken, because all three
match on English wording. `CustomerTextMarkers.ID_MARKERS` (#910 — view-id suffixes like
`customer_name` / `address_line_1`) is the **one locale-immune layer**: Android view ids do not
localize. That is the pattern to prefer whenever a defence can be anchored on structure instead of
copy.

**What the app does about it (#938):** detect and disclose, not translate. `RecognitionLocale`
(`:domain`) compares the device language against `en` at accessibility-service start; a mismatch
logs one WARN (ISO-639 code only) and posts a once-per-install dasher-visible notice. The
degradation stays real — the notice just stops it from being invisible.

**Rule authors:** a non-English vocabulary is a **corpus problem, not a rule-syntax problem**. Adding
`es` anchors without an `es` golden corpus would ship untested matches into the same priority space
as the English ones, and — because the sensitive block is `priority: 0, overrideable: false` — a bad
non-English sensitive anchor is a Pledge risk, not just a miss. So: do **not** add non-English
anchors to `rules/` until a non-English capture corpus and its `AllMatchersSuite` coverage exist.
This is a deliberate scope boundary, and it becomes load-bearing once rules ship OTA (#640/#192) to
users who never do a desk pull — the user base must not silently widen past the assumption.

## Milestone 2 (unbuilt)

The runtime OTA/CDN channel — signed JSON fetched, verified (#416), capability + sensitive-rule
survival across the swap (#419), CDN infra + signing — is greenfield. The bundled baseline here
remains the first-run seed, the offline fallback, and the fail-closed fallback when signature
verification fails.

## License

This ruleset is licensed under the **[Apache License 2.0](LICENSE)** (see [`NOTICE`](NOTICE)) —
separately from the DashBuddy application, which is source-available under PolyForm Shield 1.0.0.
The Apache-2.0 boundary is deliberate (Pillar 2 / Epic #192): the recognition layer is meant to be
**forkable** so that if an upstream source is compromised, drivers can switch to another via
configuration. Keeping the ruleset Apache-2.0 while in this monorepo lets it move to its own repo
(gated on nothing now that the licensing is settled) with no relicensing step.

**Framing (non-negotiable, per the project framing discipline).** This ruleset is **empirical
measurement of the visible offer surface** — it encodes the information a driver already sees on
their own screen so DashBuddy can present it back to them on-device. It is **not** reverse-
engineering, model recovery, or algorithm characterization of any platform, and public-facing
descriptions of it must never suggest otherwise (the DoorDash ICA §15.4 prohibits reverse-
engineering; DashBuddy does not do that).
