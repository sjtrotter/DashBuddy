status: active
issues: #556

- **🔧 MERGED — realistic $/hr + score on Shop & Deliver offers (#556). CONFIRM ON DASH.**
  The time model now estimates a shop by its item count at the dasher's shopping pace (seed 0.8
  items/min, learned from your own completed shops) instead of a flat 7-min overhead — so a grocery
  run no longer reads a wildly inflated `$/hr` / "AWESOME" score. **This is a verdict-mover** (it
  changes accept/decline rankings, not just the readout). **Confirm on dash: 0/2 —** on a Shop &
  Deliver / grocery / ACV offer (e.g. H-E-B, Sprouts) the card `$/hr` should look **realistic**
  (~$25–40/hr range, not $100+), the score should be sane, and a normal restaurant **pickup** offer
  should read the **same as before** (only shops changed). After a few shop dashes, the learned pace
  kicks in — grep the log for `ShopRate` to see it recording (`N items / M min = X/min`). If a shop
  still reads inflated, note the offer's item count + quoted miles + the `$/hr` shown.
  - Confirmed: **1/2** (06-25→30 week — 06-30's 41-item H-E-B priced $9.94/hr, a sane decline;
    shops all week in a $14–27/hr band; `ShopRate` learning lines ×11. Caveat: a 44-item shop
    estimated ~83 min vs ~2.5 h actual — seed still optimistic on giant shops).
  Triage source: first-line blame 2026-06-21 (`a60734205`, original README L2357).
