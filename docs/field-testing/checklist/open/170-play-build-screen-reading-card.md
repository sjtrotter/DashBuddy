status: active
issues: #170, #1413

- **The Play build's screen reading card (#170, #1413).**
  Install the Play build from internal testing on a phone where gig.log's service is off. The setup sheet opens at
  "Step 1 of 5", Screen reading (Accessibility). The first paragraph names DoorDash and Uber Driver. The next says
  this version can't read the offer pop-up in Uber Driver and reads its other screens. No paragraph mentions Screen
  events. The screenshots paragraph says pictures go to the gallery, where gallery sync or sharing can copy them.
  "Full privacy notice" opens PRIVACY.md on GitHub at the build's commit (the address has a commit id, not
  `master`), and that page is revision r16. In Android's accessibility settings, gig.log's description names the
  same two apps and has no Screen events sentence. Settings > Delivery apps lists only DoorDash and Uber Driver.
  On the sideload or test build the same card has the Screen events sentence and no Uber pop-up paragraph. On the
  test build the card, the Android description and Settings > Delivery apps name all four apps (DoorDash, Uber
  Driver, Instacart Shopper, Walmart Spark); the sideload build names two.
  - Issue: #170, #1413. Confirmed: 0/2 (awaiting field validation)
