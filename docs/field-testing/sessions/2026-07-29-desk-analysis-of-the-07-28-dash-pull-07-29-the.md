## 2026-07-29 — DESK ANALYSIS of the 07-28 dash (pull 07-29): the mobile bug report ROOT-CAUSED — a P0 silent effect-engine death, not a reinstall bug

**Pull:** `~/dashbuddy/logs/2026/07/29/` (586 captures, 5 logs, db v?, logcat_crash, install_times). Device
purged post-pull; PII files kept for #910 fixtures pending the redacts. **Verdict: the mobile report below
(kept verbatim for the reasoning trail) was right in FEELING and wrong in every specific hypothesis — every
one of H1–H5 + #8 was REFUTED.** Root cause (PROVEN, device stack trace in shareable.log):

**A single unescaped `}` in a regex (`EvidenceFilename.kt:27`, `\{\w*}`, from #859) is valid on the host JVM
and REJECTED by Android's ICU engine.** The class-init failure came back as `ExceptionInInitializerError` — an
`Error`, not an `Exception` — so it bypassed the effect engine's `catch (e: Exception)` and killed the single
serialized queue-drain worker. `process()` kept queuing into an unlimited channel with no consumer: **every
side effect, including `AppEffect.LogEvent` (the ONLY writer of `app_events`), was silently swallowed for the
rest of each process lifetime.** It arms on the first evidence-enabled screenshot per process (gated behind
`EvidenceConfig`), so boot looks healthy then the engine dies mid-dash. Fired twice on 07-28 (14:56, 17:17).
The bubble froze / TTS stopped / odometer never got its Stop → the dev's "the app crashed" (Bug #2) was the
engine dying while the PROCESS lived on, which is exactly why nothing recovered.

**Damage: $89.54 earned across SIX dashes, $7.44 recorded (one delivery) — 91.7% / $82.10 lost.** Only the
48-minute window between the two deaths (bracketing the 16:29 in-place UPDATE, which accidentally HELPED by
restarting the process) recorded anything. session_records: 2 rows for 6 dashes, both unclosed; the
`_unknown`-platform session is the projector's documented fail-soft on a DASH_START that was never written
(NOT a new bug). Zero null-session orphans (#8 refuted). Snapshot recovery + the v8→v9 refold both ran
PERFECTLY (H3 refuted — it's a success story). Listener/a11y/ruleset/pipeline all healthy (H1/H2/H4 refuted).

**FIXED same-session: PR #914 (merged, master @ 570bfaca)** — three layers: the escaped brace; the drain
worker hardened to isolate `Throwable` per-effect + a restart-supervisor (#430 precedent); and an
`IcuRegexGuardTest` source-scan that makes the ICU-incompatible-regex class build-red (JVM tests structurally
can't catch it — the CI blind spot that let this ship). Reviewer proved bare-`}` was the UNIQUE silent
divergence in the whole codebase. Fast-follow #913 (wedged-worker shape).

### Issues filed this analysis
- **#909 (P0) FIXED** — the engine death (above).
- **#910 (Pledge)** — FIVE raw-customer-PII sites incl. a full street address: `delivery_summary_collapsed`
  has NO redact block; `"Delivery for"` missing from `CustomerTextMarkers`; bare `customer_name` on the
  multi-order pickup surfaces. NEXT after #909.
- **#911** — 3 of 4 `dash_summary` frames logged `captured=false` (money-bearing; verify vs FrameGate on a
  CLEAN pull — this pull is #909-contaminated).
- **#912** — recognition batch (Dash-Now loading, dropoff steps, 2-Orders sheet, self-unassign confirm).
- **#913** — wedged-worker liveness (fast-follow of #909).

### Wave first-signals the pull COULD see (build boundary 16:29:29; discriminator = ruleset screen count 90→98)
- **#904 projector v8→v9**: ran + completed clean (1261 events → refold in 3.4s, 0 skipped). ✅
- **#905 accept-spinner**: A/B validated across the boundary (UNKNOWN×2 pre → offer_accepting×5 post). ✅
- **#901 redacts**: 279 masks / 16 rule ids; live-confirmed on Deliver-to/Pickup-for/Message-from/nav. ✅
- **#891 banking marker**: zero Transfer/DasherDirect/Balance hits across 586 files. ✅
- **#872/#869/#876 (uber), #896 (3-mode HUD)**: UNEXERCISED (all 07-28 captures doordash; the one-card HUD is
  explained by the effect blackout, not the resolver — Bug #4). ⚪

### Standing note
The lost 07-28 data is NOT recoverable by the fix (events were never written) — but recognition never broke,
so the five lost jobs' store chains / customer hashes / tips / a 2-store stack are in the surviving snapshots
(cv 8903→9467) + shadow-projector logs; `SessionReplay` reconstruction is an option if the dev wants the
evening back. **DO NOT DASH until the device is reinstalled with the #914 build (Evidence capture ON to
validate the fix).**

---

_The original mobile bug report (record-not-fix, logged remotely) is preserved below for the reasoning trail;
its hypotheses are superseded by the analysis above._
