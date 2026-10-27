status: active
section: §4 1/2 items older than 6 weeks / HUD
issues: #460, #324, #2
confirmed: 1/2

- [active] **Pickup/Delivery task cards redesigned to the co-hero design (#460/#324).** The task cards
  no longer show a single countdown + caption — they now have the **dual co-hero**: LEFT = the
  phase timer (counts DOWN to the deadline as "To go", then flips to "Dwell" counting UP once you
  arrive, with "at store"/"at door"), RIGHT = **"Running at $X/hr"** — the live realized rate from
  the accepted offer's net pay ÷ time, which **holds until the deadline then erodes** (shows a ↓
  and "dropping"). Below: an "arrived N early/late · deliver by H:MM" caption, a red **"Below your
  floor"** banner once overdue + the rate drops under $12/hr, the shop pace block (Shop & Deliver),
  and the store/customer detail line. On a dash: working = during pickup/dropoff the live card
  shows both heroes ticking; the $/hr roughly matches the accepted offer's $/hr and drops if you
  run late; "Running at —" only when the offer had no economics. Broken = $/hr shows "—" on a
  normal accepted offer, the timer doesn't flip to Dwell on arrival, values clip/overflow the
  bubble width, or the $/hr doesn't erode past the deadline.
  - Confirmed: 1/2 (pickup card). **2026-06-14 (DoorDash, 1 dash):** the **pickup** co-hero rendered
    (timer + "Running at $/hr") — dasher flagged it "maybe not wired right" though. The **drop-off**
    `$/hr` still reads nil (FOUND BROKEN — the blend doesn't survive into the dropoff leg, 2026-06-13 #2).
    Pickup half advanced to 1/2; drop-off half tracked as a bug.
  Triage source: first-line blame 2026-06-13 (`39a54a998`, original README L2592). Recorded first confirmation: 2026-06-14 (pickup only) (existing evidence only).
