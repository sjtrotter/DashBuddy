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
    - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): data half consistent. The 11-07 two-drop job committed both rows about 10 s after the last photo (14:42:00); the 11-11 two-drop Dash Along the Way job committed about 11 s after the photo (13:49:30). The two `#1458 … left open` lines are single-drop jobs whose drop a grace retired while the dasher was off the drop page (session log, bug 3; #1611). The headline needs the dev's eyes. Counter unchanged.
