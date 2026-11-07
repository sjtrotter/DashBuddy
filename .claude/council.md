# Council members (gig.log)

Read by `council`, `interrogate` and `sol-loop`; overrides `~/.claude/council.md`. Reviews are read-only.

## Standing rule (dev, 2026-10-27)

Every PR's adversarial review is `sol-loop`: one Sol seat, up to 3 rounds, stop at the first round whose head is
both Sol-clean and CI-green (CLAUDE.md delivery chain step 4, #1455). Use the
full council only when the developer says "council" (seats, dev 2026-10-31: Claude, Sol, Fable when the usage budget
allows, and Astra only when added). The builder is an Opus subagent (`docs/agents/workflow.md` § Model policy rule 3),
so the Claude seat is a fresh context, never the builder's.

Every round reviews the full base diff (rule (f) below); rounds 2 and 3 also get the finding ledger and
the change since the last round. This overrides `sol-loop`'s "review only open ids and changed code".

## Why an adversarial review (#589)

The design-goal review (CLAUDE.md § Git Workflow) is the author walking their own diff, and self-review shares the
author's blind spots. So after it, and before the PR is filed, run `sol-loop`: a read-only Sol (`gpt-6.1-sol`, xhigh)
review of the diff, with the round ledger, the 3-round cap and the stopping rule below, adjudicated by the session (dev
standing rule since 2026-10-27, replacing the `/code-review` + `/security-review` + Astra trio).

**Reviewer strength:** the reviewer runs at its highest effort (Sol at xhigh) and the session adjudicates every
finding against the source; an adversary weaker than the author is theater. Record the outcome in the
`### Adversarial review` block of the PR description (`file-pr`'s review record); fix findings in-PR or file and link
them. A PR merges only once the adversarial pass is clean or its findings are triaged. `sol-loop`'s ledger, round cap
and escalation rules implement (b)–(f) below.

## The three attack axes

The adversary works three axes and must not take the author's framing on any of them:

1. **Correctness**: bugs, edge cases, broken callers, reuse and simplification.
2. **Design principles**: walk principles 1–8 and the Reactive UI rules hunting for violations the author missed or
   rationalized away. Platform-agnosticism (principle 8) is mandatory for any diff touching `:core:state`,
   `:core:pipeline`, `:domain` or rule JSON.
3. **Security**: for diffs touching the untrusted-input boundaries (rule compile and ingest, accessibility-tree
   mapping, regex, PII and sensitive handling, capture, network, effects), confirm the fail-closed, bounded-ingestion
   and no-plaintext-leak properties still hold, backed by the security property/fuzz suite (#590). That suite is
   seeded (#878): every kotest property pins an explicit `PropSeeds.config(SEED)`, so PR CI draws the same samples on
   every run and a red property is a reproducible finding, never a re-run-and-move-on flake. Bump a site's `SEED`
   deliberately to explore new samples, never to turn a red run green; the unseeded breadth lives behind
   `-Dgiglog.propExplore=true` (10× samples, off the PR path).

## Rounds

From the 2026-09-30 audit of PRs #1149 (15 rounds) and #1160 (17 rounds), revised 2026-10-07 and 2026-10-19. Those
loops were productive for their first ~5 rounds (real Pledge defects) and then oscillated: on #1160 four rule
decisions were each re-decided 4–10 times. The signal is not the round number but the pattern of findings. Rule (a)
was superseded by (f) and is gone.

- **Each round's mechanics** (dev, 2026-10-31, #1455): one push of committed work; that head's CI run and Sol round
  start together on the same SHA, and the worktree is not edited while the round runs. Fix both sets of findings,
  then push once, which starts the next round. Any push that changes the diff starts a round, a CI-only fix
  included, so a clean verdict never carries onto changed code. The three-round cap counts every round. Sol cannot
  compile, so its compile claims are candidates CI settles; CI cannot judge design. The brief names the head's SHA
  and run and, once it ends, its `gate-summary.txt`.
- **(b)** Fix follow-ups in the PR that found them (dev rule 2026-10-07). File an issue only when the follow-up is
  wildly out of scope for that PR (a different subsystem or a multi-PR design), and say why in the PR.
- **(c)** A finding that reverses an earlier accepted fix, or grows the PR's scope (a new build task, a new generated
  resource, a new subsystem), is escalated to the developer, never applied by the coordinator.
- **(d)** A round whose verdict is MERGE / MERGE WITH FIXES with no confirmed defect is the last round, provided its
  head is also CI-green (delivery chain step 4: a later push that changes the diff is a new round).
- **(e) Oscillation means redesign** (dev, 2026-10-07). When a round finds a new defect of the same class the
  previous fix was meant to close (widen an anchor and it over-matches; narrow it and the leak returns), stop
  patching. Write down what the rounds taught, then restart on a fresh branch with a redesigned approach that removes
  the fragile dependency (receipts: #1264's every-window screenshot check became a 495-lines-smaller lean check;
  #1265's recognize-to-redact anchor gave way to a rule-independent backstop). Prefer the cheap design on hot paths
  (the app is an observer and must be fast). The adjudicator's job is to reject, not to relay.
- **(f) The stopping rule** (dev, 2026-10-19, after S2f's 17 and 13 review rounds). Keep every review on the full
  diff, and give later rounds the delta since the last adjudicated head and the finding ledger too. Stop at the first
  clean independent round on a CI-green head (delivery chain step 4 is the one definition), cap at three rounds per
  reviewer, and treat a reviewer that never returns zero as miscalibrated rather than as evidence of defects. Every
  reviewer answers "would you block this merge, and why"; the adjudicator fixes blocking findings in the PR and
  records the rest.

## Sol

- run: `timeout 2400 codex exec -m gpt-6.1-sol -s read-only -c 'model_reasoning_effort="xhigh"' --json -o <plan-dir>/sol-round<N>-reply.md - < <plan-dir>/sol-round<N>.md`,
  started as a background Bash call from the PR's worktree
- effort: xhigh
- plan dir: `~/dashbuddy/design/<date>-<issue>/`
- the sandbox cannot run Gradle; each round runs beside the PR CI run on the same head (#1455), and the brief names
  that run and its `gate-summary.txt` once it ends (`gh run download <id> -n junit-results-<pr>`, #1450). Sol's
  compile claims are candidates; CI settles them.

## Claude

- run: Agent tool, `subagent_type: general-purpose`, `model: opus`, a fresh context, read-only brief
- effort: high
- never beside a Gradle run (host memory, CLAUDE.local.md)

## Fable (when available)

- run: Agent tool, `subagent_type: general-purpose`, `model: fable`, a fresh context, read-only brief
- effort: high
- skip the seat and say so when the usage budget is tight or the call fails on a limit; never substitute a model
- never beside a Gradle run (host memory, CLAUDE.local.md)

## Astra (only when added)

- run: `timeout 2400 codex exec -m gpt-6-astra -s read-only -c 'model_reasoning_effort="xhigh"' --json -o <plan-dir>/astra-round<N>-reply.md - < <plan-dir>/astra-round<N>.md`,
  after the Sol seat finishes (one codex job at a time)
- effort: xhigh

## Every brief adds

- The three attack axes above: correctness; principles 1–8 and the Reactive UI rules,
  with principle 8 mandatory for `:core:state`, `:core:pipeline`, `:domain` and rule JSON; and security
  (fail-closed gates, bounded ingestion, no plaintext PII) for any diff touching recognition, capture, redaction,
  network or effects.
- Walk each new rule through the intended main case (retro 2026-10-31, #1467: a "started before the last
  shift end" rule would have rejected the shift that had just ended).
- The question "would you block this merge, and why".
- Sol may read the field pulls (`~/dashbuddy/logs`: logs, captures, the database) when a review needs the evidence
  behind a claim (dev, 2026-10-27). Briefs still keep personal data out of their own text, and nothing a reviewer
  reads goes into an issue, a PR or a commit.
