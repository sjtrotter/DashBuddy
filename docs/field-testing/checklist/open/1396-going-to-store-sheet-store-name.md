status: active
issues: #1396, #1397

- **The going-to-store sheet's store name is the store (#1396):** on the next pickups, the Navigator chat line
  ("Pickup: …") and the pickup's store name show the store, never the customer's name, including the first frame
  after a heads-up Accept. Working = every `PICKUP_NAV_STARTED` `storeName` in the pull is a merchant, or null when
  the merchant row had not rendered yet. Broken = a first-name-and-initial value as a store. Desk: the at-store and
  going-to-store captures show the merchant's instruction text as `[redacted]` (#1397), and `instructions_title`
  (the store) raw. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): consistent. All 10 `PICKUP_NAV_STARTED` store names are the merchant, and all 10 Navigator "Pickup:" lines name the merchant. #1397: `instructions_line_2_expanded` reads `[redacted]` on the 11-11 pickup frames; `instructions_title` stays raw (it carries the store's order number on 8.106.9). Counter unchanged.
