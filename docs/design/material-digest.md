# Material 3 and Android design digest for a small floating glance surface

Purpose: one place a designer can read to lay out the expanded view of an Android bubble (about phone width by 600 dp, used by a delivery driver between stops) with a native feel across Android 11 to 16. Everything below is quoted or tabulated from the sources named after each section. Where two sources disagree, both are shown. Nothing here is an opinion; "Gap" lines say where the sources are silent.

## 0. How this was gathered (read once)

- m3.material.io is a client-rendered app, so a plain page fetch returns only the title. The text below was read from the site's own page-content JSON (`m3.material.io/_dsm/content/m3/2026-09-23_06-10-05/<page>.json`, content version 2026-09-23, pages last updated 2026-05 to 2026-08). Page URLs given are the public ones.
- Numeric tokens that m3.material.io renders in a widget (type scale, shape, elevation, state opacity, component sizes, motion) were read from the Jetpack Compose Material 3 source (`androidx.compose.material3.tokens.*`, androidx-main), which is generated from the same token set. These are marked "(Compose tokens)".
- M3 Expressive (May 2025 onward) is the current M3. It changed several things: the spacing system is on an 8 dp scale, segmented buttons are "no longer recommended", the small FAB is "no longer recommended", surface tint is deprecated, "window size classes" were renamed "breakpoints", motion moved from easing/duration to springs.
- Android platform text came from developer.android.com (design and develop sections).

---

## 1. Layout

### 1.1 Grid and spacing units
- Android design guide: "If using a baseline grid, stick to measurements of 4 and 8." "Android UI utilizes an 8 dp grid for layout, components, and spacing." "Smaller elements such as icons, type, and some elements within components are best aligned to a 4 dp grid." Caption: "8-dp grids are ideal for most UI elements, while a 4-dp grid is better for smaller elements such as icons".
- M3 spacing system: "The spacing system is measured on an 8dp scale, where space100 = 8dp". "Spacing units follow an 8dp scale. Rather than defining every value, Material only defines the most recommended spacing unit values on the scale." Scale shown from 2, 4, 6, 8 at the low end to 48, 56, 64, 72 at the high end (0x to 9x of 8 dp).
- Nested units: "Values other than multiples of 8 are also used in layouts and Material components, like 2dp, 4dp, 6dp, and 10dp." Tokens exist for 0.25x (2 dp), 0.5x (4 dp), 0.75x (6 dp), 1.25x (10 dp). A custom token follows the multiplier: "space225 = 18dp (8dp x 2.25)".
- Three kinds of space: "Padding: Space inside an element. Gap: Space between elements in a grid or container. Margins: Space outside an element."
- "Use padding & gaps before using margins: Material rarely uses margins in components... Only use margins to apply further spacing beyond the parent container's padding, or in layouts." "Define padding and gaps on the parent container... Avoid defining margins on child elements."
- "When text is scaled up to 200%, the same spacing should be preserved by default." (do not shrink spacing as text grows)
- Jetpack Compose is the only platform with the spacing tokens ("The spacing system tokens are only used on Jetpack Compose").
- Gap (sources silent): no fetched current M3 page states an "8 dp gutter" or a column-gutter number. The only stated between-pane value is the 24 dp spacer (1.2). Card spec: "Padding between cards: 8dp max". Chips: "Padding between elements 8dp".
- URLs: https://m3.material.io/styles/spacing ; https://m3.material.io/foundations/layout/grids-spacing ; https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units

### 1.2 Margins, spacers, columns per breakpoint
| Breakpoint | Width | Margin | Space between panes | Recommended panes |
|---|---|---|---|---|
| Compact | under 600 dp | 16 dp | n/a | 1 |
| Medium | 600 to 839 dp | 24 dp | 24 dp | 1 (2 allowed) |
| Expanded | 840 to 1199 dp | 24 dp | 24 dp | 2 (1 allowed) |
| Large | 1200 to 1599 dp | 24 dp | 24 dp | 2 |
| Extra-large | 1600 dp+ | 24 dp | 24 dp | 2 (1 or 3 allowed) |
- Quotes: "In compact layouts, use 16dp margins." "Margins are 16dp from the leading and trailing edge of the window." "Spacers measure 24dp wide." Margin definition: "The space between the edge of the screen and any elements inside of it."
- Android guide: "A standard margin value for compact sizing is 16 dp, but margins should adapt to accommodate larger screens. Your app's body content and actions must stay within and align with these margins." "Mobile screens are often divided into four columns." On compact screens "fewer columns are used to create a focused layout"; the M3 compact example uses 4 columns, a foldable 8.
- Text line length: "Across all breakpoints, adjust margins and type styles to keep text between 40-60 characters per line."
- URLs: https://m3.material.io/foundations/layout/breakpoints ; https://m3.material.io/foundations/layout/layout-overview ; https://developer.android.com/design/ui/mobile/guides/layout-and-content/content-structure

### 1.3 Window size classes (what a bubble's window falls into)
- Width: Compact under 600 dp ("99.96% of phones in portrait"); Medium 600 to under 840; Expanded 840 to under 1200; Large 1200 to under 1600; Extra-large 1600+. Height: Compact under 480 dp, Medium 480 to under 900, Expanded 900+.
- "Window size classes are explicitly not determined by the size of the device screen... determined by the window size available to your application regardless of the type of device." "Physical devices do not guarantee a specific window size class." (A bubble window is therefore classified by its own width and height.)
- "Most apps can build an adaptive UI by considering only the width window size class. However, also consider the height window size class for scenarios such as phones... in which case two pane layouts are not practical."
- M3: "Design for breakpoints instead of specific devices because: The amount of available window space is dynamic and changes based on user behavior, such as multi-window modes..."
- URLs: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes ; https://m3.material.io/foundations/layout/breakpoints

### 1.4 Regions and panes in a compact window
- Scaffold: "The layout scaffold structures every piece of an adaptive layout into bars, rails, and panes." "Bars can frame the page..." "Rails create the perimeter space surrounding panes..." "Panes hold a product's primary content." Regions are safety region (system UI), bar, rail, pane.
- "All content must be in a pane. A layout can contain 1-3 panes." Compact: recommended pane total 1, no other totals. "All layouts need at least one flexible pane." "Single-pane layouts use one flexible pane that extends to fit the available space in a layout's width."
- Compact rails: "On compact screens, the top and bottom rail regions can be used for components like: Toolbars, Chat inputs, FABs, Other primary controls related to an individual screen". App bar: "providing a title and 1-2 essential actions". Navigation bar: "switch between 3-5 primary UI views".
- Safety region: "The safety region shouldn't contain primary content."
- Supporting pane (primary plus secondary content): "Use the supporting pane layout when the secondary content is only meaningful in relation to the primary content." Compact: "The supporting pane should appear below the focus pane. A bottom sheet can be useful for keeping focus on the primary pane while providing access to supporting information." Pane width below = flexible; beside (Expanded) = fixed 360 dp.
- Compact action surface: breakpoint table lists for Compact "Action: Bottom sheet"; "Communication: Simple dialog, Full-screen dialog".
- Containment: "Explicit grouping uses visual boundaries like outlines, dividers, and shadows... Implicit grouping uses close proximity and open space (rather than lines and shadows)."
- URLs: https://m3.material.io/foundations/layout/scaffold ; https://m3.material.io/foundations/layout/canonical-examples ; https://m3.material.io/foundations/layout/breakpoints

### 1.5 Alignment, hierarchy, grouping
- Rulers: "Rulers are a set of recommended global alignment lines that help create consistent focal points in a product." Content rulers: "Use content rulers to align and anchor key content, such as headlines".
- Grouping principles (M3): Rhythm ("Consistent spacing between related elements or groups makes them easier to navigate with the eye."), Similarity ("Similar elements should have the same spacing and sizing... Leading elements like thumbnails, avatars, or icons should always be aligned."), Proximity ("buttons should be close to the content they're affecting"), Continuity ("Use a row of chips to signal a single, unified control").
- "Give the most important content, tasks, or actions visual prominence with generous spacing and the brightest surfaces." "Consistent placement of key actions and information helps build recognizable focal points across a product."
- Usability: "The size and scale of elements shows their level of importance. The most important action or the main call to action (CTA) should be the largest element." "Avoid crowding the screen with too many large or equally prominent elements. Scale and placement create a clear focal point."
- Android: "Provide consistent alignment between similar content and UI elements." "Don't overwhelm your user with too many actions per view." "Keep essential interactions, like primary navigation, in a reachable screen area." Hierarchy cues: "Place important actions at the top or bottom of the screen (reachable with shortcuts); Place related items of a similar hierarchy next to each other".
- Density: "Information density is the consideration of the amount of information visible on the screen." "Density shouldn't automatically change across breakpoints or orientation unless a person changes it." "Don't scale layouts below 48x48dp by default." "Don't increase the density in components that alert a person of changes, such as snackbars or dialogs." "Higher density is typically applied by decreasing the top and bottom padding or overall height by 4dp."
- URLs: https://m3.material.io/foundations/layout/grids-spacing ; https://m3.material.io/foundations/usability ; https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-basics

---

## 2. Touch targets and accessibility

### 2.1 Targets
- M3: "For most platforms, consider making touch targets at least 48 x 48dp. A touch target this size results in a physical size of about 9mm, regardless of screen size. The recommended target size for touchscreen elements is 7-10mm." "an icon may appear to be 24 x 24dp, but the padding surrounding it comprises the full 48 x 48dp touch target." "Note: iOS recommends 44 x 44dp targets."
- Spacing: "In most cases, targets separated by 8dp of space or more promote balanced information density and usability." (example: 48 dp targets with 8 dp padding between).
- Android: "Ensure all touch targets are at least 48 dp, even if this extends past the UI element visual." "we recommend that each interactive UI element have a focusable area, or touch target size, of at least 48dpx48dp. Larger is even better."
- Compose: "make sure to set the minimum size to 48dp... Material components—like Checkbox, RadioButton, Switch, Slider, and Surface—set this minimum size internally, but only when the component can receive user actions." Custom elements: `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`. "many built-in Material components like Button, IconButton, and ListItem already enforce this minimum size. However, when creating custom interactive elements, you need to set the size yourself."
- Visual smaller than target is fine: "Button with height of 36dp and interaction target of 48dp." Chips are 32 dp tall (see 5.2); a secondary trailing icon "must have a 48x48dp interaction target that doesn't interfere with the chip's primary action... apply a minimum width of 88dp to the chip".
- Gestures: "Don't rely on gestures to complete all actions; create accessibility actions to support all user flows in your app." "Include a single-pointer alternative for any action that can be completed by dragging."
- Gesture insets: predictive back "respects gesture insets... Avoid adding touch gestures or drag targets under these gesture areas." "Avoid placing touch targets completely under these areas".
- URLs: https://m3.material.io/foundations/designing/structure ; https://developer.android.com/design/ui/mobile/guides/foundations/accessibility ; https://developer.android.com/develop/ui/compose/accessibility/api-defaults ; https://developer.android.com/guide/topics/ui/accessibility/apps

### 2.2 Contrast
- M3 table: "Large text (at 14 pt bold/18 pt regular and up) and graphics: At least 3:1 against the background"; "Small text: At least 4.5:1 against the background". "Disabled states do not need to meet contrast requirements." Non-text: "button containers, should meet a contrast ratio of 3:1 between their container color and the color of their background". "Elements that stand on their own... such as a FAB... don't benefit from 3:1". In a cluster "each achieve at least 3:1".
- Android (sp thresholds): "If the text is smaller than 18sp, or if the text is bold and smaller than 14sp, use foreground and background colors that result in a color contrast ratio of at least 4.5:1. For all other text, set the color contrast ratio to at least 3:1." Design page: "Ensure the contrast between the background and text is at least 4.5:1." "Use a 3:1 ratio between surfaces and non-text elements."
- Roles: "Any color roles starting with 'on-' are guaranteed to have sufficient contrast with the corresponding color role. Other color role pairs may not meet the 4.5:1 (small text) and 3:1 (large text) Material contrast requirements." "The color system is built on accessible color pairings. These color pairs provide an accessible minimum 3:1 contrast."
- Outline: "Don't use the outline variant color to create visual hierarchy or define the visual boundary of targets. Instead, use the outline color or another color providing 3:1 contrast with the surface color."
- Color is never the only cue: "use cues other than color to distinguish UI elements... different shapes or sizes, providing text or visual patterns, or adding audio- or touch-based (haptic) feedback". Android also: "Use more than one visual affordance for actions like links."
- URLs: https://m3.material.io/foundations/designing/structure ; https://developer.android.com/guide/topics/ui/accessibility/apps ; https://developer.android.com/design/ui/mobile/guides/foundations/accessibility ; https://m3.material.io/styles/color/advanced

### 2.3 Text size and scaling
- "To allow users to adjust the font size, specify font size in scalable pixels (sp)". "Don't make the body size any smaller than 12 sp. This guideline aligns with the Material typescale as a default." "Always specify font sizes in sp units."
- Android 14+: "the system supports font scaling up to 200%"; "the system applies a nonlinear scaling curve... large text doesn't scale at the same rate as smaller text." "you should still perform UI testing with the maximum font size enabled (200%)". "Don't use sp units for padding or define view heights assuming implicit padding: with nonlinear font scaling sp dimensions might not be proportional, so 4sp + 20sp might not equal 24sp." Use `TypedValue.applyDimension()`; avoid `Configuration.fontScale` / `scaledDensity` math.
- M3: "Ignoring language height can lead to overlapping text and broken UI elements" (line heights adapt by script: Latin base, "Medium (~7% taller)", "Large (~30% taller)", "Extra large (~100% taller)"). "Components with fixed heights are built for small values and may not adapt by default."
- URLs: https://developer.android.com/design/ui/mobile/guides/foundations/accessibility ; https://developer.android.com/about/versions/14/features ; https://m3.material.io/styles/typography

### 2.4 TalkBack and semantics
- "Describe UI elements in your code. Compose uses Semantics properties to inform accessibility services." "Set decorative item descriptions to null."
- Labels: "Use descriptions to convey the purpose and result of the interaction, not the visual details. Use the Role semantics property (like Role.Button or Role.Switch) to expose a UI element's type." "Avoid redundancies... make the button's description 'Submit', not 'Submit button'." "Each description should be unique." "you do not need to provide a contentDescription for Text composables." Mark decorative with `hideFromAccessibility` or `contentDescription = null`.
- Complex interaction: "provide descriptive labels for standard tap actions using parameters like onClickLabel"; for swipe or drag "use customActions".
- Collections: unique `collectionItemInfo` per item. Do not aggregate children text into a group description.
- M3 labels: label "Interactive icons or buttons with no visible text", "Visual cues (including progress bars and error handling)", "Meaningful icons (such as status icons)". Not needed: "Non-interactive UI text, as this will be automatically read by the screen reader". "Labels should concisely describe an element's content, purpose and behavior." Example: "Voice Search" not "Microphone".
- Snackbar announcement: "On Android and web, use a live region with a polite (queued) announcement instead of an assertive announcement." Do not move focus.
- URLs: https://developer.android.com/guide/topics/ui/accessibility/apps ; https://developer.android.com/develop/ui/compose/accessibility/semantics ; https://m3.material.io/foundations/designing/structure ; https://m3.material.io/components/snackbar

---

## 3. Typography

### 3.1 Scale (Compose tokens, sp / line height sp / tracking sp / weight)
| Style | Size | Line height | Tracking | Weight |
|---|---|---|---|---|
| Display Large | 57 | 64 | -0.2 | Regular |
| Display Medium | 45 | 52 | 0 | Regular |
| Display Small | 36 | 44 | 0 | Regular |
| Headline Large | 32 | 40 | 0 | Regular |
| Headline Medium | 28 | 36 | 0 | Regular |
| Headline Small | 24 | 32 | 0 | Regular |
| Title Large | 22 | 28 | 0 | Regular |
| Title Medium | 16 | 24 | 0.2 | Medium |
| Title Small | 14 | 20 | 0.1 | Medium |
| Body Large | 16 | 24 | 0.5 | Regular |
| Body Medium | 14 | 20 | 0.2 | Regular |
| Body Small | 12 | 16 | 0.4 | Regular |
| Label Large | 14 | 20 | 0.1 | Medium |
| Label Medium | 12 | 16 | 0.5 | Medium |
| Label Small | 11 | 16 | 0.5 | Medium |
- Emphasized set (May 2025): "15 baseline and 15 emphasized". Same sizes and line heights; one weight step up (Regular to Medium, Medium to Bold). "Material recommends using emphasized styles for selection, actions, headlines, and other editorial treatments." Good for "Badges, Buttons (for primary actions), Extended FAB, Selected list items".
- "Material Design uses the Major Second type scale (1.125) with 14 as its key base size." "Avoid changing the type size; this can affect how components render and reflow."
- Default typeface Roboto; brand and plain typefaces are separate slots: "The brand typeface is used for larger type styles, like Headline and Display... The plain typeface is used for smaller type styles, like Body and Label."
- URL: https://m3.material.io/styles/typography ; tokens: androidx.compose.material3.tokens.TypeScaleTokens

### 3.2 Roles, and display versus headline for a number
- "Display... As the largest text on the screen, display styles are reserved for short, important text or numerals. They work best on large screens."
- "Headlines are best-suited for short, high-emphasis text on smaller screens. These styles can be good for marking primary passages of text or important regions of content." (Example in M3: a contact name in headline style on a card; "Dialog using a headline style".)
- "Titles... medium-emphasis text that remains relatively short... divide secondary passages of text or secondary regions of content." Body: "longer passages of text". Label: "smaller, utilitarian styles, used for things like the text inside components or for very small text in the content body, such as captions. Buttons, for example, use the label large style." Chip labels use Label Large (Compose tokens).
- Display and headline sizes scale up with the device: "Scaling from watch to mobile: Increase the size of headlines, display, and body. Scaling from mobile or tablet to desktop: Increase the size of display and headline."
- Number emphasis in practice (M3 usability example): "Key numbers like 18 and 3min are larger and use emphasized styles, making it easy to scan the most important data." "Custom-scaled numbers for key statistics... are large enough to scan, but they don't dominate the screen." "The largest, most legible text on the screen could signal a primary action."
- Gap: M3 gives no numeric rule for when a number crosses from headline to display; the stated difference is "display... works best on large screens", headline "on smaller screens".

### 3.3 Tabular figures and line height
- "Use tabular figures (also known as monospaced numbers) rather than proportional digits in tables or places where values may change often, such as clocks." "Use tabular numbers to prevent layout shifting when values change, such as in a clock UI." "Use monospaced tabular numbers to keep values optically aligned for better scanning."
- Line height: "For larger type legibility using styles like title, headline, and display, we recommend a line height ratio of 1.2 times the type size." "For smaller body copy using styles like body and label, we recommend a line height ratio around 1.5 times the type size." (The token table above shows 1.12 to 1.4 for display and about 1.4 to 1.5 for body.)
- Text colour: "The default color for typography is on surface, although on surface variant is a strong alternative."
- Android: spacing measured to the baseline ("Android screens rely on distance to baselines for spacing").
- URL: https://m3.material.io/styles/typography

---

## 4. Color

### 4.1 Roles
- 26 standard roles in six groups: primary, secondary, tertiary, error, surface, outline. Vocabulary: "Surface – backgrounds and large, low-emphasis areas." "Container – Roles used as a fill color for foreground elements like buttons. They should not be used for text or icons." "On – ...text or icons on top of its paired parent color." "Variant – lower emphasis alternative."
- "Primary roles are for important actions and elements needing the most emphasis, like a FAB." "Secondary roles are for elements that don't need immediate attention and don't need emphasis, like the selected state of a navigation icon or a dismissive button." "Tertiary roles are for smaller elements that need special emphasis but don't require immediate attention, such as a badge or notification." "The tertiary color roles can be applied at the designer's discretion."
- Primary: primary / on primary / primary container / on primary container ("Standout fill color against surface, for key components like FAB"). Secondary container: "for recessive components like tonal buttons"; secondary "for less prominent components in the UI such as filter chips".
- Error: "Error is an example of a static color (it doesn't change even in dynamic color schemes)."
- Surface: "Surface: Default color for backgrounds. On surface: Text and icons against any surface or surface container color. On surface variant: Lower-emphasis color for text and icons..." Five containers: lowest, low, "Surface container: Default container color", high, highest. "The most common combination of surface roles uses surface for a background area and surface container for a navigation area." "All color mappings – but especially surface colors – should remain the same for layout regions across breakpoints."
- Inverse: inverse surface / inverse on surface / inverse primary (used by the snackbar).
- Outline: "Outline: Important boundaries, such as a text field outline. Outline variant: Decorative elements, such as dividers". "Don't use the outline color for dividers... Instead, use outline variant." "Don't use the outline color for components that contain multiple elements, such as cards. Instead, use outline variant."
- Pair only as intended: "apply colors only in the intended pairs or layering orders... Combining colors improperly may break contrast".
- URL: https://m3.material.io/styles/color/roles

### 4.2 Fixed brand palette (no dynamic color)
- Static schemes: "Static colors won't ever change based on user input or in-app content." "Use static (baseline) color if... Your product is for enterprise users who wouldn't benefit from personalized color or user-controlled contrast settings." Costs: "What you don't get: Personalized colors; Colors that change based on user's wallpaper...; User-controlled contrast settings."
- Custom brand: "colors are hand-picked by your team... Brand-based schemes are entirely created and maintained by your team." Input primary, secondary, tertiary, neutral, neutral variant and "Material will provide back the scheme's regular color roles with values derived from your reference colors." Convention: "primary and tertiary colors are the most visually prominent... Secondary, neutral variant, and neutral colors match primary in hue but are progressively less chromatic in that order."
- Mapping custom components: "Apply M3 baseline color roles to custom components and UI elements". "Always apply color roles rather than static values or tonal palette values, as these colors will break with light and dark themes, contrast control, and other features."
- Extra semantic colors: "You can define additional colors in your scheme that stay static even when other colors dynamically change... Material will return four derived color roles" (main, on-main, container, on-container). "Material provides the red Error color out of the box... so you do not need to define your own static color for a semantic red color." Custom roles need "a Material palette... and a reference tone", "Color pairings", and confirmed "contrast minimums".
- Baseline role to tone mapping, light / dark (Compose tokens): Primary 40 / 80; On primary 100 / 20; Primary container 90 / 30; On primary container 10 / 90; Secondary 40 / 80; Secondary container 90 / 30; On secondary container 10 / 90; Tertiary 40 / 80; Tertiary container 90 / 30; Error 40 / 80; Error container 90 / 30; On error 100 / 20; Surface and Background (neutral) 98 / 6; On surface 10 / 90; On surface variant (neutral variant) 30 / 80; Outline (nv) 50 / 60; Outline variant (nv) 80 / 30; Surface container lowest 100 / 4; low 96 / 10; container 94 / 12; high 92 / 17; highest 90 / 22; Surface dim 87 / 6; Surface bright 98 / 24; Inverse surface 20 / 90; Inverse on surface 95 / 20; Inverse primary 80 / 40; Scrim 0 / 0.
- URLs: https://m3.material.io/styles/color/choosing-a-scheme ; https://m3.material.io/styles/color/static ; https://m3.material.io/styles/color/advanced ; https://m3.material.io/styles/color/system

### 4.3 Elevation and dark theme
- "Elevation can be shown as tonal surface colors or shadows." "Surface tint color is deprecated. Use elevation level tokens (0–5) instead." Surface container roles "are not tied to elevation, and provide flexibility for defining containment areas." "Any overlapping containment areas or components should have different color roles in order to visually communicate separation."
- Levels (Compose tokens): 0 = 0 dp, 1 = 1 dp, 2 = 3 dp, 3 = 6 dp, 4 = 8 dp, 5 = 12 dp. "An element's resting state can be on levels 0 to +3, while levels +4 and +5 are reserved for user-interacted states such as hover and dragged." Resting levels: 0 for filled and outlined cards, chips, icon buttons, lists, tabs, sliders, segmented buttons; 1 for elevated card, elevated chip, modal bottom sheet, banner; 2 for menu, navigation bar, scrolled app bar; 3 for FAB, dialog (modal), extended FAB, search.
- "Avoid changing the default elevation of Material 3 components." "Stick to using a small amount of elevation levels." "Instead of applying shadows by default to all levels, use shadows only when required to create additional protection against a background or to encourage interaction."
- "Scrims use the scrim color role at an opacity of 32%."
- "Interactive elements ... edges must create sufficient contrast between surfaces (by meeting or exceeding accessible contrast ratios) for them to be seen as separate".
- URL: https://m3.material.io/styles/elevation

### 4.4 State layers and disabled
- "A state layer is a semi-transparent covering on an element that indicates its state... only one state layer can be applied at a given time." Overlay color is the content color (usually the on-color). Values: "Hover +8% opacity, Focus +10% opacity, Press +10% opacity, Drag +16% opacity" (Compose `StateTokens`: 0.08, 0.10, 0.10, 0.16).
- "The size of state layers is 40dp while the interactive target size is 48dp."
- Activated (selected): "the container and content of activated components change color directly. Activated components use the secondary container color for the component container and change the content color to on secondary container."
- "States have two visual indicators to ensure accessibility."
- Disabled: "communicated through color changes and reduced elevation." "Disabled states don't need to meet Material's contrast requirements." Inherited by buttons, cards, checkboxes, chips, list items, radio, switches, text fields. Not inherited by app bars, badges, dialogs, FABs, menus, navigation, sheets, tabs, tooltips: "If the action represented in the FAB is unavailable, the FAB shouldn't appear". "Disabled components can't be focused, dragged, or pressed."
- Disabled opacities (Compose tokens): label, icon and input text 0.38 of on-surface; container fill 0.12 (chips, segmented outline, slider inactive track) or 0.10 (filled button) of on-surface; filled text field container 0.04.
- URL: https://m3.material.io/foundations/interaction/states

---

## 5. Components (only the rules that bear on this surface)

### 5.1 Bottom sheets (the closest M3 analogue to a bubble's expanded view)
- "Use bottom sheets in compact and medium breakpoints. Two variants: standard and modal. Content should be additional or secondary (not the app's main content). Bottom sheets can be dismissed in order to interact with the main content."
- Standard: "co-exist with the screen's main UI region and allow for simultaneously viewing and interacting with both regions". Modal: "appear in front of app content, disabling all other app functionality... remaining on screen until confirmed, dismissed, or a required action has been taken." "Modal bottom sheets are used in mobile apps only."
- Max height: "the initial vertical position of modal bottom sheets is capped at 50% of the screen height"; larger content "can then be pulled across the full screen and scrolled internally".
- Measurements: container surface container low; shape 28 dp top corners (extra large top); "Width: Full width, up to max-width 640dp"; "Top margin 72dp" (56 dp when width over 640 dp, with 56 dp side margins); drag handle 32 x 4 dp (Compose tokens), "Drag handle padding top/bottom 22dp", colour on surface variant; elevation level 1; "Bottom sheets can be horizontally scrolled" and "should be scrollable when their content exceeds the initial viewable height".
- Drag handle a11y: "The top 48dp portion of the bottom sheet is interactive when user-initiated resizing is available and the drag handle is present." "Selecting the drag handle should toggle through preset heights or close the sheet, while selecting the scrim should always close the bottom sheet." "If the bottom sheet has multiple preset heights but can't use a drag handle, Material requires the inclusion of a single-pointer alternative to change height."
- Dismiss: tapping an item, tapping the scrim, swiping down, a close affordance in a full-screen sheet. Predictive back: "Bottom sheet detaches from the left and right edges of the screen to signal it will close".
- Contents allowed: "menu items (in list or grid layouts), actions, and supplemental content"; optional list items, dividers, media.
- URL: https://m3.material.io/components/bottom-sheets

### 5.2 Chips
- "Use chips to show options for a specific context. Four variants: assist, filter, input, and suggestion." Container height 32 dp, 8 dp corner radius, icon 18 dp, label Label Large, "Left/right padding 16dp", "with icon 8dp", "Padding between elements 8dp". Outline is outline variant, 1 dp. Elevation 0; "Chip containers can be elevated if placed on top of an image or dynamic background... Chips shouldn't be elevated when placed directly on the page."
- Variant choice table: Action = assist chip ("smart or automated actions that can span multiple apps"); Filter = filter chip ("filters for a collection"); Information user-authored = input chip ("discrete pieces of information entered by a person"); Information product-authored = suggestion chip ("dynamically-generated suggestions", example "Suggested chat response").
- Filter chip: "Tap a chip to select it. Multiple chips can be selected or unselected. Alternatively, a single chip can be selected. This offers an alternative to segmented buttons, radio buttons, or single select menus. However, avoid mixing chip set behaviors. All chip sets on a page should be either single-select or multi-select." Selected = secondary container with leading check. "Write filter chips with nouns". "Filter chips should not present only a single option."
- Suggestion chips "help narrow a person's intent". Assist chips "Write assist chips like buttons: start with a verb".
- Rules: "Chips aren't buttons... Use chips to enhance a person's current journey... Use buttons to progress them through the product and for significant actions." "Chips represent forking paths for a current task, while buttons represent linear steps." "Avoid using chips to finish or progress a task." "Don't display a single chip by itself. Chips should appear in a set." "Chip label text should be 20 characters or fewer." "Multiple chips should appear together in a set, whereas there should be no more than 3 buttons in a single arrangement."
- Overflow: chips wrap to a new row; "If there are more than two rows, consider using horizontal scrolling". Accessibility: "When there are too many chips to fit on one row, provide a way to display them all at once and avoid scrolling." "The chip label needs at least 3:1 contrast with the background." "Don't apply density to chips by default — this lowers their targets below our best practice of 48x48".
- Gap: the sources do not name a component for picking among preset dollar amounts. Chips (single-select filter chips) and the connected button group (5.3) are the two selection components the sources describe for choosing one of several short options.
- URL: https://m3.material.io/components/chips

### 5.3 Segmented buttons and the connected button group
- Segmented button: "no longer recommended in the Material 3 expressive update... use the connected button group instead". Legacy spec: "Use for simple choices between two to five items (for more items or complex choices, use chips)", height 40 dp, fully rounded, 1 dp outline, "Target size 48dp", "Segmented buttons can have 2-5 segments", "Don't allow segments to wrap onto a new line", "Avoid mixing icon-only labels with text labels", "Left/right padding Min 12dp".
- Connected button group: "Connected button groups replace the segmented button"; sizes XS to XL; "Use connected button groups when the button content is related, and buttons can be selected"; supports single-select, multi-select, selection-required. "For all connected button groups, use 2dp padding." Inner corner radius (round group): XS 4, S 8, M 8, L 16, XL 20 dp. "Extra small and small connected button groups have 48dp target areas and a minimum width of 48dp." "The connected button group should span the width of the page or surface it's placed on." "Button groups should move through layouts together in a single line. They shouldn't wrap to a second line." "Avoid mixing color styles in connected button groups."
- URLs: https://m3.material.io/components/segmented-buttons ; https://m3.material.io/components/button-groups

### 5.4 Icon buttons and FAB
- Icon buttons: "must use a system icon with a clear meaning". "In toggle buttons, use the outlined style of an icon for the unselected state, and the filled style for the selected state". "Extra small and small icon buttons must have a target size of 48x48dp or larger to be accessible." "The target size of each icon button should be at least 48dp, even when nested." Small icon button container 40 dp (Compose tokens), icon 24 dp.
- FAB: "Use a FAB for the most important action on a screen; it appears in front of all other content." Sizes: FAB 56 dp, medium 80 dp (most recommended), large 96 dp (Compose tokens); "The small FAB is no longer recommended." Resting elevation level 3 (6 dp).
- When a FAB is wrong: "Avoid using a FAB for minor or destructive actions, such as: Archive or trash; Alerts or errors; Limited tasks like cutting text; Controls better suited to a toolbar, like to adjust volume or font color". "Don't display multiple FABs on a single screen." "Individual components, such as cards, shouldn't have their own FAB." "A FAB shouldn't contain notifications or actions found elsewhere on a screen." "FABs are not needed on every screen, such as when images represent primary actions." Right actions: "Create, Favorite, Share, Start a process." Snackbars appear above FABs.
- URLs: https://m3.material.io/components/icon-buttons ; https://m3.material.io/components/floating-action-button

### 5.5 Cards
- "Use cards to contain related elements. Three variants: elevated, filled, outlined." Spec: shape 12 dp corners; "Left/right padding 16dp"; "Padding between cards 8dp max". Elevated = surface container low, level 1 (1 dp); filled = surface container highest, level 0; outlined = surface with outline variant stroke, level 0 (Compose tokens: 1 dp outline).
- "Each provides the same legibility and functionality, so the variant you use depends on style alone." Separation: elevated "more separation from the background than filled cards, but less than outlined cards".
- "Use a card to display content and actions on a single topic." "Don't force content into cards when spacing, headlines, or dividers would create a simpler visual hierarchy." "By default, cards in a collection are coplanar." Dividers in cards: "full-width dividers for content that can be expanded"; "inset dividers... to separate related content". "It isn't recommended to place text or icons on images."
- Shape caution: "Be careful not to apply large or full corners to information-dense components, such as cards".
- URL: https://m3.material.io/components/cards

### 5.6 Lists, list items, dividers
- "Use lists to help people find a specific item and act on it... Keep items short and easy to scan. Show icons, text, and actions in a consistent format." Height: "The tallest element within a list item determines the list item's height - either 56dp, 72dp, or 88dp" (one, two, three lines). "In most cases, elements in a list item are middle-aligned. If a list is 88dp or larger, or contains three or more lines of text, elements are top-aligned."
- Baseline spec: label and leading left padding 16 dp; trailing element left padding 16 dp, right padding 24 dp (spec table; Compose tokens use 16 dp trailing); leading avatar 40 dp, leading icon 24 dp, leading image 56 dp; "Targets 48dp"; divider inset left 16 dp, right 24 dp (spec) or 16 dp (tokens). Expressive segmented list: unselected "4dp inner, 16dp outer" corners, selected 16 dp, 2 dp gap.
- Slots: "Slots are not accessible by default... Use standard list item padding. Target size must be at least 48x48dp. Don't add interactive elements that make the list item difficult to navigate".
- Dividers: "Make dividers visible but not bold. Only use dividers if items can't be grouped with open space. Use dividers to group things, not separate individual items." 1 dp, outline variant. Full-width "sparingly. Too many divider lines will make an interface look cluttered"; inset "equally indented from both sides... 16dp". Mixed use: full-width for different kinds of content, inset for nested items.
- URLs: https://m3.material.io/components/lists ; https://m3.material.io/components/divider

### 5.7 Snackbar with an action (the undo pattern)
- "Snackbars shouldn't interrupt the user's experience... Usually appear at the bottom of the UI. Can disappear on their own or remain on screen until the user takes action." "Only one snackbar may be displayed at a time." "A snackbar can contain a single action. 'Dismiss' or 'cancel' actions are optional." "To allow users to amend choices, display an 'Undo' action." "A dismiss action is unnecessary, as snackbar disappear on their own by default."
- Timing: "Snackbars without actions can auto-dismiss after 4–10 seconds, depending on platform." "Snackbars with actions should remain on the screen until the user takes an action on the snackbar, or dismisses it." Accessibility: "Snackbars with actions shouldn't auto-dismiss. This way, users can read and interact with it at their own pace." "Each platform has its own requirements for auto-dismiss durations, however common acceptable durations are 4–10 seconds."
- Compose behaviour: `showSnackbar` default duration is Short (4 000 ms) with no action and Indefinite with an action; Long = 10 000 ms; the timeout is passed through `AccessibilityManager.calculateRecommendedTimeoutMillis`. (Compose source, SnackbarHost.kt.)
- Content: "In compact breakpoints, the text label can contain up to two lines of text." "Keep the snackbar text label to one line long when possible." "Avoid adding icons to snackbars." "Don't use a filled or elevated button in a snackbar"; action is a text button in inverse primary. "Snackbars shouldn't be the only way to access a core use case." "Consecutive snackbars must appear one at a time... a snackbar with updated information can immediately replace an outdated snackbar." Dialogs for high priority: "Dialogs require immediate action".
- Look: inverse surface container, inverse on surface text, 4 dp corner (extra small), elevation level 3 (6 dp). Height "expand vertically from 48dp to 64dp" in the spec text; Compose tokens: single line 48 dp, two lines 68 dp.
- Placement: bottom of the UI, in front of main content; "Avoid placing a snackbar in front of frequently used touch targets or navigation." "Snackbars should appear above FABs." "Don't animate other components along with snackbar animations."
- Focus: "announce the message but don't move focus"; "Don't trap focus"; polite live region.
- URL: https://m3.material.io/components/snackbar

### 5.8 Dollar amounts: text field, slider, stepper
- M3 has no stepper component page. Sources that touch the pattern: Text fields ("Use a text field when someone needs to enter text into a UI"; "Text fields can contain prefix text such as currency symbol"; "Every text field should have a label"; "Label text shouldn't be truncated or take up multiple lines"; error "Swap supporting text with error text"; "It's strongly recommended to show an error icon"). Outlined container height 56 dp, 1 dp outline (2 dp focused), icons 24 dp; filled field top corners 4 dp with 1 dp indicator (2 dp focused). A prefix needs its own accessible name: "the accessibility label for prefix and suffix needs to have a unique id... the currency name for a currency symbol prefix".
- Slider: "Sliders are used to select values along a track. They're ideal for adjusting settings such as volume and brightness." "Changes made with sliders must take effect immediately." "Sliders should present the full range of available values." Stops: "Avoid having too many stop indicators on a slider, because it can become visually crowded and difficult to adjust the value." Plus and minus: "Plus and minus icons, or text, can be added to the left and right of the slider". External value: "a separate text input field can be added outside of the slider. If this is added, the slider and value in this text field should automatically update to match each other." Handle 44 dp tall, 16 dp track (Compose tokens); disabled 0.38.
- Gap: no fetched page gives a rule for increment/decrement buttons around a currency value; the icon-button rules (5.4: 48 dp targets, clear system icon, contentDescription naming the action and result) apply to any such buttons.
- URLs: https://m3.material.io/components/text-fields ; https://m3.material.io/components/sliders

### 5.9 Progress indicators and loading
- "Use progress indicators to show the status of ongoing processes". Wait times: "Instant (under 200ms): No indicator; Short (between 200ms and 5s): Loading indicator; Long (Over 5s): Progress indicator". "When multiple items are loading, use a single progress indicator to show progress for the group." "A process should be represented by the same variant of progress indicator throughout the product."
- "Determinate: Known progress and wait time; Indeterminate: Unknown progress and wait time"; "When using a determinate indicator, the indicator must accurately represent the progress". Linear: 4 dp track, 4 dp stop indicator ("required if the track has a contrast below 3:1"); circular 40 dp, 4 dp thickness (Compose tokens). "Linear indicators are best when placed on the edge of a container. Circular indicators are best when centered in an element." In buttons: use circular for "short, indeterminate activities under 5 seconds", active colour = label colour for 3:1.
- Skeleton loaders: "Content should not pop in and shift locations during a transition."
- URL: https://m3.material.io/components/progress-indicators ; https://m3.material.io/styles/motion/transitions

### 5.10 Badges
- "Limit content to four characters, including a +". Small badge 6 dp dot (3 dp radius) for "an unread notification"; large badge 16 dp high ("one digit"), 16 x 34 dp at max characters, label Label Small, error / on error colours. "Anchor badges inside the icon bounding box, at the upper trailing edge of the icon." "Keep the default color mapping." "In navigation bars, hide the badge once the destination has been selected." Badges "aren't inherited" disabled state.
- URL: https://m3.material.io/components/badges

---

## 6. Motion

### 6.1 Tokens
- Current system (M3 Expressive, May 2025): "The physics system is replacing the previous system based on easing and duration." Two schemes: "Expressive... should be used for most situations, particularly hero moments and key interactions." "Standard feels more functional with minimal bounce, and should be used for utilitarian products." Spatial springs "animate x and y position, rotation, size, rounded corners... overshoots"; effects springs "color and opacity animations, where there shouldn't be any overshoot." Speeds: default "Animations that partially cover the screen, such as bottom sheet"; fast "Small components, such as switches and buttons"; slow "Full-screen animations".
- Spring values (Compose tokens, damping / stiffness): Standard spatial fast 0.9 / 1400, default 0.9 / 700, slow 0.9 / 300; Standard effects fast 1.0 / 3800, default 1.0 / 1600, slow 1.0 / 800. Expressive spatial fast 0.6 / 800, default 0.8 / 380, slow 0.8 / 200; Expressive effects same as standard. Curve equivalents with durations: Standard fast spatial 350 ms, default spatial 500 ms, slow spatial 750 ms; effects 150 / 200 / 300 ms.
- Legacy easing and duration (still used for transitions, "no longer maintained"): Emphasized 500 ms (begin and end on screen); Emphasized decelerate 400 ms (enter); Emphasized accelerate 200 ms (exit); Standard 300 ms; Standard decelerate 250 ms (enter); Standard accelerate 200 ms (exit). Curves: standard (0.2, 0, 0, 1); standard decelerate (0, 0, 0, 1); standard accelerate (0.3, 0, 1, 1); emphasized decelerate (0.05, 0.7, 0.1, 1); emphasized accelerate (0.3, 0, 0.8, 0.15); emphasized on Android is a path interpolator.
- Duration tokens: short1 to 4 = 50, 100, 150, 200 ms; medium1 to 4 = 250, 300, 350, 400; long1 to 4 = 450, 500, 550, 600; extra-long1 to 4 = 700, 800, 900, 1000.
- Rules: "Transitions that cover small areas of the screen have short durations. Those that traverse large areas have long durations." "Transitions that exit, dismiss, or collapse an element use shorter durations. Exit transitions are faster because they require less attention than the user's next task." "Selection controls have a short duration of 200ms with Standard easing." "The Standard set can be used for small utility focused transitions that need to be quick."
- URLs: https://m3.material.io/styles/motion/overview ; https://m3.material.io/styles/motion/easing-and-duration

### 6.2 What animates, what should not
- Animate: state changes and entering or leaving elements. "Enter and exit: This pattern is used to introduce or remove a component on the screen." "A snackbar at the bottom of the screen expands upwards." "This Bottom sheet enters with Emphasized decelerate and exits permanently with Emphasized accelerate." "Android components expand and collapse along the x or y axis as they enter and exit. Scale and z-axis motion is avoided since they imply elevation change". Avoid pressed-state elevation: "Avoid using elevation to indicate a chip's pressed state. Instead, use the visual ripple effect."
- Restraint: "Motion emphasizes key moments or unique experiences. However, use it sparingly since motion can be distracting." "Don't animate other components along with snackbar animations, such as the floating action button." "No jarring jump cuts: Jump cuts should generally be avoided as a default setting... If pure efficiency is a top priority, like opening a menu in a productivity app, a jump cut may be preferred." Layout stability: "Avoid content shifting positions or instantly popping in as it loads." Tabular figures "prevent layout shifting when values change".
- Progress and live data: "Progress indicators... capture attention through motion."
- Reduced motion: "Most platforms have a reduced animation setting... If that setting is on, transitions should: Use subtle fades instead of intense sliding or scaling animations; Disable decorative effects like parallax or shape morphing."
- Android platform: Compose from 1.2 honours the "Remove animations" setting (system animator duration scale 0). (Reported by the Android accessibility community; developer.android.com page for this was not located.)
- URLs: https://m3.material.io/styles/motion/transitions ; https://m3.material.io/components/chips ; https://m3.material.io/components/snackbar

---

## 7. Android bubbles

### 7.1 Platform rules (developer.android.com)
- "Notification bubbles make it easier for users to see and participate in conversations. They float on top of other app content and users can expand bubbles to reveal and interact with the app content, and they can collapse them when they're not using them." "When the device is locked, or the always-on-display is active, bubbles appear as notifications normally do."
- Expanded view: "The expanded view of the bubble is created from an activity that you choose. The activity must be resizeable and embedded. If it lacks either of these requirements, the system displays it as a notification instead." Manifest: `android:allowEmbedded="true"`, `android:resizeableActivity="true"`, a no-action-bar theme. `allowEmbedded` is defined as "the activity can be launched as the embedded child of another activity".
- Collapsed icon: `BubbleMetadata.Builder(PendingIntent, Icon)` - "The icon will be used to represent the bubble when it is collapsed. An icon should be representative of the content within the bubble. If your app produces multiple bubbles, the icon should be unique for each of them." "It is recommended to use an Icon of type TYPE_URI or TYPE_URI_ADAPTIVE_BITMAP". With a shortcut: "The shortcut icon will be used to represent the bubble when it is collapsed." The shortcut must be "published as a dynamic shortcut that was built with setLongLived being true"; if the shortcut is deleted "the bubble will be removed".
- Height: `setDesiredHeight(dp)` / `setDesiredHeightResId`: "this height may not be respected if there is not enough space on the screen or if the provided height is too small to be useful." The Android sample uses 600. Bubble windows are classified by their own size (section 1.3).
- Auto expand: `setAutoExpandBubble(true)` only "if the app creating the bubble is in the foreground"; "Generally this flag should only be set if the user has performed an action to request or create a bubble." `setSuppressNotification(true)` hides the shade entry.
- Lifecycle: "When the bubble is collapsed or dismissed, the activity is destroyed." Expanded: "normal process lifecycle... the application becoming a foreground process". (State must be restorable.)
- Back: "Use the BackHandler composable and tie its enabled parameter to your UI state so it only intercepts back presses when necessary. Once your handler disables itself, the bubble collapses."
- Launching from inside a bubble: use the activity context and no `FLAG_ACTIVITY_NEW_TASK`, otherwise "the new activity is started in a new task and the bubble is collapsed". "launching an activity within the bubble increases the task stack of the bubble and could potentially complicate the user experience, specifically around navigation."
- Unread badge on the collapsed bubble: "When a collapsed bubble receives an updated message, the bubble shows a badge icon". Clear it with `setSuppressNotification()` and set `setOnlyAlertOnce(true)`.
- Best practice: "Send a notification as a bubble only if it is important... Bubbles use screen real estate and cover other app content." "Make sure your bubble notification also works as a normal notification. When the user disables the bubble, a bubble notification is shown as a normal notification."
- User control: block all, allow selected, allow all. "The first time you send the notification to display a bubble, make sure it's in a notification channel with IMPORTANCE_MIN or higher." Android 10 and lower needed `documentLaunchMode="always"`; "Beginning with Android 11... the system automatically sets all conversations' documentLaunchMode to 'always'."
- Android 17: "As of Android 17, bubbles has been formalized as a proper windowing mode, like split screen. This allows users to add any app to the bubble UI along with notification bubbles."
- Insets: bubble activities are ordinary activities, so the edge-to-edge rules apply: "Once you target SDK 35 or higher on a device running Android 15 or higher, your app is displayed edge-to-edge." Handle overlaps with inset APIs (system bars, display cutout, system gesture insets), and "Honor device safe areas, which includes parts of the UI such as display cutouts, edge-to-edge insets... software keyboards, and system bars." Gap: no fetched page states the inset values a bubble window receives; design for zero or non-zero insets by reading them, not by constants.
- URLs: https://developer.android.com/develop/ui/views/notifications/bubbles ; https://developer.android.com/reference/androidx/core/app/NotificationCompat.BubbleMetadata.Builder ; https://developer.android.com/reference/android/app/Notification.BubbleMetadata ; https://developer.android.com/guide/topics/manifest/activity-element ; https://developer.android.com/develop/ui/views/layout/edge-to-edge ; https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-basics

### 7.2 People and conversation requirements (Android 11+)
- "If an app targets Android 11 (API level 30) or higher, a notification doesn't appear as a bubble unless it meets the conversation requirements." "If targeting Android 11 (API level 30) or higher, make sure the bubble metadata or notification references a sharing shortcut." "Publish the shortcut, otherwise the bubble metadata will not apply."
- Conversation notification test: "The notification uses MessagingStyle." and "(Only if the app targets Android 11 or higher) The notification is associated with a valid long-lived dynamic or cached sharing shortcut" via `setShortcutId()` or `setShortcutInfo()`, and "The user hasn't demoted the conversation".
- Shortcut: "must be long-lived and have Person data attached for one or more persons, identifying the other participants". Recommended: `LocusIdCompat`. "Only notifications with an associated shortcut are able to bubble."
- Targeting Android 10 or lower: bubble only if MessagingStyle plus a Person, or a foreground-service call notification with `CATEGORY_CALL` and a Person, or "The app is in the foreground when the notification is sent."
- Conversation space design: "a strong emphasis on the avatar representing people combined with the app carrying the conversation." Offered actions include "Promote this conversation to bubble (only shown if the app supports bubbles)". The docs' own sample says "In real-world applications, use bubbles for messages by humans."
- Bubble notification posting: the code sample adds `.addPerson(chatPartner)` with `Person.Builder().setName(...).setImportant(true)`.
- URLs: https://developer.android.com/develop/ui/views/notifications/bubbles ; https://developer.android.com/training/notify-user/conversations

---

## 8. Shape

- Ten-step corner scale (Compose `ShapeTokens`): None 0; Extra small 4; Small 8; Medium 12; Large 16; Large increased 20; Extra large 28; Extra large increased 32; Extra extra large 48; Full (fully rounded). "Material components use a corner radius scale to define all rectangular shapes".
- Per component (spec and Compose tokens): chips 8 (small); cards 12 (medium); menus 4; snackbar 4; text fields 4 (filled: top corners only; outlined: 4); bottom sheet 28 top corners (extra large top); dialogs extra large 28; buttons and icon buttons Full by default (round), square variants Small button 12, Medium button 16; connected group inner corners 4 to 20 by size with 2 dp inner padding; segmented button fully rounded; badge small 3 radius, large 8; drag handle full; list items (expressive segmented) 4 inner, 16 outer; FAB containers are square-ish rounded rectangles (56, 80, 96 dp by size; per-size corner values are in the Compose tokens and were not tabulated here).
- Nesting: "When nesting rounded objects, avoid using the same corner radii for both objects... Outer radius - padding = inner radius. For example: 48dp - 14dp = 34dp".
- "Be careful not to apply large or full corners to information-dense components, such as cards." "Don't compromise clarity for the sake of visual design." Generally "products should consistently use the Material 3 shape styles".
- URL: https://m3.material.io/styles/shape

---

## 9. Glanceable and driving guidance (Android for Cars)

Scope note: these rules are written for car-screen apps (Android Auto and Android Automotive OS), not for phone bubbles. They are the only Android-published numeric limits on glance reading and steps.
- Principles: "Driving is the top priority for in-car apps. A simple user interface that is easy to navigate at a glance prevents distractions." "Tasks are as short as possible: The number of taps required to complete a task should be kept as low as possible... Drivers can complete common tasks in three taps or less." "Readable at a glance: On-screen text needs to be brief and readable at a glance... Text shown while driving is kept to 1-3 lines and 120 characters to be glanceable." "Easy to pause and resume tasks: Drivers may get interrupted by something on the road... but they should never lose progress." "Robust voice controls... Microphone stays visible when open". "Notifications are suppressed when the driver is in the middle of a maneuver."
- Writing rules: "Limit text to 120 characters." "A single message or chunk of text in English shouldn't exceed 120 characters or 24 words." "The text in 1 line shouldn't exceed 80 characters." "A sentence or paragraph shouldn't span more than 3 lines of text." (does not apply to legal or privacy text.) "Front-load important information... use clear hierarchy... bullet points." Direct language ("Sign in to your account", not "Authenticate your account"). "Button labels... should always start with a verb and ideally contain no more than 3 words." "Provide confirmation messages" after a task. Errors "state the problem clearly and offer a way out". Use a "reassuring tone of voice, except in emergency". Consistent sentence case or title case and punctuation; one term per concept.
- Task-flow UX requirements (templated car apps): MUST "Keep task flows to 5 steps or fewer in length." SHOULD "Keep task flows short (2 to 3 steps in length)." "keep task flows to 3 steps or fewer when possible (4 steps or fewer for flows involving purchases)." SHOULD "Show content (or action strip buttons) for at least 8 seconds before removing that content in an auto-transition between steps." SHOULD NOT "Use auto-transitions to complete tasks without user action." SHOULD NOT "Create buttons with states, such as toggles, in places where actions are supported (toggles are supported only in list rows)." SHOULD "Show useful content when opening a template, rather than an empty state". MUST NOT for purchases: "Allow users to select multiple items for purchase in a single flow." Map text: "font size of 24dp or larger unless it is paired with a visual element"; images, icons and markers "minimum size of 36 x 36 dp".
- Driving state: "Some tasks may require too much attention for users to complete while driving. In those cases, use parked-only templates or enable your app to pause the task during the drive and continue it while parked." "List template (shows more text when parked than while driving)". Refreshes "are throttled during drives to minimize distraction".
- Other Android glanceable guidance (live update notifications): "Show the status at a glance. If using discrete steps, clearly label the distinct phases". "alert only for critical status changes. Don't alert for minor variable adjustments. For example, alert for driver arrived, but don't alert for a shift in ETA." "use the same timestamp or duration format in the collapsed Status Bar view as the expanded card view." Widgets: "Use list layouts to organize multiple items in a clear, scannable format."
- Gap: the fetched sources contain no Android "driving mode" phone-UI spec beyond the car guidance above.
- URLs: https://developer.android.com/design/ui/cars/guides/foundations/design-principles ; https://developer.android.com/design/ui/cars/guides/foundations/writing-guidelines ; https://developer.android.com/design/ui/cars/guides/ux-requirements/overview ; https://developer.android.com/design/ui/cars/guides/ux-requirements/plan-task-flows ; https://developer.android.com/design/ui/cars/guides/ux-requirements/driving-state ; https://developer.android.com/design/ui/mobile/guides/home-screen/live-updates

---

## 10. Source conflicts and version notes (all stated by the sources themselves)

- Grid: Android design guide says an 8 dp grid with 4 dp for smaller elements; current M3 says an 8 dp scale with nested 2/4/6/10 dp units. No fetched source says "4 dp grid" as the single base.
- Text contrast threshold: M3 says "14 pt bold/18 pt regular and up" is large (3:1); Android says text under 18 sp, or bold under 14 sp, needs 4.5:1. Same numbers, different units; both are quoted in 2.2.
- Snackbar height: spec text "48dp to 64dp"; Compose tokens 48 dp single line, 68 dp two lines.
- List trailing padding: spec table 24 dp right; Compose tokens 16 dp trailing.
- Deprecated or no longer recommended in current M3: segmented button (use connected button group), small FAB, surface tint (use elevation levels 0 to 5 and surface container roles), "window size classes" name (now breakpoints), easing/duration motion (now springs, still used for transitions).
- Snackbar auto-dismiss: M3 allows 4 to 10 s only for snackbars without actions; with an action, the M3 text says it stays until acted on or dismissed (Compose default for an action is Indefinite).
