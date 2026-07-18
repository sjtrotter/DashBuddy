# Your first recognition rule (current in-tree workflow)

This is a start-to-finish walkthrough for a new contributor adding their **first DashBuddy
recognition rule** — the "what screen is this and what does it say" logic that turns a raw
Android accessibility tree into a typed `Observation` the rest of the app consumes.

**Scope note.** This tutorial documents the workflow **as it exists today**, with the ruleset
kept in-tree under `matchers/` (2026-07-03 decision, [ADR-0009](../adr/ADR-0009-rule-distribution-channels.md)).
Pillar 2's eventual split of `matchers/` into a separate, forkable Apache-2.0 repo (#192/#637) is
parked — when it lands, this workflow changes (submodule + composite build instead of an in-tree
directory) and a follow-up will rewrite this doc for that shape. See [§6](#6-when-the-matchers-repo-split-lands)
below. The match-preview debugging tool from #214's original scope is **not** covered here — it
doesn't exist yet.

You don't need to write Kotlin for any of this. Rules are JSON5 data; the app's rule engine
(`RuleCompiler`, `Ruleset`, `JsonRuleInterpreter` in `:core:pipeline`) is the only thing that reads
them. For the full predicate/parse vocabulary reference, see
[ADR-0001](../adr/ADR-0001-matcher-rule-format.md) and the editor schemas
(`docs/rules.schema.json` / `docs/rules.fragment.schema.json`) — this tutorial does not restate
that spec, it walks you through *using* it once.

---

## 1. Where rules live

Recognition rules are authored as **JSON5** (JSON with comments and trailing commas) under
`matchers/rules/`. A platform's rules are either:

- **A flat file** — `matchers/rules/uber.json5`. One file holds the platform header
  (`format_version`, `platform_id`) and all its `screens`/`clicks`/`notifications` arrays.
- **A directory of surface sub-files** — `matchers/rules/doordash/`. A `_manifest.json5` holds
  *only* the platform header; every other `*.json5` file owns one family of screens
  (`offer.json5`, `pickup.json5`, `dropoff.json5`, `nav-comms.json5`, `chrome.json5`,
  `notifications.json5`, etc. — see [`matchers/rules/doordash/README.md`](../../matchers/rules/doordash/README.md)
  for what each file owns and why DoorDash is split this way). The canonicalizer **merges** every
  sub-file (sorted by filename, `_manifest` excepted) into one canonical `doordash.json`, so
  splitting a platform into a directory is purely an authoring convenience — it changes nothing
  about how rules match.

For a first rule, you'll almost always be editing an **existing sub-file** (or `uber.json5`), not
creating a new platform. Pick the file that owns the surface your screen belongs to.

**Editor autocomplete.** Every rule file references a `$schema` at the top so schema-aware editors
(VS Code, IntelliJ) give you autocomplete, inline docs, and typo detection as you type:

- `_manifest.json5` and `uber.json5` reference the **full** schema, `docs/rules.schema.json` — it
  requires the top-level `format_version`/`platform_id` fields.
- Every other DoorDash sub-file (`offer.json5`, `pickup.json5`, …) references the **fragment**
  schema, `docs/rules.fragment.schema.json` — it drops that `required` top-level check (a partial
  file legitimately has no header) but validates every rule inside `screens`/`clicks`/
  `notifications` against the *same* `$defs` the full schema uses, so you get identical
  predicate/field validation either way.

Open `matchers/rules/doordash/pickup.json5` or `matchers/rules/uber.json5` in your editor now and
confirm you see the `"$schema"` line at the top — if autocomplete isn't kicking in, that's the
thing to check first.

There is **no publish step**. Editing a `.json5` value and running any test task re-canonicalizes
and re-imports automatically:

```bash
# canonicalize the JSON5 source into build/canonical/rules/*.json (standalone, for a sanity check)
./gradlew :matchers:canonicalizeRules

# import the canonical output into generated assets/rules/*.json — runs automatically before
# :app:testDebugUnitTest and the APK build, but you can also run it standalone
./gradlew :core:pipeline:importMatchersRules
```

There are **no committed `assets/rules/*.json` files** to keep in sync — the generated directory
(`core/pipeline/build/generated/assets/importMatchersRules/rules/`) is what both the APK and the
tests actually read, and it's rebuilt from your `.json5` source every time.

---

## 2. The capture → corpus loop

Before you can write a rule for a screen, you need a **real captured snapshot** of that screen's
accessibility tree — DashBuddy's debug build writes these automatically while you use the target
app (DoorDash/Uber Driver) with accessibility service + capture enabled. Each capture is a
`CaptureEnvelope` JSON file. Grab the ones relevant to your new screen and:

1. Drop the raw `.json` file(s) into `app/src/test/resources/snapshots/INBOX/` (this directory is
   gitignored — it's a scratch intake folder, never committed).
2. Run the inbox processor:

   ```bash
   ./gradlew :app:testDebugUnitTest --tests "*InboxProcessorTest*"
   ```

3. Read the console output for each file. `InboxProcessorTest` runs your capture through the
   *live* production ruleset and sorts it:
   - **IDENTIFIED (\<intent\>)** — an existing rule already matches this screen. It gets moved
     into `snapshots/<intent>/` automatically. If you expected a *new* rule to be needed and this
     happens, you're either duplicating an existing rule or your capture is a screen you already
     handle.
   - **TOXIC** — a sensitive-screen rule matched, or the keyword scanner flagged it. The test
     **fails** on purpose; see [§5](#5-privacy-rules-of-the-road) — you handle this by hand, not
     by re-running the test.
   - **UNKNOWN** — nothing matches yet. This is the case you're here for. The test prints an
     **X-Ray report**: the screen's breadcrumb trail plus up to 5 distinct text values, up to 5
     content-description values, and up to 5 view IDs found anywhere in the tree. This is your raw
     material for writing `require`/`bind`/`parse` predicates — copy the exact ID suffixes and text
     strings you see there.

### Worked example: a hypothetical new DoorDash screen

Say DoorDash ships a new pickup-flow interstitial — a "Delivery Note" screen that lets the dasher
add a text note before starting navigation, with a title `"Add a note for this pickup"` and a
resource ID `note_entry_confirm_button` on its confirm button. You captured it, dropped it in
`INBOX/`, and `InboxProcessorTest`'s X-Ray report shows:

```
🔎 X-RAY (Top 5 items):
   🔤 Text:
      • "Add a note for this pickup"
      • "Skip"
      • "Save note"
   🆔 IDs :
      • "note_entry_title"
      • "note_entry_input_field"
      • "note_entry_confirm_button"
```

That's everything you need for §3.

---

## 3. Authoring the rule

### ID namespace

Every rule id follows `<platform>.<kind>.<name>` — e.g. `doordash.screen.pickup_navigation`,
`uber.click.decline_offer`, `doordash.notification.order_ready`. For a screen rule with no
explicit `intent`, the app derives the observation's target name by stripping the platform+kind
prefix (`RuleCompiler.deriveTargetFromId`) — `doordash.screen.pickup_note_entry` derives to
`pickup_note_entry`, which is also the golden-corpus folder name that snapshot will live in
(§4's folder-name-equals-intent convention). Pick a name that describes the screen, not the
change that added it.

### Priority and the overrideable partition (#419)

Rules are evaluated in **priority order** (lower number = evaluated first), but there's a second
axis: every rule carries `overrideable` (default `true`). At match time, the **entire
non-overrideable partition runs first** (in priority order), and only after every non-overrideable
rule has had a chance does the overrideable partition run. This means an `overrideable: false`
rule can *never* be pre-empted by a lower-priority-number rule from anywhere else — priority only
orders rules *within* their partition. DoorDash's `sensitive.known` rule
(`matchers/rules/doordash/sensitive.json5`) is the load-bearing example: priority `0`,
`overrideable: false`, so it structurally blocks everything else from ever matching a banking
screen first. Almost every ordinary screen rule you write will leave `overrideable` at its
default (`true`); reach for `false` only when a match must be unconditionally protected from being
shadowed by some other rule's priority.

**Priorities must be unique per rule type, across the whole merged platform** — not just within
the file you're editing. The compiler rejects two screen rules sharing a priority number with an
explicit `RuleCompileException` (verified while writing this tutorial: reusing `pickup_navigation`'s
priority `60` for a new rule in a *different* DoorDash sub-file failed the build with "Duplicate
priority 60 in screen rules"). Before picking a number, skim the neighboring rules in the file
you're editing and pick something unused nearby — the exact value doesn't matter, only its
ordering relative to rules it must beat or lose to. Likewise, a duplicate rule **id** across
sub-files fails the canonicalize build loud (#639) — the likelier mistake when copying an existing
rule as a template, so change the `id` first thing.

### require / bind / parse

The engine evaluates a screen rule in five phases — **bind → reject → require → parse →
validate** (full spec: ADR-0001). For a first rule you'll mostly use three of them:

- **`require`** — the positive match condition. All of it must be true for the rule to fire.
- **`bind`** — optionally locate and *name* a node during the bind phase, so `parse` can reference
  it later via `from: "$name"` instead of re-finding it. Only worth it when `parse` needs to pull
  more than one field off the *same* node, or navigate relative to it.
- **`parse`** — extract typed fields from the matched tree once `require` has confirmed the match.

Continuing the worked example — a straightforward flat-`find` rule, no `bind` needed because
nothing downstream needs to re-anchor to the title node:

```json5
{
  "id": "doordash.screen.pickup_note_entry",
  "priority": 59,
  "require": {
    "all": [
      { "exists": { "hasIdSuffix": "note_entry_title" } },
      { "exists": { "hasIdSuffix": "note_entry_confirm_button" } }
    ]
  },
  "parse": {
    "fields": {
      "promptText": {
        "find": { "hasIdSuffix": "note_entry_title" },
        "read": "text"
      }
    }
  }
}
```

Drop that into `matchers/rules/doordash/pickup.json5` (it's a pickup-flow interstitial, so it
belongs there — see the surface map in the DoorDash rules README), run the inbox loop again, and
the capture should now sort as `IDENTIFIED (pickup_note_entry)`.

### The `redact` obligation when a rule hashes PII

If a screen shows **customer** name or address text and your `parse` hashes it (the `sha256`
transform, used so customers can be differentiated for dedup without ever storing their raw info —
see [§5](#5-privacy-rules-of-the-road)), the rule **must** also declare a `redact` block masking
that same node's text in the serialized capture envelope. This is enforced at **compile time**
(`RuleCompiler`, #598): a screen rule that uses `sha256` anywhere in its `parse` block with no
top-level `redact` fails the build with an explicit error telling you to add one. There's no way to
ship a PII-hashing rule that silently leaks the plaintext to disk.

Extending the worked example: suppose the note-entry screen actually pre-fills the note field with
the customer's name (`"Note for Jordan T"`) and you need to hash it for correlation. Now you need
`bind` (so `require`/`parse` share one anchor) and `redact`:

```json5
{
  "id": "doordash.screen.pickup_note_entry",
  "priority": 59,
  "bind": {
    "noteLine": { "find": { "hasIdSuffix": "note_entry_title" } }
  },
  "redact": [
    {
      "find": { "hasIdSuffix": "note_entry_title" },
      "keepPrefix": ["Note for "],
      "normalize": "customerName"
    }
  ],
  "require": {
    "exists": { "hasIdSuffix": "note_entry_confirm_button" }
  },
  "parse": {
    "fields": {
      "customerNameHash": {
        "from": "$noteLine",
        "read": "text",
        "transform": [
          { "stripPrefix": "Note for " },
          "normalizeCustomerName",
          "sha256"
        ]
      }
    }
  }
}
```

Two details worth calling out because they're compiler-enforced, not just convention:

- `normalizeCustomerName` must sit **immediately before** `sha256` in the transform chain — never
  bare (it would persist the canonical name in the clear) and never in any other position (the
  same customer must hash identically whichever surface renders their name, e.g. "Brandy S" on one
  screen vs "Brandy Smith" on another).
- `redact`'s `normalize: "customerName"` mirrors the same canonicalization on the capture-mask
  side, so the `[redacted:<4hex>]` token on disk is derived from the *same* canonical key the parse
  hashes — a customer's mask is stable across every screen shape DoorDash renders their name in.

`redact` is **whole-rule only** — you cannot put it inside a `branches[]` entry (compiler rejects
that too, since a branch-scoped redact would silently no-op), and it's rejected outright on
CLICK-context rules (a click envelope only ever carries app-vocabulary button labels, never PII).

### What rules can NOT do

Rules are **recognition only** — they identify and extract, they never act. The compiler rejects
any effect verb that would tap or gesture on the third-party app (`click`, `tap`, `swipe`,
`scroll`, `set_text`, `long_click` — and any future gesture verb the same way, fail-closed). A rule
that wants to expose "this is the Accept button" for the app to *possibly* tap does so with a
**target binding** — a well-known `bind` name like `acceptButton`/`declineButton` — that the
app-owned `RuleAction` registry (`:domain`) consumes on its own terms (consent-gated, label-
verified at tap time). See `matchers/rules/doordash/offer.json5`'s `offer_popup` rule for a real
example (`bind: { acceptButton: ..., declineButton: ... }`) and `docs/design/rule-capability-consent.md`
for the consent model. Your first rule almost certainly won't need this — it only applies to
screens that expose an actionable button, not passive/informational screens like the worked
example above.

---

## 4. Verifying your rule

Run the fast recognition-only regression suite — no state machine, DB, or UI involved:

```bash
./gradlew :app:testDebugUnitTest --tests "*AllMatchersSuite*"
```

This is a fixed bundle of tests (`AllMatchersSuite.kt`); the two you'll interact with most as a
rule author:

- **`GoldenSnapshotRegressionTest`** — the positive guard. Every snapshot under a
  `snapshots/<folder>/` directory must still classify as `<folder>`'s intent when run through the
  live ruleset — **the folder name IS the expected intent**. This is why `InboxProcessorTest`
  filed your worked-example capture into `snapshots/pickup_note_entry/`: that folder now pins your
  new rule's behavior going forward. If a later rule change breaks it, this test catches the
  regression.
- **`ParseOutputGoldenTest`** — the parse-*output* guard (#433). It compares every snapshot's typed
  parsed fields against a committed golden file, `snapshots/approved-parse-output.json`. Adding a
  rule that parses new fields — like `promptText`/`customerNameHash` above — legitimately changes
  that golden. Regenerate it deliberately:

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "*ParseOutputGoldenTest*" -DupdateParseGolden=true
  ```

  Then **review the diff of `snapshots/approved-parse-output.json`** before committing — that
  review *is* the regression gate. Don't regenerate and commit blindly; read what changed and
  confirm it's what you intended. The same test also ratchets corpus coverage (new intents should
  ship with a corpus snapshot) and lints `dedupeKey` templates against fields the rule actually
  parses.

Once both are green (and the rest of the suite, which they will be if you didn't touch anything
else), commit the new/updated rule file **and** the snapshot(s) that moved out of `INBOX/` into
their intent folder, **and** the regenerated `approved-parse-output.json` if it changed. Never
commit anything still sitting in `snapshots/INBOX/` — that folder is gitignored intake, not a
corpus location. And treat a rule (`.json5`) change as a **code** change for CI purposes — it
alters compiled recognition behavior and generated assets, so never use `[skip ci]` on a rule PR
(that escape is strictly docs-only).

---

## 5. Privacy rules of the road

DashBuddy's non-negotiable Pledges (full text: root `CLAUDE.md`) apply to every rule you write, not
just ones that happen to touch obviously-sensitive screens:

- **The dasher's own sensitive screens are blocked, never parsed or stored.** Banking/DasherDirect
  balance & transfer, payment, and the dasher's own identity documents — plus **document-image
  capture surfaces** (the license-scan camera, the signature pad) regardless of whose ID or
  signature it is — must never be recognized-and-parsed. They're matched by a dedicated
  `sensitive` rule shape (`"parse": { "as": "sensitive" }`) instead, which the app routes to a hard
  drop before any capture is written.
- **Customers are hashed, not blocked.** Customer-facing delivery screens — including things like
  an alcohol delivery's ID-check instruction screen — are recognized normally; any customer
  name/address text in the parse gets `sha256`-hashed (§3) so customers can be told apart for
  dedup/correlation without their actual info ever being stored. The dasher's *own* name (e.g.
  first-name + last-initial in a main-menu greeting) is fine to process as plain text — the
  hash-not-block rule is about the *customer*, not the dasher.
- **Every platform that ships screen rules must ship a sensitive rule.** This is enforced at
  load time (#432, `JsonRuleInterpreter`): if a platform's screen-rule set has zero rules whose
  `parse.as == "sensitive"`, the whole platform's rules are rejected (fail-closed — no screens are
  recognized for that platform at all) rather than silently missing banking-screen coverage. If
  you're adding a brand-new platform (not just a new screen to an existing one), your very first
  rule file needs at least one `sensitive` rule before anything else in it will load.
- **Recognized captures are redacted independently of the parse** — see §3's `redact` obligation.
  A debug capture of a recognized customer screen never carries the raw customer text on disk
  either, even though the screen itself is fully recognized (not blocked).

### The SENSITIVE snapshot workflow

If `InboxProcessorTest` reports a file **TOXIC** (a sensitive rule matched it, or the keyword
scanner (`SnapshotSecurityScanner`) flagged it), the test **fails on purpose** and does *not* move
the file anywhere automatically. Handle it by hand:

1. Open the file and manually redact any raw sensitive text (account numbers, balances, the
   dasher's own PII) — replace it with placeholder text.
2. Move the redacted file into `app/src/test/resources/snapshots/SENSITIVE/` yourself.
3. Re-run `AllMatchersSuite` — its golden guard asserts every file under `SENSITIVE/` is either
   caught by an actual sensitive rule or flagged toxic by the scanner, so a snapshot that no longer
   trips either one is a regression, not a pass.

A recognition change must never blur the blocked/hashed line: don't widen a `sensitive` rule's
`require` to accidentally swallow an ordinary customer screen, and don't narrow it in a way that
lets a banking screen fall through to ordinary recognition.

---

## 6. When the matchers repo split lands

Everything above describes the **in-tree** workflow: `matchers/` is a self-contained included
Gradle build living inside the DashBuddy monorepo, and there is no publish step between editing a
`.json5` file and seeing it picked up by tests. Pillar 2 (Distributed Integrity) calls for
`matchers/` to eventually become its own separate, forkable, Apache-2.0 repository consumed as a
git submodule (see [ADR-0009](../adr/ADR-0009-rule-distribution-channels.md) for the target
composite-build shape) plus a signed CDN/OTA channel (Milestone 2, greenfield). That split is
**deliberately parked** — see the `matchers/README.md` framing and issue #637 — so nothing in this
tutorial should be read as describing that future shape. When the split lands, expect a fork/clone
step, a submodule pin, and possibly a signing/PR-review step this document doesn't cover today; a
follow-up to #637/#214 will rewrite this tutorial for that workflow rather than patch it in place.
