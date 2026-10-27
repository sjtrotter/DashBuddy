status: superseded
issues: #657, #658, #1024, #1027, #1246

- **🆕 NEW — the main dashboard is now a REVIEW surface, not a live bubble mirror (#657 / PR #658).**
  Open the app **after a dash** (not while on a task): the **Today** tiles (True Net / Net $/hr /
  Miles) should already reflect the just-completed dash with no manual refresh (the read-model folds
  each delivery as it completes). Tap the **Today / This week / Lifetime** selector and confirm the
  three tiles switch to each window's totals. The old live "This dash" ticking hero is **gone** —
  there should be **no** per-second $/hr counter on this screen. While you're online, a slim
  "🟢 Dashing — tap for the bubble" row appears above the tiles; tapping it should re-show the bubble.
  How to tell it's broken: tiles frozen/stale after a dash, the segmented selector not changing the
  numbers, a live ticking counter still present, or the dashing row showing while offline.
  - Confirmed: 0/2
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces the segmented Today/This week/Lifetime tile layout and explicitly restores a live On dash tick. — RETAIN the post-dash earnings-FRESHNESS check (#657) before pruning: the #1024 part 3 / PR #1027 replacement tests the four-block layout, not that the totals refresh after a dash (Astra review of PR #1246).
  Triage source: first-line blame 2026-07-04 (`2b315e708`, original README L1962).
