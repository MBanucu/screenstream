#!/usr/bin/env python3
"""Deviceless checks for the emulator capture gate.

No emulator, no adb and no KVM: a fake ``adb`` stands in for the device list,
control replies are the shared Bundle fixtures, and frames are built in memory
by ``streamtest``. The full job still needs a hosted run; these tests keep the
script's fail-closed preflight and the reply/frame assertions honest.
"""

import os
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "client"))
sys.path.insert(0, str(ROOT / "ci"))
sys.path.insert(0, str(HERE))

import emulator_probe as probe  # noqa: E402
import screenstream_client as client  # noqa: E402
import streamtest  # noqa: E402

FIXTURES = HERE / "fixtures" / "emulator-control"
SCRIPT = ROOT / "ci" / "emulator-e2e.sh"
DISPLAY_SCRIPT = ROOT / "ci" / "emulator-display-profile.sh"


def fixture(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def run_script(args, env=None, timeout=30):
    return subprocess.run(
        ["bash", str(SCRIPT), *args], capture_output=True, text=True,
        timeout=timeout, env=env)


class StartReplyTests(unittest.TestCase):
    def test_a_streaming_reply_yields_its_bound_port_and_size(self):
        fields = probe.require_start(fixture("start.txt"), "720x1600")
        self.assertEqual("43127", fields["port"])
        self.assertEqual("2.0", fields["protocol"])
        self.assertEqual("8", fields["frames_looked"])
        self.assertEqual("appop", fields["grant_route"])

    def test_waiting_for_grant_is_a_failure_not_a_capture(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.require_start(fixture("start-waiting.txt"))
        message = str(caught.exception)
        self.assertIn("waiting_for_grant", message)
        self.assertIn("PROJECT_MEDIA", message)

    def test_a_capture_at_the_wrong_size_fails_the_profile_assertion(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.require_start(fixture("start.txt"), "1440x3200")
        self.assertIn("720x1600", str(caught.exception))
        self.assertIn("before boot", str(caught.exception))

    def test_a_reply_without_a_bound_port_is_refused(self):
        text = ("Result: Bundle[{code=0, data=state=streaming\n"
                "port=0\nprotocol=2.0\nframes_looked=2\ngrant=yes\n}]")
        with self.assertRaises(probe.ProbeError):
            probe.require_start(text)

    def test_a_stream_with_no_composited_frame_is_refused(self):
        text = ("Result: Bundle[{code=0, data=state=streaming\n"
                "port=43127\nprotocol=2.0\nframes_looked=0\ngrant=yes\n}]")
        with self.assertRaises(probe.ProbeError) as caught:
            probe.require_start(text)
        self.assertIn("no composited frame", str(caught.exception))

    def test_the_cli_prints_only_the_port(self):
        done = subprocess.run(
            [sys.executable, str(ROOT / "ci" / "emulator_probe.py"), "start",
             "--reply", str(FIXTURES / "start.txt"),
             "--expect-size", "720x1600"],
            capture_output=True, text=True, timeout=30)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual("43127", done.stdout.strip())

    def test_the_cli_fails_for_a_waiting_start(self):
        done = subprocess.run(
            [sys.executable, str(ROOT / "ci" / "emulator_probe.py"), "start",
             "--reply", str(FIXTURES / "start-waiting.txt")],
            capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("waiting_for_grant", done.stderr)


class GrantAndLifecycleReplyTests(unittest.TestCase):
    def test_an_appop_grant_is_accepted(self):
        self.assertEqual("appop", probe.require_grant(fixture("grant.txt")))

    def test_a_refused_grant_names_the_missing_piece(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.require_grant(fixture("grant-refused.txt"))
        self.assertIn("not granted headlessly", str(caught.exception))
        self.assertIn("PROJECT_MEDIA", str(caught.exception))

    def test_record_names_the_open_mp4(self):
        path = probe.require_record_started(fixture("record.txt"))
        self.assertTrue(path.endswith(".mp4"))
        self.assertIn("/recordings/", path)

    def test_recording_names_the_finalised_mp4(self):
        path = probe.require_recording(fixture("recording.txt"))
        self.assertTrue(path.endswith(".mp4"))
        self.assertIn("/recordings/", path)

    def test_stop_must_say_stopped(self):
        self.assertEqual("stopped", probe.require_stop(fixture("stop.txt")))
        with self.assertRaises(probe.ProbeError):
            probe.require_stop(fixture("start.txt"))


class FrameValidationTests(unittest.TestCase):
    def frame(self, number, timestamp_ns, width=4, height=2, stride=None,
              size=None, major=2, minor=0, pixels=None):
        stride = width * 4 if stride is None else stride
        size = stride * height if size is None else size
        return client.Frame(number, timestamp_ns, size,
                            b"\0" * size if pixels is None else pixels,
                            width, height, stride, 1, (major, minor))

    def test_one_valid_frame_and_non_decreasing_timestamps_pass(self):
        probe.check_frames([self.frame(1, 100), self.frame(2, 100),
                            self.frame(3, 250)], 4, 2)

    def test_a_backwards_timestamp_fails(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.check_frames([self.frame(1, 300), self.frame(2, 200)])
        self.assertIn("timestamp went backwards", str(caught.exception))

    def test_a_repeated_frame_number_fails(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.check_frames([self.frame(3, 100), self.frame(3, 200)])
        self.assertIn("did not increase", str(caught.exception))

    def test_an_unknown_protocol_major_fails(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.check_frames([self.frame(1, 100, major=3)])
        self.assertIn("protocol 3.0", str(caught.exception))

    def test_a_size_that_does_not_match_stride_times_height_fails(self):
        with self.assertRaises(probe.ProbeError):
            probe.check_frames([self.frame(1, 100, size=3)])

    def test_a_frame_at_the_wrong_resolution_fails(self):
        with self.assertRaises(probe.ProbeError) as caught:
            probe.check_frames([self.frame(1, 100)], 8, 4)
        self.assertIn("expected 8", str(caught.exception))

    def test_no_frame_is_a_failure(self):
        with self.assertRaises(probe.ProbeError):
            probe.check_frames([])

    def test_the_log_has_one_line_per_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "frames.log"
            probe.write_frame_log([self.frame(1, 11), self.frame(2, 22)], path)
            lines = path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(3, len(lines))
        self.assertIn("1 11 4 2 16 2.0 32", lines[1])


class GatedSocket:
    """A still screen until ``release()``, then the prepared frames."""

    def __init__(self, data):
        self.data = bytes(data)
        self.position = 0
        self.released = False
        self.timeout = None

    def release(self):
        self.released = True

    def settimeout(self, value):
        self.timeout = value

    def close(self):
        pass

    def recv_into(self, view, size):
        if not self.released or self.position >= len(self.data):
            raise socket.timeout()
        chunk = self.data[self.position:self.position + size]
        view[:len(chunk)] = chunk
        self.position += len(chunk)
        return len(chunk)


class CollectionTests(unittest.TestCase):
    def test_at_least_one_frame_after_the_change_is_required(self):
        data = (streamtest.frame_bytes(4, 400, width=2, height=1)
                + streamtest.frame_bytes(5, 500, width=2, height=1))
        sock = GatedSocket(data)
        frames = probe.collect_after_change(
            client.Connection(sock), sock.release, timeout=1.0, quiet=0.05,
            limit=2, expect_width=2, expect_height=1)
        self.assertEqual([4, 5], [frame.number for frame in frames])

    def test_a_still_screen_after_the_change_fails(self):
        sock = GatedSocket(b"")
        with self.assertRaises(probe.ProbeError):
            probe.collect_after_change(
                client.Connection(sock), sock.release, timeout=0.2, quiet=0.05)


class ShellPreflightTests(unittest.TestCase):
    """The shell script must fail without an emulator; a skip is not a pass."""

    def fake_adb(self, directory, devices):
        bin_dir = Path(directory) / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "adb"
        # /bin/sh, not /usr/bin/env: Termux has no /usr/bin/env, and these
        # tests are meant to run there as well as on the hosted runner.
        fake.write_text(
            "#!/bin/sh\n"
            "printf 'List of devices attached\\n'\n"
            "printf '%s\\n' \"${FAKE_DEVICES:-}\"\n"
            "printf '\\n'\n",
            encoding="utf-8")
        fake.chmod(0o755)
        env = os.environ.copy()
        env["PATH"] = "%s%s%s" % (bin_dir, os.pathsep, env.get("PATH", ""))
        env["EMULATOR_OUT"] = str(Path(directory) / "out")
        env["FAKE_DEVICES"] = devices
        return env

    def test_no_attached_emulator_fails_the_preflight(self):
        with tempfile.TemporaryDirectory() as directory:
            env = self.fake_adb(directory, "")
            done = run_script(["--check-device"], env)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("no attached emulator", done.stderr)
        self.assertIn("fails the run", done.stderr)

    def test_a_physical_serial_does_not_satisfy_the_emulator_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            env = self.fake_adb(directory, "0123456789ABCDEF\tdevice")
            done = run_script(["--check-device"], env)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("no attached emulator", done.stderr)

    def test_an_attached_emulator_passes_the_preflight(self):
        with tempfile.TemporaryDirectory() as directory:
            env = self.fake_adb(directory, "emulator-5554\tdevice")
            done = run_script(["--check-device"], env)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("attached emulator-5554", done.stdout)


class DisplayProfileScriptTests(unittest.TestCase):
    def test_the_profile_is_written_before_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            avd = Path(directory) / "test.avd"
            avd.mkdir()
            config = avd / "config.ini"
            config.write_text(
                "hw.lcd.width=1080\nhw.lcd.height=1920\n"
                "hw.lcd.density=420\nskin.dynamic=yes\n"
                "skin.name=1080x1920\nskin.path=/sdk/skins/pixel_2\n",
                encoding="utf-8")
            env = os.environ.copy()
            env.update({"ANDROID_AVD_HOME": directory,
                        "DISPLAY_WIDTH": "1440", "DISPLAY_HEIGHT": "3200",
                        "DISPLAY_DENSITY": "560"})
            done = subprocess.run(
                ["bash", str(DISPLAY_SCRIPT), "test"],
                capture_output=True, text=True, timeout=30, env=env)
            self.assertEqual(0, done.returncode, done.stderr)
            text = config.read_text(encoding="utf-8")
        self.assertIn("hw.lcd.width=1440", text)
        self.assertIn("hw.lcd.height=3200", text)
        self.assertIn("hw.lcd.density=560", text)
        self.assertIn("skin.name=1440x3200", text)
        self.assertIn("skin.path=_no_skin", text)
        self.assertNotIn("skin.path=/sdk/skins/pixel_2", text)

    def test_a_missing_avd_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            env = os.environ.copy()
            env["ANDROID_AVD_HOME"] = directory
            done = subprocess.run(
                ["bash", str(DISPLAY_SCRIPT), "absent"],
                capture_output=True, text=True, timeout=30, env=env)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("no AVD config", done.stderr)


if __name__ == "__main__":
    unittest.main()
