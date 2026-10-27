## 2026-07-12 — post-install device look (no dash; first hands-on with the post-#763 build)

- **Date:** 2026-07-12
- **Platform(s) tested:** none (device UI review only — no dash run)
- **Branch under test:** `master` at `76966bc1` (post-#763 merge; carries the whole 07-11→12
  wave incl. Room v11→v14 migrations + projector v6 refold on first launch)
- **Field conditions:** dev installed the build and reviewed the bubble idle card and the
  Analytics → Patterns tab on-device.

### Field UX context

1. **Patterns tab, heatmap section: liked.** The net-$/hr hour×day graph ("the net$/hr thingy
   graph") reads well as-is. (Partial sighting noted on the #315 H5 checklist item — the
   data-correctness half still needs verification.)
2. **Patterns tab, store-cards section: needs work.** Too word-dense for a glance surface, and
   the statistical vocabulary doesn't land — "p95" means nothing to the dev, and it won't mean
   anything to a dasher either. One possibility: fewer numbers per card with plain-language
   labels (e.g. "usual wait" / "worst waits" instead of avg/median/p95), progressive disclosure
   for the rest. Issue filed from this feedback (dasher-friendly store-card copy + density).
   - **Status:** Open — filed #765 (2026-07-12).
3. **#722 gas control validated (first confirmation).** Dev exercised the idle-card gas control:
   "works fine." Checklist item moved to 1/2.
4. **#728 reconfirmed.** Dev restated the direction: gas control and vehicle control should be
   **separate cards**, and the idle-bubble layout overall "could use some polish." #728 (already
   build-ready) is promoted to the front of the build queue.
   - **Status:** Shipped in #767 (2026-07-12, same day) — two full-width cards, ≥48dp targets;
     checklist item added (0/2).
