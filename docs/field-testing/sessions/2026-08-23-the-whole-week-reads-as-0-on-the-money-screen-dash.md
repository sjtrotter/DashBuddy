## 2026-08-23 — the whole week reads as $0 on the Money screen; Dash Along the Way has stopped showing the delivery receipt out of zone

**Date:** 2026-08-23 · **Platform(s) tested:** DoorDash · **Branch under test:** `master` @ `e903a6e`
(post-#1028, the #1024 close-out — inferred from the most recent merge, dev to correct) ·
**Field conditions:** narrated live from the phone immediately after ending a dash, no data pull yet.
At least two dashes in the window (today plus one on Monday 08-17); one of today's ran as **Dash Along
the Way**, out of zone. Captures to follow.

### Bugs

**1. Nothing at all was recorded — the Money screen reports no earnings for the entire week (P0).**
After ending today's dash the dev opened the app and the Money screen said they had not earned
anything this week, despite having also dashed **Monday**. This is the headline: it is not one missing
receipt, it is a whole week absent from the read model.
- **Status:** Open.
- **Desk-side, hypotheses only — the shape of the symptom matters.** "A whole week vanished at once"
  is far more consistent with a **read-model** failure than with a per-dash capture failure, because
  capture failures are per-frame and would not retroactively take Monday with them. Two candidates,
  in order:
  - *(a) A `PROJECTOR_VERSION` wipe whose refold did not complete.* `PROJECTOR_VERSION` is currently
    **10** (`AnalyticsProjector.kt:942`), bumped recently by #1013. On the next start after a version
    change the projector **wipes `delivery_records`/`session_records`/`offer_records`/`stores`/
    `pickup_records` and resets the watermark to 0** (`AnalyticsProjector.kt:609–624`), then refolds
    the whole log. If the dev installed a build carrying that bump and the refold then died or never
    ran to completion, the tables would be **empty while `app_events` is fully intact** — which would
    present exactly as "no earnings this week" with no on-dash symptom whatsoever. This hypothesis is
    attractive precisely because it explains Monday.
  - *(b) The projector never ran / crashed at start.* It is launched from `DashBuddyApplication`
    off-main and supervised; a supervised failure would leave a stale-but-partial read model. Would
    also take the week, but should leave an ERROR line where (a) may not.
  - Both are distinguishable from a genuine capture loss **before** any rule work, and the check is
    cheap — see item 5.
- If the events **are** in `app_events` and only the projection is missing, this is recoverable
  (rebuild ≡ backfill by design) and the bug is in the refold path, not in sensing. If `app_events`
  is *also* empty for the week, this is the #909 family again and a much worse finding.

**2. Ended the dash right after tapping expand on the post-delivery receipt; that delivery did not
land either.** Sequence as narrated: delivered → the post-delivery summary appeared **collapsed** →
dev tapped **expand** → dev then **ended the dash**. Nothing recorded.
- **Status:** Open.
- **Desk-side hypothesis.** If item 1 turns out to be the read-model failure, this item is most likely
  *swallowed by* item 1 rather than being its own defect, and should be re-judged after the pull —
  do not chase it first. If item 1 resolves and this delivery is still missing, the interesting seam
  is the expand-then-immediately-end ordering: the collapsed→expanded receipt path carries a
  deliberate same-task guard (`PlatformRegionStepper.kt:537–551`, #630 R3) and the retire/close-out
  runs behind a **grace timer**, so a dash ended within the grace window is the shape worth replaying
  (`SessionReplay.reduceMixed` — real screens + the expand click + synthetic `GRACE_COMMIT` timers is
  precisely what that harness exists for). Unconfirmed either way without the capture sequence.

### Field UX context

**3. Dash Along the Way no longer shows the post-delivery summary screen at all while out of zone.**
Dev-observed and dev-attributed to DoorDash, not to us: on a Dash Along the Way, completing a drop
**outside** the zone produces **no** post-delivery summary. Once DoorDash recognizes the dasher has
arrived **in** the zone, the summary screens start appearing again for subsequent drops. So it is
conditional on in-zone/out-of-zone, not on the dash type as such.
- **Status:** Open — platform-side behavior, nothing for us to fix; it is an **input** to how we price
  those drops.
- This sharpens the standing **#999** checklist item ("is DoorDash still SHOWING the per-delivery
  earnings receipt at all?", README:188), which had the disappearance recorded but not the
  **out-of-zone condition**. Worth folding the condition into that item so the next test knows to
  check in-zone and out-of-zone separately rather than reading one null as the whole answer.
- Consequence, stated plainly: for out-of-zone Dash Along the Way drops there is **no receipt to
  parse**, so those drops can only ever be priced from the offer quote (`PayBasis.OFFER_PAY`, #691/
  #999). That is the estimate path working as designed — but it also means item 4 below is the only
  route to a *real* number for them.

### Research / design

**4. Dev proposal — treat DoorDash's own running dash total as the reconciliation oracle.** In the
dev's words: if we can parse what DoorDash says we've earned on the active dash screen, and it does
not match what we have recorded, **DoorDash's figure is the source of truth** — set to it, flag it for
review, *and* attempt to resolve it ourselves. The worked example given: an accepted offer quoted
\$5, we have not recorded the delivery, and the dash screen now reads \$5 — that is enough to conclude
the \$5 came from that job and attribute it.
- **Status:** Open — proposal, not yet an issue.
- **Desk-side: the parse ingredient already exists, unused.** Two rules already read DoorDash's own
  running total off the live dash surfaces:
  - `doordash.screen.waiting_for_offer` parses **`sessionPay`** from the id-anchored
    `running_total_pay` node (`dash-lifecycle.json5:762`) — id-anchored, so **locale-immune** (#938/
    #910), which is the good kind of anchor.
  - `doordash.screen.timeline` (the "Current dash" control sheet) parses **`sessionEarnings`** from
    `This dash`, plus **`offerEarnings`** from `This offer` (`dash-lifecycle.json5:116–125`).
  - `IdleFields.sessionPay` **is** folded today, into `PlatformRegion.session.runningEarnings`
    (`PlatformRegionStepper.kt:~511`) — but that is the **bubble HUD figure only**.
  - **`TimelineFields` is not handled by `updateSessionFields` at all** — its `when` covers `Idle`,
    `PostTask`, `SessionEnded`, `Paused`, then `else -> no session updates`. So the timeline's
    `sessionEarnings`/`offerEarnings` are parsed on every dash and **dropped on the floor**. That is a
    free, already-fielded signal, and the `offerEarnings` half is exactly the per-offer granularity the
    dev's \$5 example needs.
- **The real gap is not the parse, it is that `runningEarnings` never reaches the money.** The HUD
  figure lives in state; the Money screen is projected from `app_events` → `delivery_records`. Nothing
  currently carries "DoorDash says the dash total is X" into the read model, which is why a drop with
  no receipt can be invisible in the analytics even while the bubble shows the right running number.
- **Design note, offered as a caution, not a verdict.** The proposal's "set it to that" half is in
  tension with the **frozen-economics doctrine** (a `delivery_record` is an immutable historical fact;
  `AnalyticsRepository` is DAO-only *specifically* so historical net cannot be recomputed). The shape
  that appears to fit the existing architecture is the **correction-event** path already built for
  #650/#688/#810 — i.e. a new reconciliation event appended to the log and folded, exactly as
  `DELIVERY_ADJUSTMENT` and `OFFER_OUTCOME_CORRECTION` are, rather than a mutation of existing rows.
  The dev's "flag for review, and also try to resolve it ourselves" maps cleanly onto the **two-tier**
  pattern #810 B2 already established (Tier 1 automatic inference where the evidence is unambiguous,
  Tier 2 driver attestation where it is not), and the `unattributedPay` review flag is already the
  surface for the flagging half. Whether the auto-resolve tier should fire on a single-candidate match
  only (one unrecorded job, one exact delta) or something broader is a real design question and the
  dev's call — noting only that #745's *fail-null beats fail-wrong* rule and the ambiguity ladder in
  #997 are the nearest precedents for where to draw that line.
- Worth filing as an issue once item 1 is resolved, since item 1 may change what problem this is
  actually solving.

### Open questions / investigations

**5. What the pull needs to answer, cheapest first.**
- **Is `app_events` populated for 08-17 and 08-23?** This single question splits item 1 into
  "recoverable projection bug" vs "catastrophic sensing/write loss", and everything else waits on it.
- **What is the stored `projectorVersion` and watermark** in the projection-state row, and does the
  log carry the `AnalyticsProjector` version-change / wipe line and an ERROR after it?
- **When was the current build installed**, relative to Monday's dash — a wipe at install time would
  date the disappearance.
- **The capture sequence around the expand tap** (item 2): the collapsed receipt frame, the expand
  click, and every frame through end-of-dash, so it can go through `SessionReplay.reduceMixed`.
- **A `waiting_for_offer` / `timeline` frame from the Dash Along the Way dash** (item 3), out of zone,
  to confirm `running_total_pay` / `This dash` still parse when the receipt sheet does not exist — that
  is the precondition for item 4 being worth anything on exactly the dashes that need it most.

### Meta / architecture

**6. Carried forward from earlier today (pre-dash desk look, same session): `doordash.screen.earnings`
has zero DoorDash corpus.** The only fixture in `snapshots/earnings_activity/` is an **Uber** one, and
`doordash.screen.earnings` appears nowhere in `approved-parse-output.json` — so that rule has never
been validated against a real DoorDash frame. Separately, the #924 DasherDirect door
(`hasIdSuffix: "dxdr_nav_host_fragment"`, priority 0, `overrideable: false`, merged in #1014) would
**structurally out-rank** the earnings rule on any frame that hosts the Crimson card, since the
non-overrideable partition is evaluated first (#419). Both remain hypotheses pending the Earnings-tab
captures the dev is bringing home. Noting it here so it is not lost between sessions.
- **Status:** Open.
