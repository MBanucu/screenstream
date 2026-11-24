#!/usr/bin/env python3
"""Host-side checks for the emulator capture smoke test.

``ci/emulator-e2e.sh`` drives adb and the app; this module owns the two things
that are worth testing without a device:

* the synchronous control replies (a Bundle, through the same parser a consumer
  uses), so a start that did not reach ``state=streaming`` or did not report a
  bound port fails instead of being read as a capture;
* the raw frames after a deliberate screen change, so a header, its byte count
  and a non-decreasing device timestamp are assertions rather than log lines.

No socket is opened and no process is started by the functions the tests use;
the command-line part is a thin wrapper for the shell script. Run
``python3 -m unittest test.test_emulator_e2e -v`` for the deviceless checks.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "client"))

import screenstream_client as client  # noqa: E402
from screenstream_client.control import ControlError, parse_result  # noqa: E402

START_STATE = "streaming"
STOP_STATE = "stopped"


class ProbeError(Exception):
    """A reply or a stream did not prove what the gate needs."""


def _reply(text: str):
    try:
        return parse_result(text)
    except ControlError as failed:
        raise ProbeError(str(failed)) from failed


def _reason(reply) -> str:
    fields = reply.fields
    parts = []
    for key in ("why", "error", "detail", "command", "reason"):
        value = fields.get(key)
        if value:
            parts.append("%s=%s" % (key, value))
    return "; ".join(parts) if parts else "no reason in the reply"


def require_grant(text: str) -> str:
    """Returns the grant route for ``grant=yes``; anything else is a failure."""
    reply = _reply(text)
    if reply.code != 0:
        raise ProbeError("grant failed with code %d: %s"
                         % (reply.code, _reason(reply)))
    if reply.fields.get("grant") != "yes":
        raise ProbeError(
            "the projection was not granted headlessly (grant=%s, route=%s): %s"
            % (reply.fields.get("grant", "absent"),
               reply.fields.get("grant_route", "absent"), _reason(reply)))
    return reply.fields.get("grant_route", "unknown")


def require_start(text: str, expect_size: str | None = None) -> dict[str, str]:
    """Checks a synchronous ``start`` reply and returns its fields.

    The reply is the whole proof that a capture is live: ``state=streaming``,
    a bound port, protocol 2.x, at least one composited frame, and the frame
    size after scale. A ``waiting_for_grant`` or ``failed`` answer is a failed
    run, never a reason to poll a state file and guess.
    """
    reply = _reply(text)
    fields = reply.fields
    state = fields.get("state", "absent")
    if reply.code != 0 or state != START_STATE:
        raise ProbeError(
            "start did not reach %s (code=%d, state=%s): %s"
            % (START_STATE, reply.code, state, _reason(reply)))
    if fields.get("grant") != "yes":
        raise ProbeError("start is streaming without a headless grant (grant=%s)"
                         % fields.get("grant", "absent"))
    try:
        port = int(fields["port"])
    except (KeyError, ValueError):
        raise ProbeError("start reply has no numeric port: %r" % fields.get("port"))
    if not 1 <= port <= 65535:
        raise ProbeError("start reply reported port %d, not a bound listener" % port)
    if fields.get("protocol", "").split(".")[0] != str(client.PROTOCOL_MAJOR):
        raise ProbeError("start reply speaks protocol %r, this client speaks %d.x"
                         % (fields.get("protocol", "absent"), client.PROTOCOL_MAJOR))
    try:
        looked = int(fields.get("frames_looked", "0"))
    except ValueError:
        looked = 0
    if looked <= 0:
        raise ProbeError("start is streaming but no composited frame was looked at: %s"
                         % _reason(reply))
    if expect_size and fields.get("size") != expect_size:
        raise ProbeError("capture size is %r, expected %s (display profile or scale "
                         "did not take effect before boot)"
                         % (fields.get("size", "absent"), expect_size))
    return fields


def require_record_started(text: str) -> str:
    """Returns the device path of the MP4 a ``record`` reply opened."""
    reply = _reply(text)
    if reply.code != 0:
        raise ProbeError("record failed with code %d: %s"
                         % (reply.code, _reason(reply)))
    path = reply.fields.get("recording", "-")
    if not path.startswith("/"):
        raise ProbeError("record did not open an MP4 (recording=%r)" % path)
    return path


def require_recording(text: str) -> str:
    """Returns the finalised MP4 path from a ``recording`` reply."""
    reply = _reply(text)
    if reply.code != 0:
        raise ProbeError("recording did not finish with code %d: %s"
                         % (reply.code, _reason(reply)))
    path = reply.fields.get("recording_file", "")
    if not path.startswith("/") or not path.endswith(".mp4"):
        raise ProbeError("recording reply named no MP4 (recording_file=%r)" % path)
    return path


def require_stop(text: str) -> str:
    """Checks a ``stop`` reply and returns the final state."""
    reply = _reply(text)
    state = reply.fields.get("state", "absent")
    if reply.code != 0 or state != STOP_STATE:
        raise ProbeError("stop did not reach %s (code=%d, state=%s): %s"
                         % (STOP_STATE, reply.code, state, _reason(reply)))
    return state


def check_frames(frames, expect_width: int | None = None,
                 expect_height: int | None = None) -> None:
    """Validates one or more frames from a real stream.

    The assertions are protocol and lifecycle, not picture quality: a known
    header major, geometry that agrees with the payload size, a positive device
    timestamp, strictly increasing frame numbers and a non-decreasing timestamp.
    """
    if not frames:
        raise ProbeError("no frame arrived after the screen changed")
    previous_number = None
    previous_timestamp = None
    for frame in frames:
        major, minor = frame.protocol_version
        if major != client.PROTOCOL_MAJOR:
            raise ProbeError("frame %d speaks protocol %d.%d; this client speaks %d.x"
                             % (frame.number, major, minor, client.PROTOCOL_MAJOR))
        if frame.width <= 0 or frame.height <= 0:
            raise ProbeError("frame %d has impossible geometry %dx%d"
                             % (frame.number, frame.width, frame.height))
        if frame.stride < frame.width * client.BYTES_PER_PIXEL:
            raise ProbeError("frame %d stride %d is narrower than %d RGBA pixels"
                             % (frame.number, frame.stride,
                                frame.width * client.BYTES_PER_PIXEL))
        if frame.size != frame.stride * frame.height:
            raise ProbeError("frame %d declares %d bytes but stride*height is %d"
                             % (frame.number, frame.size,
                                frame.stride * frame.height))
        if frame.size != len(frame.pixels):
            raise ProbeError("frame %d declared %d bytes and delivered %d"
                             % (frame.number, frame.size, len(frame.pixels)))
        if frame.timestamp_ns <= 0:
            raise ProbeError("frame %d has no device timestamp" % frame.number)
        if expect_width is not None and frame.width != expect_width:
            raise ProbeError("frame %d is %d wide, expected %d (display profile "
                             "or scale did not take effect)"
                             % (frame.number, frame.width, expect_width))
        if expect_height is not None and frame.height != expect_height:
            raise ProbeError("frame %d is %d high, expected %d (display profile "
                             "or scale did not take effect)"
                             % (frame.number, frame.height, expect_height))
        if previous_number is not None:
            if frame.number <= previous_number:
                raise ProbeError("frame number did not increase: %d after %d"
                                 % (frame.number, previous_number))
            if frame.timestamp_ns < previous_timestamp:
                raise ProbeError(
                    "device timestamp went backwards at frame %d: %d ns after %d ns"
                    % (frame.number, frame.timestamp_ns, previous_timestamp))
        previous_number = frame.number
        previous_timestamp = frame.timestamp_ns


def collect_after_change(link, change, timeout: float = 30.0,
                         quiet: float = 0.75, limit: int = 5,
                         expect_width: int | None = None,
                         expect_height: int | None = None):
    """Waits for frames produced after ``change()`` runs.

    The screen is still after ``start``, so the socket is drained until it is
    quiet for ``quiet`` seconds; that gives a baseline without a fixed sleep.
    Only after the deliberate change is the bounded frame wait started, so a
    frame already queued by the first composited frame cannot stand in for the
    change under test. At least one frame is required and up to ``limit`` are
    checked for monotonic timestamps.
    """
    drain_deadline = time.monotonic() + 5.0
    while True:
        remaining = drain_deadline - time.monotonic()
        if remaining <= 0:
            break
        frame = link.read_frame(min(quiet, remaining))
        if frame is None:
            break

    change()

    deadline = time.monotonic() + max(0.1, timeout)
    frames = []
    while len(frames) < limit:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        frame = link.read_frame(min(2.0, remaining))
        if frame is None:
            if frames:
                break
            continue
        frames.append(frame)

    check_frames(frames, expect_width, expect_height)
    return frames


def write_frame_log(frames, path) -> None:
    """One line per frame, in the order the client read them."""
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("# number timestamp_ns width height stride protocol bytes\n")
        for frame in frames:
            major, minor = frame.protocol_version
            handle.write("%d %d %d %d %d %d.%d %d\n"
                         % (frame.number, frame.timestamp_ns, frame.width,
                            frame.height, frame.stride, major, minor,
                            frame.size))


def _adb(serial: str, *args: str, check: bool = False) -> None:
    done = subprocess.run(["adb", "-s", serial, *args],
                          capture_output=True, text=True)
    if check and done.returncode != 0:
        raise ProbeError("adb %s failed: %s"
                         % (" ".join(args), (done.stderr or done.stdout).strip()))


def change_screen(serial: str) -> None:
    """Makes a large, deterministic change after the client has connected."""
    _adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    _adb(serial, "shell", "wm", "dismiss-keyguard")
    _adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    # Settings is a full-screen compositor update; the runner has no person to
    # tap anything, but this needs no dialog and no fixed sleep.
    _adb(serial, "shell", "am", "start", "-a", "android.settings.SETTINGS",
         check=True)


def _size(text: str | None) -> tuple[int | None, int | None]:
    if not text:
        return None, None
    try:
        width, height = text.lower().split("x", 1)
        return int(width), int(height)
    except ValueError:
        raise ProbeError("expected WIDTHxHEIGHT, not %r" % text)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    grant = sub.add_parser("grant", help="check a grant reply")
    grant.add_argument("--reply", required=True)

    start = sub.add_parser("start", help="check a start reply and print its port")
    start.add_argument("--reply", required=True)
    start.add_argument("--expect-size", help="WIDTHxHEIGHT after scale")

    record = sub.add_parser("record", help="check a record reply")
    record.add_argument("--reply", required=True)

    recording = sub.add_parser("recording", help="print the finalised MP4 path")
    recording.add_argument("--reply", required=True)

    stop = sub.add_parser("stop", help="check a stop reply")
    stop.add_argument("--reply", required=True)

    frames = sub.add_parser("frames", help="read frames after a screen change")
    frames.add_argument("--host", default="127.0.0.1")
    frames.add_argument("--port", type=int, required=True)
    frames.add_argument("--serial", required=True)
    frames.add_argument("--out", required=True)
    frames.add_argument("--timeout", type=float, default=30.0)
    frames.add_argument("--limit", type=int, default=5)
    frames.add_argument("--expect-size", help="WIDTHxHEIGHT after scale")

    args = parser.parse_args(argv)
    try:
        if args.command == "grant":
            print(require_grant(Path(args.reply).read_text(encoding="utf-8")))
        elif args.command == "start":
            print(require_start(Path(args.reply).read_text(encoding="utf-8"),
                                args.expect_size)["port"])
        elif args.command == "record":
            print(require_record_started(
                Path(args.reply).read_text(encoding="utf-8")))
        elif args.command == "recording":
            print(require_recording(Path(args.reply).read_text(encoding="utf-8")))
        elif args.command == "stop":
            print(require_stop(Path(args.reply).read_text(encoding="utf-8")))
        elif args.command == "frames":
            width, height = _size(args.expect_size)
            with client.connect(args.host, args.port, timeout=10) as link:
                found = collect_after_change(
                    link, lambda: change_screen(args.serial),
                    timeout=args.timeout, limit=args.limit,
                    expect_width=width, expect_height=height)
            out = Path(args.out)
            out.mkdir(parents=True, exist_ok=True)
            write_frame_log(found, out / "frames.log")
            print("frames=%d numbers=%d..%d timestamps_ns=%d..%d"
                  % (len(found), found[0].number, found[-1].number,
                     found[0].timestamp_ns, found[-1].timestamp_ns))
    except (ProbeError, client.ScreenstreamError, OSError) as failed:
        print("emulator probe: %s" % failed, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
