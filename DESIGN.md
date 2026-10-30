# DESIGN.md

How gig.log's screens should look and behave. Read it before designing, reshaping or reviewing any screen,
sheet, notification or piece of UI copy. Where this file and a general design rule disagree, this file wins. Two
topics have their own owner and this file only points at them: user-facing words (`docs/design/copy-style.md`) and
keeping time values fresh (CLAUDE.md § Reactive UI Principles).

The rules here are the direction for new and changed work. Code written before this file does not follow all of
them yet; § Where the code differs today lists the known gaps, so a rule is never mistaken for a description of the
current app.

## Product and user

gig.log runs beside a delivery app and shows the driver (the dasher) the facts about their own work: offers,
pay, miles, time and profit. The dasher works alone in a car, phone in a mount, often at night. They glance at the
phone for one to three seconds at a stoplight or in a parking lot, and read longer only when parked or at home.
They are not technical. Their main goal: decide quickly whether an offer is worth taking, and know what the shift
is earning after vehicle costs.

## Two surfaces: the bubble and the main app

gig.log has two surfaces with different jobs and different rules. Decide which one a feature belongs to before
designing it. Notifications (the offer pop-up, the shift notification, the weekly plan grade, app notices) and the
spoken offer follow the bubble's rules, because they are read or heard in the same moment.

| | Bubble (the HUD) | Main app |
|---|---|---|
| Job | Read the current shift and act on it in one to three taps | Review the past, plan the next shift, set the app up |
| When it is read | During a shift, at a glance, often in a moving or stopped car | Parked, at home, before or after a shift |
| Reading time | About 2 seconds per glance | As long as the dasher wants |
| Code | `feature/bubble` (`hud/*`), hosted by `app/.../ui/bubble` (`BubbleActivity`, `BubbleScreen`) | `app/.../ui/main`, `feature/dashboard`, `feature/settings`, `feature/setup` |
| Window | An Android bubble over the delivery app, dismissed by a tap. The app asks for 600 dp of height; about 510 dp shows in practice (10-21 screenshots) | Full screen, edge to edge |
| Structure | A fixed frame (phase strip, head, top row, earnings hero, goal pill, a body, dock) whose body changes by state | Screens on a navigation stack: Home (today), the analytics hub (the past), the Playbook (the next move), Settings |
| Input | Taps on large targets; amounts from a keypad or preset buttons; no typing beyond a short note | Any Material input, including text fields |
| Mistakes | Save on the commit tap; an undo window where the entry can be taken back | Save as you go with a snackbar Undo; a confirm dialog for a step with lasting effect |
| Colour | Brand tokens (`AppTheme.colors`) | Material roles (`MaterialTheme.colorScheme`), brand tokens for meaning colours |
| Numbers | `AppTheme.num.*` styles, sized for a glance, scaled by the driving-mode multiplier | `AppStatTile` and the Material type scale |
| Font scale | Honoured, with layout tiers at 1.3, 1.5 and 1.8 | Honoured up to 200 %, layouts reflow |
| Density | One state at a time, a few figures; the body may scroll inside the frame | Lists, charts and tables; the "More numbers" tier reveals the rest |

### Bubble rules
- **The glance budget.** A glance reads one state and one or two numbers. Each state shows one big figure (the
  earnings hero) and at most one other element that stands out. A label is four words or fewer. The driving digest
  puts the useful limit at about 30 characters per glance.
- **The frame stays put, with three modes.** In the live and off-shift modes the phase strip, head, top row, hero,
  goal pill and dock keep their places and only the body changes; entry sheets rise into the body
  (`HudSheetHost`). An offer replaces the top row, hero and goal pill with the offer card, and Accept and Decline
  take the dock's place. The log shows a compact hero above the log.
- **Primary content fits about 510 dp.** The body may scroll, but what a glance needs sits above the fold.
- **Thumb reach.** Actions sit in the dock at the bottom, in reach of a thumb. The top of the panel is the
  furthest reach and sits nearest the bubble head, so it holds reads, not frequent actions.
- **Targets of at least 48 dp, and 64 dp for the dock blocks.**
- **Sheets, not screens.** An entry (cash tip, expense, fuel, odometer, goal) opens as an inline sheet with no
  scrim, so the instruments stay visible and live. One sheet is open at a time.
- **Write without asking.** An entry saves on its commit tap; the bubble never asks "Are you sure?". A cash tip,
  expense or fuel entry is followed by the undo strip for 10 seconds. An odometer reading shows a 10-second saved
  notice. A goal change saves at once and is changed back the same way.
- **Nothing moves by itself** except values that change with time (countdowns, elapsed time), a progress
  indicator while something loads, and the one-time goal-met sequence. No auto-scrolling text.
- **The headline is what the delivery app says you earned.** The hero shows the shift's earnings as the delivery
  app reports them, with no label, not changed by tips or costs (dev, 2026-10-30). Cash tips are shown apart from
  it, either as a small "$X cash" mark beside the hero or as the running cash total on the cash-tip button. The
  offer is the only bubble figure judged against vehicle costs.
- **One offer, one story.** The offer card, the offer pop-up, the shift notification's line and the spoken offer
  name the same figures in the same words, produced by one owner. Each delivery app shapes what that owner can
  say (Uber's offers re-quote live and carry different fields), never a second copy of the wording.
- **Leave for the main app** when the task needs reading, comparing or typing: history beyond the shift log,
  settings, the garage. The bubble opens the app with `openInApp`, which collapses the bubble.
- **Both themes, the same sizes.** The bubble and its notifications follow the dasher's theme, light or dark, with
  the same layout and type sizes in each; a colour that does not hold up in light is changed, and a design that only
  works in dark is redesigned (dev, 2026-10-30).

### Main app rules
- **Material first.** Use the stock Material 3 component for the job (`Scaffold`, `TopAppBar`, `ListItem`,
  `Switch`, `ModalBottomSheet`, `SnackbarHost`, `AlertDialog`) before writing a custom one.
- **One screen, one job,** stated in the screen's title bar, with a back button on every screen below Home. Home is
  today, the hub is the past, the Playbook is the next move (#1024).
- **Two tiers of detail.** Compact by default; "More numbers" reveals the remaining figures and controls on the
  hub and the Playbook (#1255). One disclosure per screen holds the caveats (`HowNumbersWorkFooter`).
- **Save as you go.** On a vehicle's details page a finished field saves, there is no Save button, and a snackbar
  offers Undo for the latest change (#1383, #1385). Rebuilt settings screens follow the same pattern.
- **Confirm only what has a lasting effect.** A reset or a retirement asks first, as the vehicle page does today
  (`VehicleDetailsScreen`); a change Undo can take back does not.
- **Rows follow Android's two-part pattern** where a row both selects and opens: the main area selects, a divider
  and a trailing icon open the details (Garage, #1379).
- **Every state is designed:** loading, empty, partial, failed and stale. A screen never shows `$0.00` while its
  data loads.

## Android and Material 3

gig.log follows Material 3 and Android's own guidance, in the current Material 3 Expressive version, through
Compose Material 3 (BOM in `gradle/libs.versions.toml`). The digest at `docs/design/material-digest.md` quotes the
rules with their sources; the rules below are the ones that come up most.

- **Spacing** on an 8 dp grid, 4 dp for small parts. Window margins 16 dp on a phone. Padding and gaps on the
  parent, not margins on children.
- **Targets** at least 48 × 48 dp, 8 dp apart. A small icon gets its full 48 dp touch area.
- **Text in sp,** at least 12 sp for words. Test at the largest font scale (200 % on Android 14+). Spacing does not
  shrink as text grows, and no row with sp text has a fixed height.
- **Contrast** at least 4.5:1 for text under 18 sp (14 sp bold), 3:1 for larger text and for controls against
  their background. Colour never carries meaning alone; pair it with a word, shape or position.
- **Colour roles, never literal colours,** in composables. A colour literal lives only in `AppColors`.
- **TalkBack.** Every control has a name that says what it does, a `Role`, and a `stateDescription` (or the
  selected and expanded semantics) when its state is not in its text. Decorative icons get
  `contentDescription = null`.
- **Back** works everywhere: the system back gesture closes a sheet before it leaves a screen (`BackHandler`), and
  predictive back is on (`enableOnBackInvokedCallback`).
- **Edge to edge** in the main app (`enableEdgeToEdge`), with content kept out of the system bars' insets.
- **Motion:** new motion uses Material's spring tokens, and all motion respects the system's reduced-motion setting
  (`ValueAnimator.areAnimatorsEnabled()`, `hud/ReducedMotion.kt`).
- **Current Material 3 no longer recommends** segmented buttons (use the connected button group), the small FAB,
  and surface tint (use the surface container roles). New work avoids all three.
- **Chips filter or suggest; buttons finish a task.** An amount that commits an entry is a button.

### Where gig.log departs from Material on purpose
- **No dynamic colour.** The palette is fixed and the same on every phone, so good, warn and bad always mean the
  same thing (#94, #313).
- **Brand fonts** instead of Roboto: Hanken Grotesk for words, Space Grotesk with tabular figures for numbers.
- **A separate number scale** for the bubble (`AppTheme.num.*`), larger than Material's type scale, because the
  hero must read at a glance.
- **The bubble's sheets have no scrim,** unlike a modal Material bottom sheet, so the live figures stay visible behind
  them. A full sheet keeps the bottom sheet's 28 dp top corners and drag handle.
- **The shift notification behind the bubble uses MessagingStyle** (`BubbleManager`), because Android shows a
  bubble only for a conversation notification.

## Colour

Two layers. `AppColors` (`core/designsystem/.../theme/AppColors.kt`) holds the brand tokens, one fixed set for
dark and one for light. `AppTheme.kt` maps them onto Material's colour roles so stock components render in the
brand. The app follows the phone's theme; a theme setting (system, light or dark, plus colour options) is planned
(dev, 2026-10-30).

| Material role | Brand token | Use |
|---|---|---|
| `background`, `surfaceContainerLowest` | `bg` | The window |
| `surface`, `surfaceContainerLow`, `surfaceContainer` | `surface` | Cards, the bubble's state body |
| `surfaceContainerHigh` | `surface2` | Sheets, dock blocks, the goal pill |
| `surfaceContainerHighest`, `surfaceVariant` | `surface3` | Keypad keys, tracks, gauge rings, input fields |
| `onSurface` | `text` | Primary text and figures |
| `onSurfaceVariant` | `text2` | Secondary text and labels |
| `outline` / `outlineVariant` | `lineStrong` / `line` | Control borders / dividers |
| `primary` / `onPrimary` | `accent` / `accentText` | The one primary action, selected states |
| `primaryContainer` | `accentDim` | A lit dock block, tonal buttons |
| `secondary` / `secondaryContainer` | `stOffer` / `stOfferBg` | Offer phase |
| `tertiary` / `tertiaryContainer` | `stPickup` / `stPickupBg` | Pickup phase |
| `error` / `errorContainer` | `bad` / `badBg` | Errors and losses |

Rules:
- Use the Material role where one exists. `good`, `warn`, `neutral`, `stDropoff`, `rest` and the `text3` grey
  have no Material role, so they are read from `AppTheme.colors`. The bubble reads `AppTheme.colors` throughout.
- Surfaces step up one level per layer of containment: window `bg`, card `surface`, sheet `surface2`, control
  `surface3`.
- **Text that carries information uses `text` or `text2`.** `text3` is 4.2:1 on `bg` and 3.4:1 on `surface2` in
  the dark theme, under the 4.5:1 small-text minimum, so it is for disabled states and decorative marks (icon
  strokes, the drag handle). Small labels use `neutral` (5.2 to 6.4:1) or `text2` (dev, 2026-10-30; first adopted
  for the bubble in #1333). The code does not follow it yet.
- The phase strip's colours: waiting `good`, offer `stOffer`, pickup `stPickup`, dropoff `stDropoff`, off shift
  and paused `rest`. The head's "Paused" label is `warn`. Each phase also has a word on screen.
- The `*Bg` tokens tint chips and badges: the status colours (`good`, `warn`, `bad`, `neutral`) at 14 % alpha in
  dark and 12 % in light, the phase colours at 16 % in dark and 12 % in light.

## Type

- **Words:** Hanken Grotesk through `AppTypography`, on gig.log's own scale rather than Material's sizes:
  body 15, 13 and 11 sp; labels 13, 11 and 10 sp; titles 13 to 18 sp.
- **Numbers:** Space Grotesk with tabular figures through `AppTheme.num.*`, so digits line up and do not jump as
  they tick. The hero shows dollars at 60 sp and cents at 28 sp, or 30 and 18 sp when compact. State
  figures are 48 and 56 sp; the other number styles step through 22, 18, 15, 13 and 11 sp.
- **Driving mode** multiplies the bubble's type by 1.12 (`DRIVING_GLANCE_MULTIPLIER`, #318). The main app never
  scales.
- **Font scale tiers in the bubble** (`FontScaleTier`, by font scale times the driving multiplier): at 1.3 labels
  move beside their figures, at 1.5 the job column drops, at 1.8 the hero turns compact.
- **No value** is the em dash (`EMPTY_VALUE`), never `$0.00` or `0`. Zero is shown only when the value is known to
  be zero.

## Shape and space

- Radii (`AppShapes`): 6, 10, 14, 20 and 28 dp. Pills use a full round shape.
- Bubble: 16 dp side margins (22 dp at the start, past the 6 dp phase strip); the state body and dock blocks 16 dp
  radius; full sheets 28 dp top corners on `surface2`; 8 dp between dock blocks. The compact expense sheet sits on
  `surface` with no drag handle.
- Main app: Material's spacing and component shapes.

## Components

- Shared components live in `:core:designsystem` with the `App*` prefix (`AppCard`, `AppChip`, `AppStatTile`,
  `AppGaugeRing`, `AppSegmented`, `AppSlider`, `AppBarChart`, `AppAccordion`, `AppHeatScale`). They take data and
  callbacks and hold no state. Reuse or extend one before writing a new one; a component used by one feature stays
  in that feature.
- Bubble parts: `HudSheetHost` (the frame entry sheets rise into), `hud/keypad/` (the shared 3 × 4 keypad),
  `UndoStrip`, `Dock`, the vehicle chip.
- Platform identity is a label and a colour only. No delivery-app wording in the app's own chrome.

## Language and consent

- English is authoritative; Spanish and French are translated at language level only, never a regional variant
  (`LocaleAllowlistGuardTest`). No user-facing text is hard-coded in a composable.
- Each automatic tap is asked for separately, there is no "allow all", and a denial lasts (#843, #1167). Consent
  copy says only what `PRIVACY.md` says the app does.

## On-screen privacy

Store names, vehicle names and the dasher's own figures may show. A customer's name or address never shows. The
dasher's own sensitive screens in the delivery app (banking and transfer screens, payment, identity documents) are
read only far enough to recognise them, then dropped before anything is parsed, saved or shown (`PRIVACY.md`
§ 2.5, which also states the limits). No screen shows their contents.

## Where the code differs today

Known gaps between the rules above and the code, found by the 2026-10-30 design audit and the review of this file.
It lists the known gaps, not every one: when a change meets code that breaks a rule here, it fixes the code or
adds the gap below, and never leaves it worse.

- **`text3` carries words** in about 157 places, including `AppStatTile`'s labels and subtitles and the bubble's
  state labels, gas row and gauge units.
- **Words under 12 sp:** `labelSmall` is 10 sp and `bodySmall` 11 sp in `AppTypography`, and the `xs` number style
  is 11 sp.
- **Targets under 48 dp:** keypad keys (38 dp), the offer pop-up's Accept and Decline (36 and 40 dp, in fixed-height
  rows), expense label chips, goal buttons (44 dp), the hub's segments, "More numbers" and needs-a-look rows.
- **The offer pop-up's colours** come from the dark palette whatever the phone's theme.
- **Numbers outside a number style:** the setup sliders' values (`MetricSliderCard`, Material `displayMedium`) and
  the bubble's gas prices (`GasQuickEdit`, which also reads Material colour roles instead of `AppTheme.colors`).
- **Bubble text the driving multiplier misses:** the offer card's literal `20.sp` text and `14.sp` countdown label
  (`OfferCard.kt`) skip `glanceSp`.
- **Fixed-height rows holding text:** `CompactHeroRow` (48 dp) and `GasRow` (56 dp), besides the offer pop-up.
- **Setup's first step has no Back button** (`WizardScreen`), though Home opens it.
- **Missing semantics:** `AppSegmented` exposes no role or selected state; `AppAccordion` no role or expanded state.
- **Goal amounts are chips** (`GoalSheet`) that commit the goal when tapped.
- **The offer channels disagree:** the card has no verdict word, while the pop-up title and the spoken offer still
  give one.
- **Main-app screens reading brand text tokens directly,** such as `TodayCard`.
- **Undesigned states:** loading on Home, the hub, Ratings, shift detail and the Garage; paused in the bubble (an
  empty panel); a failed gas-price lookup; a permanently denied permission.

## Settled decisions

- #94, #313 (closed 2026-06-09): fixed brand palette, Hanken Grotesk and Space Grotesk, no dynamic colour, dark
  default that follows the system.
- #110: the offer reaches the dasher as a heads-up notification with Accept and Decline; the bubble opens on tap,
  never by itself.
- #468 (closed 2026-06-13): design-system names use `App*`, never a platform word.
- #318: driving mode enlarges the bubble's type by 1.12; the main app never scales.
- #1024: three destinations: Home is today, the hub is the past, the Playbook is the next move.
- #1255: two presentation tiers behind one "More numbers" setting.
- `docs/design/copy-style.md` (adopted 2026-10-06) governs every user-facing word.
- #1333: the bubble is one read-and-act surface. Profit is the hero; Earned, Online and Miles sit in the top row;
  entries live in a thumb-zone dock and open inline sheets; undo replaces confirmation; a dropoff phase colour was
  added. The offer pop-up shows the same offer card without the verdict word, badges, trip line or gauge bars (S2g).
- #1379, #1383, #1385: the vehicle chip in the bubble head; save as you go with a snackbar Undo; the Garage's
  two-part rows.
- #1407 (2026-10-29): "shift" replaces "dash" and "Profit" replaces "Kept" in copy; an offer's estimate keeps "net".
- 2026-10-30 (dev): the bubble's hero is the delivery app's reported earnings, unlabelled, not changed by tips or
  costs; this replaces #1333's Profit hero and settles #1372. The analytics side includes cash tips and applies
  vehicle costs as the dasher selects. Labels move off `text3`; confirmations only for lasting effects; one offer
  story from one owner; a title bar and back button on every screen below Home; every state designed; light and
  dark both supported at the same sizes, with a theme setting to come.

## Open questions

- **The bubble declutter.** A 2026-10-21 study proposes thinning the waiting and shift-start states. It waits on
  the developer and lives outside the repo.
- **Where cash tips show in the bubble:** a "$X cash" mark beside the hero, or the running cash total on the
  cash-tip button.
- **Offer verdict and score.** Whether any surface says Accept or Decline or shows a score (with the council,
  2026-10-30).
- **Light theme colours.** On the light `bg`, small text in `accent` (3.0:1), `warn` (2.7:1), `good` (3.1:1) and
  `neutral` (4.0:1) falls under 4.5:1; those tokens change, or the surface is redesigned for light.
- **Dropoff colour.** `stDropoff` is 3.8:1 on `surface2` in the dark theme; fine for the phase strip, too faint for
  small text on a sheet.
- **`AppSegmented`** follows the segmented button, which current Material no longer recommends: replace it with the
  connected button group, or keep it as a brand component.
- **Navigation.** The hub and the Playbook hang off Home and do not link to each other; a bottom bar is one option.

## Sources

- `docs/design/copy-style.md`: every user-facing word.
- `docs/design/material-digest.md`: Material 3 Expressive and Android guidance, quoted with URLs (m3.material.io
  content version 2026-09-23, gathered 2026-10-21).
- `docs/design/driving-digest.md`: how much a driver can read in a glance (Android for Cars, NHTSA, glance
  research).
- `docs/design/analytics-redesign-brief-2026-07-28.md`, `running-hourly-rate.md`, `rule-capability-consent.md`:
  the analytics hub, the per-hour figures, the consent model.
- `PRIVACY.md`: what the app reads, stores and sends.
- `core/designsystem/`: the tokens, type, shapes and shared components.
- CLAUDE.md § Reactive UI Principles and § Module Structure.
- The design archive outside the repo (the #1333 briefs and mockups, the 2026-10-21 declutter study, the 2026-10-30
  audit) belongs to the developer; issue #1333 and its pull requests summarise the bubble's history.
