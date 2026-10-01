# census-contract/ — the census wire contract (#1173)

This plain-JVM included build owns the skeleton DTO/schema, fingerprint, hash,
id/class/kind grammars, text/case folds, anonymous-wrapper set, sensitive-marker
DATA, and the conformance golden. The phone's UiNode scan logic stays in
`:core:pipeline`; customer-PII policy (`PiiShapes`) stays in `:domain`.

Apache-2.0 lets both the PolyForm-Shield app and the AGPL `dashbuddy-census` server
consume the same contract. This resolves ADR-0011 open question 1 on 2026-10-01.

The app includes `includeBuild("census-contract")` in its root settings and
`:domain` declares `api("cloud.trotter.census:contract:0.0.0-local")`. Gradle
automatically substitutes the included build for those coordinates, exposing the
contract transitively to the app's other modules. Run its tests through the root
`censusContractTest` lifecycle task.

The server pins a DashBuddy checkout and uses
`includeBuild("../DashBuddy/census-contract")` plus the same dependency coordinates.
Keep the checkout's `gradle/libs.versions.toml` beside the build: its settings
import that shared catalog. Pin the checkout revision so app/server contract and
conformance data evolve together.

To regenerate `conformance/skeletons.jsonl.gz` from the app's committed snapshot
corpus, run from the DashBuddy root:

```bash
./gradlew :app:testDebugUnitTest --tests "*CensusGoldenExportTest*" -DexportCensusGolden=true
./gradlew :app:testDebugUnitTest --tests "*CensusGoldenExportTest*"
```

The export deliberately fails after writing: review the decompressed JSONL diff,
then rerun without the flag and commit the gzip artifact. Each fixture has one
path-sorted record containing either its refusal reason or its fingerprint,
sorted distinct hashes, and embedded skeleton. The server's ingest suite replays
this golden; it contains no plaintext text slots.

## Consuming from another build

`census-contract/settings.gradle.kts` reads the version catalog at `../gradle/libs.versions.toml`, so the build must be included from a COMPLETE DashBuddy checkout (`includeBuild("<path>/DashBuddy/census-contract")`), never vendored as a bare directory. The `dashbuddy-census` server pins that checkout to a SHA in CI.
