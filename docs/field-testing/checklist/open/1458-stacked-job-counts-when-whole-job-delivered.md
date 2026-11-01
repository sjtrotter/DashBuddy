status: must-watch
issues: #1458

- **A stacked job counts when the whole job is delivered (#1458):** on a stacked or add-on job, out of zone,
  the bubble headline does not move at the intermediate drops, unless DoorDash's own total moves. It rises by
  the job's total within about 10 s of the last drop's photo, or of the screen after it.
  - If it rises only at the next accepted offer, or at the shift end, the job was not judged complete. Note
    which of these was true: an order never showed its drop; a drop never showed its arrival or a tap; the
    last drop ended with an offer card on screen. The pull's DEBUG log names the reason (a `#1458 job … left
    open` line, tag `StateMachine`). Report how often it happens.
  - With an unassigned order, the figure rises by the delivered drops' share only.
  - In zone, it follows DoorDash's total. — Confirmed: 0/2
