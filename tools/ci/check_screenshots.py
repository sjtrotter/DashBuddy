#!/usr/bin/env python3
"""Check the key-screen screenshots PR CI rendered (#1463). Stdlib only.

usage: check_screenshots.py <dir> [<dir> ...]

Every path in screenshots.txt (beside this script) must exist under one of the dirs as
<path>-light.png and <path>-dark.png, and each file must decode as a PNG (see decode()). Those fail
the job (exit 1). A size that differs from the one screenshots.txt states (within 2 px) and a PNG
nobody expects are only warnings. Nothing is compared against stored pictures.
"""
import struct
import sys
import zlib
from pathlib import Path

TOLERANCE_PX = 2
# PNG colour type -> (samples per pixel, allowed bit depths), from the PNG specification's IHDR table.
COLOUR_TYPES = {0: (1, {1, 2, 4, 8, 16}), 2: (3, {8, 16}), 3: (1, {1, 2, 4, 8}), 4: (2, {8, 16}), 6: (4, {8, 16})}
# Adam7 passes: (x start, y start, x step, y step).
ADAM7 = [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]


def chunks(data):
    """Yield (kind, body) for every chunk, checking each length and CRC."""
    offset = 8
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("truncated chunk")
        length, kind = struct.unpack(">I4s", data[offset:offset + 8])
        if offset + 12 + length > len(data):
            raise ValueError(f"truncated {kind!r} chunk")
        body = data[offset + 8:offset + 8 + length]
        (crc,) = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])
        if zlib.crc32(kind + body) != crc:
            raise ValueError(f"bad CRC in {kind!r} chunk")
        yield kind, body
        offset += 12 + length


def decode(path):
    """Return (width, height) of a PNG whose structure checks out; raise ValueError otherwise.

    Checks the signature, every chunk's CRC, an IHDR first with legal fields, a PLTE for a palette
    image, at least one IDAT, an empty IEND last, and IDAT data that inflates to exactly the size the
    IHDR implies with every scanline's filter byte in 0..4. Pixel values are not judged.
    """
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG signature")
    found = list(chunks(data))
    if not found or found[0][0] != b"IHDR" or len(found[0][1]) != 13:
        raise ValueError("IHDR is not the first chunk")
    if found[-1] != (b"IEND", b""):
        raise ValueError("no empty IEND as the last chunk")
    kinds = [kind for kind, _ in found]
    if kinds.count(b"IHDR") != 1 or kinds.count(b"IEND") != 1:
        raise ValueError("more than one IHDR or IEND")
    width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", found[0][1])
    if width == 0 or height == 0:
        raise ValueError("zero width or height")
    if colour not in COLOUR_TYPES or depth not in COLOUR_TYPES[colour][1]:
        raise ValueError(f"colour type {colour} with bit depth {depth} is not a PNG combination")
    if compression != 0 or filtering != 0 or interlace not in (0, 1):
        raise ValueError(f"compression {compression}, filter {filtering}, interlace {interlace} not allowed")
    if colour == 3 and b"PLTE" not in kinds:
        raise ValueError("palette image without PLTE")
    idat = b"".join(body for kind, body in found if kind == b"IDAT")
    if not idat:
        raise ValueError("no IDAT")
    pixels = zlib.decompress(idat)
    samples = COLOUR_TYPES[colour][0]
    if interlace == 0:
        passes = [(width, height)]
    else:
        passes = [(-(-(width - xs) // xstep), -(-(height - ys) // ystep)) for xs, ys, xstep, ystep in ADAM7
                  if width > xs and height > ys]
    offset = 0
    for pass_width, pass_height in passes:
        row = 1 + (pass_width * samples * depth + 7) // 8
        for _ in range(pass_height):
            if offset >= len(pixels):
                raise ValueError("image data ends early")
            if pixels[offset] > 4:
                raise ValueError(f"scanline filter byte {pixels[offset]} is not 0-4")
            offset += row
    if offset != len(pixels):
        raise ValueError(f"image data is {len(pixels)} bytes, expected {offset}")
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
