status: active
issues: #1524

- **Screens change fast and Home never flashes the setup card (#1524):** switch between Home, Analytics and
  Playbook from the bottom bar. Each switch should feel instant, about 0.3 s, a quick fade with a slight zoom, not
  the old slow fade out and fade in. Open a shift's page from Analytics: it slides in from the right, and Back
  slides it out to the right. Start the back gesture slowly from the screen edge: the screen underneath shows
  through as you drag, and letting go early leaves you where you were. Cold open the app: Home shows blank or a
  spinner until its data is ready, never the first-run setup card on a set-up phone. Analytics and Playbook show a
  spinner, not an empty page, if their data is slow. With animations turned off in Android's developer options,
  screens change with no motion.
  - Confirmed: 0/2
