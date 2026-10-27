status: superseded
section: §5 Field-validated / retired / Superseded (dev-confirmed 2026-10-07 — the replacing redesign is named on each; the #657 earnings-freshness check is retained in §2)
issues: #320, #316, #1024, #1027
confirmed: 0/2

- [superseded] **✨ NEW — the home screen now shows a live "This dash" glance + entry tiles (#320/#316).**
  Open the DashBuddy main app (not the bubble) **while a dash is running**. **Working looks like:**
  the "Ready to Dash" area shows three stat tiles — **True Net** (green when positive), **Net/hr**,
  **Miles** — and the Net/hr + its sub-timer **tick up every second** without needing a state change
  (that's the reactive glance). True Net should equal session earnings minus miles × your operating
  cost/mi (same math as an offer's net verdict), and Miles should track the GPS session odometer.
  Below the tiles is a 2×2 grid — **Analytics · Ratings · Strategy · Economy**: tapping **Ratings**
  opens a screen showing your real customer-rating / on-time / completion gauges + acceptance /
  delivery-count / shopping-quality tiles (empty-state message if you haven't opened the platform's
  Ratings screen yet this run); **Strategy** and **Economy** open their existing editors; **Analytics**
  is a "Construction Area" placeholder for now. Broken = frozen Net/hr (doesn't tick), True Net that
  disagrees with the offer-card net math, a Ratings screen that's blank when the platform ratings
  screen was seen, or a tile that navigates nowhere.
  - Confirmed: 0/2
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces the live three-tile glance and old 2×2 navigation grid; dev to retain uncovered Ratings checks before pruning.
  Triage source: first-line blame 2026-07-03 (`5ed357c6c`, original README L2035).
