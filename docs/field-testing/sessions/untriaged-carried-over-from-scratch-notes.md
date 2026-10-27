## Untriaged — carried over from scratch notes

- **Final dash-summary may be unreachable from the idle/offline screen.**
  Hypothesis: the idle-map offline screen shows *before* the dash summary, and
  there may be no way to reach the summary actions once on idle/offline. Needs a
  field repro + capture to confirm.
  - **Status:** Triaged → tracked as #279 (summary attribution fixed in PR; the
    "summary after the idle screen" ordering was the root cause). Field-validate
    via the #279 checklist item above.
