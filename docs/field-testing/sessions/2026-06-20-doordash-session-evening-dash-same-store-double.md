## 2026-06-20 — DoorDash session (evening dash, same-store double stack)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (build inferred — developer to correct if running a feature branch).
- **Field conditions:** Live evening dash, separate from the earlier (~17:01) H-E-B dash. **Two
  same-store double stacks:** one at Panda Express (first dropoff ~19:39), one at Perry's Pizzeria.
  In-field markers only; no screenshots. **Markers for desk review, not concluded observations.** No
  code changes from this session.

### Verification TODOs — same-store stacked doubles (markers for desk review)

1. **Same-store double stack at Panda Express — first dropoff completed ~19:39, set as a desk-review
   marker.** Both orders in the stack are from the **same store (Panda Express)** — one pickup location,
   two distinct customer dropoffs. Dasher dropped the **first** of the two at ~19:39 and flagged it live
   so we can pull the captured data later. Stacked + multi-drop handling is a **known frontier** (#503
   slice 3b multi-drop not shipped; the same-store add-on re-mint guard is #499/#503), so this is a
   real-world specimen of exactly that case.
   - **What to pull / check at desk (from this dash's `app_events` + `app_state_snapshots`):**
     - **Offer shape:** did this arrive as **one stacked offer** (two orders in one `ParsedOffer`) or
       two separate `OFFER_RECEIVED`s? Note the offer-accept sequence around the stack.
     - **Same-store pickup identity:** with both pickups at Panda Express, did the task lifecycle keep
       **two distinct orders** but (correctly) **one pickup activity**, or did the same-store re-match
       (#499 pickup re-match by store) **collapse/merge** them or **re-mint**? Want: two orders, not one,
       and not three.
     - **Two distinct dropoffs:** each dropoff should resolve to its **own customer hash** and its **own
       address** — the multi-drop path (slice 3b) is unshipped, so watch for a **dropped, duplicated, or
       mis-ordered** second dropoff after the first completed at ~19:39.
     - **Completion + earnings:** **exactly one** `DELIVERY_COMPLETED` per dropoff (two total), each with
       a distinct customer hash and non-null pay, and **session earnings reconcile** to the sum (no
       double-count, no missing leg).
     - **Bubble/flow cards:** how did the HUD render two same-store orders — two cards, one merged card?
       (FlowCardMapper v1 assumes a single delivery in flight; a stack overwrites the accepted-economics
       accumulator — see the v1 caveat in `FlowCardMapper.kt`.)
   - **Status:** Open — **marker only**, awaiting end-of-dash log upload for the desk cross-reference
     above. Acting as field-testing agent: recorded only, no code changes.

2. **Second same-store double stack — Perry's Pizzeria — set as an additional desk-review marker.**
   A second double stack later in the same evening dash, **both orders at Perry's Pizzeria**. Same shape
   as item #1 (one store, two customer dropoffs), flagged live as another specimen to cross-reference.
   Two same-store stacks in one dash gives the desk review **two independent samples** of the multi-drop
   + same-store-pickup path to compare.
   - **What to pull / check at desk:** identical checklist to item #1 — offer shape (one stacked offer
     vs two `OFFER_RECEIVED`s), same-store pickup identity (two distinct orders, not merged/re-minted),
     two distinct dropoffs (own customer hash + address, none dropped/duplicated/mis-ordered),
     exactly one `DELIVERY_COMPLETED` per leg reconciling to earnings, and how the HUD rendered two
     same-store orders.
   - **Status:** Open — **marker only**, awaiting end-of-dash log upload. Recorded only, no code changes.
