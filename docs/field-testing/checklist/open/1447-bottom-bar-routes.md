status: active
issues: #1447

- **The bottom bar's three tabs and where each link lands (#1447).**
  Home, Analytics and Playbook sit in a bar at the bottom of those three screens only; every screen below a tab
  (Ratings, Shift details, Weekly plan, Settings, Setup) has a title bar with a back button and no bar. Settings
  opens from the gear in each tab's title bar. On the phone:
  - From Home or a tab, the weekly-plan notification opens Playbook with the plan on top; Back goes plan →
    Playbook → Home → out of the app.
  - From a screen below a tab (Shift details, Settings, Ratings), the plan notification opens the plan on top of
    that screen; Back returns to it, then down its own stack.
  - Opened from Analytics, a tap on Home shows Home.
  - The bubble's Settings and Add vehicle open over the current tab, and Back returns there.
  - Switching tabs keeps the hub's tab (Money, Offers, Time) and its scroll.
  - No extra gap above any title bar; the setup wizard's Next sits clear of the gesture bar and of 3-button
    navigation.
  - No jump when Shift details opens (the bar snaps away while the screen fades in; that snap is expected).
  - With a vehicle's details page or setup open, tap the plan notification: the plan opens on top, and Back
    returns to the page as you left it (a saved value kept; a cost field still being typed in setup is lost, as
    before).
  - Issue: #1447. Confirmed: 0/2 (awaiting field validation)
