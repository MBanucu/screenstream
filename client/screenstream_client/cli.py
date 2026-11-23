"""The two command-line clients: raw frames, and the encoded stream to a file."""

import argparse
import socket
import sys
import time

from .fmp4 import base_ns
from .wire import (
    ConnectionClosed,
    ProtocolError,
    SaveError,
    ScreenstreamError,
    connect,
    save_png,
)


def stream_main(argv=None) -> int:
    """Reads the phone's raw screen stream and prints its shape."""
    parser = argparse.ArgumentParser(
        description="Read frames from the phone's raw screen stream.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7100)
    parser.add_argument("--frames", type=int, default=0, help="stop after this many frames")
    parser.add_argument("--seconds", type=float, default=5.0,
                        help="stop after this long (0 waits for --frames)")
    parser.add_argument("--frames-dir", "--png", dest="png", default="",
                        help="write frames here, as .png")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    started = time.monotonic()
    limit = None if args.seconds <= 0 else started + args.seconds
    try:
        link = connect(args.host, args.port, 10 if limit is None else args.seconds)
    except (ScreenstreamError, OSError) as failed:
        print("stream: %s" % failed, file=sys.stderr)
        return 1
    if not args.quiet:
        print("stream: attached to %s:%d" % (args.host, args.port))

    count = 0
    numbers = []
    stamps = []
    written = []
    try:
        while True:
            if limit is not None and time.monotonic() > limit:
                break
            if args.frames and count >= args.frames:
                break
            timeout = 0.5 if limit is None else max(0.05, limit - time.monotonic())
            frame = link.read_frame(timeout)
            if frame is None:
                continue
            gap = (frame.timestamp_ns - stamps[-1]) / 1e6 if stamps else 0.0
            if not args.quiet:
                print("frame %6d  %dx%d  stride %d  %6.1f MB  ts %d ns  (+%.1f ms)"
                      % (frame.number, frame.width, frame.height, frame.stride,
                         frame.size / 1e6, frame.timestamp_ns, gap))
            numbers.append(frame.number)
            stamps.append(frame.timestamp_ns)
            count += 1
            if args.png:
                path = "%s/frame-%05d-%d.png" % (args.png, frame.number,
                                                  frame.timestamp_ns)
                written.append(save_png(frame, path))
    except ConnectionClosed:
        pass
    except (ProtocolError, SaveError) as failed:
        print("stream: %s" % failed, file=sys.stderr)
        return 1
    finally:
        link.close()

    if not count:
        print("stream: no frames in that window (a still screen sends none)")
        return 0
    spent = (stamps[-1] - stamps[0]) / 1e9 if count > 1 else 0.0
    gaps = [(b - a) / 1e6 for a, b in zip(stamps, stamps[1:])]
    print("stream: %d frame(s) over %.1f s of screen time, numbers %d..%d, "
          "gaps %.1f..%.1f ms" % (count, spent, numbers[0], numbers[-1],
                                  min(gaps) if gaps else 0.0, max(gaps) if gaps else 0.0))
    for png in written:
        print("  %s" % png)
    return 0


def fmp4_main(argv=None) -> int:
    """Attaches to the encoded stream and saves what it sends."""
    parser = argparse.ArgumentParser(
        description="Attach to the encoded stream and save what it sends.")
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
