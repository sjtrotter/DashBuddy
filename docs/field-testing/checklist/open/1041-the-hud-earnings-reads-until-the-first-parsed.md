status: active
issues: #1041

- 🆕 NEW — the HUD earnings reads "—" until the first parsed total of the dash, never "$0.00"; the first settled read replaces it (#1041). — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): needs the dev's eyes. Captures show DoorDash's own running total at $0.00 at first pickups, which is correct for each shift's first job. Counter unchanged.
