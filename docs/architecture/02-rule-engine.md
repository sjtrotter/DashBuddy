> **Detailed architecture reference — moved out of `CLAUDE.md` on 2026-09-07 (context-budget trim v3).**
> This file is the full, receipt-level narrative for this layer: every rule, its issue/PR receipt, and the
> reasoning that produced it. `CLAUDE.md` keeps the short summary an agent needs every session; this file is
> where a change's history and invariants are recorded in depth. **The 'every PR ships with context updates'
> rule applies here exactly as it does to `CLAUDE.md`:** when a PR changes an invariant described below, update
> this file in the same PR (and the `CLAUDE.md` summary if it goes stale).
> A `§N` reference below means the sibling numbered file here (or its `CLAUDE.md` summary).

# JSON Rule Engine (`core/pipeline/.../rules/` + generated `assets/rules/`)

Recognition is **data, not code**. The rule SOURCE is per-platform **JSON5** under `matchers/rules/`
(spec in ADR-0001, editor schema `docs/rules.schema.json`), owned by the included `matchers` Gradle
build (#635/#192; ADR-0009) — see the `matchers/` bullet under Module Structure for the flat-file vs
surface-directory layout and the merge. Sub-files reference `docs/rules.fragment.schema.json` (a
`required`-free fragment schema whose `screens`/`clicks`/`notifications` `$ref` the main schema's
`$defs`, so a partial file shows no false "missing format_version" error); the manifest,
`uber.json5`, and the merged output keep the strict full schema. Rule ORDER within a file is
behaviorally inert — every rule has a unique priority within its section, so `matchFirst`'s
stable-sort tie-break is never exercised — which is what made the doordash split a pure repartition
(#639: canonicalizing the pre-split flat file vs the post-split directory yields byte-identical
output once each rule array is sorted by `id`, so `ParseOutputGoldenTest` stayed green with no
regen). `:core:pipeline:importMatchersRules` imports the canonical output into **generated**
`assets/rules/*.json` (`build/generated/assets/importMatchersRules/rules/`), which both the APK (AGP
Variant-API asset merge) and the unit tests (`:app:testDebugUnitTest dependsOn` it;
`TestRulesetFactory` reads the generated dir) consume — the app loader/tests/runtime are unchanged,
still ONE file per platform. There are **no committed** `assets/rules/*.json`, so editing a JSON5
value flows straight into recognition tests with no publish step. The corpus↔rules SHA version pin
is deferred to N5/#638. The canonical files are compiled by `RuleCompiler` and matched by
`ObservationClassifier`.
Rules carry a `priority` and an `overrideable` flag. `matchFirst` evaluates the **non-overrideable
partition first** (priority-ordered), then the overrideable partition (priority-ordered), so an
`overrideable: false` classification can never be pre-empted by a lower-priority-number rule from
any other/later source (#419); priority only orders *within* a partition. The high-confidence
sensitive block (`sensitive.known`) is priority 0 + `overrideable: false` — structurally first,
blocking all further processing of banking/identity screens — while the low-confidence
`sensitive.catchall` net stays `overrideable: true` (priority 999) so specific recognition still
wins over it. Rules also carry `require` predicates, `bind` blocks, `parse`
blocks that produce typed fields via `ParsedFieldsFactory`, and an optional `redact` block
(#598) — node predicates whose matched text is masked in the capture envelope (a screen rule
that hashes PII via the `sha256` transform MUST declare a non-empty `redact`, enforced at compile;
the mask keeps a `keepPrefix` marker so recognition on replay is unchanged). An effect's `dedupeKey`
interpolates `{field}` against the branch's RAW parse plus two DERIVED reserved tokens resolved
post-factory by the classifier (`DedupeTokens`, the lint's SSOT): `{parsedHash}` = the parse's
CONTENT identity (#427) and `{presentationHash}` = its PRESENTATION identity (#859,
`ParsedFields.presentationHash` — for an offer, #830's `presentationKey`, fail-closed to
`offerHash`), so a surface that live-re-quotes itself fires its effects once per showing rather
than once per quote. There are no Kotlin matcher classes —
changing recognition means editing rule JSON plus corpus tests. **Rules cannot declare actuation**
(#425): the compiler rejects `click`/gesture effect verbs; rules instead expose well-known *target
bindings* (`acceptButton`, `declineButton`, `expandButton`) that the app-owned `RuleAction`
registry (`:domain`) consumes — see `docs/design/rule-capability-consent.md`.

**Rule-authored regexes run on a linear-time engine (#1053, ADR-0010).** Every rule pattern —
predicate, parse `find`, redact `match`, `nextSiblingMatchingRegex` — funnels through the ONE
`RegexSafety.compileRegex` → `BoundedRegex` seam, which now compiles onto **RE2J** (pure Java,
BSD-3): an NFA simulation that cannot backtrack, so match time is linear in `input × pattern` and
"accepted ⇒ bounded" is a property of the engine rather than a promise about a timer. It replaces
two controls that were both unsound. The #418 compile-time ReDoS heuristic rejected the
nested-unbounded family but could never be complete (`(a|aa)+$`, `(a?)*b`, `(.*a){20}` all passed
it); the #590 200 ms watchdog could not fire **on Android at all** — `Matcher.reset(CharSequence)`
stringifies its input and hands the match to native ICU, so the `InterruptibleCharSequence` the
interrupt depended on was never consulted again, and `java.util.regex` reaches no ICU timeout API.
The budget held only on the host, i.e. only where it was not needed: the #909 class of defect, one
layer down. Both are DELETED — the heuristic, the watchdog executor, `InterruptibleCharSequence` and
`RegexBudgetExceeded` — and `(a+)+$` is now a legal, safe, microsecond pattern. (The
`StackOverflowError` catch was deleted with them and then RESTORED in round 2: it is retained today
as `BoundedRegex.evaluating`, which no longer swallows the failure into a default but raises
`RegexEvaluationFailed` — see below.) **The program size is MEASURED, not estimated.** Linear MATCH time says nothing about COMPILE cost,
and RE2J 1.8 has no program-size ceiling (the C++ `max_mem` has no equivalent in the port), so
`(a{1000}){1000}` is 15 chars and 1 002 002 instructions and rule load runs on the DEVICE once per
rule. Round 2 bounded that with a structural walk over the pattern text and the round-3 review
defeated the estimate THREE ways — a standalone `(?i)` introduces no atom so the quantifier after it
applies to the PRECEDING expression (the walk forgot its cost: `x{200}(?i){200}` scored 200, emitted
40 002); `{0,}` means `*` so its factor is 1, not 0; and a 187-char literal inside nested repeats
packs **1 548 418 instructions (~88 MiB, ~131 ms)** into 200 chars at a product of exactly 8 192 —
plus `\Q…\E` quoting, which hid structure from the scan entirely. The lesson is structural: a walk
over pattern TEXT cannot predict what a compiler EMITS. So `compileRegex` is two gates: (1) a
**coarse pre-compile guard** — `MAX_REGEX_LENGTH` 200, `MAX_REPEAT` 200 on any single written bound,
`MAX_GROUP_DEPTH` 16, `\Q…\E` and leading-zero bounds (`a{0201}`, which RE2J reads as LITERAL TEXT)
rejected, `(?i:…)` scoped flag groups accepted, and a cost estimate capped at
`MAX_ESTIMATED_INSTRUCTIONS`=200 000 whose ONLY job is to keep the compile from exhausting memory
(approximate, may under-count, nothing trusts its number); (2) the compile wrapped in
`catch (Throwable)` so an OOM/SOE is a loud `RuleCompileException` — recovery is BEST-EFFORT
(building the message can itself fail), it over-classifies unrelated VM errors as bad patterns, and
the outcome is WHOLE-FILE rejection (`isolable = false`), which is the repo's existing policy for
resource limits;
(3) **the bound** — `Pattern.programSize()` ≤ `MAX_PROGRAM_SIZE`=**1 000**. The corpus's largest
MEASURED program is **240** instructions (the #885 name shape) — a 4× margin, re-measured and
printed by `RuleCorpusCompileBudgetTest` every run. **Round 5 lowered that from 20 000 for a
match-time reason, not a memory one:** `^((.?){100}){40}$` is 17 chars, estimates 28 244, compiles to
16 084 — both gates accepted it — and matching a FIVE-char input overflows RE2J's `Machine.add` on
256 KiB, 512 KiB AND 1 MiB stacks (ART's coroutine threads are ~1 MiB). **Round 6 lowered it again to
1 000 and struck the "threshold instruction count" claim:** recursion depth in `Machine.add` grows
with NULLABLE NESTING, not with instruction count alone — a 20-char `^((((a?)?)?)?){150}$` is 1 954
instructions and recurses ~45 % deeper than a 1 644-instruction shape that does NOT overflow — so the
cap bounds EXPOSURE and `RegexEvaluationFailed` is the backstop for whatever remains inside it. **And an evaluation
failure no longer has a single default** — `BoundedRegex` raises `RegexEvaluationFailed` (pattern
LENGTH only, one WARN per pattern per process) and each boundary answers it: recognition
(`Ruleset.matchFirst`) treats the rule as no-match and moves on, parse yields null (#745), and
**redaction masks the WHOLE node/field** with plain `[redacted]`. That last one is the defect: a
`false` default read as "no redact entry matched" and shipped the node RAW — fail-closed for a
positive predicate is fail-OPEN for a redaction selector. The catch is deliberately NOT in
`PredicateCompiler`, because a redact `find` compiles through the same `compileNodePred` and
swallowing it there would reproduce the fail-open one layer down; each CONSUMER catches, the
predicate propagates. A product-of-all-bounds estimate was rejected on
measurement: it scores that 240-instruction shape at 109 363 200, higher than the 1.5M attack, so no
ceiling separates them. The class scanner also handles a leading `]` (a MEMBER, not the terminator —
`[]\s]` was being rewritten into something that matched space-then-bracket) and opaque POSIX
`[:alpha:]` classes. The `StackOverflowError` catch is kept (`BoundedRegex.evaluating`) and is
**load-bearing**: round 2 said a match-time overflow
could not be reproduced and that was wrong — `((a?){200}){40}` against `""` overflows in RE2J's
`Machine.add` at a 256 KiB stack.
The price is the pattern **language**: rule regexes are RE2 syntax — no lookaround, no
backreferences, no possessive/atomic groups — which is exactly the language an untrusted CDN rule
source (#192/#640) needs, one whose worst case is known. The corpus cost was a single pattern:
uber's `^Going to (?!\d)` → `^Going to (?:\D|$)` (same language, verified identical over 2 842
corpus strings; it is a boolean predicate, so the zero-width-vs-consuming difference is
unobservable). **The Perl classes are TRANSLATED toward ART's Unicode classes at that same seam** (a Pledge fix):
Android's `java.util.regex` is ICU-backed and ICU's `\d`/`\s`/`\w` are UNICODE (`\d` is `\p{Nd}`,
`\s` includes `\p{Z}`, `\w` includes combining marks) while RE2's are ASCII — so moving to RE2J
silently NARROWED every rule on the device while the host stayed green, and two redacts regressed:
the #885 name shape's `\s{1,4}` stops matching a name rendered with a non-breaking space (the
customer-name mask never fires), and an Uber `Going to <non-ASCII digits>` address falls from the
address-masking dropoff rule to the redact-LESS pickup rule, which `CustomerTextMarkers` deliberately
excludes. So `RegexSafety` emits `\d`→`\p{Nd}`, `\D`→`\P{Nd}`, `\s`→`[\s\p{Z}\x{0B}\x{85}]`,
`\S`→the negation, `\w`→`[\p{L}\p{M}\p{N}\p{Pc}]`, `\W`→the negation, with join controls `\x{200C}\x{200D}` in `\w` (Android's `\w` has
`Join_Control`, and the shipped merchant-pair shape was matching ZWJ on device) (escape- and class-aware;
inside a class the un-bracketed forms, and `\S`/`\W` there are REJECTED — a negated union isn't a
class member and leaving it ASCII would reopen the gap), authors keep writing `\d`, and the
byte-SSOT pins are pins on a shape's SOURCE BYTES, not on engine semantics, so they are unchanged as
written. The length cap is measured on the pattern AS WRITTEN, before translation. **It is an
APPROXIMATION, not parity** — RE2J 1.8 and ART's ICU ship different Unicode TABLE versions, so
identical property names disagree at the edges, and the residuals are stated rather than claimed
away: RE2J's `\p{Nd}` lags ICU (U+1E951 Adlam), its `\p{Z}` still has U+180E, the `\w` form admits
superscript `²` (deliberate — over-matching `\w` widens a match while under-matching DROPS a redact),
`\b` cannot be translated at all (ASCII-only, no Unicode form — and naming the ASCII word on ONE
side is not enough, since the character on the other side decides too: the shipped distance finder
takes `17 mié` under RE2J and not under ICU), and RE2J's `\p{L}`/`\p{Nd}` LAG Android's Unicode
tables by a version so an Adlam letter/digit is a letter/digit to ART and not here — which an explicit class COULD
close one character at a time (`[\p{Nd}\x{1E951}]`) but which we decline to hand-maintain against a
moving ICU version (#1086), and which can drop the #885 name redact or send an Adlam-digit address to
the redact-less uber pickup rule, and case folding is SIMPLE where ICU's is FULL (`straße`/`STRASSE`). One consequence
that looks like a regression and is not: `CurrencyShape`'s `\d` become Unicode so a mixed-script
`$16.٧٠` now satisfies the money SCAN (its leading `[1-9]` is a literal ASCII range), and #1052's
code-point rejection in `parseGlyphCurrency` then reads it as NULL — fail-null, never a fabricated
figure. Because RE2J is pure Java the SAME engine runs on host and ART — one engine on both, modulo those
Unicode table versions — so a host regex test is a faithful device test for structure and for
everything but those edges; one instrumented spot-check (`RuleRegexIsLinearTimeTest`) runs the
headline exploit on ART for provenance, wired into `instrumented-nightly.yml` as
`:core:pipeline:connectedAndroidTest`. Kotlin
`MatchResult` no longer escapes the seam — `find` returns a `BoundedMatch` mirroring Kotlin's
conventions (`groupValues` `""`/`groups` null for a non-participating group) and `groupCount()`
replaced the compile-time `toPattern()` — and `RuleRegexEngineGuardTest` source-scans the rule
package (no `java.util.regex` — neither the package name nor `Pattern.compile(…)`/`.toPattern()`;
every `Regex(…)`/`.toRegex()`/`Regex.fromLiteral(…)` takes a string LITERAL, never a value from rule
JSON — a literal with no `$` interpolation and no non-constant concatenation, since `Regex("$pat")`
opens with a quote too; the app-authored constants sit in a frozen count ledger that only burns
down), and `RuleCorpusCompileBudgetTest` compiles every shipped pattern under a 50 ms load budget and
under `MAX_PROGRAM_SIZE`, printing both corpus maxima. Known semantic
deltas, all toward one consistent behaviour and none exercised by the corpus: RE2's `$` is
end-of-text (it does not match before a final newline — a tightening `CurrencyShape` wants), `\b` is
ASCII (the layer already assumes an English device), `\p{L}` and `(?i)` follow Unicode simple rules.

**Two primitives exist for reading an id-less render (#1029), both bounded.** DoorDash 8.93.7
shipped its money surfaces with NO view ids, so the parse vocabulary needed shape anchors where it
had only id and position anchors. (1) The **`parseGlyphCurrency` transform** reads an animated
digit-wheel — a figure rendered as per-glyph id-less `TextView`s beside a label, which `read:
allText` fuses into one string (`"This dash so far$16.70"`, or `"$3.10This dash"` when the label
trails). It first **rejects** any non-ASCII digit and any `-`/`−`/`(` (#1052 — a keep-filter
DELETES what it does not know, so a stripped Arabic-Indic digit or minus sign leaves a remainder
that still full-matches: an unreadable figure read confidently and wrong), keeps only the
`$`/digit/`.`/`,` characters, then **full-matches** a settled shape (`$` + 1–4 digits, or a single
`[1-9],\d{3}` thousands group — #1052 tightened the comma arm, which had admitted six figures
through a branch the shape's own ceiling caps at four — plus exactly 2 decimals) or returns
**null** — bounded input (256 chars), fail-closed, and that strictness is the point: ~1 in 5
fielded reads is mid-animation (`$70103.030`, `$016.603`), and a fabricated figure is strictly
worse than none. `parseCurrency` is not merely useless on that shape but WRONG — it splits on space
and takes the first token, so a space-separated wheel reads `$1.00`. (2) The
**`nextSiblingMatchingRegex(<pattern>)` navigate spec** scans up to `MAX_SIBLING_SCAN`=8 FOLLOWING
siblings and returns the first whose own text full-matches, so a rule states the SHAPE it expects
instead of a positional `sibling(N)`. The pattern compiles through `RegexSafety` at rule-LOAD time
(length cap + fail-loud RE2 parsing, a loud `RuleCompileException`, never a hot-path hang) and
matches through `BoundedRegex.matches` (the whole-input sibling of `containsMatchIn`, same
linear-time engine, fail-closed to no-match). Its receipt: 8.93.7 flattened the pay breakdown into id-less siblings
`'Customer tips', '799', '$7.00'`, where `799` is a DoorDash type CODE older builds render in the
`pay_line_item_title` slot — so `sibling(1)` + `parseCurrency` reported a **$799.00 tip on a $16.70
delivery**. No offset is right on both layouts; "the next money-shaped node" is. The spec
takes an **optional scan cap** (`nextSiblingMatchingRegex(<pattern>, <n>)`, default and ceiling
`MAX_SIBLING_SCAN`=8; a cap outside 1..8 — or present but unparsable, #1052 — isolates the rule at
load, and the default applies only when no cap was written), and the three DoorDash money scans
declare `2`: a CORRECTNESS control, not merely a bound — on a row whose value is simply absent
(`['Customer tips','799','Peak pay','$1.00']`) an unbounded scan returns the NEXT row's money AS
this one's, fail-WRONG and invisible to `sumApproxEquals` since `appPay` is null on 8.93.7. The
sibling walk itself has ONE owner, `UiNode.followingSiblings()`/`precedingSibling()`, resolved by
REFERENTIAL identity and shared with the #860/#886 mask predicates — `UiNode.equals` ignores
children, so a flattened row's twin wrappers make a structural `indexOf` start the scan from the
wrong node; positional `sibling(N)` keeps its old structural semantics. And "a well-formed currency
figure" is ONE definition, `CurrencyShape` (`:core:pipeline`), from which both `parseGlyphCurrency`
and the rules' scan patterns derive — byte-pinned by `CurrencyShapePinTest` over the generated
assets, the `FIRST_LAST_INITIAL_PATTERN` precedent. It is TIGHTER than either hand-written
predecessor: no leading-zero integer (`$016.70`, one settled digit from the fielded mid-spin
`$016.603`) and no malformed thousands group (`$1234,567.00`, which the old Kotlin shape folded to
1234567.0; the old rule-side `^\$[\d,]+\.\d{2}$` also took `$,.00` → 0.0). Neither primitive can
catch a mid-spin read that is well-FORMED but wrong — that is the settle gate's job (§3).
