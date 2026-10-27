## 2026-08-28 → 2026-09-05 (desk analysis of the 09-05 pull)

**Date:** 2026-09-05 · **Platform(s) tested:** DoorDash (app 8.93.7) · **Branch under test:**
pre-#1044 `master` (the 08-24 daytime build — the logs carry no `parseShortfall` lines anywhere
and `reportedEarnings` is still stamped `0.0`, both of which place the install before #1044 landed) ·
**Field conditions:** desk analysis of the 09-05 pull — 8 dashes, **every one ended
`early_offline`**; 13 deliveries, $220.09 folded, every row on the `OFFER_PAY` basis;
shopping-heavy (126 `pickup_shopping` and 38 `shopping_item` recognized frames); 744 captures,
236 of them UNKNOWN. Issues filed this analysis: **#1057**, **#1058**, **#1059**.

### Bugs

**1. The odometer gained 905.37 miles in 18.4 minutes on 09-03, and the gain is now permanent →
#1057.** Between `PICKUP_CONFIRMED` at 17:25 (odometer 1771.16) and `DELIVERY_ARRIVED` at 17:43
(odometer 2676.54) the reading moved +905.37 mi. Delivery row 1801 froze `realizedMiles` 907.41
and a `netProfit` of **−$302.73**; because the odometer total is persisted cumulatively, every
lifetime mileage figure now carries the +905 forward. There is **zero log signal** — no WARN, no
ERROR, nothing in 120,217 lines. *Hypothesis:* `OdometerRepository.processLocation` gates a fix on
`distanceMeters > 5` alone, with no accuracy or implied-speed bound, so a single bad location (a
cold re-acquire, a tower-derived fix) would be admitted as real distance; confirming it would mean
checking the accuracy/speed fields on the fix that produced the jump against the delta that was
admitted. Filed as #1057.

**2. Two UNKNOWN dropoff sheets shipped the customer's address and gate codes verbatim →
#1058.** Both are envelope-only leaks on the UNKNOWN path, and both are the #985 class recurring
on new surfaces. (a) An **alcohol pre-arrival variant** — 4 envelopes, 08-28 16:58
(`…UNKNOWN__172c76.json` and siblings) — where `address_line_1`/`address_line_2` masked correctly
but `address_subpremise_line` and `dasher_instruction_content_collapsed` did not; the redact
entries for that shape already exist, so this reads as a **recognition miss**, not a missing
mask. (b) An **id-less "Leave it at the door" sheet** — 2 envelopes, 08-30 16:30:48
(`…__1c7706`, `…__52eccf`) — with no view ids to anchor on at all, the same shape #985 closed for
the Timeline detail sheet. Filed together as #1058.

**3. The Persona selfie / ID-verification camera flow reaches UNKNOWN → #1059.** 11 envelopes on
08-27 (`…__36476b`). `sensitive.json5` covers the flow's **entry** screen only, so the capture
surfaces behind it fall through to the UNKNOWN path.

**4. The "Your Red Card" wallet screen reaches UNKNOWN → #1059.** 4 envelopes. Filed with item 3
as one identity-surfaces issue.

**5. `earnings_deposit` pushes store the "DoorDash Crimson account" clause — already #987.** No
new filing; recorded here as a second sighting.

**6. The 09-05 09:19 H-E-B dash under-reported by $6.75.** The receipt and the deposit both read
$42.45; the fold used the $35.70 offer quote. This is **expected on this build** — the money
parses were dead (#1029) and every row fell to `OFFER_PAY` — and is noted here so the post-#1050
pull can confirm the recovery on the same shape.

### Field UX context

**7. 37 × `Denied confirm_decline … (fail closed)`.** The quick-decline capability is *still*
ungranted on the device — three consecutive pulls now — so #577's auto-confirm remains inert in
the field. The gate is doing exactly what it should; the grant is the standing to-do before the
next dash.

### Open questions / investigations

**8. The 09-03 evening dash produced TWO deposits for ONE delivery** — $20.72 at 17:53 and $22.95
at 18:13. If the $20.72 was the settlement, then the `OFFER_PAY` estimate over-reported that drop
by $2.23 — #756 in miniature, on a single delivery rather than a stack. Would need the receipt (or
a driver attestation) to say which figure is the settlement.

**9. Is `earnings_deposit` an acceptable authoritative attestation, or does it sit behind the
payment-surface block?** Across the 8 dashes the deposit pushes matched the folded per-dash total
**to the cent on 7**, and the eighth is exactly the miss in item 6 — i.e. the surface both agrees
with us and catches us when we are wrong. The open question is whether reading it is compatible
with the Pledge's payment-surface block (#987 owns that boundary). Noted on #1035, which owns the
oracle question.

### Verification TODOs / system health

**10. Census: zero ERROR in 120,217 log lines.** `restarts=0`, `mappingFailures=0`,
`notifListenerDisconnects=0`. The #937 recognition-health signal never tripped — the UNKNOWN rate
sat at ≈18 %, at baseline — and all 136 UNKNOWN clicks carried a `screenTarget`. `shareable.log`
(705 INFO+ lines) came back clean on the PII scan.

**11. Recognized-surface redaction held.** 388 masks across the pull, and **zero** hex-suffixed
masks after `Apt`/`Suite`/`Unit` — #986/#934 and #1039 both working on fielded renders.
`sensitiveDropped=546` with **no** `dxdr_*` frame anywhere on the UNKNOWN path, so #924's
id-anchored arm held too.

**12. #991's recovery seam fired for real — 08-30 15:44:52.** A `speak()` returned −1, the
handler rebuilt the engine, and the engine re-initialized **417 ms** later; 34 of 35 utterances
spoke that day. This is the first live exercise of the ladder (the 08-24 pull only established
no-regression).

**13. Checklist outcomes this pull.** **#986/#934 VALIDATED 2/2 → retired** (item 11 above is its
second clean confirmation, on top of the 08-24 pull's 12/12 — the item leaves the checklist with
this entry). #991 → 1/2 (item 12). #924 → 1/2 (item 11 — the desk half). The unassign item
(#752/#736) → 1/2 (one `TASK_UNASSIGNED` at seq 1806, the $45.45 quote stayed unattributed, no
paid artifacts). Every #1029 / #1030 / #1032 / #1034 / #1036 / #1052 item is **not testable from
this pull** — the device ran the pre-#1044 build — and each keeps its count with that noted.
