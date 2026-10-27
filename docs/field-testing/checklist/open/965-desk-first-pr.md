status: active
issues: #965, #1126, #1213

- **Desk-first — #965 / #1126 (PR #1213):** on the next pull, (a) a `ratings` envelope from the LEGACY ratings hub
  parses `deliveriesLast30Days` as a count (the approved golden now carries 136/72/71/100 on the committed legacy
  frames; a null on a fielded legacy frame is the thing to report), and (b) every recognized dropoff envelope's
  `address_line_2` / numeric `Building Name` slot reads plain `[redacted]` — no `[redacted:<4hex>]` on those two
  slots anywhere in `captures/doordash/`.
  Triage source: first-line blame 2026-10-05 (`46f87c21c`, original README L128).
