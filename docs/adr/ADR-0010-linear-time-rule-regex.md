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
one engine on both — modulo the Unicode TABLE versions RE2J 1.8 and ART's ICU each ship (residuals
listed below), so a host unit test of a rule pattern is a faithful device test for structure and for
everything but those edges.

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

### Compiled program size is unbounded by the length cap — so it is MEASURED

RE2J 1.8 has **no program-size ceiling**. The C++ RE2's `max_mem` has no equivalent in this port:
there is no size or budget parameter on `com.google.re2j.Pattern.compile`, and `Compiler` expands a
counted repeat by copying the sub-program `n` times. So `MAX_REGEX_LENGTH` bounds the wrong
dimension — **`(a{1000}){1000}` is fifteen characters and compiles to 1 002 002 instructions**. Rule
load runs on the device, once per rule, so a careless author — or, once #192/#640 opens the CDN
channel, a hostile rule — turns a length-capped pattern into an OOM. Failing loud is not enough when
the failure is the process.

Round 2 answered this with a structural walk that bounded the *product of nested repeat factors*.
Round 3's review defeated that estimate three separate ways:

| input | what the walk scored | what RE2J emitted |
|---|---:|---:|
| `x{200}(?i){200}` | 200 | 40 002 |
| `((x{200}){0,}){200}` | 200 | 41 002 |
| `((` + `a`×187 + `){128}){64}` — 200 chars, product exactly 8 192 | "at the cap" | **1 548 418** (≈ 88 MiB, ≈ 131 ms) |

A standalone `(?i)` introduces no atom, so the quantifier after it applies to the *preceding*
expression — the walk forgot that expression's cost. `{0,}` means `*`, so its factor is 1, not 0.
And a long literal packs far more atoms into 200 characters than any product can see. `\Q…\E`
quoting defeated it a fourth way, by hiding structure from the scan entirely.

The lesson is structural, not a list of bugs: **a walk over pattern text cannot reliably predict
what a compiler emits.** The compiled program can simply be asked. So `compileRegex` is now two
gates:

1. a **coarse pre-compile guard** — `MAX_REGEX_LENGTH` 200, `MAX_REPEAT` 200 on any single written
   bound, `MAX_GROUP_DEPTH` 16, quoting and leading-zero bounds rejected, and a cheap cost estimate
   (`MAX_ESTIMATED_INSTRUCTIONS` 200 000). Its **only** job is to keep the RE2J compile in gate 2
   from exhausting memory or stack. It is approximate, it may under-count, and nothing downstream
   trusts its number;
2. the compile, wrapped in `catch (Throwable)` so an `OutOfMemoryError` or `StackOverflowError` from
   gate 1's residue is a loud per-rule `RuleCompileException` rather than process death;
3. **the bound**: `Pattern.programSize()` ≤ `MAX_PROGRAM_SIZE` = **20 000**.

The estimate is a cost walk — atom 1, concatenation sums, alternation and captures add overhead, a
quantifier multiplies the preceding unit — rather than the product of every bound in the pattern. A
product-of-all-bounds formula was considered and rejected on measurement: it scores the #885 name
shape (a **240**-instruction pattern) at **109 363 200**, higher than the 1.5 M-instruction attack,
so no single ceiling separates the corpus from the exploit. The cost walk scores that shape 156 and
the attack 1 565 058.

> **The corpus's largest measured program is 240 instructions** (the #885 first-last-initial name
> shape), with 199 and 157 behind it — an **83× margin** under the 20 000 ceiling.
> `RuleCorpusCompileBudgetTest` re-measures and prints it on every run, so the margin is a number in
> the build log rather than a claim in a comment.

**`\Q…\E` quoting is rejected outright** rather than parsed. RE2J supports it, and that is the
problem: a quoted region is opaque to any structural scan (`\Q[\E(a{1000}){1000}` slipped the round-3
walk entirely — the quoted `[` opened a phantom character class that hid the repeats), and
translating inside one is wrong anyway (`\Q\d\E` means the literal text `\d`, which the class
translation would turn into the literal text `\p{Nd}`). Refusing it closes the bypass structurally
instead of by careful bookkeeping, and costs nothing: no rule uses quoting.

**A leading-zero bound (`a{0201}`) is rejected** rather than read. RE2's counted-repeat grammar
refuses leading zeros, so RE2J reads it as the literal text `a{0201}` — the author's intent and the
engine's reading diverge with no error anywhere. Refusing the ambiguity is the only reading that
cannot surprise someone.

**Scoped flag groups (`(?i:…)`) are accepted.** They are ordinary RE2 groups; round 3 rejected them
as unsupported.

**The character-class scanner** now handles two pieces of RE2 grammar that round 3 got wrong, both of
which silently changed what a pattern matched rather than failing: a `]` immediately after `[` or
`[^` is a member, not the terminator (`[]\s]` was emitted as `[][\s\p{Z}]]`, which stopped matching a
space and started matching *space followed by* `]`), and a POSIX class `[:alpha:]` is opaque — its
`]` does not close the enclosing class.

### The `StackOverflowError` catch is restored

The first pass deleted it on the premise that a non-backtracking engine cannot recurse deeply. That
premise is not safe enough to bet a subsystem on: RE2J walks the parsed tree recursively when it
simplifies and compiles, ART's thread stacks are smaller than the host JVM's, and a
`StackOverflowError` is an **`Error`** — it escapes every `catch (e: Exception)` downstream, which
is exactly how #909 lost 91.7 % of a dash's data.

Round 2 recorded that a match-time overflow could not be reproduced. **That was wrong**, and round
3's review reproduced one: `((a?){200}){40}` matched against the empty string overflows inside
RE2J's own `Machine.add` on a 256 KiB thread stack. The catch is load-bearing, not precautionary.
`MAX_GROUP_DEPTH` bounds the same risk from the load side, and `BoundedRegex.failClosed` is
`internal inline` so the fail-closed contract is asserted directly — the reproduction is stack-size
dependent and would make a flaky test.

### Perl classes are translated toward ART's Unicode classes — an approximation

**This is the Pledge-relevant one.** Android's `java.util.regex` is ICU-backed, and ICU's `\d`, `\s`
and `\w` are **Unicode**: `\d` is `\p{Nd}`, `\s` includes `\p{Z}`, `\w` includes non-Latin letters
and combining marks. RE2's are **ASCII**. Moving rule patterns onto RE2J therefore *narrowed every
rule on the device* while every host test stayed green — the #909 shape again, and it lands on two
redacts:

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
| `\s` | `[\s\p{Z}\x{0B}\x{85}]` | `\s\p{Z}\x{0B}\x{85}` |
| `\S` | `[^\s\p{Z}\x{0B}\x{85}]` | **rejected** |
| `\w` | `[\p{L}\p{M}\p{N}\p{Pc}]` | `\p{L}\p{M}\p{N}\p{Pc}` |
| `\W` | `[^\p{L}\p{M}\p{N}\p{Pc}]` | **rejected** |

`\S`/`\W` inside a character class are the negation of a *union*, which cannot be expressed as class
members; leaving them ASCII would recreate the gap this table closes, so they fail the load. No rule
uses them. The byte-SSOT pins (`FIRST_LAST_INITIAL_PATTERN`, `CurrencyShape`) are pins on a shape's
**source bytes**, not on engine semantics, so they are unchanged as written; `SnapshotRedactor`'s
test-side Kotlin `Regex` copy is host-only and stays. The length cap is measured on the pattern **as
written**, before translation.

**It is an approximation, and the word matters.** RE2J 1.8 and ART's ICU ship different Unicode table
versions, so even identical property names disagree at the edges; exact parity is not reachable.
Round 3's review measured the differences, and they are stated rather than claimed away:

| class | residual difference |
|---|---|
| `\d` | RE2J 1.8's `\p{Nd}` lags ICU: U+1E951 ADLAM DIGIT ONE is a decimal digit to Android, not to RE2J. `\D` inherits the inverse. |
| `\s` | VT (U+000B) and NEL (U+0085) are added explicitly above, because Android's `\s` has them and `\p{Z}` does not. RE2J's older `\p{Z}` still includes U+180E, which current ICU whitespace excludes. |
| `\w` | `\p{N}` is a superset of Android's digit repertoire here — it admits superscript `²`, which ICU's `\w` excludes. Deliberate: over-matching `\w` widens a match, under-matching it drops a redact, and a dropped redact is the failure that costs. |
| `\b` | Cannot be translated at all — RE2's word boundary is ASCII-only and there is no Unicode form to map it to. Every `\b` in the corpus sits against an ASCII word (`mi\b`, `min\b`, `\bby`, `\bgate`, `\bpin`). |
| case folding | RE2J uses *simple* folding; ICU uses *full* folding for literals, so case-insensitive `straße` matches `STRASSE` on Android and not here. No corpus pattern relies on it. |

The two shipped shapes the review named are covered by the widened table: the #885 name shape again
accepts a NEL separator, and the `dropoff.json5` merchant shape again accepts a decomposed `Café`.
(A decomposed name in the #885 *name* shape stays rejected — that shape is written with `\p{L}`, not
`\w`, so it is a pre-existing limitation of the shape, not of this translation.)

One consequence that looks like a regression and is not: `CurrencyShape`'s `\d` become Unicode, so a
mixed-script figure (`$16.٧٠`) now satisfies the rule-side money *scan* — its leading `[1-9]` is a
literal ASCII range and stays ASCII. That is the correct division of labour: the scan finds a
money-shaped node, and #1052's code-point rejection inside `parseGlyphCurrency` refuses to read a
figure it cannot read. The end-to-end result is **null**, never a fabricated number.

## Known semantic deltas (RE2 vs JDK/ICU)

All were checked against the corpus; the parse-output golden is byte-identical.

- **`$` does not match before a final newline.** In RE2 (without multiline) `$` is end-of-text; the
  JDK also accepts the position before a single trailing line terminator. A node text ending in
  `"\n"` therefore no longer satisfies an anchored `…$` pattern. This is a *tightening*, no fielded
  capture exercises it, and for `CurrencyShape` — the tightest anchored shape we ship — refusing a
  trailing newline is the behaviour we want.
- **`\b` is ASCII**, and unlike `\d`/`\s`/`\w` it cannot be translated — see the residual table above.
- **Case folding is *simple*, not full** — ICU folds `ß` to `SS` for literals and RE2J does not.
- **`\d`/`\s`/`\w` are translated** toward ICU's Unicode meaning rather than left at RE2's ASCII
  one. See the section above — this one was a Pledge regression, not a nuance — and note that it is
  an approximation with a stated residual table, not parity.

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
- `RuleCorpusCompileBudgetTest` compiles every regex the generated rulesets declare, asserts each one
  under a 50 ms load-time budget **and under `MAX_PROGRAM_SIZE`**, and prints both corpus maxima
  (measured `programSize()` = 240; largest counted repeat = 60) so the margins are visible rather
  than assumed.
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
