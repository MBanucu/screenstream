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
protocol=2.0
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
grant=yes
grant_route=appop
"""

REQUIRED = (
    "state", "port", "protocol", "encoded_port", "frames_looked", "frames_changed",
    "frames_sent", "frames_skipped", "recording", "recorded_frames",
    "recording_from_ns", "grant", "grant_route",
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
        self.assertEqual("yes", reply["grant"])
        self.assertEqual("appop", reply["grant_route"])

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


class GrantTests(unittest.TestCase):
    def reply(self, data, code=0):
        return control.parse_result("Result: Bundle[{code=" + str(code)
                                    + ", data=" + data + "}]")

    def test_appop_route_is_a_grant(self):
        reply = self.reply("state=streaming\nport=43127\n"
                           "grant=yes\ngrant_route=appop\n")
        self.assertTrue(control.granted(reply))
        self.assertEqual("appop", reply["grant_route"])
        self.assertFalse(control.waiting_for_grant(reply))

    def test_no_grant_names_the_dialog_and_the_command(self):
        data = ("grant=no\n"
                "grant_route=none\n"
                "package=io.github.mbanucu.screenstream.app\n"
                "reason=dialog\n"
                "command=adb shell appops set io.github.mbanucu.screenstream.app "
                "PROJECT_MEDIA allow\n"
                "revoke_command=adb shell appops set io.github.mbanucu.screenstream.app "
                "PROJECT_MEDIA default\n")
        reply = self.reply(data)
        self.assertFalse(control.granted(reply))
        self.assertEqual("none", reply["grant_route"])
        self.assertEqual("dialog", reply["reason"])
        self.assertIn("PROJECT_MEDIA allow", reply["command"])
        self.assertIn("PROJECT_MEDIA default", reply["revoke_command"])

    def test_waiting_for_grant_never_claims_streaming(self):
        data = ("state=waiting_for_grant\n"
                "why=no capture started\n"
                "port=0\n"
                "reason=dialog\n"
                "grant=no\n"
                "grant_route=none\n"
                "command=adb shell appops set p PROJECT_MEDIA allow\n"
                "fallback=accept the system dialog on the device\n")
        reply = self.reply(data, 400)
        self.assertEqual(400, reply.code)
        self.assertEqual("waiting_for_grant", reply["state"])
        self.assertNotEqual("streaming", reply["state"])
        self.assertTrue(control.waiting_for_grant(reply))
        self.assertFalse(control.granted(reply))
        self.assertIn("appops set", reply["command"])

    def test_unknown_and_absent_grants_are_none(self):
        self.assertIsNone(control.granted(self.reply("grant=unknown\n")))
        self.assertIsNone(control.granted(self.reply("state=stopped\n")))

    def test_waiting_for_grant_is_no_grant_even_when_the_appop_is_allowed(self):
        data = ("state=waiting_for_grant\n"
                "reason=dialog\n"
                "grant=no\n"
                "grant_route=dialog\n"
                "appop=yes\n"
                "detail=the app-op is allowed, but this platform still asked\n")
        reply = self.reply(data, 400)
        self.assertTrue(control.waiting_for_grant(reply))
        self.assertFalse(control.granted(reply))
        self.assertEqual("yes", reply["appop"])
        self.assertEqual("dialog", reply["grant_route"])

    def test_dialog_route_after_a_grant_is_still_granted(self):
        reply = self.reply("state=streaming\ngrant=yes\ngrant_route=dialog\n")
        self.assertTrue(control.granted(reply))
        self.assertEqual("dialog", reply["grant_route"])


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

    def test_grant_command_has_no_extras(self):
        self.assertEqual(
            ["adb", "shell", "content", "call", "--uri", control.AUTHORITY,
             "--method", "grant"],
            control.command("grant"))

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
