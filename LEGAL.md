# LEGAL.md

> **Status:** Working draft, maintainer-authored. **Not legal advice.** This document
> records the project's good-faith reading of the DoorDash Independent Contractor
> Agreement (ICA) and the operating discipline gig.log applies to stay within it.
> Pending review by qualified counsel before public launch.
>
> **Document version:** v1.1
> **Last updated:** 2026-11-13
> **Attorney review:** _pending_

## Purpose

gig.log is built by a working DoorDash contractor for working DoorDash contractors.
The maintainer is themself bound by the ICA and intends to remain in compliance with
it. This document exists so that:

1. Contributors can read the same interpretation of the ICA the maintainer is operating
   under, and design features that stay inside it.
2. Drivers installing the app can see the legal posture of what they are running.
3. Outside reviewers (counsel, journalists, researchers, regulators) can audit the
   project's legal reasoning.

The ICA is a contract between an individual driver and DoorDash. gig.log itself is
not a party to that contract; each driver who runs the app is. The discipline below is
written so that a driver who runs gig.log as designed remains in compliance with
their own ICA.

## Clauses That Affirmatively Support gig.log

### §2.3 — Driver Independence

> "DoorDash has no right to, and shall not, control, direct, or manage the manner,
> method, or means Contractor uses to perform the Contracted Services …"

The contractor is entitled to choose their own tools, methods, and aids in performing
deliveries. Choosing to use a heads-up display that annotates information already
visible on the contractor's own device is exactly the kind of tool selection §2.3
reserves to the contractor. The platform cannot prescribe the contractor's
decision-support workflow.

### §3.4 — Right to Cancel / Decline

> "Contractor has the right to cancel a Contracted Service when, in the exercise of
> Contractor's reasonable discretion and business judgment …"

The contractor's acceptance and cancellation decisions are the contractor's own.
gig.log surfaces information (True Net Profitability, deadhead distance, expected
duration) that supports the exercise of that judgment. It does not substitute the
platform's judgment, and it does not remove the contractor's discretion — every
acceptance/decline action remains a contractor action.

## Clauses That Constrain gig.log

### §15.1 — Platform Modification

> "Contractor agrees not to modify, rent, lease, loan, sell, distribute, or create
> derivative works based on the DoorDash Platform …"

gig.log does not modify the DoorDash app. It does not patch the binary, inject code
into the DoorDash process, alter network traffic, replace screens, or override platform
behavior. It runs as a separate Android application using Android's documented
AccessibilityService API to read the contents of the contractor's own screen — the
same API screen-readers, password managers, and accessibility utilities use.

gig.log is not a derivative work of the DoorDash Platform. It contains no DoorDash
code, no DoorDash assets, no decompiled DoorDash material, and no protocol
implementations derived from DoorDash internals. Its recognition layer matches against
on-screen text rendered by the platform, in the same way a sighted contractor reads
their own screen.

### §15.4 — No Reverse Engineering

> "[Contractor agrees not to] reverse engineer, disassemble, decompile, or otherwise
> attempt to derive the source code or the underlying ideas, algorithms, structure,
> or organization of the DoorDash Platform."

This is the clause gig.log treats with the most care. The discipline is:

- gig.log does not decompile, disassemble, or static-analyze the DoorDash app
  binary.
- gig.log does not intercept, replay, or characterize DoorDash network traffic.
- gig.log does not attempt to recover, infer, or model the routing algorithm, the
  pricing algorithm, the dispatch algorithm, or any other internal platform system.
- gig.log does not attempt to characterize the "underlying ideas, algorithms,
  structure, or organization" of the platform.

The academic federation pillar (§7 of the technical architecture document; RFC #194)
is scoped as **empirical measurement of the visible offer surface as drivers
experience it**. It studies the descriptive distribution of offer characteristics that
are already displayed to drivers. It does not attempt to infer how those offers are
generated. Any contributor proposing analytical work that crosses from "what does the
visible offer market look like" into "how does the platform produce these offers"
must flag it for review against §15.4 before merging.

Public-facing copy (issues, RFCs, README, grant material, marketing) must avoid
language that suggests reverse engineering — including but not limited to:

- "reverse engineer the routing algorithm"
- "recover the pricing model"
- "infer the dispatch logic"
- "characterize the platform's algorithm"

Acceptable alternatives include "empirical measurement of the offer surface,"
"observational study of offer characteristics," "statistical analysis of observable
offer fields."

### §7.7 — Anti-Manipulation

> Manipulation/abuse provisions limited to (a) location tampering, (b) collection of
> ineligible promotions, (c) operating multiple accounts.

gig.log does none of these. It does not spoof location. It does not manipulate
promotion eligibility. It does not enable multi-accounting. It is, with respect to
§7.7, a compliant tool.

### §13.1 — Mandatory Arbitration

> Disputes between contractor and DoorDash are subject to individual arbitration.

Practical implication: enforcement risk for an individual driver running gig.log is
far more likely to be **deactivation** than litigation. The class-action shield works
in both directions; DoorDash is unlikely to litigate, but a driver who is deactivated
generally has limited recourse beyond arbitration. gig.log contributors should
weigh that asymmetry when designing features and writing public copy.

### §17.1 — Termination

> DoorDash may terminate the contractor relationship at the platform's discretion.

The realistic worst case for an individual contractor running gig.log is platform
deactivation. The application's design — on-device computation, no platform
interaction, no traffic interception, no automated platform actions without explicit
per-action driver consent — is intended to minimize the deactivation surface. It does
not eliminate it. Drivers should understand that DoorDash retains discretion to
terminate the relationship.

## What gig.log Does

- Reads the contractor's own device screen via Android's AccessibilityService API.
- Recognizes which platform screen is displayed via a chain of `ScreenMatcher`
  implementations.
- Computes True Net Profitability (and other decision-support metrics) on-device using
  the contractor's own cost inputs.
- Renders a heads-up bubble overlay with those metrics.
- Optionally records per-session and per-delivery data on the contractor's own device.
- Optionally (opt-in, k=10, DP-budgeted, PII-scrubbed) contributes anonymized
  observations to an academic federation.
- Optionally (opt-in, off by default) sends the layouts of screens it did not recognize
  to the project's census server, so the recognition rules can be kept working. See
  "The opt-in census" below.

## What gig.log Does Not Do

- Does not modify, patch, or interact with the DoorDash app process.
- Does not decompile, disassemble, or analyze the DoorDash binary.
- Does not intercept, replay, or characterize DoorDash network traffic.
- Does not script the DoorDash app, automate taps inside it, or auto-accept/decline
  offers without explicit per-action consent from a fully-informed driver who has
  configured their own decision rules.
- Does not infer, recover, or model the platform's routing, pricing, or dispatch
  systems.
- Does not spoof location, manipulate promotion eligibility, or enable
  multi-accounting.
- Does not store a driver's work records (shifts, deliveries, offers, pay, costs,
  locations) on infrastructure the project controls. They live on the driver's own
  device. The one exception to "nothing on project infrastructure" is the opt-in census
  below, which keeps recognition data per install, under a pseudonym, for a limited time.
- Does not send screens as the driver sees them, or accessibility events, off the
  device. The maintainer's own test phone is the one exception, described below.
  Sensitive screens (banking, identity, payment) are blocked at the matcher layer and are
  not described to the census either.

## The opt-in census

gig.log recognizes screens with rules written from the screens the maintainer and the
testers see. DoorDash changes its screens in some areas before others, so a rule can stop
working for drivers elsewhere while nobody on the project sees the new screen. The census
exists to keep recognition working on the screens drivers see.

A driver who opts in sends the layout of screens gig.log did not recognize: the kinds of
views on the screen, the app's own view names, how they nest, a few flags such as
"clickable", and each piece of text replaced by a one-way hash. A hash is a fingerprint:
the server cannot read the words back from it, but it can recognize a common word by
hashing a guess and comparing, which is how the app's own labels are matched. The phone
withholds the names, addresses and codes it detects before anything is hashed, and today
it withholds amounts and times too. A planned change (#1619) sends those as their format
instead (`$##.##` for an amount, never the amount). The census never sends a screen as the driver sees it. It also sends a daily
recognition health report: counts of recognized and unrecognized screens, per rule and
per delivery-app version. A planned contributor tier lets a driver confirm that a word on
a screen is the app's own wording (a button label, a heading), never a customer's
details; that word is sent only after other drivers have confirmed it too.

Uploads are off by default and can be stopped at any time. Today only the maintainer's
test build sends census data. On the maintainer's own phone that build can also send its
own screen captures, with the detected customer details masked, so a new screen can be
debugged; the server accepts these from no other install, and stops accepting them once
the first rule drafted from Play and sideload data ships (#1633). The Play and sideload
builds gain the same opt-in for layouts only, together with a button that deletes what the
server holds about the install (#1617). What each build sends, how long
the server keeps it, and who can see it are stated in `PRIVACY.md` and in ADR-0011
(`docs/adr/ADR-0011-unknown-census-privacy-model.md`, §12 to §21).

The census stays inside these limits:

- Recognition only. Its data is used to write, test and repair recognition rules and
  their privacy masks, and for nothing else.
- No rollout mapping. It collects no location or region. The project does not use it to
  map where, when or to whom DoorDash shows a screen, or to study DoorDash's tests and
  rollouts. "Installs on DoorDash app version V that saw screen layout C" is as far as it
  goes.
- Nothing from feature-testing sessions. Information a driver learns in a feature-testing
  session DoorDash invites them to is protected by ICA §2.14. The census is not a channel
  for it, and the project does not ask for it or use it.
- No binary or network analysis. The census reads only what the accessibility service
  already reads on the driver's own screen. The §15.4 discipline above applies to it in
  full.

The ICA is between each driver and DoorDash. A driver who contributes to the census makes
that choice under their own agreement, and the Play and sideload opt-in screen will say so
(#1621).
Counsel has not reviewed the census (#246); the open questions are listed below, and this
section may change after that review.

## Framing Discipline (Cross-Reference)

See `CLAUDE.md` § "Project Vision & Strategic Pillars" for the framing discipline that
contributors apply to public-facing copy. Short version: describe the academic pillar
as empirical measurement of the visible offer surface; never as reverse engineering,
model recovery, or algorithm characterization.

## Open Questions for Counsel

The following are flagged for attorney review before public launch:

1. Is the project's reading of §15.1 ("not a derivative work") defensible given that
   the matcher repository encodes structural knowledge of platform UI?
2. Does the federated aggregation pillar — even with the §15.4 discipline above — cross
   into territory the platform could plausibly characterize as derivation of "structure
   or organization"?
3. What licensing posture (Source First vs. AGPL-3.0 vs. PolyForm Shield) best protects
   the project against hostile commercial republication while remaining compatible with
   the FUTO grant terms and the academic federation pillar?
4. Are there individual-state statutes (CA AB5, MA Prop 22, NY app-driver bills) that
   change the analysis above for drivers in those jurisdictions?
5. What disclosure/consent language is required in the in-app onboarding flow to make
   the §15.4 discipline above legally meaningful from the driver's perspective?
6. The opt-in census (#246, #1617): are DoorDash's on-screen labels and view names
   confidential under any agreement a driver accepts; does collecting screen layouts from
   many drivers count as deriving "structure or organization" under §15.4; does a driver
   who sends census data or confirms a label risk breaching their own ICA; can synthetic
   test screens built from census layouts be published; may an AI agent draft rules from
   census data; and how should a driver keep a feature-testing session (§2.14) out of the
   census?

## Version History

- **v1.0 (2026-04-26):** Initial draft. Maintainer-authored. Pending counsel review.
- **v1.1 (2026-11-13):** The opt-in census (#1618): the two "Does Not Do" lines about
  screens and project infrastructure rewritten, a census section with its limits, and
  counsel question 6. Pending counsel review.
