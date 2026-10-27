status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #363, #391
confirmed: 0/2

- [review] **Tree ingestion now bounded — confirm no real screen trips the caps (#363, PR #391).**
  Accessibility trees deeper than 60 levels or larger than 4,000 nodes truncate with a loud
  log line. The caps carry 2×/10× margin over the corpus, so a normal dash should NEVER hit
  them. Post-dash: grep the log for "Tree ingestion truncated" — any hit means a real DoorDash
  screen is bigger than the corpus suggested and the caps need raising (file it).
  - Confirmed: 0/2.
  desk: desk-verifiable: inspect a representative pull for Tree ingestion truncated and measure any offending tree against the recorded caps.
  Triage source: first-line blame 2026-06-11 (`851c19591`, original README L2761).
