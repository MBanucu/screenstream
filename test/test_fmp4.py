#!/usr/bin/env python3
"""Offline checks for the fragmented MP4 parser; no ffmpeg and no device."""

import io
import struct
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "client"))
sys.path.insert(0, str(HERE))

import screenstream_client as client  # noqa: E402
import streamtest  # noqa: E402


class InitSegmentTests(unittest.TestCase):
    def test_base_ns_is_read_from_the_udta_uuid_box(self):
        init = streamtest.init_segment(base_ns=987654321)
        self.assertEqual(987654321, client.base_ns(init))

    def test_no_udta_means_no_base(self):
        self.assertIsNone(client.base_ns(streamtest.init_segment()))
        self.assertIsNone(client.base_ns(b""))

    def test_a_different_uuid_is_not_our_box(self):
        other = streamtest.box(b"udta", streamtest.box(
            b"uuid", b"\x00" * 16 + struct.pack(">IIQ", 1, 0, 42)))
        init = streamtest.box(b"ftyp", b"isom") + streamtest.box(b"moov", other)
        self.assertIsNone(client.base_ns(init))

    def test_boxes_are_ftyp_and_moov(self):
        init = streamtest.init_segment(base_ns=1)
        self.assertEqual([b"ftyp", b"moov"],
                         [kind for kind, _body, _size in client.boxes(init)])


class ReadFmp4Tests(unittest.TestCase):
    def test_complete_fragment_is_read_after_the_init(self):
        data = (streamtest.init_segment(base_ns=5)
                + streamtest.box(b"moof", b"index")
                + streamtest.box(b"mdat", b"pixels"))
        kinds = [found.kind for found in client.read_fmp4(data)]
        self.assertEqual([b"ftyp", b"moov", b"moof", b"mdat"], kinds)

    def test_bytes_and_a_binary_file_are_the_same(self):
        data = streamtest.init_segment(base_ns=5)
        from_bytes = list(client.read_fmp4(data))
        from_file = list(client.read_fmp4(io.BytesIO(data)))
        self.assertEqual([box.kind for box in from_bytes],
                         [box.kind for box in from_file])
        self.assertEqual(from_bytes[0].body, from_file[0].body)

    def test_truncated_fmp4_fragment_stops_at_the_last_complete_box(self):
        data = streamtest.init_segment(base_ns=5) + streamtest.truncated_fragment()
        boxes = list(client.read_fmp4(data))
        self.assertEqual([b"ftyp", b"moov", b"moof"],
                         [box.kind for box in boxes])
        # The mdat declared 72 bytes and only 24 arrived; it is not reported as
        # whole, and nothing raises. A live stream may stop in the middle of one.
        self.assertNotIn(b"mdat", [box.kind for box in boxes])

    def test_truncated_moof_hides_the_moof_too(self):
        moof = streamtest.box(b"moof", b"index")
        data = streamtest.init_segment() + moof[:-2]
        self.assertEqual([b"ftyp", b"moov"],
                         [box.kind for box in client.read_fmp4(data)])

    def test_a_box_smaller_than_its_header_stops_the_parse(self):
        data = streamtest.init_segment() + struct.pack(">I4s", 4, b"junk")
        self.assertEqual([b"ftyp", b"moov"],
                         [box.kind for box in client.read_fmp4(data)])


if __name__ == "__main__":
    unittest.main()
