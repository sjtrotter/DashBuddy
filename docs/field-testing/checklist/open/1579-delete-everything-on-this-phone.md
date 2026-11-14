status: active
issues: #1579

- **Delete everything on this phone leaves a first run (#1579).** Off shift, after exporting anything you want to
  keep, open Settings > Data & privacy > Delete everything on this phone and read the dialog. With the census on,
  confirm and watch for the census answer: before the census server is updated with census#79, working is the dialog
  saying the server can't take deletion requests yet with Delete now and Wait, and Wait closing it with nothing
  deleted (Recap still shows your shifts). After the update, working is "The census server confirmed it deleted this
  phone's census data", then gig.log closing. Without the census, it closes right after Delete everything. Then open
  gig.log: working is setup from the start, Recap empty, the garage empty, every automatic tap undecided on Automation
  & consent, the census off, and `adb shell ls /sdcard/Android/data/cloud.trotter.log.gig.debug/files/` showing no
  captures, logs from before the wipe, or census files. Broken = any old shift, vehicle or consent choice coming
  back, gig.log not closing, or a census upload in the server's ledger after the confirmation.
  - Confirmed: 0/2
