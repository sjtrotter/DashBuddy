#!/usr/bin/env python3
"""Builds gig.log's GitHub Pages site (https://gig.log.trotter.cloud) into an output directory.

The privacy page's words are the app's own: the `privacy_*` strings of :feature:settings, in the order the file
lists them, which is the order the in-app Privacy page shows them (PrivacySectionOrderTest pins that). The web page
describes the Google Play build, so the lines only a test build shows go in their own Test builds section. The page
template adds what a policy needs and the app page does not (publisher, contact, changes, children).

A Pages deploy publishes the whole site, so every page the site serves is built here. The feeds (#1564) add their
output to the same directory; they never deploy a site of their own.

Usage: build_site.py <repo root> <output dir>
"""
import html
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

STRINGS = "feature/settings/src/main/res/values/strings.xml"
RECEIPT = "domain/src/main/java/cloud/trotter/log/gig/domain/capability/ConsentReceipt.kt"
SITE = "site"

# Shown in the app only by a build that offers Screen events, or only by the test build.
TEST_BUILD_TITLE = "privacy_test_builds_title"
TEST_BUILD_LINES = ["privacy_reads_screen_events", "privacy_test_builds_body"]


def fail(message: str) -> None:
    print(f"build_site: FAIL {message}", file=sys.stderr)
    sys.exit(1)


def android_text(raw: str) -> str:
    """An Android string resource's text as the app shows it (the XML parser has already decoded entities)."""
    text = raw.strip()
    if len(text) >= 2 and text[0] == text[-1] == '"':
        text = text[1:-1]
    text = re.sub(r"\\n", "\n", text)
    return re.sub(r"\\(['\"@?\\])", r"\1", text)


def read_strings(path: Path) -> list[tuple[str, str]]:
    root = ET.parse(path).getroot()
    return [(el.get("name"), android_text("".join(el.itertext()))) for el in root.iter("string")]


def privacy_sections(strings: list[tuple[str, str]]) -> list[tuple[str, str]]:
    by_name = dict(strings)
    titles = [n for n, _ in strings if re.fullmatch(r"privacy_[a-z_]+_title", n) and n != TEST_BUILD_TITLE]
    if not titles:
        fail("no privacy_*_title strings found")
    sections = []
    for title in titles:
        body = title[: -len("_title")] + "_body"
        if body not in by_name:
            fail(f"{title} has no {body}")
        sections.append((by_name[title], by_name[body]))
    return sections


def revision(root: Path) -> int:
    match = re.search(r"const val REVISION: Int = (\d+)", (root / RECEIPT).read_text())
    if not match:
        fail(f"no REVISION in {RECEIPT}")
    return int(match.group(1))


def effective_date(root: Path, rev: int) -> str:
    """The date PRIVACY.md's revision history gives the current revision (`- **r<N> (YYYY-MM-DD)**`)."""
    match = re.search(rf"^- \*\*r{rev} \((\d{{4}}-\d{{2}}-\d{{2}})\)\*\*", (root / "PRIVACY.md").read_text(), re.M)
    if not match:
        fail(f"PRIVACY.md's revision history has no entry for r{rev}")
    return match.group(1)


def paragraphs(text: str) -> str:
    if re.search(r"%(\d+\$)?[sd]", text):
        fail(f"a privacy string takes a format argument, which the web page cannot fill: {text[:60]}")
    return "\n".join(f"<p>{html.escape(p)}</p>" for p in text.split("\n") if p.strip())


def section(title: str, body_html: str) -> str:
    return f"<section>\n<h2>{html.escape(title)}</h2>\n{body_html}\n</section>"


def fill(template: str, values: dict[str, str]) -> str:
    def sub(match: re.Match) -> str:
        key = match.group(1)
        if key not in values:
            fail(f"template names {{{{{key}}}}}, which the generator does not provide")
        return values[key]

    filled = re.sub(r"\{\{([a-z_]+)\}\}", sub, template)
    unused = set(values) - set(re.findall(r"\{\{([a-z_]+)\}\}", template))
    if unused:
        fail(f"template does not use {sorted(unused)}")
    return filled


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: build_site.py <repo root> <output dir>")
    root, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    strings = read_strings(root / STRINGS)
    by_name = dict(strings)
    for name in [TEST_BUILD_TITLE, *TEST_BUILD_LINES]:
        if name not in by_name:
            fail(f"missing string {name}")

    sections = "\n".join(section(t, paragraphs(b)) for t, b in privacy_sections(strings))
    test_build_title = html.escape(by_name[TEST_BUILD_TITLE])
    test_build_body = "\n".join(paragraphs(by_name[n]) for n in TEST_BUILD_LINES)

    rev = revision(root)
    site = root / SITE
    out.mkdir(parents=True, exist_ok=True)
    (out / "privacy").mkdir(exist_ok=True)
    (out / "site.css").write_text((site / "site.css").read_text())
    (out / "index.html").write_text((site / "index.html").read_text())
    (out / "privacy" / "index.html").write_text(fill(
        (site / "privacy.html").read_text(),
        {
            "sections": sections,
            "test_build_title": test_build_title,
            "test_build_body": test_build_body,
            "revision": str(rev),
            "date": effective_date(root, rev),
        },
    ))
    (out / ".nojekyll").write_text("")
    print(f"build_site: PASS {out} (privacy revision {rev})")


if __name__ == "__main__":
    main()
