status: active
section: §1 Must-watch (dev-confirmed 2026-10-07 — read these first)
issues: #1312, #301
confirmed: 0/2

- [active] 🆕 NEW — unassigning the LAST order of a job (DoorDash returns to "Finding offers") closes the job: the next accepted offer shows as a new job in the drill-down, not as an add-on to the abandoned one (#1312) — Confirmed: 0/2
  - desk 10-18 (pull of 10-14 to 10-18, build `09c70dba`): not exercised; no `TASK_UNASSIGNED` in the window.
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): not exercised as written. On 10-21 15:43 to 15:45 an H-E-B order was probably removed through support chat; no `TASK_UNASSIGNED` fired and the dash end stamped a phantom `PICKUP_CONFIRMED`. Evidence on #301.
