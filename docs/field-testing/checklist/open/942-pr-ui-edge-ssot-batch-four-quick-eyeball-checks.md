status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #942, #953
confirmed: 0/2

- [review] **🆕 NEW — #942 / PR #953 — UI-edge SSOT batch (four quick eyeball checks, one dash).**
  1. **Store-join parity:** on a stacked offer, the heads-up notification and the bubble card name
     the stores identically (`Store A & Store B`) — the notification used to say `+`.
  2. **Verdict word:** a manual-review verdict reads `REVIEW` on the bubble offer card (was
     `MANUAL REVIEW`), matching the notification.
  3. **Badge pills:** text badges read their full names (`All Orders Same Store`, `Items Can Be
     Added`) — check they don't overflow/truncate badly in the HUD pill row; if they do, shorten
     the enum `displayName` (one owner now).
  4. **Chat clock:** flip the phone's 24-hour setting with the bubble chat open; existing
     timestamps re-render in the new format without reopening.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — all four sub-checks are eyeball/UI
    checks a desk pull can't answer (no stacked offer this dash to check store-join parity against
    either, all four accepted offers single-store). Needs dev eyes.)
  desk: needs field: compare the four named UI surfaces for store joining, verdict copy, duration and percentage rendering.
  Triage source: first-line blame 2026-07-30 (`d3f31f580`, original README L1058).
