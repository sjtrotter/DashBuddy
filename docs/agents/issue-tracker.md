# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues in `sjtrotter/DashBuddy`. Use the `gh` CLI for all
operations. Workstation ids (project number, field and option ids) are in `CLAUDE.local.md`.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body-file <file>`. Then, every time:
  - apply at least one existing label from CLAUDE.md § "GitHub Issues — Labels & Project" (never create a label);
  - add it to the Roadmap project: `gh project item-add 2 --owner sjtrotter --url <issue url>`.
- **Write the body to stand alone.** A reader who has not seen the conversation must understand it: explain each
  term in plain words, say what was observed and where (file, event id, capture name), and phrase causes as
  hypotheses until a test proves them. No edit-history footers, no session links.
- **Never put personal data in an issue**: no customer names, addresses, phone numbers, PINs or codes, and none of the
  dasher's banking figures. Refer to them by kind ("a customer's first name and last initial") and by event id or
  capture filename. Merchant names are fine.
- **Read an issue**: `gh issue view <number> --json number,title,body,labels,comments`.
- **List issues**: `gh issue list --state open --json number,title,labels --limit 500`, with `--label` / `--search`
  filters. Search open and closed issues before filing; add evidence to an open issue rather than filing a duplicate.
- **Sub-issue of an epic**: `gh issue edit <epic> --add-sub-issue <child>` (gh 2.94+; this box has 2.97). Put
  `Part of #<epic>` at the top of the child body as well.
- **Blocking**: native issue dependencies. Use the `addBlockedBy` GraphQL mutation in `CLAUDE.local.md` (node ids),
  or REST: `gh api --method POST repos/sjtrotter/DashBuddy/issues/<child>/dependencies/blocked_by -F issue_id=<blocker database id>`
  (`gh api repos/sjtrotter/DashBuddy/issues/<n> --jq .id`). Saying "blocked by #X" in prose without the edge is not enough.
- **Starting work**: add the `in-progress` label and set the board Status to In Progress before branching
  (`~/dashbuddy/tools/pr-loop/start-issue.sh` does both).
- **Comment**: `gh issue comment <number> --body-file <file>`.
- **Labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`.
- **Close**: `gh issue close <number> --comment "..."`. A merged PR that says `Closes #N` closes it; set the board
  Status to Done and drop `in-progress`.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests.)_

## When a skill says "publish to the issue tracker"

Create a GitHub issue as above (labels, project, plain-language body). A `to-spec` spec is also saved as
`~/dashbuddy/design/<date>-<topic>/spec-<issue>.md`, the file the codex builder specs are derived from.

## When a skill says "fetch the relevant ticket"

Read it as in **Read an issue** above, including comments.
