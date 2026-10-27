status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #1024, #1025
confirmed: 0/2

- [review] **🆕 NEW — #1024 part 1 (PR #1025) — the Playbook destination.** Open Home → **Playbook** tile.
  Check: (a) *This week's plan* shows `Xh worked in your windows · $Y kept of the $Z you planned
  for` and each window row flips to **Done** after its end hour (leave the screen open across an
  hour boundary — it should re-render without leaving); (b) the heatmap outlines exactly the hours
  the saved plan picked, and the Rate/Hours toggle still works; (c) the store leaderboard matches
  what the old Patterns tab showed; (d) Analytics now has three tabs. With **no** plan saved the
  card says so and offers *Build a plan →*, never an empty plan. The screen's footer disclosure
  includes the plan-projection line (lifetime, not a guarantee).
  - Confirmed: 0/2
  desk: needs field: open Playbook and exercise plan, heatmap and hour-boundary rendering on the device.
  Triage source: first-line blame 2026-08-12 (`69e72d33b`, original README L777).
