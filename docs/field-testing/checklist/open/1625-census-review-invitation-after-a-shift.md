status: active
issues: #1625

- **The screen-review invitation comes after a shift, once, and Accept asks for a grant (#1625).** With the census on
  for 8 UTC days and at least 2 recorded shifts, on 3 of the last 7 days, end a shift. Working when: no
  invitation appears while the shift runs; when gig.log records the shift's end, one "Review screens gig.log doesn't
  recognize?" notification arrives on the Census channel, and Settings > Data & privacy > Census shows Screen reviews,
  Invitation open. Tapping it opens the reviewer screen; Not now closes it and no invitation follows the next shifts for
  14 days; No thanks closes it and none follows at all (the row says Off and still opens the screen). Accept shows
  "Reviewing is on. The permission ends <day>", about 30 days out, and the census server's `/ops/contributors` lists a
  live grant for this install (no phone younger than 8 UTC days is invited; a skewed phone clock shows the server's "8
  days old" answer instead). Then Stop reviewing (the row says Off), or turn the census off, or tap Delete my census data:
  each ends the permission on the phone. Broken = an invitation during a shift, a second notification before an
  answer, or anything but a yes reaching the server (the request body is `{"eligible":true}`).
  - Confirmed: 0/2
