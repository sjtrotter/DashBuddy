# DashBuddy Field Testing Log

Running log of observations made while actively dashing in the field, captured
in real time during sessions. Each session is its own file under `sessions/`,
listed **newest first** in the index at the bottom of this page.

This is a freeform capture log — a mix of bugs, open questions, meta
observations about platform UI/UX, design proposals, and verification TODOs
that were noticed during a session. The intent is to preserve raw context
*before* it gets distilled into focused work items. Items here are not yet
triaged; the developer triages to the project board manually using the
Android Studio plugin or `gh` CLI.

## Format

Each session is its own file, `sessions/YYYY-MM-DD-<slug>.md`, starting with its
`## YYYY-MM-DD (…)` heading. A new session is a new file plus one line at the top
of the `## Sessions` index below (newest first). The slug is a few lowercase words
from the heading; two sessions on one date get different slugs.

Each session entry has:

- **Date** — YYYY-MM-DD
- **Platform(s) tested** — DoorDash, Uber, etc.
- **Branch under test** — the git branch the build came from
- **Field conditions** — anything that affects interpretation (offers
  accepted vs declined, weather, multi-app testing, etc.)
- **Observations** — grouped by kind:
  - **Bugs** — reproducible defects
  - **Field UX context** — what the platform's UI actually looks like in
    the wild; helps explain why a matcher behaves the way it does
  - **Open questions / investigations** — things to look at back at the desk
  - **Meta / architecture** — broader concerns that aren't single-bug shaped
  - **Research / design** — speculative or strategic proposals
  - **Verification TODOs** — items the session itself produced ground-truth
    for, but which need cross-referencing against captured data

Item numbers are **session-local** (reset each session) and intended for
cross-referencing within a single session entry, not across sessions.

Each item also carries a **Status** line — added when the item is logged
and updated as it lands. Three shapes:

- `- **Status:** Open.` — not yet addressed.
- `- **Status:** Shipped in #NNN (YYYY-MM-DD).` — closed by a merged PR.
  Use `#NNN + #MMM` when multiple PRs were needed.
- `- **Status:** Wontfix — <one-sentence reason>.` — investigated, no
  change needed. Inline the reason so the log is self-explanatory.

For items with multiple sub-concerns at different statuses, use one
`Partially closed —` line and describe each sub-concern inline.

---

## Next field test — things to look for

The pre-dash checklist lives in [`CHECKLIST.md`](CHECKLIST.md).
Read §1 must-watch first, then §2 active items.
The ≤ 5 must-watch picks are proposals pending the dev's confirmation.
An item untouched for 6 weeks at `Confirmed: 0/2` moves to `[review]`, never auto-retired.
See §3 for aged 0/2 review proposals and §4 for older 1/2 items.
See §5 for items already recorded at 2/2; closures are the dev's.
Use [`desk-validation-playbook.md`](desk-validation-playbook.md) for post-dash checks.
Dated field reports continue below; checklist policy is in CHECKLIST §0.

## Sessions

- [2026-10-26 — desk analysis of the 10-26 pull: dashes 10-18 evening to 10-25; two accepts recorded as timeouts cost $21.40; two…](sessions/2026-10-26-desk-analysis-of-the-10-26-pull-dashes-10-18.md)
- [2026-10-19 — desk check of the S3a cash tip sheet on the first install with it: the pad does not match panel 11c](sessions/2026-10-19-desk-check-of-the-s3a-cash-tip-sheet-on-the-first.md)
- [2026-10-18 — desk analysis of the 10-18 pull: dashes on 10-14, 10-16, 10-17 and 10-18; money reconciles on 7 of 8 summary dashes; a…](sessions/2026-10-18-desk-analysis-of-the-10-18-pull-dashes-on-10-14-10.md)
- [2026-10-11 — two pulls (morning: 10-09/10-10 dashes; evening: five 10-11 dashes)](sessions/2026-10-11-two-pulls-morning-10-09-10-10-dashes-evening-five.md)
- [2026-10-05 — desk analysis of the 10-05 pull — the 10-01→10-04 dashes across seven builds; money reconciles except one #1120-class…](sessions/2026-10-05-desk-analysis-of-the-10-05-pull-the-10-01-10-04.md)
- [2026-10-03 — dash on `a41f0965` — the #1185 build; automatic census uploads confirmed from the server side; v0.7.0 server + VPN +…](sessions/2026-10-03-dash-on-a41f0965-the-build-automatic-census.md)
- [2026-09-30 — smoke test of `1fe9243f` — the TalkBack batch + census build goes live; an 18-second dash; the "Current dash" sheet is…](sessions/2026-09-30-smoke-test-of-1fe9243f-the-talkback-batch-census.md)
- [2026-09-27 → 2026-09-28 — desk analysis of the 09-29 pull — money to the cent; #1118's third clean run; four more accepts died; a stacked-job…](sessions/2026-09-27-desk-analysis-of-the-09-29-pull-money-to-the-cent.md)
- [2026-09-26 — desk analysis of the 09-27 pull — #1118 retires at 2/2; money to the cent; a decline merged into an ACCEPT; a receipt…](sessions/2026-09-26-desk-analysis-of-the-09-27-pull-retires-at-2-2.md)
- [2026-09-21 → 2026-09-24 — desk analysis of the 09-26 pull — #1118 records every decline and every accept it can see; eight accepts still died…](sessions/2026-09-21-desk-analysis-of-the-09-26-pull-records-every.md)
- [2026-09-20 — desk analysis of the 09-21 pull — the Compose offer card works perfectly and four real accepts still died; a receipt…](sessions/2026-09-20-desk-analysis-of-the-09-21-pull-the-compose-offer.md)
- [2026-09-16 → 2026-09-19 — desk analysis of the 09-20 pull — DoorDash's new Compose offer card blanked two whole days of offers; #1078 caught…](sessions/2026-09-16-desk-analysis-of-the-09-20-pull-doordash-s-new.md)
- [2026-09-13 → 2026-09-15 — desk analysis of the 09-15 pull — #1078 caught a $35.50 drop with 0.4 s to spare; a return order walked off with…](sessions/2026-09-13-desk-analysis-of-the-09-15-pull-caught-a-35-50.md)
- [2026-09-10 → 2026-09-12 — desk analysis of the 09-13 pull — the receipt auto-tap is a coin flip; a stale receipt taught a new dash the old dash's…](sessions/2026-09-10-desk-analysis-of-the-09-13-pull-the-receipt-auto.md)
- [2026-09-08 — desk analysis of the 09-09 pull — first field run of #1093, which works; a dash-end race silently ate a $21 drop](sessions/2026-09-08-desk-analysis-of-the-09-09-pull-first-field-run-of.md)
- [2026-09-08 — dev field report — the post-delivery receipt's auto-expand tap is not firing; dev has to tap it manually](sessions/2026-09-08-dev-field-report-the-post-delivery-receipt-s-auto.md)
- [2026-09-07 — desk analysis of the 09-07 pull — first field run of #1033; #1029's money re-anchoring meets the `799` code and a…](sessions/2026-09-07-desk-analysis-of-the-09-07-pull-first-field-run-of.md)
- [2026-09-05 — desk analysis of the 09-06 pull — first field run of #1029/#1030/#1032/#1036/#1052/#1057/#1066](sessions/2026-09-05-desk-analysis-of-the-09-06-pull-first-field-run-of.md)
- [2026-08-28 → 2026-09-05 — desk analysis of the 09-05 pull](sessions/2026-08-28-desk-analysis-of-the-09-05-pull.md)
- [2026-08-24 — DESK ANALYSIS of the 08-23 field report (pull 08-24): nothing was lost — gross was zeroed by a fake $0 report meeting…](sessions/2026-08-24-desk-analysis-of-the-08-23-field-report-pull-08-24.md)
- [2026-08-23 — the whole week reads as $0 on the Money screen; Dash Along the Way has stopped showing the delivery receipt out of zone](sessions/2026-08-23-the-whole-week-reads-as-0-on-the-money-screen-dash.md)
- [2026-08-09 — DESK ANALYSIS of the 08-01→08-08 field week (pull 08-09): the voice died on 08-06 and nothing said so; four new Pledge…](sessions/2026-08-09-desk-analysis-of-the-08-01-08-08-field-week-pull.md)
- [2026-07-31 — DESK ANALYSIS of the 07-30 evening dash (pull 07-31)](sessions/2026-07-31-desk-analysis-of-the-07-30-evening-dash-pull-07-31.md)
- [2026-07-30 (afternoon) — BROWSE-SESSION pull (pull2): DoorDash's NEW points-based rating system discovered, recognized, and…](sessions/2026-07-30-afternoon-browse-session-pull-pull2-doordash-s-new.md)
- [2026-07-30 — DESK ANALYSIS of the 07-29 evening dash (pull 07-30): first dash on the #914 build — the engine LIVED, money reconciles…](sessions/2026-07-30-desk-analysis-of-the-07-29-evening-dash-pull-07-30.md)
- [2026-07-29 — DESK ANALYSIS of the 07-28 dash (pull 07-29): the mobile bug report ROOT-CAUSED — a P0 silent effect-engine death, not…](sessions/2026-07-29-desk-analysis-of-the-07-28-dash-pull-07-29-the.md)
- [2026-07-29 — bug report: mid-dash app update/reinstall breaks dash tracking (logged remotely via chat; no pull yet)](sessions/2026-07-29-bug-report-mid-dash-app-update-reinstall-breaks.md)
- [2026-07-26 — five-session day, heavy shopping (pull 2026-07-27, desk-analyzed 2026-07-27)](sessions/2026-07-26-five-session-day-heavy-shopping-pull-2026-07-27.md)
- [2026-07-22→24 — DoorDash + Uber multi-app week (pull 2026-07-25, desk-analyzed 2026-07-26)](sessions/2026-07-22-doordash-uber-multi-app-week-pull-2026-07-25-desk.md)
- [2026-07-22 — dash in progress; DoorDash + SECOND Uber attempt (logged live from the field via chat; desk-analyzed 2026-07-22…](sessions/2026-07-22-dash-in-progress-doordash-second-uber-attempt.md)
- [2026-07-19 afternoon/evening — four sessions incl. the FIRST Uber attempt (desk-analyzed 2026-07-20)](sessions/2026-07-19-four-sessions-incl-the-first-uber-attempt-desk.md)
- [2026-07-18 (+ 07-19 morning) — five-session day; second field run of the #767–#791 build (desk-analyzed 2026-07-19)](sessions/2026-07-18-07-19-morning-five-session-day-second-field-run-of.md)
- [2026-07-17 — three-session evening dash; first field run of the #767–#791 build (desk-analyzed 2026-07-18)](sessions/2026-07-17-three-session-evening-dash-first-field-run-of-the.md)
- [2026-07-16 — DoorDash dash (first field run of the 07-12→07-15 merge train; desk synthesis same evening)](sessions/2026-07-16-doordash-dash-first-field-run-of-the-07-12-07-15.md)
- [2026-07-13 — desk analysis of the 07-10 & 07-12 dashes (pulled db/logs/captures; playbook-first)](sessions/2026-07-13-desk-analysis-of-the-07-10-07-12-dashes-pulled-db.md)
- [2026-07-12 — post-install device look (no dash; first hands-on with the post-#763 build)](sessions/2026-07-12-post-install-device-look-no-dash-first-hands-on.md)
- [2026-07-09 — desk analysis of the 07-07 & 07-08 dashes (pulled db/logs/captures; no in-field narration)](sessions/2026-07-09-desk-analysis-of-the-07-07-07-08-dashes-pulled-db.md)
- [2026-07-05 — DoorDash session (afternoon/evening dash; desk synthesis 07-06 from db/logs/captures)](sessions/2026-07-05-doordash-session-afternoon-evening-dash-desk.md)
- [Untriaged — carried over from scratch notes](sessions/untriaged-carried-over-from-scratch-notes.md)
- [2026-06-25 → 06-30 — DoorDash field week (desk synthesis of five dashes)](sessions/2026-06-25-doordash-field-week-desk-synthesis-of-five-dashes.md)
- [2026-06-24 — DoorDash session (live dash, in-field narration)](sessions/2026-06-24-doordash-session-live-dash-in-field-narration.md)
- [2026-06-21 — DoorDash session (live dash, in-field narration)](sessions/2026-06-21-doordash-session-live-dash-in-field-narration.md)
- [2026-06-20 — DoorDash session (evening dash, same-store double stack)](sessions/2026-06-20-doordash-session-evening-dash-same-store-double.md)
- [2026-06-20 — DoorDash session (live dash, in-field narration)](sessions/2026-06-20-doordash-session-live-dash-in-field-narration.md)
- [2026-06-19 — DoorDash session (desk analysis of captured dash data)](sessions/2026-06-19-doordash-session-desk-analysis-of-captured-dash.md)
- [2026-06-17 — DoorDash session (live dash, in-field narration)](sessions/2026-06-17-doordash-session-live-dash-in-field-narration.md)
- [2026-06-14 — DoorDash session (live dash #2 — Go Puff QR pickup, post-#495 build)](sessions/2026-06-14-doordash-session-live-dash-go-puff-qr-pickup-post.md)
- [2026-06-14 — DoorDash session (live dash, post-#494 build)](sessions/2026-06-14-doordash-session-live-dash-post-build.md)
- [2026-06-13 — DoorDash session (live evening dash, post-#487 build)](sessions/2026-06-13-doordash-session-live-evening-dash-post-build.md)
- [2026-06-13 — DoorDash session (desk review of post-#487 build)](sessions/2026-06-13-doordash-session-desk-review-of-post-build.md)
- [2026-06-12 — DoorDash session (offer-copilot field test)](sessions/2026-06-12-doordash-session-offer-copilot-field-test.md)
- [2026-06-09 — DoorDash session (Stage 2 offer-copilot live test)](sessions/2026-06-09-doordash-session-stage-2-offer-copilot-live-test.md)
- [2026-06-07 — DoorDash session (desk review of captured data)](sessions/2026-06-07-doordash-session-desk-review-of-captured-data.md)
- [2026-06-06 — DoorDash session (live capture during dash)](sessions/2026-06-06-doordash-session-live-capture-during-dash.md)
- [2026-06-05 — DoorDash session (Shop & Deliver, items/min off-by-one)](sessions/2026-06-05-doordash-session-shop-deliver-items-min-off-by-one.md)
- [2026-06-03 — DoorDash session (live capture during dash)](sessions/2026-06-03-doordash-session-live-capture-during-dash.md)
- [2026-05-29 — DoorDash session (live capture during dash)](sessions/2026-05-29-doordash-session-live-capture-during-dash.md)
- [2026-05-19 — DoorDash session (live capture during dash)](sessions/2026-05-19-doordash-session-live-capture-during-dash.md)
- [2026-05-17 — DoorDash session (first run on the flow-card bubble)](sessions/2026-05-17-doordash-session-first-run-on-the-flow-card-bubble.md)
- [2026-05-16 — DoorDash session (stacked pickups)](sessions/2026-05-16-doordash-session-stacked-pickups.md)
- [2026-05-16 — DoorDash session](sessions/2026-05-16-doordash-session.md)
- [2026-05-09 — Uber session](sessions/2026-05-09-uber-session.md)
