## 2026-11-06 (desk analysis of the 11-06 pull: dashes 11-01 and 11-05; DoorDash 8.106.9 breaks the offline home)

- **Date:** 2026-11-06 (pull). Dashes on 11-01 (DoorDash 8.105.5) and 11-05 (8.106.9, first seen that day), plus two short test dashes on 11-06 morning to capture the 8.106.9 online home.
- **Platform(s) tested:** DoorDash; one short Uber browse on 11-04.
- **Branch under test:** 0.230.0+29ced9ec.dirty (11-01), 0.230.0+4484b073.dirty (11-03, no dash), 0.230.0+0c94432f.dirty (from 11-04; the #1522 census enrol fix, built in a scratch worktree, not reproducible from a ref).
- **Field conditions:** three shifts with a summary screen (11-01 $41.95, 11-05 $43.09 and $17.40); one $0 shift on 11-05 ended without a summary.

### Bugs

1. **A shift without a summary never ends on DoorDash 8.106.9 (#1527, fix in PR #1531).** 8.106.9 renamed the offline home's earnings-mode control "Earn by time" (it was "Earnings Mode Switcher", with "Time mode on/off") and shows it on the online home too, so `idle_map` matched 9 frames on 8.105.5 and 0 on 8.106.9. The $0 shift started 11-05 17:49:49 and ended in DoorDash about 18:00; the app stayed online overnight. The read model counts about 10 online minutes (bounded by the last event), but GPS added about 44 miles after the shift's last offer, and the 11-06 morning test dashes folded into the same open shift. The 8.106.9 `offline_screen` ids are on both homes; the main button's whole label tells them apart ("Dash" offline, "Return to Dash" online, under "You're dashing now"). The open shift needs a correction once the fix is installed.
2. **A two-order shop offer is scored as a quick pickup (#1528).** "Shop for 2 orders (25 items, ~32 min)" did not match the `Shop for N items` patterns, so the offer stored 0 items, not a shop, one order; the estimate was 16.8 min and $55.95/hr against about 68 min and $15/hr realized. The same wording appears on 11-01 under 8.105.5, so it is not new with 8.106.9.
3. **A drop logged as arrived a mile before the door (#1529, exploratory).** The first drop of the same job logged DELIVERY_ARRIVED 42 s after pickup at the same odometer, from a `dropoff_handoff` frame that showed "Mark as delivered" while the dasher was still driving. Its leg reads 0.0 mi (about 1 mi lost) and its dwell about 11 min instead of about 5. Hypothesis: the #603 assumption (the completion button appears only at the door) does not hold for this drop type or this version; needs a second 8.106.9 hand-to-customer drop.
4. **One census batch rejected as bad_request (#1530).** 30 skeletons lost on 11-04 12:43, right after the Uber browse. Hypothesis: a very large Uber skeleton fails server validation; the server log will tell.
5. **Gas price fetch fails with HTTP 403 since 11-01 (no issue, by the dev's call).** 30 of 30 tries. Hypothesis: the three builds were made in worktrees without `local.properties`, so the EIA key compiled in blank. Install from the main checkout or CI.

### Field UX context

- **DoorDash 8.106.9 homes.** Offline: side menu, "Earn by time", "This week" pill, "Dash" button. Online: the same plus "You're dashing now" and "Return to Dash" (capital D). An offline home still drawing can lack its button.

### Verification TODOs

- Money: all three summary shifts reconcile to the cent. No pipeline, state machine, snapshot or projector errors; no restarts. PII sweep of 226 UNKNOWN captures clean.
- Checklist (desk evidence, no count changes): #1058 check 1 clean and the alcohol card recognised and masked 4 times on 11-01; #919 15 chat envelopes masked, backstop not reached; #1458 data half consistent (job 1148's two rows landed 4 s after the last drop), the headline needs the dev's eyes; #1108, #1310, #1360, #1333 odometer, #1388, the heads-up Decline taps and #1311 consistent; #1134, #1135, the cash tip sheet and #1315 not exercised.
