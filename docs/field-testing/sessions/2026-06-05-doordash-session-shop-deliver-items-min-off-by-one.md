## 2026-06-05 — DoorDash session (Shop & Deliver, items/min off-by-one)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (post-#297 merge era). Data archived to
  `logs/2026/06/04/` and `logs/2026/06/05/` (`captures/`, `app.log` + rotations,
  `db/dashbuddy-v2.db`).
- **Field conditions:** multiple Shop & Deliver orders. Developer observation:
  on Shop & Deliver, the **items/min count finished one short** — at the end of
  shopping the HUD showed one less than the full item count even though shopping
  was actually done.

### Bugs

#### 1. Shop & Deliver items/min (and `shop X/total`) caps at `total − 1` — terminal `To shop (0)` frame deduped away

- **Validated against the data — on 06-04/06-05 every shop order ends at `remaining = 1`, never `0`:**
  | Session | Order size | Last *processed* `pickup_shopping` frame |
  |---|---|---|
  | 06-04 ~20:18 | 20 items | `shopped=19 / remaining=1` |
  | 06-05 ~18:32 | 32 items | `shopped=31 / remaining=1` |
  | 06-05 ~20:05 | 15 items | `shopped=14 / remaining=1` |

  (from `db/dashbuddy-v2.db` `observations`, `ruleId = doordash.screen.pickup_shopping`.)
- **But 06-03 DID record the terminal frame** (`remaining = 0`): order #1 hit
  `shopped=21 / remaining=0` at 17:28:39, order #2 hit `shopped=46 / remaining=0`
  at 21:35:26. So this is **intermittent, not an inherent gap** — the `To shop (0)`
  frame *can* be and *was* observed; on 06-04/05 it was dropped. (On 06-05 the log
  even shows `pickup_shopping` frames classified *after* the last recorded one at
  20:05:19 that never reached the `observations` table — i.e. **dropped**, not absent.)
- **Root cause — the post-classification dedup discards count-only changes.**
  `AccessibilityPipeline` suppresses an observation when its identity equals the
  previously-emitted one (`AccessibilityPipeline.kt:134`, `identity == lastIdentity`).
  Identity = `ObservationIdentity("screen", target, parsed.dedupeHash(), modeHint)`
  (`ObservationIdentity.kt:29`), and **`TaskFields.dedupeHash()` excludes
  `itemsRemaining` / `itemsShopped`** (`ParsedFields.kt` — it hashes only
  phase / subFlow / storeName / arrivalConfirmed). So *every* `pickup_shopping`
  frame shares one identity regardless of progress; a frame that differs only by
  item count (including the decisive `To shop (0)` / `Done(total)`) is treated as a
  duplicate and dropped. It's intermittent because an interleaving different-identity
  screen (`shopping_item`) sometimes breaks the dedup chain right before the (0)
  frame (06-03) and sometimes doesn't (06-04/05).
- **Why the metric shows the symptom (code, `FlowCardItem.kt:420-428`):** the
  Shop & Deliver tertiary line renders `shop $shopped/$total` and `%.1f/min` where
  `shopped = itemsShopped`, `total = shopped + itemsRemaining`,
  `perMin = shopped / elapsedMin`. The per-frame parse is correct; because the
  `→ total` frame is deduped, `itemsShopped` freezes at `total − 1` and the pace is
  computed on `total − 1`.
- **Direction (hypothesis):** include the shopping item counts in
  `TaskFields.dedupeHash()` so each shopping-progress state is a distinct identity
  and the dedup never collapses count changes — which makes the terminal frame
  (and every intermediate count) record reliably. Lower-risk than a "finalize on
  completion" heuristic, and fixes the live card too. To re-confirm on a future
  dash: watch that the shop card reaches `total/total` at the end.
