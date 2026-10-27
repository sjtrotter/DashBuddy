status: active
issues: #843, #417

- **🆕 NEW — #843 — prompted per-capability automation consent (no auto-grant).** Automations are
  no longer pre-granted; each must be consented to individually via a prompt at the app's front
  door (Google Play policy). On upgrade, a one-shot migration clears the old auto-grants, so every
  automation starts OFF.
  **What to watch (first app-open after installing this build):**
  1. On first open (once accessibility/notification permissions are in), a **consent sheet**
     appears listing each automation **individually** — Accept, Decline, Confirm-decline, Open
     pay breakdown — each with its own Allow / Don't-allow buttons and a source line ("Built-in
     DoorDash rules"). There is **no "allow all"** button; "Not now" dismisses and it should
     re-appear next time you foreground the app while anything is still undecided.
  2. Before you grant anything, **quick-decline / auto-expand should do nothing** (stay MANUAL) —
     the automation is off until consented.
  3. Grant **exactly one** automation (e.g. Decline) and leave the others "Not now": on the next
     offer, only that one fires; the un-granted ones still require a manual tap. A "Don't allow"
     choice must never re-prompt.
  - Issue: #843. Confirmed: 1/2 (2026-07-24 install, desk 07-26: the MECHANISM half is proven from
    `app.log` — the one-shot migration cleared prior grants at 17:58:32, the reconcile that followed
    granted NOTHING ("none granted — awaiting consent"), and three grants arrived only as separate
    explicit user acts ~16 s later; the 19:30:17 decline chain fired only after those grants. The UI
    half — sheet layout, "Not now" re-prompt, "Don't allow" durability, item 3's exactly-one-grant
    check — still needs dev eyes. Likely 4th capability left ungranted is accept (no automation
    accept line post-install; hashes-only logs can't prove identity). Desk 07-27 corroboration:
    across a full day the three granted automations fired constantly (confirm_decline ×14,
    decline ×6, expand ×8) while accept NEVER fired automatically over 10 manual accepts —
    second independent support for the ungranted-accept read. Desk 07-30, the 07-29 REINSTALL day:
    third mechanism corroboration — two `Consent: reconciled 4 capabilit(ies)… none granted —
    awaiting consent` lines at install (11:02/11:03), ZERO grant lines all evening, and zero
    automation fired across 8 declines + 4 accepts (every decline a dasher tap — 3 heads-up, 5
    in-app): item 2's before-you-grant-anything check proven negatively. Still 1/2 — the UI half
    (sheet layout, "Not now" re-prompt, "Don't allow" durability, exactly-one-grant) needs dev eyes.
    Desk 07-31, the 07-30 dash: FOURTH mechanism corroboration, this time a genuinely FRESH install
    (not a reinstall) — `INFO/Consent: reconciled 4 capabilit(ies) from rule load (none granted —
    awaiting consent)` at 14:50:52.611, and the fail-closed gate was caught live actually denying an
    automation 5× (`WARN/Effects: Denied confirm_decline — no granted capability for rule
    'doordash.screen.offer_popup_confirm_decline' (fail closed)`), the clearest single-log evidence
    yet that #417's gate and #843's no-auto-grant compose correctly. Still 1/2 — the UI half is
    unchanged and still needs dev eyes.
    Desk 08-09, the 08-01→08-08 window: SIXTH mechanism corroboration, and the longest observation
    window yet — the 07-31 install's two `Consent: reconciled 4 capabilit(ies)… none granted —
    awaiting consent` lines, then **zero** grant lines across nine days and five dashes, with the
    fail-closed gate denying `confirm_decline` **21 times** over that stretch (`WARN/Effects:
    Denied confirm_decline — no granted capability … (fail closed)`). Item 2's
    before-you-grant-anything check is now proven negatively over a full field week rather than a
    single evening. Still 1/2 — the UI half (sheet layout, "Not now" re-prompt, "Don't allow"
    durability, exactly-one-grant) has still never been exercised and needs dev eyes.)
  Triage source: first-line blame 2026-07-23 (`d394b8713`, original README L1217). Recorded first confirmation: 2026-07-24 install, desk 2026-07-26 (mechanism only) (existing evidence only).
