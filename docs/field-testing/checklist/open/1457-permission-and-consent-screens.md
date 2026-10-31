status: active
issues: #1457

- **Permission and consent screens say only what each one does (#1457).**
  On a fresh install (or after clearing app data), walk the permission sheet: it shows "Step N of 6" on
  every card, in the order Screen events, Screen reading (Accessibility), Notification access, Location,
  Notifications, Bubble. Screen reading is its own screen with Don't allow / Allow; Allow shows the toast
  and opens Android's Accessibility settings, Don't allow skips to the next step. Steps 3 to 6 have
  "Not now". Swiping the sheet down puts every open step off: Home then shows "Screen reading is off"
  (or "Setup isn't finished") with a button that reopens the sheet at the first open step. A deferral
  holds across rotation and a trip to Android settings, and comes back on a cold start.
  Refuse Location twice (or pick "Don't allow"): the card then says to turn it on in Android settings and
  its button opens the app's settings page; granting Approximate clears it.
  The automatic-tap prompt lists only Confirm declines and Open pay details, never Accept or Decline.
  Settings > Data & privacy > Automation & consent: turning Screen events on shows the disclosure first;
  Back writes nothing; the status line reads "Allowed, <date>, app <version>, privacy notice 12" after a
  choice. In a test build, turning it off asks first. Settings > Data & privacy > Privacy shows the
  privacy page, with a Test builds section only in a test build.
  - Issue: #1457. Confirmed: 0/2 (awaiting field validation)
