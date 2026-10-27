## 2026-07-29 — bug report: mid-dash app update/reinstall breaks dash tracking (logged remotely via chat; no pull yet)

**Platform(s) tested:** Not stated (DoorDash assumed — developer to correct).
**Branch under test:** The post-reinstall build — not stated; presumably `master` at/near
`49380e9` (post-#901 merge) or the 07-26 build wave the 07-27 reinstall was staged for.
Developer to correct.
**Field conditions:** The app was updated (reinstalled) **while a dash was in progress**. The
developer reports two distinct symptoms: (a) the mid-update dash's tracking "completely broke",
and (b) **every dash started since the reinstall** has been tracked incompletely / incorrectly —
i.e. the breakage is *persistent across dashes*, not confined to the interrupted one. No data
pull or log export yet; this entry is the raw report plus desk-side code exploration done
remotely (record-not-fix protocol — no code changes made).
**Correction (later same session):** symptom (b) retracted — the tracking break was **only the
first (mid-update) dash**. Subsequent dashes tracked fine; what they showed instead was a HUD
display behavior: **the bubble displayed only one card, whichever was active** (Bug #4 below,
developer-rated low severity — "not a huge deal"). Items drafted before the correction are
annotated inline rather than deleted, so the reasoning trail stays honest.
**Second clarification (same session):** the developer walked the correction back partway —
"I don't know exactly what was going on tbh so I can't claim that they were tracking
everything." Subsequent dashes are **unverified, not confirmed clean**. And a recalled prior
symptom may fit: **"we've seen the symptom before where the events were just not getting a
dash id assigned"** — i.e. the null-`sessionId` attribution class (#655/#660, the
"(No session)" bucket). That recall makes open question #8 the **leading candidate** for
whatever wrongness the subsequent dashes have: events captured intact but not attributed to a
dash. The pull decides.

### Bugs

1. **Dash tracking broken on the dash that was in progress during the app update/reinstall.**
   ~~All subsequent dashes incompletely/incorrectly tracked~~ — **rescoped by the same-session
   correction: the first (mid-update) dash is the confirmed break**; per the second
   clarification, subsequent dashes are **unverified** (not confirmed clean — the developer
   recalls the events-without-dash-id symptom, see open question #8). Exact failure shape for
   the first dash (missing session? missing deliveries? missing miles? wrong attribution?)
   not yet characterized — needs the verification pull below before triage.
   - **Status:** Open.
2. **Suspected app crash mid-dash, around delivering or accepting an offer** (developer:
   "the app like crashed I think as I delivered or accepted an offer" — not certain it was a
   crash vs. a system kill vs. the bubble/HUD just vanishing). Unconfirmed and
   uncharacterized; needs crash evidence from the device (Verification TODO #6). Two readings
   to keep apart at triage: (a) a one-off crash whose recovery machinery then explains that
   dash's gaps, or (b) a *recurring* crash on the accept/delivery path in the new build —
   which, if it fires every dash, would by itself explain Bug #1's "every dash since is
   incompletely tracked" (each crash loses the in-flight frames and any un-persisted effects
   between the last snapshot and the kill). See H5. *(Post-correction: the "recurring every
   dash" reading (b) is moot — subsequent dashes tracked fine — leaving reading (a), a one-off
   death during the first dash.)*
   - **Status:** Open.
4. **HUD showed only ONE card — whichever was active — on the dashes after the reinstall**
   (from the same-session correction; developer-rated **low severity**, "not a huge deal").
   Not yet clear whether this is (a) the *intended* presentation of the new #867
   follow-active session resolver (the HUD deliberately follows the active dash — and with
   one platform running, one session's card set is correct), (b) a card-stack regression
   (`CardStackAssembler` / `FlowCardMapper` rendering a single card where a stack of
   status+offer+chat cards should interleave), or (c) a #867 Dev-settings presentation
   experiment toggle left in a non-default position after the reinstall wiped/reset prefs.
   Needs a dev glance at the Dev settings toggle state plus one field look at whether
   multiple cards return during an offer-over-active-task moment.
   - **Status:** Open.

### Open questions / investigations (hypotheses — none concluded)

Context that matters for all of these: the 07-26 entry above records **"Device purged + cleared
for reinstall post-pull"** (2026-07-27). So the likely shape is a *full data purge + reinstall*,
not an in-place `install -r` update — which hypothesis family applies depends on which it
actually was, and that's Verification TODO #1.

*Post-correction triage note (amended by the second clarification):* H1/H2/H4 were drafted
against the "every dash since" symptom; the correction deflated them, but with subsequent
dashes now **unverified** they're demoted rather than dismissed (kept below for the trail; the
cheap TODO #2 settings sweep still costs nothing to confirm — note the recalled symptom is
*attribution*, events present but dash-id-less, which points **away** from dead-sensor
hypotheses like H1/H4 and toward #8). Leading candidates: **open question #8** (null-session
attribution — the developer has seen this exact symptom before) for the subsequent dashes, and
**H3** (mid-dash state loss/resurrection across the update) + **H5's one-off reading** (the
Bug #2 crash killing that dash's tail) for the first-dash break.

3. **H1 — listener rebind loss after package update/reinstall.** Android's
   `NotificationListenerService` is notorious for silently not rebinding after its package is
   updated/reinstalled until notification access is toggled off/on (or the device reboots);
   accessibility services can behave similarly after a reinstall. If either listener is dead,
   one whole sensor pipeline is missing — which would look exactly like "every dash since is
   partially tracked" and would persist until manually re-toggled. Would need to confirm by
   checking both toggles on-device and looking for a total absence of notification-sourced
   events in the pull.
4. **H2 — incomplete re-setup after the purge.** A purge+reinstall revokes *everything* the app
   was granted: accessibility, notification access, overlay, location (odometer dead → no
   miles), the battery-optimization exemption (process free to be killed mid-dash → per-dash
   gaps), and — by design — all #843 capability consents (all capabilities return to
   *undecided*; the consent prompt must be re-answered; automation staying off is expected
   behavior, not a tracking break, but it changes what "correct" looks like). If any one item
   of the re-setup chain was missed mid-dash, its absence would produce a *persistent* partial
   tracking failure with a per-item signature (no miles vs. no notifications vs. mid-dash
   process deaths).
5. **H3 — resurrected mid-dash snapshot (in-place-update shape only).**
   `SnapshotStore.restoreLatest()` has **no age guard** — it restores the latest decodable
   snapshot regardless of how old it is (`core/state/.../SnapshotStore.kt:70`; pruning runs
   only on the *write* path, `SnapshotStore.kt:56`). If the process died mid-dash with a live
   Online session and data survived, the next launch resurrects that session as live. The
   stale-grace-end + fresh-mint arm (`core/state/.../JobCloseEffects.kt:39`) covers the
   killed-while-offline-grace-pending shape, but if the session restores as plain Online with
   no pending grace, one possibility is that the *next* dash's Online frames read as a
   continuation of the resurrected session (no new `DASH_START`), mis-attributing the new
   dash. On a purged device this hypothesis is moot (no snapshot survives) — hence
   Verification TODO #1 first.
6. **H4 — fail-closed ruleset gate eating all frames.** If the new build's ruleset load fails
   for a platform (compile reject, duplicate-id skip), the #432 gate drops every frame
   (`core/pipeline/.../AccessibilityPipeline.kt:105` — "Dropping … rulesets not loaded") —
   which would look like *total* non-tracking on that platform, every dash, until fixed. The
   shareable.log check below would show this immediately.
7. **H5 — crash on the accept/delivery path (Bug #2).** *(Post-correction: the recurring
   reading is moot; only the one-off reading below stands.)* A single crash during the first
   dash — around an accept landing or a delivery confirm — would explain that dash's lost
   tail, and it exercises exactly the recovery seams the architecture defends (snapshot every
   5 obs + journal tail-replay, recovery-suppressed externals, `effects_fired` dedup), so
   post-crash state may *look* subtly wrong (e.g. dead GPS until the #438 B5 reconcile fires)
   even when recovery "worked". Needs the crash evidence (TODO #6); the logcat stack decides
   where it goes.
8. **Open question — is there a known post-#660 signature for this? (LEADING CANDIDATE per
   the second clarification** — the developer recalls this exact symptom from before: "events
   were just not getting a dash id assigned".) The "(No session)" bucket + orphan-delivery
   machinery (#655/#660) exists precisely because mid-dash restarts produce null-session
   deliveries; if the pull shows the since-reinstall deliveries landing in that bucket, the
   breakage is *attribution*, with the underlying events intact and recoverable via the
   existing `DELIVERY_SESSION_ASSIGN` correction path (Money-tab callout → orphan list →
   session picker). Worth also checking whether a *session* record exists at all for each
   affected dash (no `DASH_START` observed → nothing to attribute to) vs. exists but the
   deliveries missed it — the two shapes have different causes.

### Verification TODOs

1. **Establish the reinstall shape first** — was it the planned purge+reinstall from the 07-27
   note (data wiped) or an in-place update (data preserved)? This decides whether H2 or H3/H4
   is even in play.
2. **On-device settings sweep** (5 toggles): Accessibility service ON; Notification access ON
   (toggle off/on regardless, per H1); overlay permission; Location "Allow all the time";
   battery optimization = Unrestricted. Note which, if any, were found off.
3. **Export shareable.log** (Settings → Data & Privacy → Export Data) and grep for:
   "Dropping … rulesets not loaded" (H4), "Restored from snapshot at cv=" / "Replaying N
   observations after snapshot" / "State recovery failed — starting fresh" (H3), the periodic
   `PipelineStats` lines (which gate is eating frames), and any ERROR lines.
4. **Data pull:** `session_records` since the reinstall (open sessions with no end, session
   count vs. real dash count), `delivery_records` with `sessionId IS NULL` (the "(No session)"
   bucket — open question #8), and the `app_events` sequence around the **first (mid-update)
   dash** specifically (is the event log itself complete and only the fold wrong, or are
   events missing at the source?).
5. **Consent state:** did the #843 consent prompt re-fire post-reinstall, and what was
   answered? (Explains any "automation stopped working" component of "not tracked correctly".)
6. **Crash evidence for Bug #2:** `adb logcat -b crash -d` (or `adb bugreport`) for a DashBuddy
   stack trace near the accept/delivery moment; on-device Settings → Apps → DashBuddy can also
   show a recent-crash marker. Cross-check the DEBUG firehose `app.log` for an abrupt cut and
   the next launch's recovery lines ("Restored from snapshot at cv=" / "Replaying N
   observations"), which timestamp the death precisely. Note whether it was a real crash
   (stack trace) vs. a system/OOM kill (no trace) — the triage differs. If it reproduces,
   note the exact screen it fires on.
7. **Bug #4 (one-card HUD):** check the #867 Dev-settings presentation toggle's state
   post-reinstall; then, on the next dash, note whether the card stack comes back when an
   offer lands over an active task (multiple cards expected) — that one moment distinguishes
   intended follow-active display from a stack-assembly regression.

### Meta

- Logged remotely by the mobile agent under the record-not-fix protocol — desk-side code
  exploration only, no fixes applied, no issue filed (developer triages).
