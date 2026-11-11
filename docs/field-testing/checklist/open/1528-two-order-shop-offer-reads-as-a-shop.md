status: active
issues: #1528

- **A two-order shop offer reads as a shop (#1528):** on the next DoorDash offer whose shop row reads
  `Shop for 2 orders (M items, ~T min)`, the offer shows as a shop with M items, and its time estimate includes
  shopping (well above a quick pickup's). Working = the stored offer has `isShop` 1 and `itemCount` M. Broken = a
  quick-pickup estimate, `itemCount` 0 or `isShop` 0. The order count is still read as 1 (not part of this fix).
  — Confirmed: 0/2
