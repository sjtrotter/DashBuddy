status: active
issues: #916, #1226

- **Bubble lifecycle is visible in the shareable stream (#916):** after a dash, `grep -h 'Bubble' shareable.log`
  shows session start/end, `bubble post requested … canBubble=true` / `bubble post returned`, and activity
  create/start/stop/destroy; swiping the bubble's NOTIFICATION out of the shade logs `bubble notification
  removed (deleteIntent)` (dragging the chathead to the X does NOT — that is #1226). Use the sequence to
  assess whether a vanished bubble was dismissed, torn down, had `canBubble=false`, or the process died
  (an abrupt end without destroy is a clue, not proof).
  - Confirmed: 1/2 (desk 10-18: `shareable.log` carries 13 session start/end pairs, 140 `bubble post requested ... canBubble=true` / `returned` pairs, activity create/start/stop/destroy, and one `bubble notification removed (deleteIntent)` at 10-16 10:21:09.)
  Triage source: first-line blame 2026-10-05 (`6b8540319`, original README L96).
