status: active
issues: #1615

- **A full backup moves the test build's history into the Play build (#1615):** in the test build, Settings → Data & Privacy → Export data → Full backup → Back up, and save the file. In the Play build, the same screen → Restore, pick the file, check that the dialog names the backup's date and the test build's version, and confirm. gig.log restarts. Compare the two builds: the shift list (count and the newest few shifts), one week's totals on Recap, the vehicles in the Garage, and the gas price setting. The Play build asks for screen events and automatic-tap consent again; nothing was granted by the restore. The Export data screen shows one line that the restore finished, once.
  - Refused file: pick a file that is not a backup (any photo or text file); the screen says it is not a gig.log backup and the Play build's data is unchanged.
  - Confirmed: 0/2
