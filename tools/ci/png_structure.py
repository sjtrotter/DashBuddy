"""Structural PNG reading, shared by check_screenshots.py and tools/play/play_listing.py. Stdlib only."""
import struct
import zlib
from dataclasses import dataclass

SIGNATURE = b"\x89PNG\r\n\x1a\n"
# PNG colour type -> (samples per pixel, allowed bit depths), from the PNG specification's IHDR table.
COLOUR_TYPES = {0: (1, {1, 2, 4, 8, 16}), 2: (3, {8, 16}), 3: (1, {1, 2, 4, 8}), 4: (2, {8, 16}), 6: (4, {8, 16})}
# Adam7 passes: (x start, y start, x step, y step).
ADAM7 = [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]
# Everything read() can raise for a file that is not a well-formed PNG.
ERRORS = (ValueError, zlib.error, struct.error)


@dataclass(frozen=True)
class Png:
    width: int
    height: int
    depth: int
    colour: int
    kinds: tuple  # every chunk type, in file order


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


def read(path):
    """Return the [Png] header of a file whose structure checks out; raise one of [ERRORS] otherwise.

    Checks the signature, every chunk's CRC, an IHDR first with legal fields, a PLTE for a palette
    image, at least one IDAT, an empty IEND last, and IDAT data that inflates to exactly the size the
    IHDR implies with every scanline's filter byte in 0..4. Pixel values are not judged.
    """
    data = path.read_bytes()
    if data[:8] != SIGNATURE:
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
    return Png(width, height, depth, colour, tuple(kinds))
