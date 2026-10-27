status: active
issues: #461, #1, #2

- **Offer card surfaces Shop & Deliver: item count in the hero row + a SHOP badge (#461 a/b).**
  The item count moved from a small footer caption up to the hero row (beside the score ring /
  $/hr), and a Shop & Deliver offer now shows a "Shop & Deliver" badge pill. On a shop offer:
  working = the item count is prominent in the hero and a "Shop & Deliver" pill shows; a plain
  pickup offer shows NO shop pill. Broken = item count missing/duplicated, or the shop pill on a
  non-shop offer. (**#461 stays open** for part (c) — the finished/PostTask card showing the
  order type, which needs offer→job→delivery data flow.)
  - Confirmed: 1/2 (single-order). **2026-06-14 (DoorDash):** the Shop & Deliver badge shows and the item
    count is up in the hero on a **single** order. **FOUND BROKEN on STACKED orders:** the hero shows the
    **# of stacked orders, not the # of items** (logged 2026-06-14 #1). Single-order half advanced to
    1/2; the stacked-count is a tracked bug. (See also the design rethink, 2026-06-14 #2.)
  Triage source: first-line blame 2026-06-13 (`299145d7f`, original README L2533). Recorded first confirmation: 2026-06-14 (single-order only) (existing evidence only).
