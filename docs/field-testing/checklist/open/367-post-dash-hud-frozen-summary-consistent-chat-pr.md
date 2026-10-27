status: review
issues: #367

- **Post-dash HUD: frozen summary + consistent chat (#367, PR pending).**
  Two visible fixes after a dash ends: (a) the "Last session" Duration on the idle bubble is
  now FROZEN (it used to keep growing while you sat idle — check it shows the real dash length
  and stays put); (b) the chat ticker and the card stack now both show the finished dash
  (the ticker used to go empty the moment the dash ended while cards stayed). Also: platform
  toggles/screens now stop collecting flows while the app is backgrounded — no user-visible
  change expected, just confirm nothing looks stale when foregrounding.
  - Confirmed: 0/2.
  desk: needs field: watch the finished dash's duration stay frozen and confirm chat/card agreement after session end.
  Triage source: first-line blame 2026-06-11 (`851c19591`, original README L2753).
