# Domain docs

How the engineering skills read this repo's domain documentation. Layout: **single-context**.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root, when it exists. It does not exist yet; `domain-modeling` creates it when the
  first term is settled. Until then the domain vocabulary lives in `CLAUDE.md` (architecture summaries §1–§5) and
  `docs/architecture/0N-*.md`.
- **`docs/adr/`**: the ADRs that touch the area you are about to work in. Files are named
  `ADR-NNNN-slug.md`; copy the newest one's header block (Status, Issue, Date, Builds on) and its Context /
  Decision / Consequences sections.
- **`docs/architecture/`**: the per-layer reference that CLAUDE.md's §1–§5 summaries point to.

If a file does not exist, proceed silently. Do not suggest creating it up front.

## Use the glossary's vocabulary

When your output names a domain concept (an issue title, a refactor proposal, a hypothesis, a test name), use the
term the glossary or CLAUDE.md uses: a dash is a `Session`, a delivery is a `Task` inside a `Job`, an offer's identity
is its `presentationKey`, money a dasher keeps is `kept`. Do not drift to synonyms.

If the concept you need has no term yet, either you are inventing language the project does not use (reconsider) or
there is a real gap (note it for `domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, say so explicitly rather than overriding it silently:

> _Contradicts ADR-0011 (UNKNOWN census privacy model), but worth reopening because…_
