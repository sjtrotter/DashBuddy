# Play store listing

The Play listing for `cloud.trotter.log.gig` lives here as files, in fastlane's supply layout, and the manual
"Play store listing" workflow (`.github/workflows/play-listing.yml`, `upload` mode) pushes it with
`tools/play/play_listing.py`.

```
play/listing/en-US/title.txt                       required, at most 30 characters
play/listing/en-US/short_description.txt           required, at most 80 characters
play/listing/en-US/full_description.txt            required, at most 4000 characters
play/listing/en-US/images/icon.png                 required, 512 x 512, 32-bit PNG (RGBA), at most 1024 KB
play/listing/en-US/images/featureGraphic.png       required, 1024 x 500, 24-bit PNG (RGB, no alpha)
play/listing/en-US/images/phoneScreenshots/*.png   required, 2 to 8, 24-bit PNG (RGB, no alpha), each side 320 to
                                                   3840 px, the long side at most twice the short; name order is
                                                   listing order (01-offer.png, 02-shift.png, ...)
```

- `python3 tools/play/play_listing.py check [root]` checks the tree (default `play/listing`) against the limits
  above, including each PNG's structure, bit depth and colour type, and needs no credentials or Google
  libraries. `upload` runs the same check and refuses on any problem before it opens an edit; the workflow also
  runs `check` before its auth step. Its tests: `python3 -m unittest tools/play/test_play_listing.py` (PR CI
  runs them).
- Only `en-US` is read. Another locale folder is ignored until the script's `LOCALES` names it.
- `upload` replaces the listing text and every image type.
- `inspect` prints what the Console holds and changes nothing.
- The listing is public and shared by every track, internal testing included. Text follows
  `docs/design/copy-style.md`.

## Images

The images are rendered from the app's own screens over sample data, never from a real shift: the
`PlayListingScreenshotsTest` classes in `:app` (Home, Recap, the shift page, the Playbook, the icon and the feature
graphic) and in `:feature:bubble` (the bubble's offer, shift and log, in a frame with a caption). A ready PR's CI run
renders them into its `screenshots-<pr>` artifact under `play-listing/`. To stage them:

```
tools/play/stage_listing_images.sh <run-id> <light|dark>
```

It downloads that run's artifact with `gh`, takes the phone screenshots of the chosen theme, `icon-light.png` and
`featureGraphic-dark.png`, converts them with ImageMagick (`magick`) to sRGB with no metadata (the icon as RGBA,
the rest as opaque RGB), checks the result with `check`, and only then replaces `images/`. Look at every image
before committing them.
