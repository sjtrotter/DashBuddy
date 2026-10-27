status: active
issues: #1000, #1013

- **🆕 NEW — #1000 (PR #1013) — a blown-through pickup still links its offer to the job.** Watch
  for a dash where a pickup is arrived-but-never-confirmed followed by a normal delivery
  completion. **Desk:** `SELECT linkedJobId, storeKey FROM offer_records WHERE offerHash =
  '<the accepted offer's hash>'` — non-null `linkedJobId` with a **null** `storeKey` is correct
  (the fail-null residual: no store leaderboard entry, dwell sample, or `milesToStore` from that
  job, by design). The PROJECTOR_VERSION 10 refold should have healed the 08-08 Zaxbys accept on
  first launch — check it shows linked in the next pull.
  - Confirmed: 1/2 (desk 08-24: the v10 refold healed the 08-08 Zaxbys accept on first launch —
    `anchorless job link: jobId=job-doordash-…-322 offerSeq=1521` in the 08-09 23:40 refold, and
    the pulled DB shows that offer with `linkedJobId` set and `storeKey` NULL, the correct
    fail-null shape. A LIVE blown-through pickup on-dash is the remaining half.)
  Triage source: first-line blame 2026-08-09 (`f41237874`, original README L830). Recorded first confirmation: desk 2026-08-24 (refold only) (existing evidence only).
