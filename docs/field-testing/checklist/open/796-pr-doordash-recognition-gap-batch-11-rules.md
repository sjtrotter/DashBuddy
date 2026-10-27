status: active
section: §4 1/2 items older than 6 weeks / recognition
issues: #796, #837, #806, #815, #865, #910, #926
confirmed: 1/2

- [active] **🆕 NEW — #796 / PR #837 — DoorDash recognition-gap batch (11 rules).** Dropoff issue
  menu/resolution, task feedback, receipt photo, barcode confirm/failed, navigate-to-zone loading,
  and the Quality Rate / Dasher Rewards family now recognize (all recognize-only; zero state
  impact expected).
  **What to watch:** nothing on-dash. **Desk:** the UNKNOWN family census should drop these
  families to ~zero; `dropoff_issue_resolution` envelopes must mask the fused header as
  `For [redacted:<4hex>]` (marker kept, name+store masked). The known residual: the
  pickup-card-over-zone COMPOUND frame stays UNKNOWN deliberately (PII-bearing; follow-up gap
  noted on #796) — its customer name is covered by the #806/#815 UNKNOWN scrub, verify it masks.
  - Confirmed: 1/2 (desk 07-26: 8 of the 11 families recognize with ZERO UNKNOWN siblings
    (task_feedback, receipt photo, barcode confirm/failed, zone loading, rate detail, orders list,
    ratings, qr_confirm). Three residual gaps confirmed still UNKNOWN — acceptance-rate detail,
    scrolled last-100 list, customer-rating detail — filed as #865. **Desk 07-30: the residual's
    "verify it masks" check FAILED** — an UNKNOWN pre-render sheet shipped `user_name_label`
    ='Delivery for' + a RAW `user_name` sibling (18:14:06); the #806 prefix scan structurally
    can't reach a bare-name node. Field-confirmed as #910's V5; the #910 build's node-ID backstop
    is the fix. The #865-side trio was separately closed by PR #926's ratings broadenings.)
  Triage source: first-line blame 2026-07-23 (`722aa4c19`, original README L1357). Recorded first confirmation: desk 2026-07-26 (partial families) (existing evidence only).
