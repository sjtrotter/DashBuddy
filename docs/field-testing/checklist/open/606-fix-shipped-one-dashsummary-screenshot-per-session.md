status: active
issues: #606, #433

- **🔧 FIX SHIPPED — one DashSummary screenshot per session end, and offer screenshots are named
  with real pay (#606). CONFIRM ON DASH.** Two independent owners were both saving a
  "DashSummary - \<earnings\>" screenshot at session end: the `dash_summary` rule effect
  (deduped + throttled, fires on recognition) and EffectMap's own SESSION_ENDED commit — the
  commit-side add had a null `effectKey`, bypassing both `effects_fired` and the throttle, so
  every dash produced two near-identical dash-summary captures ~2.5s apart (the
  `AUTHORITATIVE_GRACE_MS` window). The commit-side add is now deleted; the rule owns the shot.
  Separately, all 46 offer screenshots that week saved as the literal filename
  `Offer - {storeName}.png` — the prefix template referenced `storeName`, which lives inside the
  offer's per-order `orders[]` array, never at the rule's top level where `{field}` templates
  resolve, so it never interpolated. The prefix now uses `{payAmount}` (a top-level, always-parsed
  field), so filenames read e.g. `Offer - 26.75.png`. A new lint (`ParseOutputGoldenTest`, the
  #433 family) now catches this whole class going forward: any `{field}` template in a rule's
  screenshot/bubble/log effect args that leaves a literal `{field}` in the saved string on ≥1
  corpus frame (i.e. fails to interpolate) fails the build. **Confirm on dash: 0/2 —** after a
  session ends, `Pictures/DashBuddy` should contain exactly **one** `DashSummary - <earnings>.png`
  for that end (not two ~2.5s apart), and offer screenshots from that dash should be named
  `Offer - <pay>.png` (a dollar amount), never the literal `Offer - {storeName}.png`. (Trailing
  zeros drop — `Offer - 26.7.png` for a $26.70 offer, `Offer - 5.0.png` for $5.00, is correct;
  only a literal `{storeName}`/`{payAmount}` token is broken.) Broken = a duplicate dash-summary
  pair, or any offer screenshot with a literal `{storeName}` in the filename.
  Triage source: first-line blame 2026-07-03 (`8a6122464`, original README L2138).
