# ADR-0013: The Signed Feed Channel (one Pages site, signing per feed, keyless CI identity)

**Status:** Accepted. Decided, not built: on 2026-11-14 the Pages site serves only the privacy page, no part of the
feed channel exists (#1563 to #1568, #640, #641 and #695 are open), and the developer setup it needs (#1562) is
partly done.
**Issue:** #1561
**Epic:** #1559 (feeds, rules delivery, recognition health); the rules half is also epic #192
**Date:** 2026-11-14
**Builds on:** ADR-0009 (rule distribution channels, Milestone 2 amended in the same PR), ADR-0011 (census privacy
model, §9 and §18)
**Related:** #1562 (developer setup), #1563 (`feed-contract/`), #1564 (fuel publisher and Pages deploy), #1565
(transport, cache, consent), #1568 (rules batch loader), #640 (rules client), #641 (rules publishing), #695
(constants), #1569 (rules advisory), #1570 (custom rules sources), #1582 (privacy site)

---

## Context

Three kinds of data change on their own schedule and today reach the phone only through an app release or a key
compiled into the APK:

- Fuel prices. Every phone calls `api.eia.gov` with an API key compiled from `local.properties`
  (`core/network/build.gradle.kts:29-30`). CI builds carry no key, and any shipped key can be pulled out of an APK.
- Yearly constants such as the IRS mileage rate (#695).
- Recognition rules. A rule fix needs a code change, a build, Play review and the user's update. The runtime
  signature gate exists (`RulesetVerifier`), but `RulesetVerifier.verify` and `fromConfig`
  (`core/pipeline/src/main/java/cloud/trotter/log/gig/core/pipeline/rules/RulesetVerifier.kt:75,97`) have no
  production caller, and no signature has ever been published.

All three need the same thing: signed bytes, fetched without identifying the dasher, verified on the phone and kept
as last-good. ADR-0009 left this as Milestone 2 (OTA/CDN) with the host and signer undecided. ADR-0011 §9 makes
human approval before signing structural for rules. Decided in `~/dashbuddy/design/2026-11-08-feeds/plan-r7.md`
(outside the repo), which supersedes `design-r3.md` where they differ, after three council rounds and a security
review (`~/dashbuddy/design/2026-11-08-plan-validation/security-reply-claude.md`, findings X1 to X8).

## Decision

Everything in this section is planned unless it cites a file or setting that exists today.

### 1. One channel, one site, on GitHub Pages

Every feed (fuel, constants, rules) is served from one GitHub Pages site, `gig.log.trotter.cloud`, under `/feeds/`.
Each feed has a directory `/feeds/<feed>/` holding one `envelope.json` (the manifest as an exact string plus its
signature) and payload files named by their own sha256. The Pages source is GitHub Actions, so no `gh-pages` branch
exists and no publisher holds `contents: write`. The custom domain is set once, because the app pins the URL and
GitHub does not redirect a Pages URL after a repository rename.

The same site serves the privacy policy at `/privacy/` (#1582). A Pages deploy replaces the whole site, so the site
has one builder (`tools/site/build_site.py`) and one deploy workflow (`.github/workflows/pages.yml:1-3`). A feed
publish adds its feed to that build and carries every other feed over from the last deployed site, re-verified
against the pinned keys; it never deploys a site of its own and never replaces the site. Only a feed's owning job
replaces that feed: fuel by the cron on master, rules by a `rules-v*` tag, constants by a `constants-v*` tag. Today
the deploy job runs only on master (`pages.yml:46`); #1564 adds the feed triggers.

### 2. The census never serves feeds

The census server (`sjtrotter/dashbuddy-census`) takes authenticated, private uploads from opted-in phones. The
feeds are anonymous public downloads that every phone fetches with the same bytes. The census stays the rules drafting
workbench (§11 and the ADR-0009 Milestone 2 amendment); it imports the published rules from Pages like any client.
This reverses the 2026-10-04 expectation in #192 that the census would publish the ruleset.

### 3. Signing per feed

All feeds use one scheme: ECDSA P-256 with SHA-256, base64 DER, over the exact UTF-8 bytes of the manifest string.
This is `RulesetVerifier`'s scheme and the output of `matchers/tools/sign-ruleset.sh:33`.

- **Fuel** is signed in CI by the fuel publisher, with a key held as a secret of the `feed-fuel` environment. That
  environment is deployable only from master and holds only the EIA key and the fuel signing key. The client binds
  every verified file to its feed, so the fuel key cannot sign rules or constants.
- **Rules and constants** are signed by a Google Cloud KMS P-256 key that cannot be exported, through a dedicated
  workload identity pool (§4). The pull request carries the unsigned manifest, `FEED_SEQUENCE` and `FEED_DIGEST`; the
  tag job signs exactly those bytes and logs which key signed. No signing secret is stored in GitHub.

Each `(source, feed)` has one trusted epoch at a time: a primary key and a backup key, each named by a key id (the
first 16 hex characters of sha256 over the DER public key). New trust for gig.log's feeds comes only from an app
release; a manifest never names its successor key. The fuel feed's backup key is kept offline in an encrypted
backup.

### 4. Keyless CI identity (WIF)

Rules and constants signing uses a workload identity pool that exists only for feeds. Its provider admits a token
only when all of these hold: the repository matches by `repository_id` and `repository_owner_id` (not by name),
the ref is `refs/tags/rules-v*` or `refs/tags/constants-v*`, and `assertion.environment == 'feed-rules'`. The KMS
service account is bound only to that pool and holds `signerVerifier` on the one key and nothing else. #641's
acceptance includes a rejected-identity test: a job on the wrong ref or in the wrong environment gets no credential.

The Play pipeline's provider is a separate one. It admits only the `release` environment on a `v*` tag and the
`play-listing` environment on master (`docs/RELEASE.md:105-109`). A feed job or a Pages deploy matches neither, so it
cannot reach the Play service account, and a release job cannot reach the KMS key.

### 5. Tags, the environment and the ancestor check

A tag ruleset restricts creating, updating and deleting `rules-v*` and `constants-v*` tags. Since 2026-11-14 (#1646)
the repository's tag ruleset covers every tag, with a bypass for the admin role. The `feed-rules` environment
requires the developer's approval (own approval allowed), and its deployment policy admits only the two tag
patterns, so a run on a branch ref, a branch-ref `workflow_dispatch` included, cannot request it. A dispatch that
targets an allowed tag still can; the approval is the control for that run. The `github-pages` environment's policy admits
master plus the same two tag patterns; today it admits master only. The `feed-rules` environment, the two tag
patterns on `github-pages` and the feeds pool do not exist yet (#1562).

A tag can point at any commit, and the master ruleset does not require branches to be up to date, so two rule PRs
can merge stale. The tag job therefore re-checks the tagged commit before it signs: the commit is an ancestor of
master, the digest recomputed from the generated assets equals the committed `FEED_DIGEST`, and the sequence exceeds
the live one. A sequence at or below the live one with a different digest, or any sequence below it, is refused.
On any refusal the repair is a new pull request, never a re-signed tag. `pr-check` owns the `FEED_SEQUENCE` and
`FEED_DIGEST` rule on pull requests (#641).

### 6. The reserved advisory slot

The rules manifest reserves the top-level key `advisory` for a later signed rules advisory (#1569): a bounded record
that tells phones a known platform change broke recognition before a fix ships. No feed may use the key for
anything else. Readers of format version 1 ignore unknown top-level keys, so the advisory needs no format change.
When it lands it travels through the same tag and KMS path as the rules and adds no network request of its own.
The per-rule canary share and the `shadow` mark of ADR-0011 §18 also ride the signed rules manifest (#1630, #1631).

### 7. Self-hosting

- **Fuel:** fork the repository, register for an EIA API key, generate a signing key, create the `feed-fuel`
  environment and paste the fork's feed URL and public key into the app as a custom fuel source (#1564, #1567). A
  custom source's id is `custom:<key id>`, which can never equal `gig.log`.
- **Rules:** fork, own signing key, own build. The app accepts rules only from gig.log in v1 and never files a user
  pin under `gig.log` or for the rules feed, so a fork publishes rules for an app it builds with its own pins. Custom
  rules sources in the shipped app come later (#1570).
- **Census:** the census repository's own `docs/SELF-HOSTING.md`.

Each of the three has a clean-fork walkthrough in its issue's acceptance.

### 8. One pin file

The pinned public keys for every gig.log feed live in one file. The app's `FeedTrust` reads it, and every workflow
that verifies a signature (the assembly's carry-over check, the verify-after-deploy step, the tag job) reads the same
file. No second copy of a key exists.

### 9. One verifier in `feed-contract/`

The feed format, the bounded reader, `FeedTrust`, `VerifiedFeedFile` and the acceptance function live in a small
Apache-2.0 included build, `feed-contract/`. `:domain` depends on it (`api`), and the census repository includes the
same build, the pattern of `census-contract/`. The app and the census check signatures with the same code (#1563).
This supersedes design-r3's placement in `:domain` `domain.feed`.

### 10. Security statements

**X1, agent credentials.** Agent sessions (Claude Code and codex) authenticate with a fine-grained token that has no
Administration, Environments or Deployments write, so no agent can approve a `feed-rules` run, edit its reviewers
or change a ruleset. The developer's admin login is used only in the browser and on the phone. The `feed-rules`
deployment policy admits only the two tag patterns, so a branch run cannot request it. This is the required posture
and it is not in place: on 2026-11-14 agent sessions still use the developer's admin `gh` token, and one used it
that day to set the repository's rulesets. The fine-grained token is developer setup (#1562), and the decision is
due before #641. #641's spec verifies how ruleset bypass applies to a fine-grained token of an admin user; the
environment approval is the control either way. A PreToolUse hook that stops Claude Code sessions from pushing the
two tag patterns (`git push`, `gh release create`, `gh api .../git/refs`) or calling the `pending_deployments` API is
a convenience: it covers Claude Code only, not a codex builder or a shell script. The tag ruleset and the
`feed-rules` approval are the controls.

**X2, the hash side.** The rules batch loader (#1568) checks that every customer field (`CUSTOMER_FIELDS_BY_SHAPE`)
parsed by a remote rule goes through `sha256` and that the rule declares a non-empty `redact`. That stops a customer
field from being parsed unhashed. It cannot stop a signed hostile rule from routing a customer's text into a field
stored as-is, such as `storeName`, because the rule chooses what each field means. Against a hostile signed
generation, customer privacy rests on the signing controls (X1, §4, §5) and on review, not on load-time gates.

**X4, the backup key.** The backup key of each KMS-signed feed's epoch (rules, constants) is generated on a hardware
token and cannot be exported. The tag job also accepts `feeds-src/<feed>/envelope.json` committed in the tagged
commit, signed offline with the backup key over the committed sequence and verified against the pinned backup key
and the recomputed manifest bytes, so losing Google Cloud needs no workflow edit. Compromise of the backup key means an
epoch rotation, shipped in an app release. The tag job logs which key signed.

**X5, the catch-all rules.** "A remote rule set can add blocks but never remove or pre-empt one" holds for the
protected group, `sensitive.known` (priority 0, `overrideable: false`,
`matchers/rules/doordash/sensitive.json5:6-8`, `matchers/rules/uber.json5:8-10`). The `sensitive.catchall` rules are
overrideable at priority 999 and 998 by design (`matchers/rules/doordash/sensitive.json5:386-394`,
`matchers/rules/uber.json5:125-132`), so any more specific rule, bundled or remote, can pre-empt them. The batch
loader protects from removal every bundled rule whose compiled branches all have the sensitive shape, the catchall
rules included (#1568), but protection from removal is not protection from pre-emption. Review is the control.

**X7, no human signer.** Only the WIF service account holds `signerVerifier` on the KMS key. No human principal does,
so a laptop `gcloud` login cannot sign. The developer's hardware backup key (X4) is the one signer outside the tag
job, and it is for loss of Google Cloud only.

### 11. Rules authoring stays in the repository

The census drafts a rule fix, the draft reaches `matchers/` as a pull request, app CI gates every rule, and a
`rules-v*` tag publishes it (ADR-0009's 2026-09-30 amendment and its Milestone 2 amendment of 2026-11-14). A
rule-only, corpus-neutral fix made from the phone runs PR CI and one Sol round before the tag (#1559, decision 4).

## Consequences

### Positive

- One format, one verifier, one pin file and one deploy path for three feeds; constants and rules share the signing
  path.
- The EIA key leaves the APK (#1566), and a fuel request no longer names the dasher's region.
- No signing key is stored in GitHub for rules or constants, and no person or agent can sign with the KMS key.
- A rule fix reaches phones without a Play release, behind the same CI gates and a human approval.
- The privacy site and the feeds share one domain and one deploy, so they cannot overwrite each other.

### Negative

- Hosting availability is shared across feeds and the privacy page: a Pages outage stops all of them. Integrity is
  per feed.
- A cancelled tag deploy (all feed deploys share one concurrency group) needs a manual re-run of the tag job.
- Workflow artifacts on a public repository last at most 90 days, so a site idle longer bootstraps from empty and
  each feed must be republished.
- The developer pays for the Google Cloud project (dedicated card, budget alert).

## Residuals

- **Admin bypass.** The developer's admin token can bypass both the tag ruleset and the `feed-rules` approval. This
  is accepted only with X1 in place; until then an agent session holding that token could tag and approve a
  signing run.
- **Stolen fuel key.** It can sign a plausible wrong price, for fuel only, until the key is rotated out of trust
  (a new epoch in an app release); each price it signs stays in use as last-good, and the 21-day limit is only when
  the app labels that price old, never when it stops using it. With a lasting network position it can keep the
  high-water a day ahead and starve real updates. Records written in that time keep the price, since economics are
  frozen per record.
- **Old installs.** After a key compromise, installs that have not updated trust the old epoch; clock admission,
  freshness and plausibility bound what it can do.
- **Network metadata.** GitHub and its CDN (Fastly) see each phone's IP address and user agent, and the rules
  foreground check tells them when the phone opens a delivery app. PRIVACY.md's network row states it when #640
  lands.
- **Freshness trusts the wall clock.** A phone set far ahead shows every feed as old. Admission uses
  `max(wall clock, BuildConfig.BUILD_TIME_MS)`, so a manifest issued more than a day after that time is refused with
  `ClockBehind`: a phone set back is held to its build time, not refused outright.
- **Hostile signed rules.** X2 and X5 bound what load gates can stop; beyond them the controls are signing and review.
