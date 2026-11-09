#!/usr/bin/env python3
"""Check the key-screen screenshots PR CI rendered (#1463). Stdlib only.

usage: check_screenshots.py <dir> [<dir> ...]

Every path in screenshots.txt (beside this script) must exist under one of the dirs as
<path>-light.png and <path>-dark.png, and each file must decode as a PNG (see decode()). Those fail
the job (exit 1). A size that differs from the one screenshots.txt states (within 2 px) and a PNG
nobody expects are only warnings. Nothing is compared against stored pictures.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import png_structure  # noqa: E402

TOLERANCE_PX = 2


def decode(path):
    """Return (width, height) of a PNG whose structure checks out (png_structure.read); raise otherwise."""
    png = png_structure.read(path)
    return png.width, png.height


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 2
    roots = [Path(arg) for arg in sys.argv[1:]]
    found = {}
    for root in roots:
        for png in sorted(root.glob("**/*.png")):
            found[png.relative_to(root).as_posix()] = png
    manifest = Path(__file__).with_name("screenshots.txt")
    expected = [line.split() for line in manifest.read_text().splitlines()
                if line.strip() and not line.startswith("#")]
    failures, warnings, checked = [], [], set()
    for name, width, height in expected:
        want = (int(width), int(height))
        for theme in ("light", "dark"):
            key = f"{name}-{theme}.png"
            checked.add(key)
            png = found.get(key)
            if png is None:
                failures.append(f"missing {key}")
                continue
            try:
                size = decode(png)
            except png_structure.ERRORS as error:
                failures.append(f"undecodable {key}: {error}")
                continue
            if any(abs(got - w) > TOLERANCE_PX for got, w in zip(size, want)):
                warnings.append(f"{key} is {size[0]}x{size[1]} px, expected {want[0]}x{want[1]}")
    warnings += [f"unexpected {key} (not in screenshots.txt)" for key in sorted(set(found) - checked)]
    for line in warnings:
        print(f"::warning::{line}")
    for line in failures:
        print(f"::error::{line}")
    print(f"{len(checked) - len(failures)} of {len(checked)} screenshots present and decodable, "
          f"{len(warnings)} warnings.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
