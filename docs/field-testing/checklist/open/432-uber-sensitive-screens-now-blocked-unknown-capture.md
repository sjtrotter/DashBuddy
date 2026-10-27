status: review
issues: #432

- **Uber sensitive screens now blocked + UNKNOWN-capture scrub (#432).**
  Uber finally has matcher-layer sensitive rules (wallet / Instant Pay / cash-out / bank /
  identity). On a dash, briefly open Uber's earnings/wallet area: working = the app treats it
  as sensitive (no capture, no state change, log shows the sensitive gate) and normal offer
  recognition is unaffected. Also new: UNKNOWN screens whose text contains sensitive markers
  are no longer captured for triage (PipelineStats logs `unknownScrubbed`), and the pipeline
  drops all frames until rulesets finish loading at startup. Broken = an Uber wallet screen
  shows up in captures/, or offer screens misclassify as sensitive (keywords too broad —
  capture the screen text).
  - Confirmed: 0/2
  desk: desk-verifiable: correlate exercised Uber sensitive-screen captures/markers with gate drops and unchanged normal offer recognition.
  Triage source: first-line blame 2026-06-12 (`0d8456a58`, original README L2716).
