status: active
issues: #1200

- **Trusted UNKNOWN captures (#1200):** Share UNKNOWN captures ON → after a dash with UNKNOWN screens the status
  reads `Captures shared: <queued> queued · <n> sent last run` and the server's cluster detail page shows a wireframe (0/2).
  UNKNOWN screens get no rule redaction — only the marker backstops — so a capture can still contain customer details,
  and the server operator can read them. Trusted installs are the operator's own device today.
  - Confirmed: 1/2 (device half, the 10-04 dash on `e35dee8f`: `envelopesPaired=70 Unpaired=0`, Held/Spooled/Posted 70/70/70, zero `not_trusted`; server half — a wireframe on a cluster from that dash — still to eyeball)
  Triage source: first-line blame 2026-10-04 (`285ea1c85`, original README L149).
