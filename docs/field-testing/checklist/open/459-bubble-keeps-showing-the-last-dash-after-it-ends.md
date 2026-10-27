status: active
issues: #459, #473

- **Bubble keeps showing the last dash after it ends / after a crash (#459).**
  The bubble's chat + card stack used to go EMPTY after a dash ended (8b: collapse it >5s then
  reopen) or after a crash with no active dash (8a) — the fallback dash id was a volatile
  in-memory latch. It's now sourced durably from the event log (most-recent dash). On a dash:
  end a dash, collapse the bubble for >5s, reopen → working = the chat + completed cards of the
  just-finished dash are still shown (not empty); start the next dash → it switches to the new
  dash. Also force-stop/crash right after a dash and reopen → still shows the last dash. Broken =
  empty chat/cards after dash-end-then-reopen, or the wrong dash shown.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** after the dash ended the bubble **kept showing the last
    dash** (chat + completed cards), not empty — clean confirmation of the #473 durable fix. One more.
  Triage source: first-line blame 2026-06-13 (`273742fba`, original README L2609). Recorded first confirmation: 2026-06-14 (existing evidence only).
