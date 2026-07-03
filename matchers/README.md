# matchers/ — the JSON5 rule source + canonicalizer (N1, #635)

This directory owns the **single source of truth for DashBuddy recognition rules**. It is an
in-repo stand-in for the future separate, forkable, Apache-2.0 **matchers repo** (Pillar 2 /
Epic #192) — same Gradle topology, so when the repo splits out (gated on the #246 licensing
decision) this becomes a git **submodule** with no consumer-side change. See
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

## Milestone 2 (unbuilt)

The runtime OTA/CDN channel — signed JSON fetched, verified (#416), capability + sensitive-rule
survival across the swap (#419), CDN infra + signing — is greenfield. The bundled baseline here
remains the first-run seed, the offline fallback, and the fail-closed fallback when signature
verification fails.
