status: active
issues: #1442

- **One offer story, no score or verdict (#1442).**
  On a shift, check the pop-up title ("DoorDash offer" or "Uber offer") and compare its net, miles,
  minutes and hourly with the card and chat line. Listen for "Offer. [Store]. Estimated net: N dollars."
  followed by miles, minutes and hourly last; the dollars truncate the displayed cents. Check that a
  re-quote speaks only once and adds no second chat row. Accept/Decline buttons must still work.
  Check an add-on with Developer options > Add-on figures set to Job totals, then Only what it adds:
  all four surfaces must change together, with quoted added pay preserved. Check a blocked store:
  "Blocked store" appears after the store in text and voice, and in the pop-up note.
  No score, ring or verdict should appear on any offer surface, the HUD log, analytics offers or the
  Settings offer preview. Unknown pay/distance must not appear or be spoken as zero.
  - Issue: #1442. Confirmed: 0/2 (awaiting field validation)
