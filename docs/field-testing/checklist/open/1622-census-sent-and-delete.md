status: active
issues: #1622

- **What gig.log sent matches the server, and Delete my census data empties it (#1622).** With the census on for a few
  days, open Settings > Data & privacy > Census > What gig.log sent. Working when: each day's screens, notifications and
  daily counts match the census server's ledger for this install (`/ops/ledger`), no row shows a code or a value, and the
  next upload's time left counts down while the screen is open. Then tap Delete my census data, read the dialog and
  confirm. Before the census server is updated with census#79, working is the row saying the server can't take
  deletion requests yet, and no upload at all until it can. After the update, working is: the row shows the server's
  confirmation (records and the day), the install's rows are gone from
  the server's operator views, the census switch stays as it was, and the next daily run sends nothing queued before the
  request. Try once with the phone in airplane mode: the row says the request is waiting, and when the phone is back
  online the server's deletion arrives before any upload. Broken = an upload before a waiting deletion, a row showing a
  code, or the switch turning itself off.
  - Confirmed: 0/2
