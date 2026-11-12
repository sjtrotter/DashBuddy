status: active
issues: #1528

- **A two-order shop offer reads as a shop (#1528):** on the next DoorDash offer whose shop row reads
  `Shop for 2 orders (M items, ~T min)`, the offer shows as a shop with M items, and its time estimate includes
  shopping (well above a quick pickup's). Working = the stored offer has `isShop` 1 and `itemCount` M. Broken = a
  quick-pickup estimate, `itemCount` 0 or `isShop` 0. The order count is still read as 1 (not part of this fix).
  — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): pre-fix, broken as expected on both cards (11-07, 11-11): PICKUP, 0 items. The 11-11 card reads `Shop for 2 orders (20 items, 26 units)` with no `~T min`; the #1595 regex covers it, `DoorDashMultiOrderShopRowTest` does not. Counter unchanged.
