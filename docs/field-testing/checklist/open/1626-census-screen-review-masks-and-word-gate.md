status: active
issues: #1626

- **A screen review shows what leaves, covers customer data, and holds a confirmed word until its gate opens
  (#1626).** With reviewing on (the #1625 item), dash a shift that passes through a screen gig.log doesn't recognize.
  Working when: Settings > Data & privacy > Census > Screen reviews lists the screen under Screens to review, "Waiting
  for the daily census upload" until the daily upload, then "Ready tomorrow", and "Ready to review" from the next UTC
  day (after 00:00 UTC, without leaving and reopening the screen); after a later shift ends one "Screens to review"
  notification arrives on the Census channel. Opening the screen shows one row per text: no customer name, address,
  code or note and none of your greeting, account or place labels appears as a word (they read Covered, a format, a
  number, or greyed as changing, by area or store, or not seen by enough drivers); only rows the server calls constant
  show a word in quotes with "The app's own wording". Confirm one, label another (for example Total pay), Send: the
  screen says Sent and the screen leaves the list. On the census server, `/ops/contributors` shows the reads, and no
  contributor word for that slot appears under `/ops/contributors/words` until a second phone confirms the same word.
  Delete drops a screen and sends nothing. Broken = any customer text shown as a word, a word on a greyed row, a
  contributor word on the server from one phone alone, or a review that still opens after Stop reviewing.
  - Confirmed: 0/2
