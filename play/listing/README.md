# Play store listing

The Play listing for `cloud.trotter.log.gig` lives here as files, in fastlane's supply layout, and the manual
"Play store listing" workflow (`.github/workflows/play-listing.yml`, `upload` mode) pushes it with
`tools/play/play_listing.py`. Nothing is here yet: #1416 adds the text and the images. Until then `upload`
refuses to run, so an empty tree never publishes.

```
play/listing/en-US/title.txt                       required, at most 30 characters
play/listing/en-US/short_description.txt           required, at most 80 characters
play/listing/en-US/full_description.txt            required, at most 4000 characters
play/listing/en-US/images/icon.png                 required, 512 x 512
play/listing/en-US/images/featureGraphic.png       required, 1024 x 500
play/listing/en-US/images/phoneScreenshots/*.png   optional; when present they replace the listing's phone screenshots
```

- Only `en-US` is read. Another locale folder is ignored until the script's `LOCALES` names it.
- `upload` replaces the listing text and every image type it finds; an image type with no files here is left as
  the Console has it.
- `inspect` prints what the Console holds and changes nothing.
- The listing is public and shared by every track, internal testing included. Text follows
  `docs/design/copy-style.md`.
