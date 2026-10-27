status: field-validated
issues: #935

- **🆕 NEW — a live drop-off sheet never shows as a receipt (#935).** Check that no
  `delivery_summary_collapsed` capture carries the customer card (`drop_off_bottom_sheet_container`
  or `complete_delivery_steps_button`). Every real receipt must still be recognized, and
  `expand_earnings` must still fire. — Confirmed: 2/2 (desk 10-18, 8.102.6: 11 `delivery_summary_collapsed` envelopes, none carries `drop_off_bottom_sheet_container` or `complete_delivery_steps_button`; 7 of 7 receipts expanded through `expand_earnings`; the 6 UNKNOWN receipt frames are mid-spin digit wheels.)
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): SECOND CONFIRMATION. 14 receipt envelopes, none carries `drop_off_bottom_sheet_container` or `complete_delivery_steps_button`; 4 of 4 collapsed receipts expanded through `expand_earnings`.
