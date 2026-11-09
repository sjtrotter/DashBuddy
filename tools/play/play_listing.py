"""Check the store listing under play/listing/<locale>/, push it to Play, or print what Play has.

Runs in the "Play store listing" workflow (.github/workflows/play-listing.yml) with the same service account
the release pipeline uses. Credentials come from Application Default Credentials (in CI, the file the auth
step writes). The layout follows fastlane's supply convention (play/listing/README.md):

    play/listing/en-US/title.txt
    play/listing/en-US/short_description.txt
    play/listing/en-US/full_description.txt
    play/listing/en-US/images/icon.png
    play/listing/en-US/images/featureGraphic.png
    play/listing/en-US/images/phoneScreenshots/*.png

check reads the files against Play's limits (text lengths, image sizes and PNG formats) and needs no
credentials and no Google libraries. upload runs the same check and refuses before it opens an edit on any
problem, so an empty or half-filled tree never publishes. The PNG checks cover the chunk structure, CRCs, IHDR
first and IEND last, critical chunk types and tRNS, not every ordering rule of the PNG specification.

Usage: play_listing.py inspect|upload|check [listing root, default play/listing]
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "ci"))
import png_structure  # noqa: E402

PACKAGE = "cloud.trotter.log.gig"
LISTING_DIR = Path("play/listing")
LOCALES = ("en-US",)  # only what the listing maintains; a stray folder must not publish
# Text file -> (API field, Play's character limit).
TEXT_FILES = {
    "title.txt": ("title", 30),
    "short_description.txt": ("shortDescription", 80),
    "full_description.txt": ("fullDescription", 4000),
}
ICON = "icon.png"
FEATURE_GRAPHIC = "featureGraphic.png"
PHONE_SCREENSHOTS = "phoneScreenshots"
ICON_SIZE = (512, 512)
ICON_MAX_BYTES = 1024 * 1024
FEATURE_GRAPHIC_SIZE = (1024, 500)
SCREENSHOT_COUNT = (2, 8)
SCREENSHOT_SIDE = (320, 3840)
# PNG colour types: 2 is RGB (24-bit at depth 8), 6 is RGB with alpha (32-bit at depth 8).
RGB, RGBA = 2, 6
# The critical chunks the PNG specification defines; a critical chunk is one whose type starts upper-case.
KNOWN_CRITICAL = {b"IHDR", b"PLTE", b"IDAT", b"IEND"}


def service():
    import google.auth
    from googleapiclient.discovery import build

    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    # Live discovery: the client's bundled document lags the API and lacks
    # commit's changesInReviewBehavior, which the upload relies on.
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False, static_discovery=False)


def image_problems(path, colour, size=None, max_bytes=None):
    """Problems with one PNG: its structure (png_structure.read: IHDR first, IEND last, CRCs, image data), bit depth 8
    and [colour], no tRNS, no critical chunk the PNG specification does not define, then [size], else Play's
    screenshot side and ratio limits, then [max_bytes]."""
    try:
        png = png_structure.read(path)
    except png_structure.ERRORS as error:
        return [f"{path}: not a well-formed PNG ({error})"]
    problems = []
    if png.depth != 8 or png.colour != colour:
        want = "24-bit RGB with no alpha" if colour == RGB else "32-bit RGBA"
        problems.append(f"{path}: colour type {png.colour} at bit depth {png.depth}, Play wants {want} "
                        f"(colour type {colour}, bit depth 8)")
    if b"tRNS" in png.kinds:
        problems.append(f"{path}: has a tRNS (transparency) chunk")
    unknown = sorted({kind.decode("latin-1") for kind in png.kinds if kind[:1].isupper()} -
                     {kind.decode("latin-1") for kind in KNOWN_CRITICAL})
    if unknown:
        problems.append(f"{path}: unknown critical chunk {', '.join(unknown)}")
    if size is not None:
        if (png.width, png.height) != size:
            problems.append(f"{path}: {png.width} x {png.height} px, Play wants {size[0]} x {size[1]}")
    else:
        low, high = SCREENSHOT_SIDE
        short, long = sorted((png.width, png.height))
        if short < low or long > high:
            problems.append(f"{path}: {png.width} x {png.height} px, each side must be {low} to {high}")
        if long > 2 * short:
            problems.append(f"{path}: {png.width} x {png.height} px, the long side may be at most twice the short")
    if max_bytes is not None and path.stat().st_size > max_bytes:
        problems.append(f"{path}: {path.stat().st_size} bytes, Play allows {max_bytes}")
    return problems


def collect(root=LISTING_DIR):
    """Every locale's (body, images), and every problem found against Play's limits."""
    problems = []
    listings = {}
    for locale in LOCALES:
        locale_dir = root / locale
        body = {"language": locale}
        for name, (field, limit) in TEXT_FILES.items():
            path = locale_dir / name
            if not path.is_file():
                problems.append(f"{path}: missing")
                continue
            text = path.read_text(encoding="utf-8").strip()
            if not text:
                problems.append(f"{path}: empty")
            elif len(text) > limit:
                problems.append(f"{path}: {len(text)} characters, Play allows {limit}")
            body[field] = text
        images_dir = locale_dir / "images"
        images = {}
        for name, image_type, rules in (
            (ICON, "icon", {"colour": RGBA, "size": ICON_SIZE, "max_bytes": ICON_MAX_BYTES}),
            (FEATURE_GRAPHIC, "featureGraphic", {"colour": RGB, "size": FEATURE_GRAPHIC_SIZE}),
        ):
            path = images_dir / name
            if path.is_file():
                problems += image_problems(path, **rules)
            else:
                problems.append(f"{path}: missing")
            images[image_type] = [path]
        shots_dir = images_dir / PHONE_SCREENSHOTS
        shots = sorted(shots_dir.glob("*.png"))
        low, high = SCREENSHOT_COUNT
        if not low <= len(shots) <= high:
            problems.append(f"{shots_dir}: {len(shots)} PNG screenshots, Play wants {low} to {high}")
        for shot in shots:
            problems += image_problems(shot, RGB)
        images[PHONE_SCREENSHOTS] = shots
        listings[locale] = (body, images)
    return listings, problems


def read_listing(root=LISTING_DIR):
    """Reads and checks every locale's files; exits with every problem before any API call."""
    listings, problems = collect(root)
    if problems:
        raise SystemExit("listing not uploaded:\n  " + "\n  ".join(problems))
    return listings


def check(root=LISTING_DIR):
    _, problems = collect(root)
    for line in problems:
        print(f"problem: {line}")
    print(f"{root}: {len(problems)} problems" if problems else f"{root}: ready to upload")
    return 1 if problems else 0


def inspect(api, edit_id):
    edits = api.edits()
    print("details:", json.dumps(edits.details().get(packageName=PACKAGE, editId=edit_id).execute(), indent=2))
    print("listings:", json.dumps(edits.listings().list(packageName=PACKAGE, editId=edit_id).execute(), indent=2))
    for locale in LOCALES:
        for image_type in ("featureGraphic", "icon", PHONE_SCREENSHOTS):
            images = edits.images().list(
                packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
            ).execute()
            print(f"{locale} images/{image_type}:", [i["id"] for i in images.get("images", [])])
    print("tracks:", json.dumps(edits.tracks().list(packageName=PACKAGE, editId=edit_id).execute(), indent=2))


def upload(api, edit_id, listings):
    edits = api.edits()
    for locale, (body, images) in listings.items():
        edits.listings().update(packageName=PACKAGE, editId=edit_id, language=locale, body=body).execute()
        print(f"{locale}: listing text updated")
        for image_type, files in images.items():
            upload_images(edits, edit_id, locale, image_type, files)
    # Never cancel a release that is sitting in review to get a listing out.
    edits.commit(packageName=PACKAGE, editId=edit_id, changesInReviewBehavior="ERROR_IF_IN_REVIEW").execute()
    print("committed")


def upload_images(edits, edit_id, locale, image_type, files):
    from googleapiclient.http import MediaFileUpload

    edits.images().deleteall(
        packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
    ).execute()
    for file in files:
        edits.images().upload(
            packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
            media_body=MediaFileUpload(str(file), mimetype="image/png"),
        ).execute()
        print(f"{locale}: {image_type} <- {file.name}")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", nargs="?", default="inspect", choices=("inspect", "upload", "check"))
    parser.add_argument("root", nargs="?", default=str(LISTING_DIR), help="listing root (default play/listing)")
    args = parser.parse_args(argv)
    root = Path(args.root)
    if args.mode == "check":
        return check(root)
    listings = read_listing(root) if args.mode == "upload" else None
    api = service()
    edit_id = api.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
    if args.mode == "upload":
        upload(api, edit_id, listings)
    else:
        try:
            inspect(api, edit_id)
        finally:
            api.edits().delete(packageName=PACKAGE, editId=edit_id).execute()
    return 0


if __name__ == "__main__":
    sys.exit(main())
