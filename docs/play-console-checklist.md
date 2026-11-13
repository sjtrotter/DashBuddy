# Play Console answers

The forms under Play Console → App content and Store settings are filled by hand; the API cannot set them. This file
records each answer and the reason for it, so a later change to the app shows which answer it breaks. The listing text
and images are files (`play/listing/`, pushed by the "Play store listing" workflow). The accessibility declaration
and its video script are in `play/accessibility-declaration.md`.

Revisit every answer below when a release adds a sign-in, a server gig.log runs, the census in a Play build, a payment,
or a new data type in `PRIVACY.md` § 6.

| Form | Answer | Why |
|---|---|---|
| Privacy policy | `https://gig.log.trotter.cloud/privacy/` | Built from the app's own `privacy_*` strings by `tools/site/build_site.py`. |
| App access | All functionality is available without special access | No sign-in, no paywall, no invite. A reviewer cannot work a delivery shift; that is shown in the accessibility declaration video, not here. |
| Ads | No ads | The app has no ad SDK. |
| Content rating | Utility / productivity questionnaire: no violence, no user-to-user content or chat, no location sharing with other users, no digital purchases | The app shows the driver their own work. |
| Target audience | 18 and over only | The delivery apps it reads require drivers to be adults. Not designed for children. |
| News app | No | |
| Health apps | None | |
| Government app | No | |
| Financial features | My app doesn't provide any financial features | It records the driver's own earnings, expenses and miles; it never moves money, lends, links an account, gives advice or holds funds. Banking and DasherDirect screens are dropped. Google's form gives no guidance for bookkeeping-only apps; if review asks, the fallback is Support services → Other, described as "earnings, expense and mileage records for the driver's own use". |
| Data safety: collects or shares | Yes | |
| Data safety: data types | Location → Approximate location: collected, shared, optional, not processed ephemerally, purpose App functionality | The gas-price refresh (off by default on a new install) hands Android's Geocoder a location rounded to about 11 km and sends the resulting EIA region code to `api.eia.gov` (`PRIVACY.md` § 2.4, § 6). The precise fix never leaves the phone. "Shared" because both go straight to third parties that are not gig.log's service providers. What else can leave the phone in the Play build, and why none of it is declared: the vehicle lookup sends year, make and model to fueleconomy.gov after the user taps it (not a Play data type); exports go where the user saves them; evidence screenshots, once the user turns on the master switch and a category, save automatically to the gallery, and a syncing gallery may upload them (the gallery app's transfer, not gig.log's); Android backup copies the history to the user's Google account (a system service); spoken offers go to the phone's text-to-speech engine, which the user picks and which may use the network. The census (#1620) runs in the Play build once the driver accepts its disclosure: see the census row below. #1566 replaces the location lookup with an area the driver picks, after which no location leaves the phone: change this answer in the same release. |
| Data safety: census (#1620, decide in #1623) | Proposed: App info and performance → Diagnostics, and → Other app performance data; Device or other IDs. Each: collected, not shared, optional, not processed ephemerally, purpose App functionality | The census is opt-in, off by default, behind its own disclosure (`CensusScreen`). It sends gig.log's own census server outlines of unrecognized screens and notifications (layout, one-way word codes, value formats; no screen text), daily recognition counts per delivery app and app version, and a random per-install ID (`PRIVACY.md` § 6). It goes to the developer's own server, so it is collected, not shared. Which Play data types fit an outline is a judgement for #1623 and counsel (#246); the ID is an app-generated random value, not a hardware or advertising ID. |
| Data safety: installed apps | Not collected | The app checks only the two driver apps it names in the manifest's `<queries>`, and the result stays on the phone. |
| Data safety: encrypted in transit | Yes | Every endpoint is HTTPS; the app's network security config refuses cleartext for every host (#1620). |
| Data safety: deletion | Decide in #1623: on the phone, clearing storage or uninstalling deletes everything; the census server's copy has no in-app delete until #1622 (and census#79 on the server) | Clearing the app's storage or uninstalling deletes its private data. Exports, screenshots and the Android backup are separate copies the user deletes where they live (`PRIVACY.md` § 7). The census server keeps what a census install sent under ADR-0011's retention table; "Delete my census data" is #1622, so until it ships the form cannot claim a deletion request path. |
| Countries/regions | United States only, on closed testing and production (internal testing has no country list) | Recognition reads the U.S. English driver apps; money is dollars and miles; gas prices (EIA), vehicles (EPA) and the IRS mileage deduction are U.S.-only. The API cannot set this. |
| App category | Finance | Where the comparable driver earnings apps are listed. Business is the alternative. |
| Contact details | Email `support@trotter.cloud`, website `https://gig.log.trotter.cloud/` | The same address as the About screen and the privacy page. trotter.cloud forwards it with Cloudflare Email Routing. |
| Accessibility API | Declaration and video per `play/accessibility-declaration.md` | `isAccessibilityTool` is false in the Play build. |
| Precise location | Live tracking, with the justification in `play/location-declaration.md` | Required from 2027-01-27. GPS counts the miles of a running shift; approximate location is too coarse for that. |
