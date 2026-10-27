## 2026-06-14 — DoorDash session (live dash #2 — Go Puff QR pickup, post-#495 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `9240d54` (post-#495 merge; field build on the
  `claude/gopuff-qr-pickup-recognition-2vb5zu` branch, which is even with master — no code
  changes of its own yet).
- **Field conditions:** new live dash narrated in real time while driving. First order is a
  **Go Puff** pickup — a "special" pickup type where the dasher must **scan a QR code at the
  store** to pick the order up. Recorded for triage — **hypotheses, not concluded fixes.** The
  dasher expects **several new Go-Puff-specific screens** that will each need recognition and will
  feed captures separately.

### Bugs

#### 1. Go Puff QR pickup — **post-arrival screen(s) not recognized** (UNKNOWN)
On a Go Puff pickup the **post-arrival** step fell to UNKNOWN. Go Puff differs from a normal store
pickup: instead of (or in addition to) the usual "Pickup from / Confirm pickup" flow, the dasher
arrives and has to **scan a QR code** to claim the order, which appears to introduce one or more
Go-Puff-specific screens between arrival and pickup-complete that the DoorDash ruleset doesn't
cover yet.

- **What's already covered (desk, `core/pipeline/src/main/assets/rules/doordash.json`):** there is
  a `doordash.screen.pickup_qr_confirm` rule (priority 53) keyed on `"Confirm that the code was
  scanned"` + `"Scan code again"` — i.e. the **post-scan confirmation** screen. The standard
  `pickup_arrival` / `pickup_pre_arrival` / `pickup_navigation` screens also exist. So the gap is
  the **Go-Puff arrival / QR-prompt surface(s)** that sit *before* that confirm screen (the screen
  that actually tells the dasher to scan, and possibly a Go-Puff-branded arrival card), which match
  none of the current `require` predicates and so classify UNKNOWN.
- **Likely cause (hypothesis):** the Go Puff arrival/scan-prompt screens carry text/viewIds that
  none of the existing pickup rules' predicates match (the existing pickup rules key on
  "Pickup from"/"Pickup for"; the only scan rule keys on the *confirm* copy). Without a Go-Puff
  arrival/QR-prompt rule, the post-arrival frame has no match → UNKNOWN → captured to disk for
  triage, never stepped into the flow, so the pickup task likely doesn't advance on the bubble for
  this order.
- **To confirm (desk, after capture download):** pull this dash's `UNKNOWN/` captures for the Go
  Puff order and read the X-Ray for the arrival + scan-prompt screens; enumerate the full set of
  Go-Puff-specific screens (arrival card, "scan QR" prompt, the in-app QR/scanner surface itself,
  any "code scanned / confirm" and error states), then decide which are **recognize-only flow
  steps** vs. **document-capture surfaces**. Note for the privacy posture: a **QR/barcode scanner
  camera surface** is plausibly an image-capture surface, but a QR for *order pickup* is not a
  government ID / signature — so unlike the alcohol license-scan, the Go Puff scan prompt is most
  likely a **recognize-only** pickup step, not a blocked sensitive surface. Confirm against the
  actual captured tree before writing rules. Desk call — not a concluded fix.
- **Captures needed:** the dasher will supply the Go-Puff arrival + QR-scan + post-scan screens
  (drop into `snapshots/INBOX/`, run `InboxProcessorTest` for the X-Ray) so the new rules can be
  written against real trees.
- **Triaged 2026-06-15 → [#501](https://github.com/sjtrotter/DashBuddy/issues/501)** (recognize the
  Go Puff / DoorDash-Drive flow; deep-dive over the 06-14 captures/db/log was run — db aggregate
  `session-doordash-1781450940064-21` confirms NO clean `PICKUP_ARRIVED` on the warehouse pickup).
  Recognize-only, not a sensitive surface.

#### 2. Go Puff stacked order — **2-dropoff order logged FOUR drop-offs (doubled)**
The Go Puff order was a **stacked order with two orders / two drop-offs** (not three — the earlier
"three" note was the in-the-moment count, corrected here). The app **logged a total of four
drop-offs** for it — i.e. **each real dropoff was logged twice (2 → 4 doubling)**. That's the
"weird drop-off situation." The dasher's hypothesis: it's likely **specific to the Go Puff order
type** — a **later, ordinary (non-Go-Puff) stacked order on the same dash is so far working fine on
the pickup**, which points to the Go Puff flow (unrecognized QR arrival, #1) as the trigger rather
than stacked-handling in general.

- **Why it matters / hypothesis:** a 2→4 doubling is the **partial-render / unsettled-frame class**
  again (cf. 2026-06-13 #1 premature drop-off card, recurred 2026-06-14 dash #1; #458 frozen-twin;
  #470/#458 double-dropoff). The new wrinkle: it fired on a **Go Puff** order whose **arrival was
  UNKNOWN (#1)**. One possibility is that the unrecognized Go Puff QR/arrival sequence churned the
  flow (UNKNOWN frames between arrival and pickup) such that each dropoff committed twice — i.e. #1
  and #2 may be the **same root** (an unsettled Go-Puff pickup destabilizing the downstream dropoff
  commits), not two independent bugs. Would need the capture replay to confirm.
- **To confirm (desk, after capture download):** replay this session's `captures/` + `app_events`
  for the Go Puff order and count dropoff commit/log events vs. the two real stops — find where the
  extra two come from (re-observation of a dropoff frame? a grace commit firing twice? a task split
  spawning a phantom?). Compare against the later non-Go-Puff stacked order on the same dash (which
  the dasher says is behaving) to isolate whether the Go Puff path is the differentiator. Desk
  call — not a concluded fix.
- **Triaged 2026-06-15 → [#501](https://github.com/sjtrotter/DashBuddy/issues/501) +
  [#503](https://github.com/sjtrotter/DashBuddy/issues/503).** db confirms this is the unrecognized
  Go Puff warehouse pickup + a **multi-drop batch** (one offer → 4 drop subtasks with phantom same-ms
  `DELIVERY_NAV/ARRIVED`, seq 82–84). Recognizing the warehouse-pickup phase (#501) plus the Job
  container owning the drop subtasks (#503) is what removes the doubling — not a generic settle gate.
Strong case study for "what goes right vs. wrong" on stacked-shop + add-on. The order was a double
stack: **Sprouts + PetSmart/Petco**. Sequence the dasher narrated:
1. The app wanted **PetSmart first**, but the dasher would pass **Sprouts** on the way, so they used
   the **timeline screen to manually switch task order** and go to Sprouts first. *(Good: the
   `doordash.screen.timeline` screen — `TimelineFields` task chain, `ParsedFields.kt:225` — is
   recognized, so the reorder surface is at least seen.)*
2. While **shopping at Sprouts**, an **add-on offer** popped. The dasher accepted it.
3. **Bug:** because an offer came in, it **shouldn't have changed the task — but it effectively
   "re-minted" the task** (treated it as a new/restarted task) instead of folding the add-on into
   the active Sprouts shop.

**What it SHOULD have done (dasher):** for a **same-store** add-on it should *not* re-mint — it
should **bump the combined shop counts on the same task**, **add time to the pickup deadline**, and
**update the pickup line** (same store). 

- **Desk context — there's already a test asserting exactly the desired behavior:**
  `TaskLifecycleGuardTest."shopping add-on (or same-store stack) bumps the combined counts on the
  same task"` (`core/state/.../TaskLifecycleGuardTest.kt:327`) asserts `taskId` is **not re-minted**
  and the to-shop count grows. Job-model intent agrees: add-ons **append** to the active job, not
  replace it (`domain/.../state/Job.kt:7,31`; `TransitionDefaults.kt:18`). So the field behavior
  **diverges from the modeled/asserted behavior** — meaning the real path isn't the one the test
  exercises.
- **Likely cause (hypothesis):** the test models the add-on as a same-store `TaskPickupArrived`
  observation bumping counts. The **real** path went through the **offer→accept (new Job) flow**,
  and — per the dasher — the UI may have shown a **pickup-navigation frame first, then realized it
  was already at the store and swapped into the combined/"multi-pickup store" shop interface**. That
  navigation→store re-entry on a freshly-accepted offer is a plausible trigger for the stepper to
  start a fresh task (re-mint) rather than recognizing it as the same-store continuation the guard
  expects. So the guard may simply **not cover the accept-then-renavigate path**.
- **Open question — is the "multi-pickup store interface" its own recognized screen?** Grep shows
  pickup rules (`pickup_arrival`/`pickup_pre_arrival`/`pickup_navigation`) but no explicit combined
  **multi-pickup / two-pickup store** shop screen. If that interface is a distinct tree, it may need
  its own recognition so the add-on/same-store-stack reads as a continuation, not a new arrival.
- **Field UX context — same-store heuristic:** a pop-up add-on *while on another offer* **can** route
  to a different store, but **shopping add-ons frequently route to the same store**. A useful signal
  the dasher noted: **absence of an interstitial pickup arrival/navigation screen** ⇒ likely the
  **same store** (so fold in, don't re-mint). Not deterministic, but a candidate disambiguator.
- **To confirm (desk, after capture download):** replay the Sprouts add-on moment — the
  `offer_popup` + accept event, then the frame sequence (navigation? → combined shop interface?),
  and watch whether `activeTask.taskId` changes (re-mint) or counts bump on the same task. Capture
  the multi-pickup shop tree for a possible new rule. Desk call — not a concluded fix.
- **Triaged 2026-06-15 → [#499](https://github.com/sjtrotter/DashBuddy/issues/499) (blocked-by
  [#503](https://github.com/sjtrotter/DashBuddy/issues/503)).** Direction: don't tune the 10s
  `TASK_RETIRE` grace — re-model the **Job as the offer-accumulating container** so an add-on offer
  accumulates into the active job and the returning shop screen **re-matches the existing subtask via
  the offer's store hint** (the same-store correlation the dasher does by eye). Re-mint then can't
  happen by construction.
