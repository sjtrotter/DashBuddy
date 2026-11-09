"""Push the store listing under play/listing/<locale>/ to Play, or print what Play has.

Runs in the "Play store listing" workflow (.github/workflows/play-listing.yml) with the same service account
the release pipeline uses. Credentials come from Application Default Credentials (in CI, the file the auth
step writes). The layout follows fastlane's supply convention (play/listing/README.md):

    play/listing/en-US/title.txt
    play/listing/en-US/short_description.txt
    play/listing/en-US/full_description.txt
    play/listing/en-US/images/icon.png
    play/listing/en-US/images/featureGraphic.png
    play/listing/en-US/images/phoneScreenshots/*.png   (optional)

upload refuses before it opens an edit when a required file is missing, empty or over Play's length limit,
so an empty or half-filled tree never publishes.

Usage: play_listing.py inspect|upload
"""
import argparse
import json
from pathlib import Path

import google.auth
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "cloud.trotter.log.gig"
LISTING_DIR = Path("play/listing")
LOCALES = ("en-US",)  # only what the listing maintains; a stray folder must not publish
# Text file -> (API field, Play's character limit).
TEXT_FILES = {
    "title.txt": ("title", 30),
    "short_description.txt": ("shortDescription", 80),
    "full_description.txt": ("fullDescription", 4000),
}
REQUIRED_IMAGES = {"icon.png": "icon", "featureGraphic.png": "featureGraphic"}
OPTIONAL_IMAGE_DIRS = {"phoneScreenshots": "phoneScreenshots"}


def service():
    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    # Live discovery: the client's bundled document lags the API and lacks
    # commit's changesInReviewBehavior, which the upload relies on.
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False, static_discovery=False)


def read_listing():
    """Reads and checks every locale's files; exits with every problem before any API call."""
    problems = []
    listings = {}
    for locale in LOCALES:
        locale_dir = LISTING_DIR / locale
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
        images = {}
        for name, image_type in REQUIRED_IMAGES.items():
            path = locale_dir / "images" / name
            if not path.is_file():
                problems.append(f"{path}: missing")
            images[image_type] = [path]
        for name, image_type in OPTIONAL_IMAGE_DIRS.items():
            files = sorted((locale_dir / "images" / name).glob("*.png"))
            if files:
                images[image_type] = files
        listings[locale] = (body, images)
    if problems:
        raise SystemExit("listing not uploaded:\n  " + "\n  ".join(problems))
    return listings


def inspect(api, edit_id):
    edits = api.edits()
    print("details:", json.dumps(edits.details().get(packageName=PACKAGE, editId=edit_id).execute(), indent=2))
    print("listings:", json.dumps(edits.listings().list(packageName=PACKAGE, editId=edit_id).execute(), indent=2))
    image_types = sorted(set(REQUIRED_IMAGES.values()) | set(OPTIONAL_IMAGE_DIRS.values()))
    for locale in LOCALES:
        for image_type in image_types:
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
    edits.images().deleteall(
        packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
    ).execute()
    for file in files:
        edits.images().upload(
            packageName=PACKAGE, editId=edit_id, language=locale, imageType=image_type,
            media_body=MediaFileUpload(str(file), mimetype="image/png"),
        ).execute()
        print(f"{locale}: {image_type} <- {file.name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", nargs="?", default="inspect", choices=("inspect", "upload"))
    args = parser.parse_args()
    listings = read_listing() if args.mode == "upload" else None
    api = service()
    edit_id = api.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
    if args.mode == "upload":
        upload(api, edit_id, listings)
    else:
        try:
            inspect(api, edit_id)
        finally:
            api.edits().delete(packageName=PACKAGE, editId=edit_id).execute()


if __name__ == "__main__":
    main()
