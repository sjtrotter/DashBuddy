status: active
issues: #1413

- **The Play build does not read an Uber offer pop-up (#1413).**
  On the Play build from internal testing, with Uber Driver enabled, go online in Uber Driver and wait for one
  offer. gig.log does not read the pop-up: no offer card or spoken offer comes from it (a notification Uber posts
  for the offer is a separate path; note whether one was read). Uber Driver's other screens (online, trip, shift
  summary) are still recognized. This is the expected limit the card
  states, recorded as an observation: a Play build that DOES read the pop-up means the limit is gone and the card
  and listing are wrong; note the Uber Driver version and report it. The developer decided not to run a dedicated
  flag-off test before release (2026-11-09); this item stands in for it.
  - Issue: #1413. Confirmed: 0/2 (awaiting field validation)
