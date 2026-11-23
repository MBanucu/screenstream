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

The init segment carries the device clock's reading for the track's zero, in a
`udta`/`uuid` box, printed as `base_ns` when it arrives. A frame's device time is
then `base_ns + pts * 1000`, with pts from the container - the same clock the raw
stream's frame headers and the harness's `device_ns` use.
"""

import argparse
import socket
import struct
import sys
import time

# The udta/uuid box's identifier: a private extension, skipped by size by every
# reader that does not know it.
BASE_UUID = bytes.fromhex("6d42414e7563750073747265616d0001")


def boxes(data, start, end):
    """Every complete box in data[start:end], as (type, body, body size)."""
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        if size < 8 or position + size > end:
            return
        yield kind, position + 8, size - 8
        position += size


def base_ns(init):
    """The device time of the track's zero from the init segment, or None."""
    for kind, body, size in boxes(init, 0, len(init)):
        if kind != b"moov":
            continue
        for kind2, body2, size2 in boxes(init, body, body + size):
            if kind2 != b"udta":
                continue
            for kind3, body3, size3 in boxes(init, body2, body2 + size2):
                if kind3 != b"uuid" or size3 < 32 or init[body3:body3 + 16] != BASE_UUID:
                    continue
                version = struct.unpack(">I", init[body3 + 16:body3 + 20])[0]
                if version != 1:
                    continue
                return struct.unpack(">Q", init[body3 + 24:body3 + 32])[0]
    return None


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
    head = b""
    reported = False
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
                if not reported and len(head) < (1 << 16):
                    head += chunk
                    found = base_ns(head)
                    if found is not None:
                        print("base_ns=%d (device clock, ns)" % found)
                        reported = True
    except OSError as failed:
        print("could not read the stream: %s" % failed, file=sys.stderr)
        return 1
    span = time.monotonic() - started
    if not reported:
        print("base_ns=unknown (no udta in the init segment)")
    print("%s: %d bytes in %.1fs (%.0f KiB/s)" % (args.out, total, span, total / 1024 / span))
    return 0 if total else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
