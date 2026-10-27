status: review
issues: #728, #767, #722

- **🆕 NEW — idle bubble: gas + vehicle are now two separate full-width cards (#728 / PR #767).**
  The cramped one-row `JustInTimeActions` (the layout the dev called "a nightmare to try to operate")
  is now a stacked pair of full-width cards on the idle dashboard card: one for the #722 mode-adaptive
  gas control, one for Vehicle — every touch target ≥48dp (stepper −/+, refresh, "Resume auto" chip,
  and the whole vehicle card is the tap surface).
  **How to tell it's working (on-device, no dash needed):** open the bubble while OFFLINE — two
  visually distinct cards instead of one crowded row; every control comfortably tappable with a thumb;
  gas behavior itself unchanged (AUTO refresh / take-manual / Resume auto per the #722 item above);
  Vehicle tap still opens Personal Economy. Watch for: content clipping on the MANUAL gas row (known
  residual on very narrow windows — strictly better than before, but capture it if seen), and any
  visual double-ripple or dead tap zone on the vehicle card.
  - Confirmed: 0/2
  desk: needs field: inspect and operate the idle gas/vehicle cards to assess touch targets and mode transitions.
  Triage source: first-line blame 2026-07-12 (`e880c6457`, original README L1548).
