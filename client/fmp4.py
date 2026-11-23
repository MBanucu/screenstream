#!/usr/bin/env python3
"""Attach to the encoded stream and save what it sends.

The raw stream on 7100 is the display itself - RGBA, about 18 MB a frame at this
phone's size. This is the same capture as H.264 in a fragmented MP4, on 7101, which
is a couple of megabits: the frames go through the encoder the recording uses, so a
viewer sees exactly the pictures the file would hold.

    adb forward tcp:7101 tcp:7101
    python3 client/fmp4.py --seconds 10 --out out/live.mp4

What is written is playable as it stands - the stream starts with an init segment
and every fragment after it carries its own index - so ffprobe and ffplay read the
file without a remux, and a player can start before the capture ends.

The stream asks the encoder for a keyframe when a viewer attaches, so the first
second or two can be empty while that frame arrives. `--seconds` is how long to
listen, not a deadline for the stream: whatever arrived is what is saved.
"""

import argparse
import socket
import sys
import time


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7101)
    parser.add_argument("--seconds", type=float, default=10.0,
                        help="how long to listen (0 runs until killed)")
    parser.add_argument("--out", default="live.mp4",
                        help="where to write the fragmented MP4")
    args = parser.parse_args(argv)

    started = time.monotonic()
    deadline = started + args.seconds if args.seconds > 0 else None
    total = 0
    try:
        with socket.create_connection((args.host, args.port), timeout=5) as link, \
                open(args.out, "wb") as out:
            link.settimeout(1.0)
            while deadline is None or time.monotonic() < deadline:
                try:
                    chunk = link.recv(1 << 16)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                out.write(chunk)
                total += len(chunk)
    except OSError as failed:
        print("could not read the stream: %s" % failed, file=sys.stderr)
        return 1
    span = time.monotonic() - started
    print("%s: %d bytes in %.1fs (%.0f KiB/s)" % (args.out, total, span, total / 1024 / span))
    return 0 if total else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
