status: field-validated
section: §5 Field-validated / retired
issues: #1069
confirmed: 2/2

- [field-validated] **Same-store consecutive offers are two offers (#1069):** after a dash with two H-E-B (same store)
  offers back to back, `app_events` holds TWO `OFFER_RECEIVED` rows with two `offerHash`es, the first
  offer's outcome reads DECLINED/TIMEOUT on its own row (never 'enriched' into the second), and the
  bubble spoke both. — KNOWN one-time effect: an offer restored from a pre-#1069 snapshot (install mid-dash / crash-recovery across the upgrade) keeps its old store key, so its next re-render logs one spurious `OFFER_TIMEOUT "Replaced by new offer"` + re-speak — not a regression.
  - Confirmed: 2/2 (desk 10-18: 10-16 16:11 to 16:14, eight H-E-B offers back to back, eight `offerHash`es, each with its own `OFFER_DECLINED` or `OFFER_ACCEPTED` row, eight `Tts: speaking` lines.)
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): SECOND CONFIRMATION. H-E-B back to back three times on 10-20 18:26 to 18:27, and twice each on 10-25 15:25/15:26 and 16:19/16:20: each offer has its own `offerHash`, its own outcome row and its own spoken read.
  Triage source: first-line blame 2026-10-05 (`5ae8d68ea`, original README L72).
