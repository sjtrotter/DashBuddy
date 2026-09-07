# ADR-0010: Linear-Time Rule Regex (RE2J behind `BoundedRegex`)

**Status:** Accepted — implemented in #1053.
**Issue:** #1053 (opened out of the #1052 review as a HIGH pre-existing finding)
**Date:** 2026-09-06
**Builds on:** ADR-0001 (matcher rule format), ADR-0009 (rule distribution channels)
**Related:** #418 (compile-time ReDoS guard), #590 (security property/fuzz suite),
#909 (the host-JVM / Android-ICU divergence), #192 / #640 (CDN rule delivery)

---

## Context

Recognition rules are **data, not code** (ADR-0001), and rule JSON is treated as *untrusted input*
(Development Principle 6): bounded ingestion, fail-closed validation, and — once #192/#640 opens a
CDN channel — signature verification before compile. A rule may carry a regex, and that regex runs
on the per-event classification hot path against text supplied by a third-party app.

Two controls were supposed to make "an accepted rule regex has bounded match time" true:

1. **#418 — a compile-time ReDoS heuristic.** `RegexSafety.assertNoCatastrophicBacktracking`
   rejected the nested-unbounded family (`(a+)+`, `(a*)*`, `(.*)+`, `(\d+){2,}`).
2. **#590 — a runtime match budget.** `BoundedRegex` ran the match on the calling thread against an
   `InterruptibleCharSequence` while a daemon watchdog interrupted that thread after 200 ms; the
   guarded sequence threw from `charAt` on the next backtracking step.

Both are unsound, and the second is unsound *specifically on the device*:

- **A structural heuristic can never be complete.** #590's own KDoc listed three shapes that pass
  it and then backtrack unbounded — ambiguous alternation `(a|aa)+$`, optional-inside-star
  `(a?)*b`, bounded-outer-over-unbounded-inner `(.*a){20}` — plus backreference blowups. There are
  more; there is no finite list.
- **The watchdog cannot fire on ART.** Android's `java.util.regex` is ICU-backed:
  `Matcher.reset(CharSequence)` stringifies its input and hands the match to native ICU
  (`Matcher.java:1812`/`:658`), so `InterruptibleCharSequence.charAt` is never called again and the
  interrupt has nothing to land on. `android.icu` exposes no `RegexMatcher` reachable from
  `java.util.regex`, so there is no timeout API to reach for either. The 200 ms budget held only on
  the host JVM — i.e. only in the tests, and never in production.

That is the **#909 class of defect**: a property asserted green on the host that does not exist on
the device. `IcuRegexGuardTest` (#909) scans Kotlin *literals* for the same divergence; nothing in
PR CI ever ran a **rule-authored** pattern on ICU at all.

The corpus census settled what a change could cost. Across both platforms' JSON5, 121 regex-bearing
strings (44 distinct patterns) use unbounded quantifiers, character classes, `\b`, `\p{L}`, lazy
`.+?`, `(?:…)`, numbered capture groups and anchors. **Exactly one** feature lies outside RE2
syntax: the Uber notification title `^Going to (?!\d)`, a negative lookahead. No backreferences, no
possessive or atomic groups, no named groups.

## Decision

**Rule-authored regexes compile onto RE2J** (`com.google.re2j:re2j:1.8`, pure Java, BSD-3-Clause,
~250 KB), behind the existing `BoundedRegex` seam.

RE2J simulates an NFA instead of backtracking. Match time is linear in
`input.length × pattern.size`, with no exponential blowup and no per-repetition recursion — so
"accepted ⇒ bounded match time" becomes a **theorem about the engine** rather than a promise about
a timer. And because it is pure Java, the *same implementation* runs on the host JVM and on ART:
every host unit test of a rule pattern is now a faithful device test.

Consequences, all of which are the point:

- **The compile-time ReDoS heuristic is deleted.** `(a+)+$` is a microsecond match on a
  non-backtracking engine, so a guard rejecting it is a second, weaker owner of a property the
  engine already guarantees — rejecting *safe* patterns for a risk that no longer exists
  (Principle 5).
- **The watchdog machinery is deleted** — the executor, `InterruptibleCharSequence`,
  `RegexBudgetExceeded`, the completion gate, `BUDGET_MS`, and the `StackOverflowError` catch that
  existed because the JDK engine recurses per repetition.
- **Bounded ingestion is unchanged.** `RuleCompiler.MAX_REGEX_LENGTH` (200 chars) still caps the
  pattern text, and an unparseable pattern is still a loud `RuleCompileException` at load, per file.
  What changed is the *time* bound, not the *size* bound.
- **The rule pattern language is now RE2 syntax:** no lookaround, no backreferences, no possessive
  or atomic groups. `docs/rules.schema.json` states this at every regex-bearing field.
- **`^Going to (?!\d)` → `^Going to (?:\D|$)`** in `matchers/rules/uber.json5` — the same language
  ("`Going to ` followed by a non-digit, or by end of title"), verified identical against 2 842
  corpus strings. It is a boolean `titleMatchesRegex` predicate, so the one-character difference in
  match *length* between a zero-width lookahead and a consuming class is unobservable.
- **Kotlin `MatchResult` leaves the rule engine.** `BoundedRegex.find` returns a `BoundedMatch`
  (`value`, `range`, `groupValues`, `groups`) mirroring Kotlin's conventions exactly, and
  `groupCount()` replaces the compile-time `toPattern().matcher("").groupCount()`. Nothing outside
  the seam can reach a matcher, which is what makes the engine swappable and the guarantee
  checkable.

### Rejected alternatives

**B. A dedicated match worker with `Future.get(200 ms)`, abandoning the thread on timeout.** The
frame would fail closed, but the runaway native match keeps burning a core forever — ICU cannot be
interrupted — so it needs an abandonment cap, after which rule regex must be disabled wholesale.
Most code, ugliest failure mode, and the host still never reproduces the device behaviour.

**C. Widen the static heuristic** (alternation overlap, nullable-body stars). A heuristic is never
sound; `(a|aa)+$`, `(a?)*b` and `(.*a){20}` are three shapes and there are more. It leaves the
property false while looking like it is enforced, which is the situation this ADR exists to end.

## What RE2J does not bound, and what we added (round 2)

An independent security pass found three things the first version of this change got wrong. All
three share a shape: *linear match time* was treated as if it settled every question the old
machinery had been answering. It does not.

### Compiled program size is unbounded by the length cap

RE2J 1.8 has **no program-size ceiling**. The C++ RE2's `max_mem` has no equivalent in this port:
there is no size or budget parameter on `com.google.re2j.Pattern.compile`, and `Compiler` expands a
counted repeat by copying the sub-program `n` times. So `MAX_REGEX_LENGTH` bounds the wrong
dimension — **`(a{1000}){1000}` is fifteen characters and compiles to 1 002 002 instructions**, and
a 23-character nested repeat exhausts a capped heap *at compile*. Rule load runs on the device, once
per rule, so a careless author — or, once #192/#640 opens the CDN channel, a hostile rule — turns a
length-capped pattern into an OOM. Failing loud is not enough when the failure is the process.

`RegexSafety` therefore walks the pattern before RE2J sees it and rejects:

| cap | value | what it bounds |
|---|---|---|
| `MAX_REPEAT` | 64 | any single written bound in `{n}` / `{n,m}` / `{n,}` |
| `MAX_REPEAT_PRODUCT` | 4 096 | the product of NESTED repeat factors (an unbounded `*`/`+` counts as `MAX_REPEAT`) |
| `MAX_GROUP_DEPTH` | 16 | group nesting |

The arithmetic: a group's repeat multiplies every repeat inside it, so `(a{50}){50}` is 2 500 copies
(≈ 2 602 instructions) and `((a{50}){50}){50}` is 125 000 (≈ 130 102 — the OOM shape). Sequential
repeats *add*, so only nesting multiplies. Under these caps the worst pattern that fits in 200
characters measures **123 010 instructions and 8 ms**, versus 1 002 002 for fifteen unchecked ones.
`(a+)+` costs 4 096 and stays legal, because on a non-backtracking engine it is genuinely safe — the
caps bound the compiler, they are not the ReDoS heuristic returning under another name.

> **`MAX_REPEAT` = 64 is tight against the shipped corpus.** The largest counted repeat in either
> ruleset is **60** (`^.{1,60} \((\d{2,6}…)\)$`, the payout store-name shape), with `{1,48}`
> behind it. `RuleCorpusCompileBudgetTest` measures and prints that number so an author who needs a
> longer bound is told what the margin was; the honest fix is to raise the constant after checking
> the product arithmetic, not to work around it in a rule.

### The `StackOverflowError` catch is restored

The first pass deleted it on the premise that a non-backtracking engine cannot recurse deeply. That
premise is not safe enough to bet a subsystem on: RE2J walks the parsed tree recursively when it
simplifies and compiles, ART's thread stacks are smaller than the host JVM's, and a
`StackOverflowError` is an **`Error`** — it escapes every `catch (e: Exception)` downstream, which
is exactly how #909 lost 91.7 % of a dash's data.

Stated honestly: the reviewer reported a ≤ 200-character pattern that overflows during a match, and
**it could not be reproduced here** — 99-deep nesting (the deepest the length cap admits) compiles
and matches at a 256 KB stack. So the catch is a *defence*, not a fix for a demonstrated crash. It
costs one `try` on a path already measured in microseconds. `MAX_GROUP_DEPTH` bounds the same risk
from the load side, and `BoundedRegex.failClosed` is `internal inline` so the fail-closed contract is
asserted directly rather than through a pattern that must overflow a real stack.

### Perl classes are translated to Unicode RE2 classes

**This is the Pledge-relevant one.** Android's `java.util.regex` is ICU-backed, and ICU's `\d`, `\s`
and `\w` are **Unicode**: `\d` is `\p{Nd}`, `\s` includes `\p{Z}`, `\w` includes non-Latin letters.
RE2's are **ASCII**. Moving rule patterns onto RE2J therefore *narrowed every rule on the device*
while every host test stayed green — the #909 shape again, and it lands on two redacts:

1. the #885 first-last-initial name shape separates tokens with `\s{1,4}`, so a customer name
   rendered with a non-breaking space would stop matching and **the redact entry that masks it would
   silently not fire**;
2. Uber's two `Going to …` notification rules discriminate on `\d`, so a dropoff address in
   non-ASCII digits would fall from the address-masking dropoff rule to the **redact-less** pickup
   rule — and `CustomerTextMarkers` deliberately excludes the store-ambiguous `"Going to "` prefix,
   so a raw customer address would reach the envelope.

So `RegexSafety` translates at the one compile seam and rule authors keep writing `\d`/`\s`/`\w`:

| written | outside a class | inside a class |
|---|---|---|
| `\d` | `\p{Nd}` | `\p{Nd}` |
| `\D` | `\P{Nd}` | `\P{Nd}` |
| `\s` | `[\s\p{Z}]` | `\s\p{Z}` |
| `\S` | `[^\s\p{Z}]` | **rejected** |
| `\w` | `[\p{L}\p{N}_]` | `\p{L}\p{N}_` |
| `\W` | `[^\p{L}\p{N}_]` | **rejected** |

`\S`/`\W` inside a character class are the negation of a *union*, which cannot be expressed as class
members; leaving them ASCII would recreate the gap this table closes, so they fail the load. No rule
uses them. The byte-SSOT pins (`SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN`, `CurrencyShape`) are
pins on a shape's **source bytes**, not on an engine's semantics, so they are unchanged as written;
`SnapshotRedactor`'s test-side Kotlin `Regex` copy is host-only and stays.

The length cap is measured on the pattern **as written**, before translation — that is what the
schema validates and what a rule review reads — and translation makes patterns longer.

**`\b` is a stated residual.** RE2's word boundary is ASCII-only and there is no Unicode form to
translate it to. Every `\b` in the corpus sits against an ASCII word (`mi\b`, `min\b`, `\bby`,
`\bgate`, `\bpin`), so nothing regresses today, but a `\b` beside a non-ASCII letter now behaves
differently on ART than it did before this change.

One consequence worth stating because it looks like a regression and is not: `CurrencyShape`'s `\d`
become Unicode, so a mixed-script figure (`$16.٧٠`) now satisfies the rule-side money *scan* — its
leading `[1-9]` is a literal ASCII range and stays ASCII. That is the correct division of labour:
the scan finds a money-shaped node, and #1052's code-point rejection inside `parseGlyphCurrency`
refuses to read a figure it cannot read. The end-to-end result is **null**, never a fabricated
number, and it is asserted as such.

## Known semantic deltas (RE2 vs JDK/ICU)

All were checked against the corpus; the parse-output golden is byte-identical.

- **`$` does not match before a final newline.** In RE2 (without multiline) `$` is end-of-text; the
  JDK also accepts the position before a single trailing line terminator. A node text ending in
  `"\n"` therefore no longer satisfies an anchored `…$` pattern. This is a *tightening*, no fielded
  capture exercises it, and for `CurrencyShape` — the tightest anchored shape we ship — refusing a
  trailing newline is the behaviour we want.
- **`\b` is ASCII**, and unlike `\d`/`\s`/`\w` it cannot be translated — see the residual above.
- **`(?i)` and `\p{L}` follow Unicode simple rules.** `\p{L}` is supported and behaves as expected
  for the #885 first-last-initial name shape; case folding is Unicode simple folding.
- **`\d`/`\s`/`\w` are translated**, so they keep ICU's Unicode meaning on the device rather than
  RE2's ASCII one. See the round-2 section above — this one was a Pledge regression, not a nuance.

Every delta moves *toward* one consistent behaviour on both host and device, which is the second
thing this change buys: for rule patterns, the ICU/JDK divergence that bit #909 no longer exists.

## Enforcement

- `RegexReDoSTest` / `RegexBudgetPropertyTest` (seeded, #878) now assert the **real** property — the
  catastrophic shapes compile and match within a hard wall-clock bound, measured on the calling
  thread with no watchdog to hide behind.
- `RuleRegexEngineGuardTest` (`:core:pipeline`, the `IcuRegexGuardTest`/`TimberTagGuardTest`
  doctrine) source-scans the rule package: no `java.util.regex` — neither the package name nor its
  entry points `Pattern.compile(…)` / `.toPattern()` — every `Regex(…)` / `.toRegex()` /
  `Regex.fromLiteral(…)` construction takes a string literal (app-authored, never a value from rule
  JSON), and the app-authored constants sit in a frozen count ledger that can only burn down.
- `RuleCorpusCompileBudgetTest` compiles every regex the generated rulesets declare and asserts each
  one under a 50 ms load-time budget, then prints the corpus's largest counted repeat against
  `MAX_REPEAT` so the margin is visible rather than assumed.
- `RuleRegexIsLinearTimeTest` (`:core:pipeline` `androidTest`) runs the headline exploit on ART for
  provenance. Instrumented, so it rides the emulator nightly rather than gating PR CI — and
  `.github/workflows/instrumented-nightly.yml` actually runs `:core:pipeline:connectedAndroidTest`,
  which round 2 found it did not.
- The corpus is the compile proof: `AllMatchersSuite` compiles all 121 rule patterns, and
  `ParseOutputGoldenTest` proves recognition output is unchanged.

## Licensing

RE2J is **BSD-3-Clause** — permissive, compatible with the app's PolyForm Shield license and with
the Apache-2.0 `matchers/` build, and imposing no obligation on either delivery channel. It is a
dependency of `:core:pipeline` only. This matters beyond convenience: the CDN rule path (#192/#640)
means accepting patterns the developer never reviewed, and an untrusted pattern language must be one
whose worst case is known.
