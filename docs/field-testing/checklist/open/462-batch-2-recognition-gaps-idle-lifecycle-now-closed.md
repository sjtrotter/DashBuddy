status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #462
confirmed: 0/2

- [review] **Batch-2 recognition gaps — idle/lifecycle (#462, now CLOSED).** The last UNKNOWN screens are
  recognized: the **"Navigate to zone / We'll look for orders along the way / Spot saved until …"**
  repositioning card (now a recognized idle screen, and the "Spot saved until HH:MM" countdown
  should populate); the **scheduled-dash slot picker** ("Start time / End Time"); the **dropoff
  reminder** ("Deliver to door of … / Got it"); the **pickup QR-confirm** ("Confirm that the code
  was scanned"); and the **help/support menu** ("Get an account checkup / Dashing FAQs"). Working =
  these show recognized (not UNKNOWN); the repositioning card shows a spot-save countdown. Broken =
  any still UNKNOWN, or the navigate-to-zone card mis-reads the idle state.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect the listed idle/lifecycle captures and compare repositioning countdown parses with their source text.
  Triage source: first-line blame 2026-06-13 (`1a2bf6bf3`, original README L2575).
