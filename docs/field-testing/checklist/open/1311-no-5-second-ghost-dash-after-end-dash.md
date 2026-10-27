status: active
issues: #1311

- **🆕 NEW — no 5-second ghost dash after End dash (#1311).** End a dash, dismiss the
  summary, and let the dash-controls sheet briefly reappear before the idle map. The session list
  and per-day stats must show only the ended dash, with no new zero-offer dash or start bubble.
  A real re-dash still starts after sustained Online screens, anchored to the first hint; an offer
  or task starts immediately. — Confirmed: 0/2
  - desk 10-18: the re-dash half was witnessed once: on 10-17 the dash ended on its summary at 18:21:45, a hint-only Online frame at 18:22:24 committed through `MODE_RESUME_COMMIT` at 18:22:32 as a dash started at 18:22:24, and six offers followed, so it was a real re-dash. No zero-offer ghost dash appears in the window. The ghost scenario itself (the dash-controls sheet re-shown after the summary) was not identified. Counter unchanged.
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): 5 summary-screen dash ends, no ghost session; the 10-18 18:30 14 s session was a real online / End dash pair. The ghost shape was not provoked. Counter unchanged.
