status: active
issues: #1119, #1334

- 🆕 NEW — accepting an ORDINARY offer card while the app still shows a job open closes that job and starts a new one: the drill-down shows two jobs, the old one's un-arrived drops read "unassigned" and an arrived drop reads completed at the accept time; accepting an "Add to route" card still joins the running job (#1119 slice 1, PR #1334) — Confirmed: 0/2
  - desk 10-18: not exercised; every accept in the pull came after the previous job closed.
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): not exercised; no standalone accept while a job was open.
