# Council members (gig.log)

Read by `council`, `interrogate` and `sol-loop`; overrides `~/.claude/council.md`. Reviews are read-only.

## Standing rule (dev, 2026-10-27)

Every PR's adversarial review is `sol-loop`: one Sol seat, up to 3 rounds, stop at the first clean round. Use the
full council (Sol plus the Claude seat below) only when the developer says "council".

Every round reviews the full base diff (CLAUDE.md review rule (f)); rounds 2 and 3 also get the finding ledger and
the change since the last round. This overrides `sol-loop`'s "review only open ids and changed code".

## Sol

- run: `timeout 2400 codex exec -m gpt-6.1-sol -s read-only -c 'model_reasoning_effort="xhigh"' --json -o <plan-dir>/sol-round<N>-reply.md - < <plan-dir>/sol-round<N>.md`,
  started as a background Bash call from the PR's worktree
- effort: xhigh
- plan dir: `~/dashbuddy/design/<date>-<issue>/`
- the sandbox cannot run Gradle; the brief points at the green PR CI run's `gate-summary.txt` (downloaded with
  `gh run download <id> -n junit-results-<pr>`, #1450)

## Claude

- run: Agent tool, `subagent_type: general-purpose`, `model: opus`, a fresh context, read-only brief
- effort: high
- never beside a Gradle run (host memory, CLAUDE.local.md)

## Every brief adds

- The three attack axes from CLAUDE.md § Git Workflow: correctness; principles 1–8 and the Reactive UI rules,
  with principle 8 mandatory for `:core:state`, `:core:pipeline`, `:domain` and rule JSON; and security
  (fail-closed gates, bounded ingestion, no plaintext PII) for any diff touching recognition, capture, redaction,
  network or effects.
- The question "would you block this merge, and why".
- Sol may read the field pulls (`~/dashbuddy/logs`: logs, captures, the database) when a review needs the evidence
  behind a claim (dev, 2026-10-27). Briefs still keep personal data out of their own text, and nothing a reviewer
  reads goes into an issue, a PR or a commit.
