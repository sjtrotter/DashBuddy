status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #605
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — pausing during/after a delivery shows exactly one "Dash Paused!", no phantom "resumed" flap (#605). CONFIRM ON DASH.**
  DoorDash's pause sheet is a modal on top of the just-completed delivery summary, so accessibility
  frames alternate `dash_paused` (paused) ↔ `delivery_summary_collapsed` (online) for a few seconds.
  Before #605 the state machine flipped mode on every edge — re-minting `DASH_PAUSED` + "Dash Paused!"
  on each online→paused and firing a spurious "Session resumed (grace)" card on each paused→online,
  while the dasher was still paused (06-28 15:04:32–38 receipt). Now a screen-implied resume OUT of
  Paused is **graced** (8 s): an online frame while paused arms a pending resume instead of flipping;
  a paused frame within the window cancels it; only sustained online past the grace (or an incoming
  offer) actually commits the resume. **Confirm on dash: 0/2 —** pause once during/right after a
  delivery and watch the bubble/notification stream: it should show **exactly one** "Dash Paused!"
  and **no** "Session resumed (grace)" card while the pause sheet is still up. Then tap **Resume** for
  real — the resume should surface within ~8 s (a known ≤8 s lag; if it feels too slow on-dash, the
  follow-up is wiring the `resume_dash` click to commit instantly). Broken = two or more "Dash Paused!"
  in a row, or a "resumed" card appearing while you're still paused.
  Triage source: first-line blame 2026-07-03 (`127efa96d`, original README L2092).
