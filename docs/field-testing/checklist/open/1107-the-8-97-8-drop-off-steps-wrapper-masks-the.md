status: active
issues: #1107, #1110

- **🆕 NEW — the 8.97.8 "Drop off steps" wrapper masks the customer's instruction body (#1107 — PR
  #1110).** The wrapper page 8.97.8 routes leave-at-door through shipped a gate code, cross streets
  and a landmark raw to UNKNOWN capture on 09-13 (that entry's Bug 1). It is now recognized as
  `dropoff_step_instructions` with its `description_text_view` body plain-masked whole (and the id on
  `ID_MARKERS`). **On-dash:** nothing should change — no bubble, no narration, no state move; the rule
  declares no flow. **Desk:** the frames land in `dropoff_step_instructions/`, never `UNKNOWN/`; on an
  order that carries a customer instruction, the `description_text_view` value must read a PLAIN
  `[redacted]` while `step_title` / `step_description` stay raw (DoorDash's own vocabulary).
  - Issue: #1107. Confirmed: 0/2
    - desk 09-20: RECOGNITION HALF CONFIRMED — 5 `dropoff_step_instructions` envelopes (09-19), none in
      `UNKNOWN/`, first sighting of the folder. **Redaction half UNEXERCISED:** none of the five orders
      rendered a `description_text_view` node at all, so the `plainMask` never had anything to mask. It
      needs a drop whose customer actually left an instruction body.
    - desk 09-21: UNEXERCISED — the `dropoff_step_instructions/` folder is absent from the pull; the
      "Drop off steps" wrapper page did not render at all on DoorDash **8.98.5** this dash, so
      neither half was touched. Worth checking whether 8.98.5 still routes leave-at-door through it.
    - desk 09-26: UNEXERCISED — no `dropoff_step_instructions` folder and no "Drop off steps" /
      `description_text_view` node anywhere in the pull, on 8.98.5 or 8.99.20. The wrapper may be gone on these builds.
    - desk 09-27: UNEXERCISED — no `dropoff_step_instructions` folder, 0 `description_text_view` / "Drop off steps"
      anywhere (8.99.20, a whole dash).
    - desk 09-29: UNEXERCISED — 0 `dropoff_step_instructions` / `description_text_view` / "Drop off steps" in 702
      envelopes (8.99.20).
  Triage source: first-line blame 2026-09-20 (`74239b425`, original README L368).
