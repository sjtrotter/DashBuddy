#!/usr/bin/env bash
# Stage the Play listing images from one PR CI run's screenshots artifact.
#
# usage: tools/play/stage_listing_images.sh <run-id> <light|dark>
#
# Downloads the run's screenshots-* artifact, takes the play-listing renders (PlayListingScreenshotsTest in :app
# and :feature:bubble), converts them to the PNG formats Play accepts, checks the result with
# play_listing.py check, and only then replaces play/listing/en-US/images/. The phone screenshots come from the
# chosen theme; the icon is always icon-light.png and the feature graphic featureGraphic-dark.png (each is the
# same picture in both themes).
set -euo pipefail

usage() { echo "usage: $0 <run-id> <light|dark>" >&2; exit 2; }
[ $# -eq 2 ] || usage
run_id=$1
theme=$2
case "$theme" in light|dark) ;; *) usage ;; esac

for tool in gh magick python3; do
  command -v "$tool" >/dev/null || { echo "error: $tool is not installed" >&2; exit 1; }
done

repo=$(git rev-parse --show-toplevel)
cd "$repo"
dest=play/listing/en-US/images
app=app/build/outputs/screenshots/play-listing
bubble=feature/bubble/build/outputs/screenshots/play-listing
# Listing order: <file name in phoneScreenshots/> <render under the artifact>.
shots=(
  "01-offer $bubble/offer"
  "02-shift $bubble/shift"
  "03-home $app/home"
  "04-shift-page $app/shift-page"
  "05-recap-week $app/recap-week"
  "06-playbook $app/playbook"
  "07-log $bubble/log"
)

tmp=$(mktemp -d)
# Beside the destination, so the swap below is two renames on one filesystem.
new_tree=play/listing/en-US/.images.new
old_tree=play/listing/en-US/.images.old
# A killed earlier run can leave the previous set aside; put it back before starting over.
if [ -e "$old_tree" ] && [ ! -e "$dest" ]; then mv "$old_tree" "$dest"; fi
rm -rf "$new_tree" "$old_tree"
# On any exit: a previous set that was moved aside and not replaced goes back, then the work dirs go.
cleanup() {
  if [ -e "$old_tree" ] && [ ! -e "$dest" ]; then mv "$old_tree" "$dest"; fi
  rm -rf "$tmp" "$new_tree" "$old_tree"
}
trap cleanup EXIT
gh run download "$run_id" --pattern 'screenshots-*' --dir "$tmp/artifact"

# The one file under the artifact whose path ends in $1.
render() {
  local hits
  hits=$(find "$tmp/artifact" -type f -path "*/$1")
  if [ -z "$hits" ]; then echo "error: run $run_id has no $1" >&2; return 1; fi
  if [ "$(printf '%s\n' "$hits" | wc -l)" -ne 1 ]; then echo "error: run $run_id has more than one $1" >&2; return 1; fi
  printf '%s\n' "$hits"
}

# Opaque 8-bit RGB, sRGB, no metadata (Play's 24-bit PNG).
rgb() { magick "$1" -background white -alpha remove -alpha off -colorspace sRGB -strip PNG24:"$2"; }

listing=$tmp/listing/en-US
mkdir -p "$listing/images/phoneScreenshots"
cp play/listing/en-US/*.txt "$listing/"

icon=$(render "$app/icon-light.png")
feature=$(render "$app/featureGraphic-dark.png")
sources=()
for entry in "${shots[@]}"; do
  sources+=("$(render "${entry#* }-$theme.png")")
done

magick "$icon" -colorspace sRGB -strip PNG32:"$listing/images/icon.png"
rgb "$feature" "$listing/images/featureGraphic.png"
for i in "${!shots[@]}"; do
  rgb "${sources[$i]}" "$listing/images/phoneScreenshots/${shots[$i]%% *}.png"
done

python3 tools/play/play_listing.py check "$tmp/listing"

cp -R "$listing/images" "$new_tree"
diff -r "$listing/images" "$new_tree" >/dev/null || { echo "error: the copy beside $dest differs from the checked set" >&2; exit 1; }
if [ -e "$dest" ]; then mv "$dest" "$old_tree"; fi
mv "$new_tree" "$dest"
rm -rf "$old_tree"
echo "staged run $run_id ($theme) into $dest:"
find "$dest" -type f -name '*.png' | sort
