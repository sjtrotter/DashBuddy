status: active
issues: #1108, #1120

- **🆕 NEW — a stacked job's two drops must not be charged the same miles twice (#1108).** **Desk:** per
  session, `Σ realizedMiles` must be ≤ `lastOdometer − startOdometer`; and no
  `delivery_records.realizedMinutes` may be negative. Watch specifically for a stacked job where the
  second drop-off card appears without an arrival frame (09-13 session 499: 24.32 mi of legs on an
  18.70 mi span, and a −7.1 min row).
  - Issue: #1108. Confirmed: 0/2
    - desk 09-20: CLEAN, no recurrence (not a confirmation — the causing shape did not repeat):
      `Σ realizedMiles ≤ (lastOdometer − startOdometer)` on all 15 sessions, largest gap 0.03 mi; zero
      negative `realizedMinutes`; the one stacked job (…-594, two drops) shows legs 21.98 vs span 21.98.
    - desk 09-21: CLEAN, still not a confirmation — `Σ realizedMiles ≤ (lastOdometer −
      startOdometer)` on every session (largest gap 0.32 mi), zero negative `realizedMinutes`, and
      the day's one stacked job (…-674) shows legs 7.68 + 6.22 = 13.90 mi, both non-zero. The
      causing shape did not recur. (That job DID lose $28.51 — to #1120, a different mechanism.)
    - desk 09-26: CLEAN, still not a confirmation — `Σ realizedMiles ≤ span` on every session (largest gap 2.44 mi,
      session 707), zero negative `realizedMinutes`, and no stacked job in the window.
    - desk 09-27: CLEAN, still not a confirmation — Σ realizedMiles 75.83 ≤ span 75.85 mi, zero negative
      `realizedMinutes`, no stacked job.
    - desk 09-29: CLEAN, still not a confirmation — Σ realizedMiles ≤ span everywhere; the one stacked job (…-777,
      two stores) has both arrivals and legs 8.91 + 2.13 mi; 0 negative `realizedMinutes`.
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L408).
