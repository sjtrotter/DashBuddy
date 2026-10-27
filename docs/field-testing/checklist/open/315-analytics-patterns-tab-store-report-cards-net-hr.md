status: active
section: §4 1/2 items older than 6 weeks / analytics
issues: #315, #773, #979, #763
confirmed: 1/2

- [active] **🆕 NEW — Analytics → Patterns tab: store report cards + net-$/hr heatmap (#315 H5 / PR).**
  The Patterns tab (Analytics hub, no period selector — it's lifetime/rate-based) now renders two real
  sections: (A) **store report cards** newest-visited-first, one per resolved store — chain name + a
  location chip (the running key, or a "location unknown" chip for chain-only entities), pickup/delivery
  counts, cash-inclusive gross/net, avg/median/p95 pickup dwell, and first/last-seen; and (B) a **7×24
  hour×day heatmap** of the driver's OWN realized net $/hr, best-hour callout on top. **How to tell it's
  working (open Analytics → Patterns after a few dashes):** the store cards should list the actual stores
  from recent dashes with running keys that match reality and **sane dwell numbers** (minutes, not
  seconds/hours — dwell = confirm − arrive on real pickups); the heatmap's warmer cells should fall on the
  hours/days you actually earn the most, cells you barely dashed should read as "too little time" (dim,
  distinct from a worked-but-earned-nothing cell), and the "best hour so far" line should feel right.
  Framing check: all copy is "YOUR net $/hr" — flag any wording that reads as a platform-pay claim.
  - Confirmed: 1/2 (the **data half**, desk 08-09, the 08-01→08-08 window: 14 `pickup_records`
    visits, dwell 0.0–71.1 min — all minutes-scale, none negative, none hours-scale — and the
    resolved store keys read as real places rather than placeholders, including the #773
    address-tier form `doordash|h-e-b|@5910`. That is the "running keys match reality + sane dwell"
    correctness half this item was held open for. The **UI half** — cards rendering, the heatmap's
    warm cells falling on hours that feel right, the best-hour line — still needs dev eyes, and
    note #979 has since reshaped this tab into a leaderboard, so the second confirmation should be
    taken against that item's description, not this one's card layout.)
  - **2026-07-13 desk pass:** data half sane — pickup dwell all minutes-scale
    (1.8–53 min across 15 visits). **2026-07-12 partial dev sighting (post-#763 build):** the heatmap section (B)
    renders well and the dev likes it; the store-cards section (A) was flagged for UX polish — too
    word-dense, and "p95" means nothing to a dasher (issue filed from this feedback). Data-correctness
    halves (running keys match reality, sane dwell) still unverified — this item stays open for those.
  Triage source: first-line blame 2026-07-10 (`743db59a5`, original README L1649). Recorded first confirmation: desk 2026-08-09 (data half) (existing evidence only).
