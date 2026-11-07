# gig.log recognition and replay tests

Read this before adding snapshot fixtures, changing a rule's parse output, or writing a replay test. `CLAUDE.md`
§ Snapshot and replay tests keeps the four rules every session needs; this file holds the full workflow.

The recognition tests are **data-driven**: captured UI hierarchies (JSON) verify that the production rules identify
and parse each screen correctly. The replay tests feed chronological capture sequences through the real state
machine (and, end to end, the effect engine and the read model).

## Directory structure

* **`java/.../core/pipeline/recognition/matchers/`**: the intake tools.
    * `InboxProcessorTest.kt`: sorts new snapshots out of `INBOX/` (mutates the corpus; run it by name).
    * `UnknownScreenAnalysisTest.kt`: the X-Ray report for unrecognized screens (mutates the corpus; run it by name).
* **`java/.../core/pipeline/rules/`**: rule engine tests. `DefaultRulesIntegrationTest.kt` compiles the generated
  production rule assets (`TestRulesetFactory.rulesDir`) and spot-checks screen, click and notification
  classification.
* **`java/.../test/suites/AllMatchersSuite.kt`**: the ruleset and recognition regressions in one run.
* **`java/.../test/util/`**: `TestRulesetFactory`, `SessionReplay`, `E2ESessionReplay` and the other harnesses.
* **`resources/snapshots/`**: the corpus.
    * `INBOX/`: the landing zone for raw, unclassified JSON dumps (gitignored, so raw data is never committed).
    * `SENSITIVE/`: redacted snapshots of the dasher's sensitive screens.
    * `UNKNOWN/negative/`: the committed negative corpus (frames that must stay UNKNOWN).
    * `<screen folder>/`: categorized snapshots; the folder name is the expected intent.
    * `approved-parse-output.json`: the parse-output golden.

## Snapshot regression testing

Tests are data-driven using captured UI hierarchy JSON files under
`app/src/test/resources/snapshots/`.

**Adding new test cases (Inbox Workflow):**

1. Drop raw UI hierarchy `.json` files into `snapshots/INBOX/` (gitignored).
2. Run `InboxProcessorTest` — it auto-sorts recognized screens into category folders, fails on PII,
   and prints an X-Ray report for unknowns.
3. For **unknown screens**: read the X-Ray report, add or broaden a rule in the platform's JSON5
   source — the flat `matchers/rules/<platform>.json5` (uber) or the matching surface sub-file under
   `matchers/rules/<platform>/` (doordash, e.g. `pickup.json5`) — canonicalized/merged into generated
   assets by `:core:pipeline:importMatchersRules` (#635/#639), re-run.
4. For **sensitive screens**: manually redact the JSON, run it through `SnapshotRedactor.sanitizeProvenance`
   (the hand-move bypasses `SnapshotLibrarian`, so the provenance placeholders are NOT applied for you — the
   corpus guard fails on a real fingerprint), move to `snapshots/SENSITIVE/`, verify with
   `AllMatchersSuite` (the golden guard asserts every `SENSITIVE/` snapshot is caught by a
   sensitive rule or flagged toxic by `SnapshotSecurityScanner`).
5. Commit only the sorted files from their category folders (never from `INBOX/`). Intake also rewrites capture provenance (#1206): `SnapshotRedactor.sanitizeProvenance` sets `deviceFingerprint` / `captureId` to fixed placeholders, and `CaptureRedactionCorpusTest` fails on any committed fixture carrying a real `Build.FINGERPRINT` or capture UUID. The parse-golden dead-template ratchets are keyed per FIXTURE (`snapshots/<folder>/<file>|<rule>|<field>`, #1045), so a template that dies on one more frame fails instead of hiding under a `rule:field` pin.
6. New corpus changes the parse-output golden — regenerate it deliberately (next section, step 4)
   and the census conformance golden (`./gradlew :app:testDebugUnitTest --tests "*CensusGoldenExportTest*" -DexportCensusGolden=true`,
   then review the diff of `census-contract/conformance/skeletons.jsonl.gz` — both goldens pin the
   corpus the working tree holds, so regenerate after sorting, before committing).
   Commit `snapshots/approved-parse-output.json` and the census golden together with the new snapshots.

**The intake tools MUTATE the corpus, and are excluded from a plain sweep (#941).**
`InboxProcessorTest` and `UnknownScreenAnalysisTest` redact/move/prune/delete files under
`snapshots/` as their normal operation, so `app/build.gradle.kts` excludes them from an UNFILTERED
`testDebugUnitTest` run (alongside the pre-existing `*Suite` exclusion) — "a sweep never rewrites
the corpus" is structural. Run them deliberately by name:
`./gradlew :app:testDebugUnitTest --tests "*InboxProcessorTest"`. Naming a test sets Gradle's
`commandLineIncludePatterns`, which turns the whole exclusion block off.

**Corpus reads fail LOUD (#941).** `TestResourceLoader.loadSnapshots` THROWS on an unparseable
file for every directory except the two *staging* areas (`INBOX/`, `UNKNOWN/`), where a malformed
raw pull is expected traffic — otherwise a corrupt committed fixture sits in git looking like
coverage while no test reads it. `snapshots/UNKNOWN/negative/` is committed corpus and therefore
strict: the leaf directory name decides.

**The pruner keeps the NEWEST, and never evicts an incumbent to admit a newcomer (#929/#941).**
`SnapshotLibrarian.pruneFolder` caps a folder at 15 distinct content variants. It sorts by the
capture timestamp *parsed out of the filename* (both conventions: legacy `20260128_155954_491_…`
and current `2026-07-17_18-08-53-720__…`, whose lexical orders are inverted against their real
chronology — PR #926 deleted 9 fixtures over exactly that). And when the folder was already at cap
**before** the run, the file the run just added is the one dropped: incumbents are never evicted to
make room (PR #800's capped-additions-only discipline). Below cap, a fresher
capture of identical content still replaces the stale one — that is the retention policy working.

**The negative corpus (`snapshots/UNKNOWN/negative/`, #941).** Over-match is the dangerous
recognition failure: under-match only drops a frame to UNKNOWN, but over-match *forges state*
(#857/#874/#875 claimed `modeHint: offline` from absence and split live dashes; #858 minted an
offer out of a "no longer available" banner). `NegativeCorpusStaysUnknownTest` (an
`AllMatchersSuite` member) asserts every committed frame there still classifies UNKNOWN under its
own platform partition, with a minimum-count floor so an emptied folder fails instead of
green-passing. To add one: sweep it for PII, keep the `__<platformWire>__` capture-naming token,
and drop it in. The parent `snapshots/UNKNOWN/` stays gitignored staging; `negative/` is
un-ignored by an explicit negation, and the subfolder placement is load-bearing — the flat
staging loaders list files, so they can never graduate or prune a committed negative frame.

**Adding or changing a recognition rule** (there are no matcher classes to register — rules are data):

1. Edit the platform's JSON5 rule source — the flat `matchers/rules/<platform>.json5` (uber) or the
   relevant surface sub-file under `matchers/rules/<platform>/` (doordash); `$schema` gives editor
   autocomplete/validation (the full `docs/rules.schema.json`, or `docs/rules.fragment.schema.json` for a
   sub-file). Golden-corpus folder name == expected intent.
2. Tests compile the **canonicalized production** rule files via `TestRulesetFactory` (which reads the
   generated `assets/rules/`, populated by `:core:pipeline:importMatchersRules`) — nothing else to wire up.
3. Run `InboxProcessorTest` (sorting) and then `AllMatchersSuite` (golden guard + parse-output
   golden + ruleset + classifier regressions) to verify.
4. If the change legitimately alters **parse output** (`ParseOutputGoldenTest` fails, #433):
   regenerate with `./gradlew :app:testDebugUnitTest --tests "*ParseOutputGoldenTest*"
   -DupdateParseGolden=true`, then **review the diff of `approved-parse-output.json`** — that
   review is the regression gate — and commit it with the rule change. The same test also
   ratchets corpus coverage (new intents should ship with corpus) and lints dedupeKey
   `{field}` templates against fields the rule actually parses. The coverage ratchet is keyed
   by **platform + intent** (#941) and measured by classifying the corpus inside each
   platform's rule partition — not by "a folder with that intent's name exists" — so a
   DoorDash-populated folder can no longer mark the same-named Uber intent covered.

## Session replay (capture sequence → recognition → state machine)

The snapshot tests above are **per-frame** (does this screen recognize/parse right, in isolation).
To reproduce a **field bug that's emergent across a session** — a ghost offer, a re-mint, doubled
dropoffs — `SessionReplay` (`app/src/test/.../test/util/SessionReplay.kt`) replays a *chronological
sequence* of real device `CaptureEnvelope`s: `replayRecognition` (Level A → `List<Observation>` via
the production rules), `reduce` (Level B → folds through the real `StateMachine`, returning a
per-frame trace of `{frame, observation, stateAfter, events}` where events match the db `app_events`
shape), and `reduceMixed` (Level B with **click + grace-timer injection** — a timestamp-ordered mix of
real screens, real/synthetic clicks, and synthetic `GRACE_COMMIT` timers folded through one
`StateMachine` + one classifier wired to both screen and click rulesets).
The captured session's db `app_events` is a **characterization** oracle (it *encodes* the bug), so
Level-B assertions are hand-authored correct-behaviour invariants, **never `replay == db`**.
`GhostOfferReplayTest` (Level A/B, #498) and `SingleDeliveryReplayTest` (click+timer injection:
accept-click → pickup → dropoff → complete, exactly one dropoff, #498/#503/#518) are the worked
examples, both green. Remaining frontier (on-device review tool, verdict export, eval-loopback net
economics, GoPuff multi-drop repro) is tracked under epic #505.

**End-to-end (#1271):** `E2ESessionReplay` (`test/util/`, Robolectric under `ReplayApplication`) goes
past `StateMachine.step`: the production classifier → `StateManagerV2` → the real `SideEffectEngine`
and handlers → in-memory Room → `AnalyticsProjector`, on ONE `TestCoroutineScheduler` whose virtual
clock every production seam reads (defaulted `java.time.Clock` params, system UTC bound in
`DomainModule`). Only external edges are fake (`ReplayEdges`: clock, memory DataStores, GPS, TTS engine,
accessibility tree, notification UI); no evaluation, event row or timeout is ever injected, and time
moves only by `advanceTo` (never `advanceUntilIdle`). `DoorDashFullDashE2ETest` is scenario 1
(`DoorDashFullDashJourney` documents its splices, and exposes them as timed `steps()`); `InfoLogPiiGateE2ETest` is the INFO+ PII gate, which
REQUIRES handler-written INFO+ lines; `CrashRestartE2ETest` is scenario 4 — a disk-backed DB +
surviving stores (`ReplayEdges.DurableStores`), `crash()` mid-grace / mid-commit, a relaunched harness,
and the same durable outcome as the uninterrupted run.
`PrivacyExportE2ETest` / `PrivacyExportReplay` is scenario 6: real sensor → capture → census spool → worker → signed request bytes, plus the shareable log.
Fakes: Android accessibility service/nodes and notifications, Keystore (reversible sealer), HTTP (recording interceptor), memory preference stores, the upload scheduler and replay metadata, and platform preferences.
Release DI, Android event delivery and screenshots are out of scope.

## Running the regressions

```bash
# Ruleset and recognition only (fast; side-effect-free, does not sort INBOX or re-triage UNKNOWN)
./gradlew :app:testDebugUnitTest --tests "*AllMatchersSuite*"
```

The full unit gate runs in PR CI; locally, run only small targeted runs (see `CLAUDE.md` § Build Commands).
