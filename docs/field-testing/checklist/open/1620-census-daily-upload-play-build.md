status: active
issues: #1620

- **A Play-build install with the census on uploads once a day, on Wi-Fi only (#1620).** Install the Play (or
  sideload) build, accept the census in Settings > Data & privacy > Census and leave Wi-Fi only on. Dash as usual,
  including stretches on mobile data. Working when: the census server's ledger shows this install's uploads (skeletons
  and health) on at most one run a day, at a time of day that changes from day to day; a day whose time passed while the
  phone was on mobile data has no upload at all if Wi-Fi came back more than an hour after it (an upload within that
  hour of the target, at the moment Wi-Fi returns, is expected); no upload arrives
  while the phone is on mobile data only; and an unrecognized screen seen many times
  in a day arrives once that day. With Wi-Fi only off, the day's run may use mobile data. Unchecking a delivery app in
  the census choices stops its skeletons and health rows. Broken = an upload minutes after an unrecognized screen, two
  scheduled runs in one day, or an upload over mobile data with Wi-Fi only on.
  - Confirmed: 0/2
