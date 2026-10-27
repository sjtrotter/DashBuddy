## 2026-07-30 (afternoon) — BROWSE-SESSION pull (pull2): DoorDash's NEW points-based rating system discovered, recognized, and re-parsed same-day

**Date:** 2026-07-30 · **Platform(s):** DoorDash (browse only — NO dashes in this slice) · **Branch
under test:** master @ `ff99eb8f` device build · **Field conditions:** the dev deliberately browsed
the redesigned Ratings area to capture the new system. Pull:
`~/dashbuddy/logs/2026/07/30/pull2/` (75 captures, 54 UNKNOWN windows); device purged post-pull.

**The finding:** DoorDash replaced its ratings layout with a **points-based overall rating** —
`Overall rating <N>` (e.g. 73/100 pts), Silver/Gold/Platinum tiers at 55/75/85 pts, and six factor
rows each rendering `label → value → "N of M points" → band` (incl. a NEW **Quality rate** factor),
all **id-less Compose** — the old `textView_title`-anchored parse read null on every field (the
residual PR #926 documented became the fielded reality).

**Design rulings (dev, recorded on #962):** (1) tiers are NOT modeled — DoorDash's gamification is
platform vocabulary and mission-inverted to mirror; two plain FACTS only (`overallRatingPoints`,
`tierLabel`). (2) **No code may ever DEPEND on ratings data** — capture is opportunistic (only
updates when the dasher browses Ratings; may be absent/stale forever); protect-stats is a
*behavior*, not a calculation, and reads no rating. Enforced, not aspirational:
`RatingsSnapshotIsDisplayOnlyTest` source-scans both consumption routes. (3) Trend history — the
one version of ratings data the platform doesn't show the dasher — filed dev-gated as **#964**.

**Shipped same-day (PR #963, merged):** parse re-anchored via legacy-first coalesce (legacy goldens
byte-identical; the drawer-vs-hub tier trap closed with a scoped bind + negative test), 3 additive
`RatingsSnapshot` facts + a plain display row, 4 recognize-only rules (rewards board,
overall-rating explainer, per-order feedback drill-down, pause-offers sheet), 10 corpus fixtures.
**Ride-along Pledge fix:** the sweep caught a fielded leak — the on-time per-order drill-down
renders `Delivery to <customer first name>`, a THIRD conjugation missing from
`CustomerTextMarkers` — added to the SSOT (zero corpus hits; the surface stays deliberately
UNKNOWN and is now scrubbed). Follow-up filed: **#965** (the legacy `deliveriesLast30Days` anchor
never matched — parenthesized label vs fielded plain).
