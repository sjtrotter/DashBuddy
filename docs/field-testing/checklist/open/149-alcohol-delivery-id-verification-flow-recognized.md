status: review
issues: #149

- **Alcohol delivery ID-verification flow recognized + arrival timing (#149).**
  On an alcohol dropoff, the ID-check flow is now recognized (previously
  UNKNOWN). Two things to confirm:
    - The flow screens are recognized (no longer UNKNOWN): the intro/legal screen
      ("Scan and verify the recipient's ID" / "Agree and continue") and the scan
      screen ("ID barcode scan" / "Start scan").
    - **Arrival fires on the SCAN screen, not the intro.** Tapping into the flow
      and landing on the intro should *not* mark the dropoff arrived (guards an
      accidental tap); advancing to the barcode-scan screen *should* mark arrival.
    - Watch that no screen in this flow exposes the customer's actual ID data
      (name/DOB/license #). If one does, it must be blocked as **sensitive**, not
      recognized — flag it for a redaction + sensitive rule.
  - Confirmed: 0/2.
  desk: needs field: an alcohol delivery must exercise ID-check and arrival timing; then inspect recognized instructions and blocked scanner output.
  Triage source: first-line blame 2026-06-03 (`fe0246df3`, original README L2930).
