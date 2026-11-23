#!/usr/bin/env python3
"""Host-side checks for the control reply format; no phone is needed.

The provider's `data` is the state file's key=value vocabulary, and `content call`
wraps it in a Bundle. These tests pin the parsing and formatting a consumer does,
so a change to either shape fails here rather than in a device suite.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "client"))

import control  # noqa: E402


STATUS = """state=streaming
port=43127
size=1440x3200
scale=1
format=rgba8888
frames_looked=8
frames_changed=3
frames_sent=3
frames_skipped=0
clients=1
taps=0
recording=-
recorded_frames=0
recorded_ms=0
recording_from_ns=0
encoded_port=43128
encoded_clients=0
encoded_frames=0
encoded_bytes=0
encoder=-
"""

REQUIRED = (
    "state", "port", "encoded_port", "frames_looked", "frames_changed",
    "frames_sent", "frames_skipped", "recording", "recorded_frames",
    "recording_from_ns",
)


class ParseTests(unittest.TestCase):
    def test_status_reply(self):
        reply = control.parse_result(
            "Result: Bundle[{code=0, data=" + STATUS + "}]")
        self.assertEqual(0, reply.code)
        self.assertEqual(STATUS, reply.data)
        self.assertEqual("streaming", reply["state"])
        self.assertEqual("43127", reply["port"])
        self.assertEqual("43128", reply["encoded_port"])
        self.assertEqual("-", reply["recording"])

    def test_all_status_keys_survive_the_round_trip(self):
        reply = control.parse_result(
            "Result: Bundle[{code=0, data=" + STATUS + "}]")
        for key in REQUIRED:
            self.assertIn(key, reply.fields, "missing %s" % key)

    def test_failure_reply_with_newlines(self):
        data = ("state=failed\n"
                "why=could not listen on 127.0.0.1:7100: Address already in use\n"
                "port=7100\n"
                "encoded_port=-\n")
        reply = control.parse_result("Result: Bundle[{code=400, data=" + data + "}]")
        self.assertEqual(400, reply.code)
        self.assertEqual("failed", reply["state"])
        self.assertIn("127.0.0.1:7100", reply["why"])
        self.assertEqual("-", reply["encoded_port"])

    def test_adb_noise_before_the_result_is_ignored(self):
        reply = control.parse_result(
            "WARNING: linker: unused library\nResult: Bundle[{code=0, data=state=stopped\n}]")
        self.assertEqual(0, reply.code)
        self.assertEqual("stopped", reply["state"])

    def test_non_bundle_output_is_an_error(self):
        with self.assertRaises(control.ControlError):
            control.parse_result("Error: Unknown URI content://elsewhere\n")


class FieldTests(unittest.TestCase):
    def test_value_keeps_equals_and_colons(self):
        fields = control.parse_fields(
            "why=could not listen on 127.0.0.1:7100: Address already in use\n"
            "recording=/sdcard/a=b/c.mp4\n")
        self.assertEqual("could not listen on 127.0.0.1:7100: Address already in use",
                         fields["why"])
        self.assertEqual("/sdcard/a=b/c.mp4", fields["recording"])

    def test_blank_and_non_key_value_lines_are_skipped(self):
        fields = control.parse_fields("\nnot a field\nstate=stopped\n")
        self.assertEqual({"state": "stopped"}, fields)

    def test_last_duplicate_wins(self):
        self.assertEqual("stopped", control.parse_fields(
            "state=streaming\nstate=stopped\n")["state"])


class CommandTests(unittest.TestCase):
    def test_status_command(self):
        self.assertEqual(
            ["adb", "shell", "content", "call", "--uri", control.AUTHORITY,
             "--method", "status"],
            control.command("status"))

    def test_start_extras_are_typed_and_ordered(self):
        extras = control.start_extras(0, 0, 2, 5000)
        self.assertEqual(["port:i:0", "encoded_port:i:0", "scale:i:2",
                          "timeout_ms:i:5000"], extras)
        self.assertEqual(
            ["adb", "-s", "emulator-5554", "shell", "content", "call",
             "--uri", control.AUTHORITY, "--method", "start",
             "--extra", "port:i:0", "--extra", "encoded_port:i:0",
             "--extra", "scale:i:2", "--extra", "timeout_ms:i:5000"],
            control.command("start", "emulator-5554", extras))

    def test_no_extras_means_defaults_on_the_device(self):
        self.assertEqual([], control.start_extras(None, None, None, None))


if __name__ == "__main__":
    unittest.main()
