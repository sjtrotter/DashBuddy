status: active
issues: #1627

- **Ask the developer sends the masked outline to the quarantine, and the screen says when it is deleted (#1627).**
  With reviewing on and a screen Ready to review (the #1626 item), open it, confirm one word and label one row, then
  tap Ask the developer. Working when: a sheet says what goes (the outline as the rows show it, covered rows stay
  covered, a confirmed word as its code), that only the developer can open it and that the server deletes it after 3
  days, and offers No note plus three notes; pick one and Send. The screen then reads "Sent to the developer. The
  census server deletes it by <date>" and the screen leaves Screens to review. On the census server, `/ops/quarantine`
  lists one item with that note and an `expiresDay` 3 UTC days out; opened offline with the operator key, it holds the
  skeleton, the confirmation as a hash and the label, and no word of a slot whose gate was closed. The item never
  shows in `/ops/clusters` or the counts. Broken = a customer name, address or code in the opened item, a plain word
  for a slot whose gate was closed, the "couldn't confirm" message on a normal send, or the screen still listed after
  Sent. Also: Privacy page > Open-source notices opens THIRD_PARTY_NOTICES.md.
  - Confirmed: 0/2
