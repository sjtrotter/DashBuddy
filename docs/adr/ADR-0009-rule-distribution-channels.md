# ADR-0009: Rule Distribution Channels and the Single Local Dev Loop

**Status:** Proposed (spike-validated)
**Issue:** Sub-RFC of Epic #192 (matchers split); gated on #246 (licensing)
**Date:** 2026-07-03
**Builds on:** ADR-0001 (matcher rule format), ADR-0003 (versioning and API contracts)
**Related:** #361 (volatile hot-swap ref), #416 (signature verification), #419 (capability /
sensitive-rule survival across OTA)

---

## Context

Recognition rules are **data, not code** (ADR-0001). They live today as per-platform JSON
files bundled into the APK at `core/pipeline/src/main/assets/rules/*.json`
(`doordash.json` ~104 KB, `uber.json` ~20 KB), loaded at runtime by
`JsonRuleInterpreter.loadDefaults()` via `context.assets.list("rules")`, and in tests by
`TestRulesetFactory` (which walks the same directory on the filesystem).

Pillar 2 of the project — **Distributed Integrity (the matchers)** — requires the recognition
layer to become a **separate, forkable, Apache-2.0 repository**, delivered to running app
instances by two means: (1) bundled into the APK as a baseline, and (2) fetched over a CDN so
deployed apps update without a rebuild (#192). The agreed authoring model is **JSON5 source**
(comments + trailing commas for human editing) → the matchers repo's CI **canonicalizes** it to
the streamlined JSON the app already consumes → both delivery channels ship that one canonical
artifact.

The open design question this ADR answers: **how does the developer keep a single local dev
loop** — edit a rule locally, run tests, no publish step — once the rules live in a separate
repo? The developer specifically asked about **Gradle composite build / dependency
substitution** (the "consume a published artifact, but substitute the local source" pattern).

A spike on branch `feature/192-rule-distribution-poc` was built to de-risk the mechanism before
committing to it. This ADR records the design of record **as validated by that spike** — not a
hypothetical.

## Decision

### 1. Two channels, one artifact

Both delivery channels consume the **single canonical JSON artifact** the matchers repo's CI
produces from JSON5 source. Neither channel re-derives it.

| | **Build-time bundle** (baseline) | **Runtime OTA** (Milestone 2) |
|---|---|---|
| Source | matchers repo as a **git submodule** of the app repo | matchers repo CI publishes to **CDN** |
| Wiring | **Gradle composite build** (`includeBuild`) substitutes the local submodule for the pinned artifact | app fetches signed JSON, verifies, hot-swaps |
| Rebuild to update? | **Yes** — coupled to an app build/release | **No** — deployed apps update in place |
| Trust | in-tree, pinned by submodule SHA | **untrusted** until signature-verified (#416) |
| Role | first-run / offline / signature-failure **fallback**, and the CI test corpus baseline | primary update path for fielded apps |
| Status | spike-validated (this ADR) | greenfield, unbuilt (#416, #419, CDN infra, signing) |

The bundled baseline is **not** retired when OTA lands. It remains the first-run seed (before
any fetch), the offline fallback, and — critically — the **fail-closed fallback when signature
verification fails** (#416). The runtime path is a volatile hot-swap of the compiled ruleset
bundle behind one `@Volatile` reference (#361), already present in `JsonRuleInterpreter`.

### 2. The canonical artifact and canonicalization

JSON5 source → canonical JSON is a **deterministic, byte-stable** transform. The canonical form
is the pretty-printed, 2-space, insertion-order JSON already committed under `assets/rules/`
(with the trailing newline). Canonicalization must be **faithful**: a JSON5 file that is the
canonical file plus JSON5-only affordances (comments, trailing commas) must canonicalize back to
the exact committed bytes.

The spike implements this as a dependency-free, **string-aware, text-preserving** stripper: it
removes `//` and `/* */` comments and trailing commas while preserving every other byte (and
skipping `//` / `,` that appear inside string values). Byte-preservation — rather than
parse-and-re-serialize — is what guarantees byte-identity against the committed pretty-printed
file. **This is spike-grade.** Production wants a real JSON5 parser + JSON-Schema validation
(`docs/rules.schema.json`) + a canonical re-serializer, run in the matchers repo's CI, so the
canonical form is defined by the serializer rather than by preserving author formatting.

### 3. The single local dev loop — Option A (composite build), recommended

The spike evaluated two mechanisms. **Option A works cleanly and is recommended**; Option B is
the lighter fallback and the correct pre-split first step (see Migration).

**Option A — composite build + dependency substitution.** The matchers repo is a self-contained
plain-JVM Gradle build. It owns the JSON5 source **and** the canonicalize task, and exposes the
canonical output as an outgoing artifact. The app includes it (guarded) and resolves the artifact
through a custom configuration; `includeBuild` substitutes the local submodule for the coordinates
the app would otherwise resolve from a repo.

Guarded include in the app's `settings.gradle.kts` (OFF by default):

```kotlin
// SPIKE (#192) — guarded composite build. OFF by default; opt in with -PuseLocalMatchers.
if (providers.gradleProperty("useLocalMatchers").isPresent) {
    includeBuild("matchers")
}
```

The matchers build (`matchers/build.gradle.kts`) canonicalizes and publishes the output:

```kotlin
plugins { base }
group = "cloud.trotter.matchers"
version = "0.0.0-local"

val canonicalRulesDir = layout.buildDirectory.dir("canonical/rules")
val canonicalizeRules = tasks.register("canonicalizeRules") {
    inputs.dir(layout.projectDirectory.dir("rules")).withPropertyName("json5Source")
    outputs.dir(canonicalRulesDir).withPropertyName("canonicalOut")
    doLast { /* strip comments + trailing commas -> build/canonical/rules/*.json */ }
}

val rulesElements: Configuration by configurations.creating {
    isCanBeConsumed = true; isCanBeResolved = false
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "matchers-rules")) }
}
artifacts { add(rulesElements.name, canonicalRulesDir) { builtBy(canonicalizeRules) } }
```

The consumer (`:core:pipeline`, guarded) resolves the substituted artifact through a matching
custom configuration:

```kotlin
val matchersRules: Configuration by configurations.creating {
    isCanBeResolved = true; isCanBeConsumed = false
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, "matchers-rules")) }
}
dependencies { add(matchersRules.name, "cloud.trotter.matchers:matchers:0.0.0-local") }
tasks.register<Sync>("importMatchersOptionA") { from(matchersRules); into(/* generated assets dir */) }
```

`./gradlew :core:pipeline:dependencies --configuration matchersRules` confirms the substitution
is real:

```
matchersRules
\--- cloud.trotter.matchers:matchers:0.0.0-local -> project :matchers
```

**Production vs local path.** In production the coordinates `cloud.trotter.matchers:matchers:<pinned>`
resolve from a repository (a published artifact pinned by version). Locally, the *same* dependency
is transparently substituted by `includeBuild("matchers")` for the nested submodule — no code
change, no publish. That symmetry is the whole point of the composite-build pattern.

**How "one place" physically looks.** The matchers repo is nested as a git **submodule** inside
the app repo (`git clone --recursive`). Editing `matchers/rules/uber.json5` and running a build
re-runs canonicalize and re-imports the output — **no publish step**. The submodule SHA pins the
exact rule version the app was built against.

### 4. Option A vs Option B

| | **Option A — composite build** | **Option B — in-app canonicalize task** |
|---|---|---|
| `matchers/` shape | a nested **Gradle build** (submodule) | a nested **data dir** of `*.json5` (submodule) |
| Canonicalize lives in | the **matchers build** (mirrors prod CI topology) | the **app** (`:core:pipeline`) re-implements it |
| Consumption | resolve + `Sync` the outgoing artifact | task reads `matchers/rules/*.json5`, writes generated assets |
| Machinery | more (separate build, outgoing/incoming configs, attributes, coordinates) | less (one task, one generated dir) |
| SSOT of canonicalizer | **one** (in the repo that owns rules) | **two** (matchers CI + app copy) |
| Matches production | **yes** — app consumes the artifact the matchers CI produces | partially — app re-derives instead of consuming |

**Recommendation: Option A**, because it mirrors the production topology (canonicalization is
co-located with the rules, exactly where the matchers repo's CI will own it; the app merely
*consumes* the artifact) and delivers the published-artifact-substitution pattern the developer
asked about — with, in this project, **no friction**. Option B is strictly less machinery but
forces the app to carry a second copy of the canonicalizer (an SSOT smell), and does not exercise
the artifact-consumption path that OTA and the pinned-artifact production build also use.

## What the spike proved

Run with `JAVA_HOME=/usr/lib/jvm/java-25-openjdk` (Gradle 9.3.0, AGP 9.0.1):

1. **Faithful canonicalization (byte-identical).** `matchers/rules/uber.json5` is the committed
   `uber.json` plus a `//` comment and a trailing comma. Canonicalizing it and comparing against
   the committed asset:
   ```
   ./gradlew -PuseLocalMatchers :core:pipeline:verifyMatchersCanonical
   > PROOF OK: canonicalized uber.json is byte-identical to committed assets/rules/uber.json (20402 bytes).
   ```
2. **Local loop, no publish.** Editing the JSON5 source re-runs `:matchers:canonicalizeRules`
   (no longer `UP-TO-DATE`) with no publish step; the artifact re-syncs only when the canonical
   **bytes** actually change (Gradle content-based up-to-date checks). A throwaway `_looptest.json5`
   with a comment + trailing comma canonicalized to valid comment-free, comma-free JSON.
3. **Default build unchanged, OFF by default.** With no flag: `./gradlew projects` shows **no**
   `:matchers` project; `:core:pipeline:tasks --all` shows **no** matchers tasks; the committed
   assets are untouched; and `./gradlew :core:pipeline:testDebugUnitTest` is **green**. All the
   spike machinery is created only under `if (providers.gradleProperty("useLocalMatchers").isPresent)`.
4. **Composite substitution resolves cleanly.** The dependency report shows
   `cloud.trotter.matchers:matchers:0.0.0-local -> project :matchers`, and the opt-in path
   coexists with the real `:core:pipeline` tests (both green in one invocation).

### Friction found (and what the plan got wrong)

The plan anticipated Option A hitting **AGP variant-resolution** friction and/or
`FAIL_ON_PROJECT_REPOS` / version-catalog conflicts, with Option B as the likely fallback. **That
friction did not materialize.** The reasons are worth recording:

- **Custom resolvable configuration sidesteps AGP attribute matching.** Consuming the artifact
  through a freshly created `configurations.creating { isCanBeResolved = true }` with a single
  custom `Category` attribute — rather than an AGP-managed configuration — means AGP injects no
  Android variant attributes into the request, so there is no "no matching variant" clash with the
  plain-JVM producer.
- **`FAIL_ON_PROJECT_REPOS` is irrelevant to composite substitution.** That mode governs
  *repository* declarations in project builds; `includeBuild` substitution is coordinate matching,
  not a repository lookup, so it is unaffected.
- **The version catalog is not shared into the included build,** and does not need to be — the
  matchers build only needs the `base` plugin, no catalog libraries.

The one genuine cost that remains for **both** options: the resolved/generated canonical files
still need a final step to land where the runtime loader and `TestRulesetFactory` look. The spike
deliberately stops at a **generated dir** and does **not** rewire `JsonRuleInterpreter`'s asset
loading or `TestRulesetFactory` (that is the real migration, below). So the spike proves the
**mechanism** — canonicalize + faithful output + guarded substitution — standalone; closing the
loop into the actual test corpus is migration work, not spike work.

## Migration path

1. **In-repo canonicalize first (pre-split).** Before the repo exists, land canonicalization as
   an in-repo step (Option B shape): JSON5 sources live in the app repo, a Gradle task
   canonicalizes them into the assets. This is the correct first move because there is no separate
   build to include yet.
2. **Create the public Apache-2.0 matchers repo.** **Gated on #246** (the Apache-2.0 relicense /
   counsel decision) — this is the pacing item, not code.
3. **Submodule + composite-build consumption.** Nest the matchers repo as a submodule; wire
   `includeBuild("matchers")` (Option A) for the local loop, with the pinned published artifact as
   the production path.
4. **Decouple `TestRulesetFactory` and lock corpus↔rules versions.** `TestRulesetFactory`
   currently hardcodes `core/pipeline/src/main/assets/rules` filesystem paths; point it at the
   canonical artifact (generated dir) instead. Establish a **version lock** so the snapshot corpus
   (`app/src/test/resources/snapshots/`) is always tested against the rules SHA it was captured
   for (per ADR-0003 compatibility checks).
5. **Resolve the `doordash.json` per-platform partition in the subrepo** (tracked separately; the
   ~104 KB monolith is split in the matchers repo, not before).
6. **Milestone 2 — OTA/CDN.** Signature verification before compile (#416), capability
   enumeration + sensitive-rule survival across the swap (#419), CDN infrastructure, and signing.
   All greenfield and unbuilt; the bundled baseline (steps 1–3) remains the fallback throughout.

## Consequences

### Positive

- The developer keeps one local loop: edit `matchers/rules/*.json5`, build, test — no publish.
- Canonicalization is co-located with the rules (Option A), matching where production CI owns it;
  one canonicalizer, not two.
- The bundle and OTA channels consume the *same* artifact, so a rule behaves identically however
  it was delivered.
- The mechanism is provably off by default — zero impact on the default build or CI until opted in.

### Negative / Tradeoffs

- **Version-pin discipline.** A submodule SHA (bundle) and a pinned artifact version (OTA) must be
  managed deliberately; a drifting submodule or an unpinned fetch reintroduces the corpus↔rules
  skew ADR-0003 guards against.
- **Greenfield canonicalize/publish CI.** The faithful-canonicalization guarantee moves from the
  spike's text stripper to a real JSON5-parse + schema-validate + canonical-serialize CI in the
  matchers repo — which must itself be built and trusted.
- **`TestRulesetFactory` refactor cost.** Decoupling it from hardcoded asset paths is real work and
  a prerequisite for the split; the spike intentionally does not touch it.
- **Licensing paces the split.** Repo creation is gated on #246, a legal/counsel decision, not on
  engineering — the code side (this spike) is ready ahead of it.
- **Composite-build ergonomics.** Included builds add a small configuration-time cost when opted in
  and can interact with the configuration cache / IDE sync in ways worth watching; none blocked the
  spike, but they are sharper edges than a plain in-app task (Option B).

### Future Work

- Replace the spike's text stripper with a real JSON5 parser + `docs/rules.schema.json` validation
  in the matchers repo CI.
- Wire the canonical artifact into `JsonRuleInterpreter` asset loading and `TestRulesetFactory`
  (migration steps 3–4).
- OTA/CDN as Milestone 2 (#416, #419).
