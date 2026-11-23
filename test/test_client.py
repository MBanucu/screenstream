#!/usr/bin/env python3
"""Offline checks for the raw frame protocol; no phone and no socket.

Frames are built in memory and served by a socket-like object, so the cases a
real TCP connection produces by accident - a frame split across reads, two
frames in one read, a header that stops halfway - are deterministic here.
"""

import re
import struct
import sys
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "client"))
sys.path.insert(0, str(HERE))

import screenstream_client as client  # noqa: E402
import streamtest  # noqa: E402


def connection(data, reads=None, eof=True):
    return client.Connection(streamtest.FakeSocket(data, reads, eof=eof))


class FrameParsingTests(unittest.TestCase):
    def test_frame_split_across_reads(self):
        data = streamtest.frame_bytes(7, 123456789, width=3, height=2)
        link = connection(data, reads=[1] * len(data))
        frame = link.read_frame()
        self.assertEqual(7, frame.number)
        self.assertEqual(123456789, frame.timestamp_ns)
        self.assertEqual(3 * 4 * 2, frame.size)
        self.assertEqual(data[-frame.size:], frame.pixels)
        self.assertEqual((2, 0), frame.protocol_version)

    def test_two_frames_in_one_read(self):
        data = (streamtest.frame_bytes(1, 100, width=2, height=1)
                + streamtest.frame_bytes(2, 200, width=2, height=1))
        # The fake serves everything the caller asks for from one byte string:
        # both frames are available together, as one TCP read can deliver them.
        link = connection(data, reads=[len(data)])
        first = link.read_frame()
        second = link.read_frame()
        self.assertEqual((1, 100), (first.number, first.timestamp_ns))
        self.assertEqual((2, 200), (second.number, second.timestamp_ns))
        with self.assertRaises(client.ConnectionClosed):
            link.read_frame()

    def test_zero_length_header_is_a_sentence_not_a_decode_error(self):
        with self.assertRaises(client.ConnectionClosed) as caught:
            connection(b"").read_frame()
        self.assertIn("closed", str(caught.exception))
        self.assertIn("frame header", str(caught.exception))
        self.assertNotIsInstance(caught.exception, struct.error)

    def test_partial_header_is_a_sentence_not_a_decode_error(self):
        data = streamtest.frame_bytes(1, 1)[:10]
        with self.assertRaises(client.ConnectionClosed) as caught:
            connection(data).read_frame()
        self.assertIn("frame header", str(caught.exception))
        self.assertIn("bytes", str(caught.exception))
        self.assertNotIsInstance(caught.exception, struct.error)

    def test_a_newer_minor_is_accepted(self):
        data = streamtest.frame_bytes(1, 1, minor=9)
        frame = connection(data).read_frame()
        self.assertEqual((2, 9), frame.protocol_version)

    def test_partial_header_timeout_is_a_sentence_too(self):
        data = streamtest.frame_bytes(1, 1)[:10]
        with self.assertRaises(client.ProtocolError) as caught:
            connection(data, eof=False).read_frame(0.01)
        self.assertIn("stopped in the middle", str(caught.exception))

    def test_unknown_major_version_is_refused_with_a_sentence(self):
        data = streamtest.frame_bytes(1, 1, major=3, minor=4)
        with self.assertRaises(client.ProtocolError) as caught:
            connection(data).read_frame()
        message = str(caught.exception)
        self.assertIn("protocol 3.4", message)
        self.assertIn("understands 2.x", message)
        self.assertIn("upgrade", message)
        self.assertNotIsInstance(caught.exception, struct.error)

    def test_unknown_magic_is_not_read_as_fields(self):
        with self.assertRaises(client.ProtocolError) as caught:
            connection(b"HTTP/1.1 200 OK\r\n").read_frame()
        self.assertIn("not a frame header", str(caught.exception))

    def test_legacy_raw1_frame_is_still_accepted(self):
        data = streamtest.legacy_frame_bytes(5, 500, width=2, height=2)
        frame = connection(data).read_frame()
        self.assertEqual(5, frame.number)
        self.assertEqual((1, 0), frame.protocol_version)
        self.assertEqual(2 * 4 * 2, frame.size)

    def test_impossible_geometry_is_refused(self):
        # stride smaller than one RGBA row cannot describe a frame.
        data = streamtest.frame_bytes(1, 1, width=8, height=1, stride=4)
        with self.assertRaises(client.ProtocolError) as caught:
            connection(data).read_frame()
        self.assertIn("impossible frame geometry", str(caught.exception))

    def test_frame_is_the_documented_four_tuple_first(self):
        frame = connection(streamtest.frame_bytes(3, 30)).read_frame()
        number, timestamp_ns, size, pixels = frame
        self.assertEqual(3, number)
        self.assertEqual(30, timestamp_ns)
        self.assertEqual(frame.size, size)
        self.assertEqual(frame.pixels, pixels)

    def test_padded_rows_are_removed_for_a_picture(self):
        # 2x2, stride 12: each row is two RGBA pixels (8 bytes) and 4 of padding.
        pixels = (b"a1b2c3d4" + b"PPPP" + b"e5f6g7h8" + b"QQQQ")
        data = streamtest.frame_bytes(1, 1, width=2, height=2, stride=12,
                                      pixels=pixels)
        frame = connection(data).read_frame()
        self.assertEqual(b"a1b2c3d4e5f6g7h8", frame.body)

    def test_frames_iterator_stops_at_the_close(self):
        data = (streamtest.frame_bytes(1, 1) + streamtest.frame_bytes(2, 2))
        self.assertEqual([1, 2], [f.number for f in connection(data).frames()])

    def test_module_level_helpers_take_a_connection(self):
        data = streamtest.frame_bytes(4, 40)
        link = connection(data)
        self.assertEqual(4, client.wait_for_change(link, 1.0).number)
        self.assertEqual([], list(client.frames(link)))


class WaitForChangeTests(unittest.TestCase):
    def test_returns_the_next_frame_after_since(self):
        data = (streamtest.frame_bytes(1, 10) + streamtest.frame_bytes(2, 20))
        link = connection(data)
        first = link.wait_for_change(1.0)
        second = link.wait_for_change(1.0, since=first.number)
        self.assertEqual(1, first.number)
        self.assertEqual(2, second.number)

    def test_a_still_screen_times_out_with_none(self):
        link = connection(b"", eof=False)
        started = time.monotonic()
        self.assertIsNone(link.wait_for_change(0.05))
        self.assertLess(time.monotonic() - started, 2.0)


class VersionTests(unittest.TestCase):
    """One version for the AAR and the client, and one protocol major."""

    def test_pyproject_gradle_and_build_sh_agree(self):
        pyproject = (ROOT / "client" / "pyproject.toml").read_text()
        match = re.search(r'(?m)^version = "([^"]+)"', pyproject)
        self.assertIsNotNone(match, "no version in client/pyproject.toml")
        pyproject_version = match.group(1)

        gradle = (ROOT / "build.gradle.kts").read_text()
        gradle_version = re.search(
            r'getOrElse\("([^"]+)"\)', gradle).group(1)
        script = (ROOT / "build.sh").read_text()
        script_version = re.search(
            r'version=\$\{VERSION:-([^}]+)\}', script).group(1)

        self.assertEqual(pyproject_version, gradle_version)
        self.assertEqual(pyproject_version, script_version)

    def test_the_client_speaks_the_javas_header_version(self):
        java = (ROOT / "src/main/java/io/github/mbanucu/screenstream/"
                "ScreenStream.java").read_text()
        major = int(re.search(r"PROTOCOL_MAJOR = (\d+);", java).group(1))
        minor = int(re.search(r"PROTOCOL_MINOR = (\d+);", java).group(1))
        self.assertEqual(client.PROTOCOL_MAJOR, major)
        self.assertEqual(client.PROTOCOL_MINOR, minor)
        self.assertIn('put((byte) \'R\').put((byte) \'A\')'
                      '.put((byte) \'W\').put((byte) \'2\')', java.replace("\n", ""))
        # The state file carries the same version on both the live and idle path.
        self.assertEqual(2, java.count('out.append("protocol=")'))

    def test_header_is_the_documented_size(self):
        self.assertEqual(40, client.HEADER.size)
        self.assertEqual(36, client.LEGACY_HEADER.size)


if __name__ == "__main__":
    unittest.main()
