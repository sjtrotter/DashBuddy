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

## Known semantic deltas (RE2 vs JDK/ICU)

All were checked against the corpus; the parse-output golden is byte-identical.

- **`$` does not match before a final newline.** In RE2 (without multiline) `$` is end-of-text; the
  JDK also accepts the position before a single trailing line terminator. A node text ending in
  `"\n"` therefore no longer satisfies an anchored `…$` pattern. This is a *tightening*, no fielded
  capture exercises it, and for `CurrencyShape` — the tightest anchored shape we ship — refusing a
  trailing newline is the behaviour we want.
- **`\b` is ASCII.** RE2's word boundary uses ASCII word characters. The recognition layer already
  assumes an English device (CLAUDE.md §1), so this differs only where a non-ASCII letter abuts a
  word.
- **`(?i)` and `\p{L}` follow Unicode simple rules.** `\p{L}` is supported and behaves as expected
  for the #885 first-last-initial name shape; case folding is Unicode simple folding.

Every delta moves *toward* one consistent behaviour on both host and device, which is the second
thing this change buys: for rule patterns, the ICU/JDK divergence that bit #909 no longer exists.

## Enforcement

- `RegexReDoSTest` / `RegexBudgetPropertyTest` (seeded, #878) now assert the **real** property — the
  catastrophic shapes compile and match within a hard wall-clock bound, measured on the calling
  thread with no watchdog to hide behind.
- `RuleRegexEngineGuardTest` (`:core:pipeline`, the `IcuRegexGuardTest`/`TimberTagGuardTest`
  doctrine) source-scans the rule package: no `java.util.regex`, every `Regex(…)` construction takes
  a string literal (app-authored, never a value from rule JSON), and the app-authored constants sit
  in a frozen count ledger that can only burn down.
- `RuleRegexIsLinearTimeTest` (`:core:pipeline` `androidTest`) runs the headline exploit on ART for
  provenance. Instrumented, so it rides the emulator nightly rather than gating PR CI.
- The corpus is the compile proof: `AllMatchersSuite` compiles all 121 rule patterns, and
  `ParseOutputGoldenTest` proves recognition output is unchanged.

## Licensing

RE2J is **BSD-3-Clause** — permissive, compatible with the app's PolyForm Shield license and with
the Apache-2.0 `matchers/` build, and imposing no obligation on either delivery channel. It is a
dependency of `:core:pipeline` only. This matters beyond convenience: the CDN rule path (#192/#640)
means accepting patterns the developer never reviewed, and an untrusted pattern language must be one
whose worst case is known.
