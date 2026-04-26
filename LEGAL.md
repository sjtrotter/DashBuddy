# LEGAL.md

> **Status:** Working draft, maintainer-authored. **Not legal advice.** This document
> records the project's good-faith reading of the DoorDash Independent Contractor
> Agreement (ICA) and the operating discipline DashBuddy applies to stay within it.
> Pending review by qualified counsel before public launch.
>
> **Document version:** v1.0
> **Last updated:** 2026-04-26
> **Attorney review:** _pending_

## Purpose

DashBuddy is built by a working DoorDash contractor for working DoorDash contractors.
The maintainer is themself bound by the ICA and intends to remain in compliance with
it. This document exists so that:

1. Contributors can read the same interpretation of the ICA the maintainer is operating
   under, and design features that stay inside it.
2. Drivers installing the app can see the legal posture of what they are running.
3. Outside reviewers (counsel, journalists, researchers, regulators) can audit the
   project's legal reasoning.

The ICA is a contract between an individual driver and DoorDash. DashBuddy itself is
not a party to that contract; each driver who runs the app is. The discipline below is
written so that a driver who runs DashBuddy as designed remains in compliance with
their own ICA.

## Clauses That Affirmatively Support DashBuddy

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
DashBuddy surfaces information (True Net Profitability, deadhead distance, expected
duration) that supports the exercise of that judgment. It does not substitute the
platform's judgment, and it does not remove the contractor's discretion — every
acceptance/decline action remains a contractor action.

## Clauses That Constrain DashBuddy

### §15.1 — Platform Modification

> "Contractor agrees not to modify, rent, lease, loan, sell, distribute, or create
> derivative works based on the DoorDash Platform …"

DashBuddy does not modify the DoorDash app. It does not patch the binary, inject code
into the DoorDash process, alter network traffic, replace screens, or override platform
behavior. It runs as a separate Android application using Android's documented
AccessibilityService API to read the contents of the contractor's own screen — the
same API screen-readers, password managers, and accessibility utilities use.

DashBuddy is not a derivative work of the DoorDash Platform. It contains no DoorDash
code, no DoorDash assets, no decompiled DoorDash material, and no protocol
implementations derived from DoorDash internals. Its recognition layer matches against
on-screen text rendered by the platform, in the same way a sighted contractor reads
their own screen.

### §15.4 — No Reverse Engineering

> "[Contractor agrees not to] reverse engineer, disassemble, decompile, or otherwise
> attempt to derive the source code or the underlying ideas, algorithms, structure,
> or organization of the DoorDash Platform."

This is the clause DashBuddy treats with the most care. The discipline is:

- DashBuddy does not decompile, disassemble, or static-analyze the DoorDash app
  binary.
- DashBuddy does not intercept, replay, or characterize DoorDash network traffic.
- DashBuddy does not attempt to recover, infer, or model the routing algorithm, the
  pricing algorithm, the dispatch algorithm, or any other internal platform system.
- DashBuddy does not attempt to characterize the "underlying ideas, algorithms,
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

DashBuddy does none of these. It does not spoof location. It does not manipulate
promotion eligibility. It does not enable multi-accounting. It is, with respect to
§7.7, a compliant tool.

### §13.1 — Mandatory Arbitration

> Disputes between contractor and DoorDash are subject to individual arbitration.

Practical implication: enforcement risk for an individual driver running DashBuddy is
far more likely to be **deactivation** than litigation. The class-action shield works
in both directions; DoorDash is unlikely to litigate, but a driver who is deactivated
generally has limited recourse beyond arbitration. DashBuddy contributors should
weigh that asymmetry when designing features and writing public copy.

### §17.1 — Termination

> DoorDash may terminate the contractor relationship at the platform's discretion.

The realistic worst case for an individual contractor running DashBuddy is platform
deactivation. The application's design — on-device computation, no platform
interaction, no traffic interception, no automated platform actions without explicit
per-action driver consent — is intended to minimize the deactivation surface. It does
not eliminate it. Drivers should understand that DoorDash retains discretion to
terminate the relationship.

## What DashBuddy Does

- Reads the contractor's own device screen via Android's AccessibilityService API.
- Recognizes which platform screen is displayed via a chain of `ScreenMatcher`
  implementations.
- Computes True Net Profitability (and other decision-support metrics) on-device using
  the contractor's own cost inputs.
- Renders a heads-up bubble overlay with those metrics.
- Optionally records per-session and per-delivery data on the contractor's own device.
- Optionally (opt-in, k=10, DP-budgeted, PII-scrubbed) contributes anonymized
  observations to an academic federation.

## What DashBuddy Does Not Do

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
- Does not store driver-level data on infrastructure the project controls. Per-driver
  data lives on the driver's own device.
- Does not transmit screens or accessibility events off-device. Sensitive screens
  (banking, identity, payment) are blocked at the matcher layer.

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

## Version History

- **v1.0 (2026-04-26):** Initial draft. Maintainer-authored. Pending counsel review.
