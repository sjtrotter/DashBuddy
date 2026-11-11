status: active
issues: #1119

- 🆕 NEW — the offer pool links a missed accept to its job (#1119 S2): an offer you accept with no tap the app saw waits until the first work screen that names its store, then becomes that job's offer; two offers at one store link neither and the ended shift reads "1 job could be from 2 offers"; offers never linked end the shift "Not taken". Watch every real accept against DoorDash's own counter at the shift end (Dash summary "Offers accepted N out of M") — Confirmed: 0/2
  - desk (1): zero `OFFER_TIMEOUT` events after the install; the shift end logs `OFFER_NOT_TAKEN` instead.
  - desk (2): every `OFFER_NOT_TAKEN` followed within 60 s by an offer-less job's `PICKUP_NAV_STARTED` is a missed link. Classify it: a tie (`ambiguousJobIds` set; count ties whose candidates share one offer hash), a store mismatch (the `#1119 no pooled offer at this store` DEBUG line), or a store-less first frame (the `#1119 store-less hold ends in a bare job` DEBUG line with its reason).
  - desk (3): the INFO link lines `#1119 offer … linked to job … (left Ns before …)`; a link older than a few minutes is a stale-link suspect.
  - desk (4): a linked single-drop job with a receipt shows `RECEIPT_OBSERVED` scope `PROVEN_SINGLE` and receipt pay; a linked drop with no receipt keeps the `OFFER_PAY` estimate.
  - desk (5): the ended shift page shows "1 job could be from N offers" for each tie, and tapping it lands on the job.
  - desk (6): `SELECT max(length(stateJson)) FROM app_state_snapshots` (the pool rides every snapshot; the 60-entry synthetic bound is about 150 KB).
  - desk (7, council round 6): count demotions (DEBUG `pooled via … demoted by the decline sheet`), demotion ties (the tie WARN's `(N demoted)`), card-back exceptions (DEBUG `card back after the decline sheet`), and holds that hit a cap (DEBUG `store-less hold ends in a bare job (time cap|frame cap, …)`). A demoted offer ends the shift `OFFER_DECLINED` with `declineEvidence` `SHEET_WINDOW`.
