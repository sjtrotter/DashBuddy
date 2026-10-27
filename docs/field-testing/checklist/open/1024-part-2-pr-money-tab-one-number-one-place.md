status: review
issues: #1024, #1026

- **🆕 NEW — #1024 part 2 (PR #1026) — Money tab: one number, one place.** Open Analytics on a
  dashed week. Working: kept appears exactly once (the big number), gross exactly once (`$X came
  in.`), deliveries/miles only in the grey facts line; five bordered cards at most (money story,
  rates+day chart, by platform, needs a look, recent sessions) then ONE `How these numbers work`
  row at the bottom of every tab. Broken: a `TOP STORES` card, a second disclosure mid-scroll, or
  `Stayed with you 63%` in the legend (the percentage fallback — the note went null instead of
  blank). Rates survive a single-day/Lifetime window (`—` where a denominator is missing, chart
  simply absent). Two-platform week (needs an Uber dash): one row per platform with chip, bar,
  `$X kept`, count.
  - Confirmed: 0/2
  desk: needs field: count the Money cards and labels on a dashed week, including the two-platform view.
  Triage source: first-line blame 2026-08-12 (`69e72d33b`, original README L786).
