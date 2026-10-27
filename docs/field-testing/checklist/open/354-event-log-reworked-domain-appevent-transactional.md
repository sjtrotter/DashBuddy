status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / state machine
issues: #354, #300, #119, #382
confirmed: 0/2

- [review] **Event log reworked: domain AppEvent + transactional insert + obs-derived timestamps (#354/#300/#119, PR #382).**
  The bubble HUD's completed-card stack now renders from payloads decoded at the repository
  (was: Gson inside the mapper), `app_events.occurredAt` is the observation timestamp (was: wall
  clock at execution), and each event row + its idempotency mark commit in one transaction. To
  check during a normal dash: (a) the **completed cards** (Awaiting → Offer → Pickup → Delivery →
  PostTask) still populate with store names, pay, and evaluation chips exactly as before;
  (b) card **timestamps/durations** look right (obs-derived times should match what you saw on
  screen, not when the DB write happened); (c) after any crash/restart mid-dash, **no duplicate
  events** — the card stack shouldn't show a phase twice (this was #300's duplicate
  DELIVERY_CONFIRMED). Post-dash, a quick `app_events` query confirming one row per phase
  boundary seals it.
  - Confirmed: 0/2.
  desk: desk-verifiable: compare observation timestamps, app_events and idempotency records on replay; completed-card rendering remains a device check.
  Triage source: first-line blame 2026-06-11 (`04afd29c6`, original README L2792).
