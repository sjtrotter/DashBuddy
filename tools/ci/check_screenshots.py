#!/usr/bin/env python3
"""Check the key-screen screenshots PR CI rendered (#1463). Stdlib only.

usage: check_screenshots.py <dir> [<dir> ...]

Every path in screenshots.txt (beside this script) must exist under one of the dirs as
<path>-light.png and <path>-dark.png, and each file must decode as a PNG: signature, chunk CRCs,
an IHDR, and IDAT data that inflates to the size the IHDR implies. Those fail the job (exit 1).
A size that differs from the expected one (dp x 420/160, within 2 px) and a PNG nobody expects are
only warnings. Nothing is compared against stored pictures.
"""
import struct
import sys
import zlib
from pathlib import Path

DENSITY = 420 / 160
TOLERANCE_PX = 2
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}  # PNG colour type -> samples per pixel


def decode(path):
    """Return (width, height) of a PNG whose structure checks out; raise ValueError otherwise."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG signature")
    offset, header, idat = 8, None, b""
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("truncated chunk")
        length, kind = struct.unpack(">I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + length]
        (crc,) = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])
        if len(body) != length or zlib.crc32(kind + body) != crc:
            raise ValueError(f"bad {kind!r} chunk")
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        offset += 12 + length
    if header is None or not idat:
        raise ValueError("no IHDR or IDAT")
    width, height, depth, colour, _, _, interlace = header
    pixels = zlib.decompress(idat)
    if interlace == 0 and colour in CHANNELS:
        expected = height * (1 + (width * CHANNELS[colour] * depth + 7) // 8)
        if len(pixels) != expected:
            raise ValueError(f"image data is {len(pixels)} bytes, expected {expected}")
    return width, height


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
    for name, width_dp, height_dp in expected:
        want = (round(int(width_dp) * DENSITY), round(int(height_dp) * DENSITY))
        for theme in ("light", "dark"):
            key = f"{name}-{theme}.png"
            checked.add(key)
            png = found.get(key)
            if png is None:
                failures.append(f"missing {key}")
                continue
            try:
                size = decode(png)
            except (ValueError, zlib.error, struct.error) as error:
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
