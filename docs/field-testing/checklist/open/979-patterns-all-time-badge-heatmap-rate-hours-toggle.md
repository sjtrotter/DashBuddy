status: review
issues: #979, #1, #2, #1024

- **🆕 NEW — #979 — Patterns: ALL-TIME badge, heatmap Rate/Hours toggle, store leaderboard
  (redesign stage 5).** Open Analytics → Patterns. Three things to check.
  (a) **ALL TIME badge:** an `All time` chip sits at the very top with the caption "patterns need
  history — this tab always reads your whole record" — and paging the header `‹ ›` pager above must
  NOT move anything on this tab (Patterns always reads your whole history regardless of the
  selected window; that's the point of the badge).
  (b) **Heatmap Rate/Hours toggle:** a segmented control (Rate / Hours) sits above the grid. Rate is
  the pre-existing net-$/hr coloring (unchanged). Hours re-colors the SAME grid by how many hours
  you've spent online in each slot — an hour you've genuinely never worked should render as the same
  dim "no data" swatch as an under-covered Rate cell (there's no red/bad state in Hours mode, only
  more-or-less green). The legend and title/caption below the grid must switch with the toggle.
  (c) **Store leaderboard:** the store cards are now dense ranked rows — `#1`, `#2`, … at the left,
  name + location chip, a horizontal net bar, `N deliveries · <time> usual wait` underneath, the net
  figure and chevron at the right. Sort chips **By net / By wait / Recent** re-rank the rows (rank
  numbers should update; the bar lengths should NOT rescale when you switch chips — they stay
  relative to your #1 store by net). A store whose usual wait is notably worse than everywhere else
  you go should render that wait figure in amber/warn color; check it stays plain-colored on stores
  with only 1-2 total stores having a wait sample (thin data must never manufacture an outlier).
  Tapping a row must still open the same detail sheet as before. The "manually-added deliveries…"
  footnote must still be there.
  - Confirmed: 0/2
  desk: needs field: inspect the leaderboard and heatmap in the newer #1024 part 1 Playbook destination; the old Patterns location changed.
  Triage source: first-line blame 2026-07-30 (`c9fe5ff68`, original README L967).
