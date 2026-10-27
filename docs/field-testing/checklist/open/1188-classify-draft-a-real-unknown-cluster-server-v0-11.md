status: active
issues: #1188

- **Classify & draft a real UNKNOWN cluster (#1188, server v0.11.0):** on the VPN dashboard open a cluster from today's
  dash that has a trusted capture → "Classify & draft" → pick the class, mark two id anchors + the parse fields → Preview
  shows a JSON5 draft (or a refusal naming the weak node) → Save (TOTP) → the detail page shows the class chip and the
  draft link; paste the draft into the platform's surface file and run `AllMatchersSuite` — it should recognise the
  frame. Working = a draft that compiles and recognises its own capture on the first try for an id-anchored screen.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-04 (`2e089bd25`, original README L142).
