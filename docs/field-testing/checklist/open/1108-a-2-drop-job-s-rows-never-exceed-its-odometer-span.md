status: active
issues: #1108

- 🆕 NEW — a 2-drop job's rows never exceed its odometer span and never show negative minutes (#1108)
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): CLEAN. Σ drop miles ≤ span on all 4 shifts (53.2/59.1, 22.0/22.9, 8.0/8.4, 6.6/6.6), 0 negative minutes. The 11-07 5.9 mi gap is a single drop whose leg reads 0.0 after an early commit (session log, bug 3; #1611). Counter unchanged.
