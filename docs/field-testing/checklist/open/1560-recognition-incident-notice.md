status: active
issues: #1560

- **A recognition incident posts one notice and shows on Home (#1560).**
  On a normal shift no "not read" or "not recognized" notice appears and Home shows no recognition card; a stray
  one is a false trip (note the title, then pull the logs: `Parse rot on` WARN lines name the rule). After a
  delivery-app update that breaks reading, one notice appears per app, titled like "DoorDash offers: pay not
  read", and Home shows the same card with a "For …" line that counts up by the minute. With notifications muted
  or denied the card still shows. After a force-stop and reopen the old notice is gone from the shade, the card
  comes back after a few more broken offers, and no second notice appears within 24 h. When the screens read
  again (three good reads of the same screen), the card and the notice go away; a "not recognized" card stays
  until gig.log restarts. Desk check: `grep -h 'Recognition notice posted\|Parse rot' shareable.log` shows at
  most one "posted" line per app per 24 h.
  - Issue: #1560. Confirmed: 0/2 (awaiting field validation)
