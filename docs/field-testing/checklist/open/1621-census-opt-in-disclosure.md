status: active
issues: #1621

- **The census turns on only through its disclosure (#1621).** After installing this build the census is off even if
  it was on before: an earlier opt-in has no receipt. Open Settings > Data & privacy > Census and turn it on: the full
  disclosure shows first, with the test-build line saying when it uploads. Not now leaves the switch off; Accept turns it
  on. Working when: after Accept, Developer options shows the census status moving again (`uploaded <n>` or
  `spool_empty` within about an hour, or at once with Upload now), and turning the switch off stops it (the next run
  writes nothing new). The delivery-app and Wi-Fi choices save as they change; the test build's uploads ignore them.
  - Confirmed: 0/2
