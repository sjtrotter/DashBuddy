# PR workflow detail and model policy

`CLAUDE.md` § Agent skills (delivery chain) and § Git Workflow hold the steps every PR session follows. This file
holds the detail those steps point at: the subagent model policy, reading CI, the CI escape hatches, and the rules for
remote sessions. Read it before spawning a subagent, when CI will not start, when a PR needs its artifacts, or when you
work from a remote session.

## Model policy

The shape of substantial work is three phases: **plan and vet at the top tier, build with a lower tier, validate at
the top tier.** The developer pays the top-tier cost at both ends on purpose: ideas, design and vetting happen rich and
up front (waiting out a usage reset is accepted; the developer prefers more spent on planning over cheap plans), and
the review closes the loop. A well-vetted plan with sharp boundaries is what makes the build safe to delegate, and the
review is what makes a cheap build trustworthy. Saving the scarce tier means not spending it on mechanical execution
in the middle; it never means skimping on planning, design, vetting or review. Never dip into usage credits or
pay-by-token on the developer's behalf (on 2026-06-25 a mid-workflow session-limit collapse killed 36 agents and
wasted all their spend).

Rules, in precedence order:

1. **The merge gate runs at full strength.** The adversarial review is `sol-loop` (Sol, `gpt-6.1-sol`, at xhigh;
   seats in `.claude/council.md`), and the session adjudicates every finding that gates a merge, against the source.
   The reviewer is the safety net for everything built below it; it has repeatedly caught author-missed defects
   (PRs #609/#610/#611). Save budget on the builder, never on the reviewer.
2. **Planning, design and vetting run at the top tier.** Usually that is the main loop itself. Top-tier subagents are
   also legitimate in this phase (adversarial design vetting, judge panels, adjudicating conflicting evidence,
   Pledge or security verdicts); name the phase when spawning one. The phase's deliverable is a plan bounded enough
   to hand down: named files, the expected change shape, acceptance criteria, and what the tests and review must
   prove.
3. **The default builder is a Claude Opus subagent** (`model: opus`, set explicitly, never omitted to inherit; dev
   decision 2026-10-31). From 2026-09-30 it was the codex CLI (`gpt-6-astra`), chosen after the third usage-limit
   collapse (06-25, 08-09, 09-30); Astra's #1442 build then reached PR CI with compile breaks that CI found one per
   ~13-minute run. The session writes a bounded spec to a scratchpad file (named files, exact change shape, done
   criteria, which suites must go green, and the `docs/architecture/0N` file for each layer touched, since a builder
   sees only `CLAUDE.md` unless the spec names more). The Opus subagent builds it in the PR's worktree, may run a
   small compile or one module's tests through `gt.sh`'s memory guard (never the full gate, never beside another
   Gradle run or a Claude analysis agent), and does not commit; the session commits, pushes to the draft PR and
   reads PR CI. Builders never open `~/dashbuddy/logs` or raw captures (Sol reviews may).
4. **The budget fallback builder is codex Astra**: `timeout N codex exec -m gpt-6-astra -s workspace-write -c
   'model_reasoning_effort="high"' … - < spec.md`. It has no network and no repo memory, so its spec must be
   self-contained.
5. **Any lower-tier builder gets only bounded work**: building a well-bounded implementation from an already-vetted
   plan, and only when confident it can execute: named files, an explicit change shape, and cheap verification
   (existing tests or the review catch a failure inexpensively; boundedness is what makes verification cheap, which
   is what makes the delegation save anything). Haiku only for trivially mechanical scans. Never hand a lower tier
   open-ended research, design, root-cause analysis or judgement work: boundedness is the control that limits hallucinations and expansive errors. If the plan is not bounded enough to delegate,
   that is a planning gap: sharpen the plan at the top tier, do not promote the builder.
6. **Weigh the usage limit at the moment of delegation.** There is no meter to read, so treat top-tier scarcity as a
   standing assumption, not a measurement. If limit errors have appeared this session, or the developer has signalled
   they need their usage (for example, about to dash), shrink or defer the work, or ask before launching: a workflow
   that dies mid-fan-out wastes everything it already spent. When the budget is tight, economize in the middle phase;
   planning and review keep their priority.

## Reading CI and its artifacts

PR CI (`pr-check.yml`, job `build-and-test`) is the gate because the laptop cannot run the full gate safely (#1450).

- Draft and ready (#1548): one job, named by the PR's state. A draft PR's run is `draft-check`: compile, the debug
  APK, the unit tests and the schema check, with no `lintVitalRelease` and no screenshots. Marking the PR ready
  (`gh pr ready`, a `ready_for_review` run) and every later push run the full `build-and-test`, as do master pushes
  and dispatch runs. A draft never creates a `build-and-test` check, not even a skipped one, because a skipped
  required check counts as passed: right after `gh pr ready` the head has no `build-and-test` until the full run
  reports. Mark the PR ready before the round the session expects to be the last (Sol found nothing blocking, or only
  small fixes), so that round's CI is the full run; marking it ready seconds before merging adds a full run at merge.
  A break only release lint or the screenshots catch (`NewApi`, `ExtraTranslation`, a render crash) then shows at
  ready and costs a round.
- `pr-wait --until green` waits for the required `build-and-test`, so on a draft it never returns: watch a draft's run
  with `gh run watch <id> --exit-status`.

- Read a red run with `gh run view <id> --log-failed` and its `gate-summary.txt`
  (`gh run download <id> -n junit-results-<pr>`); `tools/ci/junit_summary.py` reads downloaded JUnit XML.
- Local Gradle is only small runs (`:domain:test`, one module's compile) through `~/dashbuddy/tools/pr-loop/gt.sh`'s
  memory guard; that guard is what the skills' `free -m` check means here.
- Screens: `gh run download <id> -n screenshots-<pr>` gives light and dark PNGs of the key screens, recorded only,
  never compared (#1463). The same run renders them after the gate, also when the gate is red as long as the build
  compiled. `tools/ci/screenshots.txt` lists the expected files, and `tools/ci/check_screenshots.py` fails the job
  only on a missing or undecodable one (`screenshots-summary.txt` in the artifact names a crashed render). Locally the
  PNGs land in `feature/bubble/build/outputs/screenshots` and `app/build/outputs/screenshots`. A draft's run
  (`draft-check`) skips the render; for a draft's pictures, dispatch the full run (`gh workflow run pr-check.yml -f
  pr=<pr>`).
- A red "Schemas committed" step uploads the exported JSON as `room-schemas-<pr>`: download it, review it and commit
  it, with no laptop build.
- CI runs Gradle with `--parallel --max-workers=4 --build-cache` (#1547, CI command lines only; the laptop keeps its own
  limits). Master pushes and PR runs write the Gradle cache, and a PR reads master's and its own earlier runs'. Test
  tasks and `:core:database`'s KSP tasks are never cached (undeclared inputs and outputs), so a cache hit can never
  pass a test or a schema export that did not run.

## Skip-ci, force-ci and the dispatch escape hatch

- `pr-check.yml` skips every step of `build-and-test` when the PR **body** contains the literal `[skip ci]` (not
  `[no-ci]`, not a comment, not a label), and the job passes in seconds. Use it only when the diff has no code; when in
  doubt, let CI run. The job itself always runs (`RUN_GATE`, #1548): a job skipped by a job-level `if` reports its
  name expression unevaluated, so the dynamically named job would never produce the required `build-and-test`.
- The token is read when a run starts: a code branch's early draft body must not carry it, and a later body edit
  (`file-pr`) neither re-runs CI nor skips it.
- Footgun (#902/#928): the check is a bare `contains()`, so a code PR whose body merely describes the token skips its
  own CI. Paraphrase it ("the skip-ci token") or add `[force ci]` to the body, which forces the run back on.
- The `CLAUDE.md` size budget runs in its own workflow (`claude-md-budget.yml`) and ignores both tokens.
- If Actions refuses to dispatch runs for a PR's tree (the 2026-07-27 class: no run object across shas and
  branches), use the `workflow_dispatch` escape hatch: Actions → PR Check → Run workflow → enter the PR number. It
  builds that PR's merge ref and reports a real `build-and-test` commit status onto the PR head it tested (pinned
  before the build, #1450), which satisfies the required check without an admin override. A commit that changes the
  tree (an empty commit does not) got `pull_request` runs again on 2026-11-08 (#1548), and a `ready_for_review` on a
  refused tree starts no run either. From a shell: `gh workflow run pr-check.yml --ref <branch> -f pr=<pr>`.

## Branch protection, lint and release secrets

- `master` is protected by a ruleset (#1450): a PR is required, merge commits only, `build-and-test` must pass (a
  skipped run counts), no approvals, and the admin bypass applies to PRs only.
- The signing secrets live in the `release` environment, which only `v*.*.*` tags can use.
- The full gate (`build-and-test`, not a draft's `draft-check`) also runs `:app:lintVitalRelease` (#907) after the
  unit tests; `:app`'s `lint { checkDependencies = true }` folds the library modules into that one run, so a local `:app:build` or `assembleRelease` also pays the
  multi-module vital pass (accepted). Only the `NewApi` id is promoted to fatal (root `lint.xml`, #1162);
  `InlinedApi` is benign by construction.
- `values-es` is intentionally incomplete until the translation-completion work lands; that completeness gate is
  deferred, not forgotten. `LocaleAllowlistGuardTest` enforces the locale allowlist.

## Remote sessions cannot delete branches

Branch deletion is workstation-only. Field and remote agents (Claude Code on web or mobile) must not try to delete
their own branch: they reach GitHub through a local auth proxy that refuses ref deletions, and every form of the
delete hangs the connection, then reports a misleading `Everything up-to-date`. The GitHub MCP server has no
branch or ref delete tool, and `merge_pull_request` has no delete-branch option, so a remote agent has no way to
delete a branch. Do not retry it. After merging via MCP, leave the branch and say in the final reply that it needs
manual cleanup (the PR page's "Delete branch" button, or the workstation's `post-merge.sh`).

On the workstation, each issue's branch lives in a linked worktree (`~/dashbuddy-wt/<n>`), and `gh pr merge
--delete-branch` fails to delete a branch that is checked out there. That is why `post-merge.sh` merges with
`--merge`, removes the worktree first, then deletes the branch locally and on the remote.
