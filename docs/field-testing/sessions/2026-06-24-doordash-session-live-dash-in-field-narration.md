## 2026-06-24 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `2fc557b` (post-#581 merge — latest, includes #457/#576
  notification buttons, #577/#580 quick-decline, #578/#581 rich offer notification).
- **Field conditions:** Live dash, narrated in-field while driving. Offers declined via the
  heads-up notification + a couple of accepts, including a **Sizzling Wok same-store double stack**.
  One screenshot of the bubble HUD (the Sizzling Wok delivery card). **Hypotheses, not concluded
  fixes.** No code changes from this session.

### Verification TODOs (confirmations this dash)

1. **✅ #577 / PR #580 — quick-decline auto-confirm of the 2nd ("are you sure?") screen — CONFIRMED 2/2.**
   With quick-declines ON, declined an offer and the second confirm screen **auto-advanced both
   times** (validated twice, independently). Validated — moved off the "things to look for"
   checklist. (Slowness caveat below as item #1.)

2. **✅ #457 / PR #576 — offer Accept/Decline as a separate heads-up notification — CONFIRMED.**
   The notification buttons **work again**: both **Decline** and **Accept** fired correctly from the
   heads-up over DoorDash (the decline button was exercised on both auto-decline runs above; accept
   verified too). Closes the "No live windows" regression that #576 targeted. Moved off the
   checklist.

### Bugs / Field UX context

1. **Auto-decline second-screen confirm feels slow (#577).** When quick-declines is ON, the dasher's
   expectation is that the second ("are you sure?") screen is dismissed near-instantly — "if you've
   said you want to auto-decline the second screen, it should be minimal delay." In the field it
   advances reliably but with a noticeable lag.
   - **Light investigation (hypothesis, not a fix).** The auto-tap is deferred behind a fixed settle
     delay: `EffectMap.diffConfirmDeclineAction` (`core/state/.../EffectMap.kt:908`) schedules the
     `CONFIRM_DECLINE` tap as a `ScheduleTimeout(SETTLE_UI, EXPAND_SETTLE_MS)` where
     `EXPAND_SETTLE_MS = 500L` (`EffectMap.kt:72`) — the **same 500ms constant borrowed from the
     post-delivery `EXPAND_EARNINGS` chevron tap**, whose comment justifies it by "lets the summary
     finish animating." On top of that 500ms, the confirm dialog first has to be *recognized*: the
     "are you sure?" frame clears the content-changed debounce (`ContentChangedPipeline.debounceWithTimeout(150L, 300L)`,
     `core/pipeline/.../content_changed/ContentChangedPipeline.kt:32`) — or the 100ms windows-changed
     debounce — before it classifies and the effect can even be emitted. So perceived delay ≈
     ~150–300ms (recognition debounce) + 500ms (settle) + click execution ≈ ~0.7–1s before the tap,
     before counting DoorDash's own dialog animation.
   - The `diffConfirmDeclineAction` comment notes the 500ms exists because "~half the captured confirm
     frames are transitional, so the settle wait lets the button render." So the delay is intentional
     (avoid tapping a half-rendered button), not a bug — but it's tuned to the earnings-chevron case,
     not the simpler confirm dialog. **If this hypothesis holds**, one direction *might* be a shorter
     dedicated settle for `CONFIRM_DECLINE`, or firing as soon as the `declineButton`/confirm target
     binding is present in the observation (the binding implies the button rendered) rather than
     waiting a fixed 500ms — **would need a capture of the confirm-frame sequence** to see how many
     frames are genuinely transitional and how early the binding appears.
   - **Status:** Open. Behavior is correct (2/2 validation above); only the latency is the concern.

2. **Sizzling Wok same-store double stack ended with only ONE delivery card.** Accepted a double
   stack — two orders, both from Sizzling Wok — but at the end the bubble HUD showed a **single**
   delivery card (screenshot): one OFFER (Sizzling Wok $18.40, Accepted), one PICKUP (Sizzling Wok,
   arrived 19:37), one DROPOFF ("Sizzling Wok's customer", delivered 19:47, $30/hr), Earnings Saved
   $16.75. The second order's card is missing.
   - **Light investigation (hypothesis, not a fix).** This looks like the **known multi-drop frontier**,
     not a fresh regression: per CLAUDE.md and #503, "multi-drop is slice 3b, **not yet shipped** — a
     stacked/GoPuff multi-drop may still mis-handle the extra dropoffs" (epic #505). The current build
     pre-creates **one** dropoff subtask at offer-accept and resolves the customer onto it
     (`JobEconomicsTest`: "accept pre-creates exactly one dropoff subtask", #503 slice 3) — so a stack
     that arrives as a single offer with two customers would only ever materialize one dropoff card.
     A **same-store** stack is doubly suspect: the same-store resume logic (#499/#503 slice 2)
     deliberately *re-matches by store* to avoid re-minting a task, which on two-same-store-orders could
     fold the second order onto the first instead of spawning a distinct dropoff.
   - **Open questions for the desk / next capture:** Was this **one** DoorDash offer covering two
     orders, or **two** offers (an add-on accepted mid-stack)? Did the bubble ever show two cards and
     then collapse to one, or only ever one? Was the $18.40 / $16.75 the pay for **both** orders or just
     one (i.e. is earnings also undercounted, or only the card missing)? Needs the **capture sequence**
     (offer → pickup → both dropoffs → summary) + the job's `app_events` / `app_state_snapshots` to tell
     a missing *card* from a missing *task* from a collapsed-same-store task.
   - **Status:** Open. Likely the unshipped multi-drop slice 3b (#503/#505), but the **same-store**
     variant is worth its own capture — flag for triage.

### Research / design

1. **The "&lt;store&gt;'s customer" dropoff card title (#568) doesn't read well — what should a dropoff
   card be titled?** In the field "Sizzling Wok's customer" felt awkward as a card title. The dasher
   floated options but is **explicitly undecided** — recording the design space, not picking:
   - **(a) Use the real customer name, hash the address instead.** *Tension with the privacy pledge.*
     Today customer name + address are **both** `sha256`'d at the edge in the parse (#463/#362); state
     only ever holds `customerNameHash` — there is no raw name to display. Showing the name would mean
     **not** hashing it, i.e. keeping raw customer PII in on-device state. CLAUDE.md's Pledge: "Customers
     are hashed, not blocked … we can tell customers apart … without ever keeping their actual info."
     So this option isn't a pure UI change — it would reopen a non-negotiable privacy decision (the
     dasher's *own* first + last-initial is the only name that's fine to process). Worth a deliberate
     decision, not a drive-by; flagged because it touches a pledge.
   - **(b) Title the dropoff by the restaurant instead — e.g. "Dropoff: Sizzling Wok".** Privacy-safe
     (store names aren't PII), and it's essentially a **relabel** of the data #568 already resolves
     (the store is matched to the drop). Loses the ability to *distinguish two customers of the same
     store on a stack* by name — but the hash already differentiates them under the hood, and a
     same-store stack would read "Dropoff: Sizzling Wok" twice (could disambiguate by address-hash
     suffix or drop #1/#2).
   - **(c) Keep "&lt;store&gt;'s customer" (status quo, #568).** It does encode the store and stays
     privacy-safe; the complaint is purely that the phrasing reads oddly as a title.
   - **Status:** Open — design question for the developer. No code change this session. (Note: this is
     entangled with Bug #2 above — a same-store stack is exactly the case where any of these titles is
     hardest to disambiguate.)
