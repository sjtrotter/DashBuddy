status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #463, #485
confirmed: 0/2

- [review] **Sensitive model corrected — block the DASHER's data + ID/signature IMAGES; HASH customers (#463/#485).**
  The privacy rule is now: block the **dasher's own** sensitive screens (DasherDirect Savings /
  banking — plaintext balances) and the **document-image capture surfaces** (the license-SCAN
  camera + the SIGNATURE pad/handoff), regardless of whose; but **recognize** the alcohol
  **ID-CHECK instruction** ("Identity verification … matches the recipient") and the alcohol
  **arrival card**, with the customer name/address **hashed** (we hash customers, we don't block
  them). On a dash:
  - **Banking:** DasherDirect → Savings, small transfer → NO capture (log shows the sensitive gate).
  - **Alcohol delivery (21+):** the license-SCANNER and the SIGNATURE pad screens produce **NO
    capture**; but the ID-check instruction + the arrival card + the verify-step screens **recognize
    normally**, and the customer name appears only as a HASH (never raw) in any log/capture.
  Broken = a Savings/Transfer balance OR a license-scan/signature screen shows in captures/; OR the
  alcohol arrival/ID-check stays UNKNOWN / mis-steps the flow; OR a raw customer name/address
  appears anywhere.
  - Confirmed: 0/2
  desk: desk-verifiable: replay the named sensitive and instruction-screen families through the capture gate, checking dropped image surfaces and permitted masked instructions.
  Triage source: first-line blame 2026-06-13 (`1a2bf6bf3`, original README L2649).
