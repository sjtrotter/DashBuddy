# matchers/ — SPIKE (#192), throwaway stand-in

This directory is a **proof-of-concept stand-in** for the future separate, forkable,
Apache-2.0 **matchers repo** (Pillar 2 / Epic #192). It is **not** production wiring — it
exists to de-risk the rule-distribution local dev loop before the repo split. See
[`docs/adr/ADR-0009-rule-distribution-channels.md`](../docs/adr/ADR-0009-rule-distribution-channels.md).

It is a self-contained plain-JVM Gradle build that:

- owns the **JSON5 rule source** (`rules/*.json5`), and
- canonicalizes it to the streamlined JSON the app consumes (`build/canonical/rules/*.json`)
  via the `canonicalizeRules` task, exposing that output as the `rulesElements` outgoing artifact.

Everything is **OFF by default**. The app build only includes it when opted in:

```bash
# faithful-canonicalization proof (byte-identical to committed assets/rules/uber.json)
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -PuseLocalMatchers :core:pipeline:verifyMatchersCanonical

# canonicalize standalone
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew -PuseLocalMatchers :matchers:canonicalizeRules
```

The JSON5 → canonical stripper here is **spike-grade** (a dependency-free, string-aware
comment/trailing-comma remover that preserves all other bytes — which is what makes
canonicalization byte-identical to the committed pretty-printed file). Production wants a real
JSON5 parser + JSON-Schema validation + canonical re-serializer in the matchers repo's CI.

In the real design this directory becomes a **git submodule** of the app repo, included via
`includeBuild("matchers")` for the local loop and resolved as a pinned published artifact in
production. `rules/uber.json5` is the committed `core/pipeline/src/main/assets/rules/uber.json`
plus a `//` comment and a trailing comma, purely to demonstrate JSON5-ness survives
canonicalization.
