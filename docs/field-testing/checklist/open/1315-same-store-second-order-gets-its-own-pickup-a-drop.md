status: active
issues: #1315

- **🆕 NEW — same-store second order gets its own pickup; a drop confirmed
  through the completion steps closes the job (#1315).** — Confirmed: 0/2
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): completion-steps half seen: on 10-23 two drops (Panda Express 17:37 to 17:48, Taco Bell 18:02 to 18:11) completed through "Complete delivery steps" with no arrival frame; the `complete_delivery` tap closed both jobs and the next accepts minted new jobs. The same-store half was not exercised. Counter unchanged.
