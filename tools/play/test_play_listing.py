"""Tests for play_listing.py's check. Stdlib only; the fixture PNGs are written here with zlib.

Run from the repository root: python3 -m unittest tools/play/test_play_listing.py
"""
import contextlib
import io
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import play_listing  # noqa: E402

SAMPLES = {0: 1, 2: 3, 4: 2, 6: 4}


def chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def png_chunks(width, height, colour, depth=8, extra=()):
    """The (type, body) chunks of a well-formed, all-zero PNG; [extra] chunks go between IHDR and IDAT."""
    row = b"\x00" + bytes((width * SAMPLES[colour] * depth + 7) // 8)
    ihdr = struct.pack(">IIBBBBB", width, height, depth, colour, 0, 0, 0)
    return [(b"IHDR", ihdr), *extra, (b"IDAT", zlib.compress(row * height)), (b"IEND", b"")]


def assemble(chunks):
    return b"\x89PNG\r\n\x1a\n" + b"".join(chunk(k, b) for k, b in chunks)


def png(width, height, colour, depth=8, extra=()):
    return assemble(png_chunks(width, height, colour, depth, extra))


class CheckTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.locale = self.root / "en-US"
        self.images = self.locale / "images"
        (self.images / "phoneScreenshots").mkdir(parents=True)
        self.write_text("title.txt", "gig.log: Delivery Driver Log")
        self.write_text("short_description.txt", "Profit, pay per mile and pay per hour.")
        self.write_text("full_description.txt", "A log of your own delivery work.")
        self.write_image("icon.png", png(512, 512, 6))
        self.write_image("featureGraphic.png", png(1024, 500, 2))
        for i in (1, 2, 3):
            self.write_image(f"phoneScreenshots/0{i}-shot.png", png(320, 640, 2))

    def write_text(self, name, text):
        (self.locale / name).write_text(text + "\n", encoding="utf-8")

    def write_image(self, name, data):
        (self.images / name).write_bytes(data)

    def problems(self):
        return play_listing.collect(self.root)[1]

    def assertOneProblem(self, *fragments):
        problems = self.problems()
        self.assertEqual(1, len(problems), problems)
        for fragment in fragments:
            self.assertIn(fragment, problems[0])

    def test_a_complete_listing_has_no_problems(self):
        self.assertEqual([], self.problems())

    def test_check_prints_ready_and_returns_zero(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, play_listing.main(["check", str(self.root)]))
        self.assertIn("ready to upload", out.getvalue())

    def test_title_of_31_characters_is_rejected(self):
        self.write_text("title.txt", "x" * 31)
        self.assertOneProblem("title.txt", "31 characters", "30")

    def test_icon_of_the_wrong_size_is_rejected(self):
        self.write_image("icon.png", png(500, 500, 6))
        self.assertOneProblem("icon.png", "500 x 500")

    def test_icon_without_alpha_is_rejected(self):
        self.write_image("icon.png", png(512, 512, 2))
        self.assertOneProblem("icon.png", "colour type 2")

    def test_icon_over_1024_kb_is_rejected(self):
        self.write_image("icon.png", png(512, 512, 6, extra=[(b"tEXt", b"pad\x00" + b"x" * 1024 * 1024)]))
        self.assertOneProblem("icon.png", "bytes")

    def test_feature_graphic_with_alpha_is_rejected(self):
        self.write_image("featureGraphic.png", png(1024, 500, 6))
        self.assertOneProblem("featureGraphic.png", "colour type 6")

    def test_feature_graphic_with_trns_is_rejected(self):
        self.write_image("featureGraphic.png", png(1024, 500, 2, extra=[(b"tRNS", bytes(6))]))
        self.assertOneProblem("featureGraphic.png", "tRNS")

    def test_icon_with_trns_is_rejected(self):
        self.write_image("icon.png", png(512, 512, 6, extra=[(b"tRNS", bytes(6))]))
        self.assertOneProblem("icon.png", "tRNS")

    def test_screenshot_with_trns_is_rejected(self):
        self.write_image("phoneScreenshots/01-shot.png", png(320, 640, 2, extra=[(b"tRNS", bytes(6))]))
        self.assertOneProblem("01-shot.png", "tRNS")

    def test_unknown_critical_chunk_is_rejected(self):
        self.write_image("featureGraphic.png", png(1024, 500, 2, extra=[(b"ZZZZ", b"x")]))
        self.assertOneProblem("featureGraphic.png", "unknown critical chunk ZZZZ")

    def test_unknown_ancillary_chunk_is_allowed(self):
        self.write_image("featureGraphic.png", png(1024, 500, 2, extra=[(b"zzzz", b"x")]))
        self.assertEqual([], self.problems())

    def test_ihdr_not_first_is_rejected(self):
        chunks = png_chunks(512, 512, 6)
        chunks.insert(0, (b"tEXt", b"k\x00v"))
        self.write_image("icon.png", assemble(chunks))
        self.assertOneProblem("icon.png", "IHDR is not the first chunk")

    def test_iend_not_last_is_rejected(self):
        self.write_image("icon.png", assemble(png_chunks(512, 512, 6) + [(b"tEXt", b"k\x00v")]))
        self.assertOneProblem("icon.png", "no empty IEND as the last chunk")

    def test_feature_graphic_at_16_bits_is_rejected(self):
        self.write_image("featureGraphic.png", png(1024, 500, 2, depth=16))
        self.assertOneProblem("featureGraphic.png", "bit depth 16")

    def test_screenshot_at_2_3_to_1_is_rejected(self):
        self.write_image("phoneScreenshots/02-shot.png", png(400, 920, 2))
        self.assertOneProblem("02-shot.png", "twice")

    def test_screenshot_side_under_320_is_rejected(self):
        self.write_image("phoneScreenshots/02-shot.png", png(300, 500, 2))
        self.assertOneProblem("02-shot.png", "320 to 3840")

    def test_nine_screenshots_are_rejected(self):
        for i in range(4, 10):
            self.write_image(f"phoneScreenshots/0{i}-shot.png", png(320, 640, 2))
        self.assertOneProblem("9 PNG screenshots", "2 to 8")

    def test_one_screenshot_is_rejected(self):
        for i in (2, 3):
            (self.images / f"phoneScreenshots/0{i}-shot.png").unlink()
        self.assertOneProblem("1 PNG screenshots", "2 to 8")

    def test_a_broken_png_is_rejected(self):
        data = bytearray(png(320, 640, 2))
        data[-5] ^= 0xFF  # the IEND CRC
        self.write_image("phoneScreenshots/01-shot.png", bytes(data))
        self.assertOneProblem("01-shot.png", "not a well-formed PNG")

    def test_missing_images_fail_check_without_a_traceback(self):
        for path in self.images.rglob("*.png"):
            path.unlink()
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(1, play_listing.main(["check", str(self.root)]))
        self.assertIn("icon.png: missing", out.getvalue())
        self.assertIn("featureGraphic.png: missing", out.getvalue())
        self.assertIn("0 PNG screenshots", out.getvalue())

    def test_upload_refuses_before_any_api_call(self):
        self.write_text("title.txt", "")
        with self.assertRaises(SystemExit) as refused:
            play_listing.read_listing(self.root)
        self.assertIn("title.txt: empty", str(refused.exception))


if __name__ == "__main__":
    unittest.main()
