# While-driving display guidance (digest, gathered 2026-10-21)

Scope: these sources govern car head units (Android Auto / Automotive, CarPlay) and in-vehicle or mounted devices.
DashBuddy's bubble is a phone app in a mount, so none of them binds it; they are the best published evidence on
how much a driver can read in a glance. Quoted text is marked; everything else is a summary of the named source.
The Material digest (`docs/design/material-digest.md` §9) already quotes the Android for Cars task-flow and driving-state pages.

## 1. Android for Cars — Driving UX design principles (docs.partner.android.com/drivingux/foundations/design-principles)
- "A driver should be able to read content and understand the system state within 2 seconds."
- "After a driver interacts with the system, the system should respond within 0.25 seconds."
- "If content takes more than 2 seconds to load, use a spinner or similar indicator to show that the device is responding."
- "Prioritize information needed for safe driving (like navigation directions) over other content."
- "For text items using the Roman alphabet, you can use up to 120 characters, including punctuation and spaces."
- "Primary text (used for decision making) should be 32dp. Secondary text should be 24dp."
- "The minimum size for touch targets is 76 x 76dp." "Allow at least 23dp between touch targets."
- "During nighttime, use Night Mode (light text on a dark background) for better visibility."
- "The contrast ratio for icons, text, and other images must be at least 4.5:1."
- "Avoid showing dynamic visual information such as videos and auto-scrolling text while driving."
- "Suppress notifications when the driver is in the middle of a maneuver."
- The page gives no rule on the number of items per screen or on overall density.

## 2. Android for Cars — Design principles (developer.android.com/design/ui/cars/guides/foundations/design-principles)
- "Driving is the top priority for in-car apps. A simple user interface that is easy to navigate at a glance prevents distractions."
- "Drivers can complete common tasks in three taps or less."
- "Text shown while driving is kept to 1-3 lines and 120 characters to be glanceable."
- "Drivers may get interrupted by something on the road ... but they should never lose progress." "Drivers can easily continue tasks from where they left off."
- "The user interface should adapt to the current driving state to help drivers focus on the road."

## 3. Android for Cars — Visual principles (developers.google.com/cars/design/design-foundations/visual-principles)
- Primary text "used for pieces of information required for decision making ... should be 32dp"; secondary "supporting information ... 24dp".
- "Icons and terminology should consistently map one-to-one to their functions." "interaction patterns should be consistent and predictable."
- "The interface should clearly distinguish allowed from disallowed features – for example, by dimming distracting media content." "the search box should be hidden while driving."
- Touch targets must not overlap. Content at night "must be negative polarity".
- Icon sizes 44 / 36 / 24 dp for primary / secondary / tertiary (Android Auto sizing page, via search summary).

## 4. NHTSA visual-manual driver distraction guidelines (voluntary, not regulation)
- Phase 1 (2013, built-in devices): a task should need single glances of no more than **2 seconds** and **12 seconds**
  of total eyes-off-road time.
- The 2012 proposal treated as "per se" unsafe while moving, to be locked out: video, static images not related to
  driving, manual text entry, **displaying more than 30 characters of text** (a number counts as one character),
  and automatically scrolling text; and no more than **six** button presses per task. (Seen in secondary sources
  and an NTSB presentation; whether the final text kept the 30-character and six-press figures was not confirmed.)
- Phase 2 (2016 proposal, portable and aftermarket devices — a phone in a mount): pair with the car when possible;
  otherwise a "Driver Mode" brings the phone to the Phase 1 criteria; lock out text for reading (books, web, social,
  messages) and scrolling text.
- Earlier Alliance of Automobile Manufacturers guidelines (2003): 2-second single glances, about 20 s per task.

## 5. Apple CarPlay HIG (interaction page, via search excerpt; the page itself would not load)
- "Don't expect people to keep reaching out to touch the screen, especially while the car is in motion."
- "Require as few manual interactions as possible to reach content and initiate functions."
- CarPlay apps are limited to Apple's system templates, so third-party apps cannot draw custom dense screens (secondary source).

## 6. Glanceable display research
- Matthews, Forlizzi, Rohrbach (UC Berkeley EECS-2006-113): glanceability = understanding information with low
  cognitive effort; separates catching attention from rapid interpretation once noticed; a taxonomy of visual
  variables for glanceable design.
- Blascheck et al., "Characterizing Glanceable Visualizations" (2021): glances of a few hundred milliseconds
  (runner and smartwatch); values: presence and accessibility, simplicity and understandability, suitability and purposefulness.
- Google Glass design guidelines: keep the interface uncluttered, a clear hierarchy, no squinting, short content.

## What this means for a phone bubble (session reading, not a source)
A 2-second glance reads one or two numbers and one state, not a table. 32 / 24 dp text and 76 dp targets are for a
head unit an arm's length away; a mounted phone is closer, so they are an upper bound, not a floor. The 30-character
figure is the useful one: count the characters a glance must read.
