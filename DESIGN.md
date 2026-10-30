# DESIGN.md

How DashBuddy's screens should look and behave. Read it before designing, reshaping or reviewing any screen,
sheet, notification or piece of UI copy; the `ux-design` agent skill reads it first. Where this file and a
general design rule disagree, this file wins. Two topics have their own owner and this file only points at
them: user-facing words (`docs/design/copy-style.md`) and keeping time values fresh (CLAUDE.md § Reactive UI
Principles).

## Product and user

DashBuddy runs beside a delivery app and shows the driver (the dasher) the facts about their own work: offers,
pay, miles, time and profit. The dasher works alone in a car, phone in a mount, often at night. They glance at the
phone for one to three seconds at a stoplight or in a parking lot, and read longer only when parked or at home.
They are not technical. Their main goal: decide quickly whether an offer is worth taking, and know what the shift
is earning after vehicle costs.

## Two surfaces: the bubble and the main app

DashBuddy has two surfaces with different jobs and different rules. Decide which one a feature belongs to before
designing it.

| | Bubble (the HUD) | Main app |
|---|---|---|
| Job | Read the current shift and act on it in one to three taps | Review the past, plan the next shift, set the app up |
| When it is read | During a shift, at a glance, often in a moving or stopped car | Parked, at home, before or after a shift |
| Reading time | About 2 seconds per glance | As long as the dasher wants |
| Code | `feature/bubble` (`hud/*`), hosted by `app/.../ui/bubble` (`BubbleActivity`, `BubbleScreen`) | `app/.../ui/main`, `feature/dashboard`, `feature/settings`, `feature/setup` |
| Window | An Android bubble: about phone width by 600 dp, over the delivery app, dismissed by a tap | Full screen, edge to edge |
| Structure | One fixed frame: phase strip, head, top row, Profit hero, goal pill, one state zone, dock | Screens on a navigation stack: Home (today), the analytics hub (the past), the Playbook (the next move), Settings |
| Input | Taps on large targets; amounts from a keypad or preset buttons; no typing beyond a short note | Any Material input, including text fields |
| Mistakes | Undo for 10 seconds instead of a confirm dialog | Live save with a snackbar Undo; a dialog only for a step that cannot be undone |
| Colour | Brand tokens (`AppTheme.colors`) | Material roles (`MaterialTheme.colorScheme`), brand tokens for meaning colours |
| Numbers | `AppTheme.num.*` styles, sized for a glance, scaled by the driving-mode multiplier | Material type scale; `AppStatTile` for figures |
| Font scale | Honoured, with layout tiers at 1.3, 1.5 and 1.8 | Honoured up to 200 %, layouts reflow |
| Density | One state at a time, a few figures, nothing to scroll in the instruments | Lists, charts and tables; the "More numbers" tier reveals the rest |

### Bubble rules
- **The glance budget.** A glance reads one state and one or two numbers. Each state shows one big figure (the
  Profit hero) and at most one other element that stands out. Text a glance must read stays short: a label of four
  words or fewer. The driving digest puts the useful limit at about 30 characters per glance.
- **The frame stays put.** The phase strip, top row, hero, goal pill and dock keep their places in every state;
  only the state zone's contents change. The offer card and the sheets rise into the same frame (`HudSheetHost`).
- **Thumb reach.** Actions sit in the dock at the bottom, in reach of a thumb. The top of the panel is the
  furthest reach and sits nearest the bubble head, so it holds reads, not frequent actions.
- **Targets of at least 48 dp, and 64 dp for the dock blocks.**
- **Sheets, not screens.** An entry (cash tip, expense, fuel, odometer, goal) opens as an inline sheet with no
  scrim, so the instruments stay visible and live. One sheet is open at a time.
- **Write, then undo.** An entry saves on the commit tap and the undo strip follows for 10 seconds. The bubble
  never asks "Are you sure?".
- **Nothing that moves by itself** except values that change with time (countdowns, elapsed time) and the one-time
  goal-met sequence. No auto-scrolling text, no looping animation.
- **Leave for the main app** when the task needs reading, comparing or typing: history beyond the shift log,
  settings, the garage. The bubble opens the app with `openInApp`, which collapses the bubble.
- **Dark first.** The bubble is designed in the dark theme and checked in the light one.

### Main app rules
- **Material first.** Use the stock Material 3 component for the job (`Scaffold`, `TopAppBar`, `ListItem`,
  `Switch`, `ModalBottomSheet`, `SnackbarHost`, `AlertDialog`) before writing a custom one.
- **One screen, one job,** stated in the screen's title. Home is today, the hub is the past, the Playbook is the
  next move (#1024).
- **Two tiers of detail.** Compact by default; "More numbers" reveals the remaining figures and controls on the
  hub and the Playbook (#1255). One disclosure per screen holds the caveats (`HowNumbersWorkFooter`).
- **Settings save as you go.** On a vehicle's details page a finished field saves, there is no Save button, and a
  snackbar offers Undo for the latest change (#1383, #1385). Rebuilt settings screens follow the same pattern.
- **Rows follow Android's two-part pattern** where a row both selects and opens: the main area selects, a divider
  and a trailing icon open the details (Garage, #1379).

## Android and Material 3

DashBuddy follows Material 3 and Android's own guidance, in the current Material 3 Expressive version, through
Compose Material 3 (BOM in `gradle/libs.versions.toml`). The digest at `docs/design/material-digest.md` quotes the
rules with their sources; the rules below are the ones that come up most.

- **Spacing** on an 8 dp grid, 4 dp for small parts. Window margins 16 dp on a phone. Padding and gaps on the
  parent, not margins on children.
- **Targets** at least 48 × 48 dp, 8 dp apart. A small icon gets its full 48 dp touch area.
- **Text in sp,** never smaller than 12 sp for words. Test at the largest font scale (200 % on Android 14+).
  Spacing does not shrink as text grows.
- **Contrast** at least 4.5:1 for text under 18 sp (14 sp bold), 3:1 for larger text and for controls against
  their background. Colour never carries meaning alone; pair it with a word, shape or position.
- **Colour roles, never literal colours,** in composables. A colour literal lives only in `AppColors`.
- **TalkBack.** Every control has a name that says what it does, a `Role`, and a `stateDescription` when its state
  is not in its text. Decorative icons get `contentDescription = null`.
- **Back** works everywhere: the system back gesture closes a sheet before it leaves a screen (`BackHandler`), and
  predictive back is on (`enableOnBackInvokedCallback`).
- **Edge to edge** in the main app, with content kept out of the system bars' insets.
- **Motion:** new motion uses Material's spring tokens, and all motion respects the system's reduced-motion setting
  (`ValueAnimator.areAnimatorsEnabled()`, `hud/ReducedMotion.kt`).
- **Current Material 3 no longer recommends** segmented buttons (use the connected button group), the small FAB,
  and surface tint (use the surface container roles). New work avoids all three.

### Where DashBuddy departs from Material on purpose
- **No dynamic colour.** The palette is fixed and the same on every phone, so good, warn and bad always mean the
  same thing (decision 2026-06-09).
- **Brand fonts** instead of Roboto: Hanken Grotesk for words, Space Grotesk with tabular figures for every number.
- **A separate number scale** for the bubble (`AppTheme.num.*`), larger than Material's type scale, because the
  hero must read at a glance.
- **The bubble's sheets have no scrim,** unlike a Material bottom sheet, so the live figures stay visible behind
  them. They keep the bottom sheet's 28 dp top corners and drag handle.
- **The shift notification behind the bubble uses MessagingStyle,** because Android bubbles require a
  conversation notification (decision 2026-06-09).

## Colour

Two layers. `AppColors` (`core/designsystem/.../theme/AppColors.kt`) holds the brand tokens, one fixed set for
dark and one for light. `AppTheme.kt` maps them onto Material's colour roles so stock components render in the
brand. Dark is the default; the app follows the system setting.

| Material role | Brand token | Use |
|---|---|---|
| `background`, `surfaceContainerLowest` | `bg` | The window |
| `surface`, `surfaceContainerLow`, `surfaceContainer` | `surface` | Cards, the bubble's state zone |
| `surfaceContainerHigh` | `surface2` | Sheets, dock blocks, the goal pill |
| `surfaceContainerHighest`, `surfaceVariant` | `surface3` | Keypad keys, tracks, gauge rings, input fields |
| `onSurface` | `text` | Primary text and figures |
| `onSurfaceVariant` | `text2` | Secondary text and labels in the main app |
| `outline` / `outlineVariant` | `lineStrong` / `line` | Control borders / dividers |
| `primary` / `onPrimary` | `accent` / `accentText` | The one primary action, selected states |
| `primaryContainer` | `accentDim` | A lit dock block, tonal buttons |
| `secondary`, `tertiary` (+ containers) | `stOffer`, `stPickup` | Offer and pickup phases |
| `error` / `errorContainer` | `bad` / `badBg` | Errors and losses |

Rules:
- The main app reads `MaterialTheme.colorScheme`; the bubble and any meaning colour read `AppTheme.colors`. A
  meaning colour (`good`, `warn`, `bad`, `neutral`, the phase colours) has no Material role, so it is always read
  from `AppTheme.colors`.
- Surfaces step up one level per layer of containment: window `bg`, card `surface`, sheet `surface2`, control
  `surface3`. Overlapping areas never share a surface colour.
- **`text3` never carries words.** It is 4.2:1 on `bg` and 3.4:1 on `surface2` in the dark theme, under the 4.5:1
  small-text minimum, so it is for icon strokes, the drag handle and disabled states. Small labels use `neutral`
  (5.2 to 6.4:1).
- The phase colours: waiting `good`, paused `warn`, offer `stOffer`, pickup `stPickup`, dropoff `stDropoff`, off
  shift `rest`. Each phase also has a word on screen.
- The `*Bg` tokens are their base colour at 14 % (dark) or 12 % (light) alpha, for tinted chips and badges.

## Type

- **Words:** Hanken Grotesk through `AppTypography`, Material's scale (body 14 sp, labels 12 sp).
- **Numbers:** Space Grotesk with tabular figures through `AppTheme.num.*`: the Profit hero's dollars at 60 sp with
  cents at 28 sp (30 sp when compact), state figures at 48 and 56 sp, and steps of 22, 18, 15, 13, 11 and 10 sp. Every number on every surface uses a number
  style, so digits line up and do not jump as they tick.
- **Driving mode** multiplies the bubble's type by 1.12 (`DRIVING_GLANCE_MULTIPLIER`, #318). The main app never
  scales.
- **Font scale tiers in the bubble** (`FontScaleTier`, by font scale times the driving multiplier): at 1.3 labels
  move beside their figures, at 1.5 the job column drops, at 1.8 the hero turns compact.
- **No value** is the em dash (`EMPTY_VALUE`), never `$0.00` or `0`. Zero is shown only when the value is known to
  be zero.

## Shape and space

- Radii (`AppShapes`): 6, 10, 14, 20 and 28 dp. Pills use a full round shape.
- Bubble: 16 dp side margins (22 dp at the start, past the 6 dp phase strip); state zone and dock blocks 16 dp
  radius; sheets 28 dp top corners; 8 dp between dock blocks.
- Main app: Material's spacing and component shapes.

## Components

- Shared components live in `:core:designsystem` with the `App*` prefix (`AppCard`, `AppChip`, `AppStatTile`,
  `AppGaugeRing`, `AppSegmented`, `AppSlider`, `AppBarChart`, `AppAccordion`, `AppHeatScale`). They take data and
  callbacks and hold no state. Reuse or extend one before writing a new one; a component used by one feature stays
  in that feature.
- Bubble parts: `HudSheetHost` (the frame sheets rise into), `hud/keypad/` (the shared 3 × 4 keypad), `UndoStrip`,
  `Dock`, the vehicle chip.
- Amounts are picked with buttons, not chips: chips filter or suggest, they do not finish a task.
- Platform identity is a label and a colour only. No delivery-app wording in the app's own chrome.

## On-screen privacy

Store names and the dasher's own figures may show. A customer's name or address never shows. The dasher's banking
screens are never read, so their figures never appear. PRIVACY.md and CLAUDE.md § Development Principles 6 hold
the full rules.

## Settled decisions

- 2026-06-09: fixed brand palette, Hanken Grotesk and Space Grotesk, no dynamic colour, dark default that follows
  the system (#313, #94).
- 2026-06-09: the offer reaches the dasher as a heads-up notification with Accept and Decline; the bubble opens on
  tap, never by itself (#110).
- 2026-06-13: design-system names use `App*`, never a platform word (#468).
- #318: driving mode enlarges the bubble's type by 1.12; the main app never scales.
- #1024: three destinations: Home is today, the hub is the past, the Playbook is the next move.
- #1255: two presentation tiers behind one "More numbers" setting.
- 2026-10-06: copy follows `docs/design/copy-style.md`.
- #1333 (October 2026): the bubble is one read-and-act surface. Profit is the hero; Earned, Online and Miles sit in
  the top row; entries live in a thumb-zone dock and open inline sheets; undo replaces confirmation; a dropoff phase
  colour was added; labels use `neutral`, never `text3`. The offer heads-up shows the same offer card, without the
  verdict word, badges, trip line or gauge bars (S2g).
- #1379, #1383, #1385: the vehicle chip in the bubble head; settings save as you go with a snackbar Undo; the
  Garage uses two-part rows.
- 2026-10-29: "shift" replaces "dash" and "Profit" replaces "Kept" in copy; an offer's estimate keeps "net" (#1407).

## Open questions

- **The bubble declutter** (2026-10-21): the waiting and shift-start states carry more than a glance can read. A
  recommended option ("waiting first": one large wait clock, Earned secondary, vehicle and odometer visible, the
  rest moved to Details) waits on the developer. Notes in the design archive.
- **Light theme contrast.** On the light `bg`, small text in `accent` (3.0:1), `warn` (2.7:1), `good` (3.1:1) and
  `neutral` (4.0:1) falls under 4.5:1. Either darken those tokens or keep them off small text in the light theme.
- **Dropoff colour.** `stDropoff` is 3.8:1 on `surface2` in the dark theme; fine for the phase strip, too faint for
  small text on a sheet.
- **Keypad keys** are 38 dp tall at minimum (`hud/keypad/Keypad.kt`), under the 48 dp target. Raise them, or
  record why the keypad is the exception.
- **`AppSegmented`** follows the segmented button, which current Material no longer recommends. Replace it with
  the connected button group, or keep it as a brand component.

## Sources

- `docs/design/copy-style.md`: every user-facing word.
- `docs/design/material-digest.md`: Material 3 Expressive and Android guidance, quoted with URLs (read from
  m3.material.io on 2026-09-23).
- `docs/design/driving-digest.md`: how much a driver can read in a glance (Android for Cars, NHTSA, glance
  research).
- `docs/design/analytics-redesign-brief-2026-07-28.md`: the analytics hub's design brief.
- `core/designsystem/`: the tokens, type, shapes and shared components.
- CLAUDE.md § Reactive UI Principles (time values tick) and § Module Structure (where each surface's code lives).
- The bubble's design history (#1333 briefs, mockups, the declutter study) is kept outside the repo in the
  developer's design archive; issue #1333 and its slice pull requests summarise it.
