# Play Console: AccessibilityService declaration

The answers gig.log gives in Play Console's Permission Declaration Form for the AccessibilityService API, and the
script for the demo video the form asks for. They describe the Play build (`cloud.trotter.log.gig`, the `release`
build type), which declares `isAccessibilityTool="false"`. They are drawn from the advance notice sent to the App
Review team in November 2026 and from [PRIVACY.md](../PRIVACY.md), which names the file behind each claim. When the
code or PRIVACY.md changes a claim made here, this file changes in the same PR.

## Declaration answers

### Is the app an accessibility tool?

No. gig.log is not built for people with disabilities. The Play build declares `isAccessibilityTool="false"`.

### Core use

gig.log is a record-keeping and offer-reading app for delivery drivers. It uses an AccessibilityService to read the
screens of the delivery-driver apps a driver works in: DoorDash (`com.doordash.driverapp`) and Uber Driver
(`com.ubercab.driver`). It reads each delivery offer aloud while the driver is on the road, and it keeps the
driver's own record of offers, deliveries, pay and costs on the phone.

### Data the service accesses

- The service is bound to the two apps above through `android:packageNames`. Android filters out every other app's
  events before they reach the service.
- It subscribes to four event types: window state changed, window content changed, view clicked and windows
  changed. It subscribes to no text-changed, focus or selection events.
- It reads the text, content descriptions, view ids and bounds of those apps' windows
  (`canRetrieveWindowContent`, `flagReportViewIds`, `flagRetrieveInteractiveWindows`,
  `flagIncludeNotImportantViews`). To find a delivery app's offer window, it reads other windows only far enough to
  learn which app owns them, then skips them.
- It can take a screenshot (`canTakeScreenshot`) for the evidence screenshots, which are off until the driver turns
  them on. A screenshot is the whole display and is saved to the phone's gallery. gig.log never uploads one.
- In this version, the service can't read Uber Driver's offer pop-up. Android 14 and later show that pop-up's content
  only to services that declare `isAccessibilityTool="true"`. gig.log reads Uber Driver's other screens.

What the app does with it:

- Recognition, offer evaluation and every pay calculation run on the phone. The Play build has no account, no
  analytics SDK, no advertising identifier and no upload path for screen content.
- A screen recognized as showing the driver's banking, payment or identity-document details, or the ID-scan camera
  or the signature pad, is dropped before it is parsed or saved. A rule-independent text check drops screens the
  rules miss. These filters are written for the English-language layouts of these apps.
- A customer's name or address is saved in the driver's history only as a one-way hash.
- The offer sentence goes to the phone's text-to-speech engine, which may use the network under its own terms.
- Android backup, gallery sync of screenshots and exports the driver makes can copy data off the phone.

### Why the AccessibilityService API

The delivery apps offer drivers no API that reports the offers they saw, accepted or declined, or the pay and
distance on each. An offer is on screen for a few seconds. The only record of it is the screen the driver already
sees, and the AccessibilityService API is the Android API that reads another app's screen. A notification listener
alone is not enough: the offer card, the pickup and drop-off screens and the shift summary are app screens, not
notifications.

gig.log taps a button in a delivery app only when the driver asks, or under a permission the driver granted for one
specific action:

- When the driver presses Accept or Decline in gig.log, the service taps the matching button in DoorDash. gig.log
  taps nothing in Uber Driver.
- Two follow-up actions in DoorDash, each behind its own grant that starts undecided: opening the collapsed pay
  breakdown on a completed-delivery screen, and confirming a decline the driver already chose (the latter also needs
  the driver's quick-declines setting).
- Before every tap the app finds the target again and checks that it belongs to the expected app and can be
  clicked; Accept and Decline must also carry an expected label. If a check fails, the app does not tap.
- gig.log never accepts an offer on its own.

### User benefit

A driver hears each offer's pay and distance without taking their eyes off the road to read a small card, and keeps
their own record of every offer, delivery, mile and cost, which the platforms do not give them.

### Prominent disclosure

Before sending the driver to Android's accessibility settings, gig.log shows a disclosure card (setup step 1 of 5 in
the Play build, `AccessibilityDisclosureCard`). It names the apps the service reads, says what it reads and what it
drops or saves as codes, says that this version can't read Uber Driver's offer pop-up, says what screenshots contain
and that they go to the gallery, says that screen reading and calculations stay on the phone and what can copy data
off it, says when the app taps a button, and links gig.log's own Privacy page, which in turn links the privacy
policy and the technical details. Allow opens Android's accessibility
settings; Don't allow leaves the service off. The service description in Android's settings repeats the disclosure.

## Demo video script

Before recording: install the Play build. For the first declaration the Play Store cannot serve the app yet (the
release waits on this video), so build an APK from the tag run's `play-bundle` AAB with `bundletool build-apks
--mode=universal`, signed with any local key, and install it over USB (`docs/RELEASE.md` §3); uninstall it before
installing from Play. For later recordings, install from internal testing. In Android settings, grant gig.log every other
setup permission, so the screen reading card is the only step left: notification access, location (precise),
notifications and bubbles. Leave gig.log's accessibility service off. Force-stop gig.log, so the process is new and
no earlier "Don't allow" is remembered. Scroll slowly enough that every line of the card can be read.

1. Open gig.log. Home opens and the setup sheet shows "Step 1 of 5", Screen reading (Accessibility).
2. Scroll through the whole card slowly: the paragraphs, the "Full privacy notice" link and "Without it". Tap the
   link: gig.log's Privacy page opens. Scroll it, tap "Privacy policy" at its foot to show the policy page, then
   go back to gig.log and back again to Home. The setup sheet shows the card again.
3. Tap Allow. Android's accessibility settings open. Find gig.log, open it, show the service description, turn the
   service on and accept Android's dialog. Return to gig.log: the setup sheet closes. If gig.log then asks about
   automatic taps, tap "Not now".
4. Open Android's accessibility settings again and turn gig.log's service off. Return to gig.log.
5. The setup sheet opens again by itself with the card ("Step 1 of 5"). Tap Don't allow: the sheet closes and the
   service stays off. Home shows "Screen reading is off".
6. Tap "Turn on screen reading" on Home: the card shows again. (A cold start of the app shows it too.) Tap Allow and
   turn the service on, as in step 3.
7. Go online in DoorDash. When an offer arrives, gig.log reads it aloud. Press Accept on gig.log's offer card:
   DoorDash accepts the offer.

The live part needs a delivery-driver account and an offer, so step 7 is recorded on a real shift.
