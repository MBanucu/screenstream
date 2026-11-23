#!/usr/bin/env python3
"""DEVICE TEST - never run by CI; it needs the app on a phone.

    adb forward tcp:7200 tcp:7100
    SCREENSTREAM_DEVICE=1 SCREENSTREAM_PORT=7200 \
        python3 -m unittest test.test_device_wire -v

It is skipped unless ``SCREENSTREAM_DEVICE=1``. It checks that the parser and
the real stream agree on one frame: the size the header promises is the number
of bytes that arrives, the geometry is sane, the version is the one this client
speaks, and a second frame (when the screen changes) has a later timestamp.
"""

import os
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "client"))

import screenstream_client as client  # noqa: E402

ENABLED = os.environ.get("SCREENSTREAM_DEVICE") == "1"


@unittest.skipUnless(ENABLED, "device test: set SCREENSTREAM_DEVICE=1 with the app running")
class DeviceWireTests(unittest.TestCase):
    def test_one_frame_matches_its_header(self):
        host = os.environ.get("SCREENSTREAM_HOST", "127.0.0.1")
        port = int(os.environ.get("SCREENSTREAM_PORT", "7200"))
        with client.connect(host, port, timeout=10) as link:
            frame = link.wait_for_change(10.0)
            self.assertIsNotNone(frame, "no frame arrived in 10 s")
            self.assertEqual(frame.stride * frame.height, frame.size)
            self.assertEqual(frame.size, len(frame.pixels))
            self.assertGreater(frame.width, 0)
            self.assertGreater(frame.height, 0)
            self.assertEqual((client.PROTOCOL_MAJOR, client.PROTOCOL_MINOR),
                             frame.protocol_version)
            self.assertGreater(frame.number, 0)
            self.assertGreater(frame.timestamp_ns, 0)

            second = link.wait_for_change(2.0, since=frame.number)
            if second is not None:
                self.assertGreaterEqual(second.timestamp_ns, frame.timestamp_ns)
                self.assertGreater(second.number, frame.number)


if __name__ == "__main__":
    unittest.main()
