#!/usr/bin/env python3
"""Attach to the phone's raw screen stream and read frames out of it.

The screenstream app serves the display as a socket: raw RGBA, changed frames only
(VFR), each with the device's presentation time. An app on the phone connects to
127.0.0.1:7100; adb reaches the same port through a forward, which is how a
machine on the other end of the cable sees the same stream:

    adb shell am start -n io.github.mbanucu.screenstream.app/io.github.mbanucu.screenstream.ScreenStreamActivity
    adb forward tcp:7100 tcp:7100
    python3 client/stream.py --seconds 5 --png out/stream

Every frame is a 36-byte big-endian header and then its pixels:

    magic "RAW1"   4 bytes
    width          u32
    height         u32
    row stride     u32   bytes per row in the payload, padding included
    format         u32   1 = RGBA_8888, 4 bytes per pixel
    frame number   u64   changed frames since the capture began
    timestamp      u64   nanoseconds, the device's elapsed clock

The frame number and the timestamp are the ones to use for anything that has to
line up later: the number says how many changes came before this frame, and the
timestamp is on the same clock `screenrecord` writes into its metadata track, so
a frame here and a frame in the recording are comparable without an offset.

What this prints is the shape of the stream, not its contents: numbers, sizes and
the gaps between frames. `--png` writes frame files through ffmpeg when a picture
is wanted, and only then.
"""

import argparse
import os
import socket
import struct
import subprocess
import sys
import time

HEADER = struct.Struct(">4sIIIIQQ")  # magic, w, h, stride, format, frame, ts_ns
MAX_FRAME_BYTES = 64 * 1024 * 1024


def connect(host, port, seconds):
    """A connection to the source, retried while it is still starting."""
    deadline = time.monotonic() + (10 if seconds is None else seconds)
    while True:
        try:
            sock = socket.create_connection((host, port), timeout=5)
            sock.settimeout(None)
            return sock
        except OSError as error:
            if time.monotonic() > deadline:
                sys.exit("stream: cannot reach %s:%d (%s)" % (host, port, error))
            time.sleep(0.2)


def read_exactly(sock, size):
    data = bytearray(size)
    view = memoryview(data)
    got = 0
    while got < size:
        chunk = sock.recv_into(view[got:], size - got)
        if chunk <= 0:
            raise EOFError("the source closed the connection")
        got += chunk
    return data


def save(frame, number, directory, prefix):
    """Writes one frame: pixels with the row padding removed, and a PNG if asked."""
    os.makedirs(directory, exist_ok=True)
    width, height, stride = frame["width"], frame["height"], frame["stride"]
    row = width * 4
    pixels = frame["pixels"]
    if stride == row:
        body = pixels
    else:
        # Row padding is part of the raw frame; a picture does not want it.
        body = bytearray(width * height * 4)
        for y in range(height):
            start = y * stride
            body[y * row:(y + 1) * row] = pixels[start:start + row]
    raw = os.path.join(directory, "%s%05d-%d.rgba" % (prefix, number, frame["ts_ns"]))
    with open(raw, "wb") as handle:
        handle.write(body)
    return raw


def to_png(raw, frame, directory):
    width, height = frame["width"], frame["height"]
    png = raw[:-5] + ".png"
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "%dx%d" % (width, height),
         "-i", raw, "-frames:v", "1", png], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("stream: ffmpeg could not write %s: %s" % (png, result.stderr.strip()))
    return png


def main(argv):
    parser = argparse.ArgumentParser(
        description="Read frames from the phone's raw screen stream.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7100)
    parser.add_argument("--frames", type=int, default=0, help="stop after this many frames")
    parser.add_argument("--seconds", type=float, default=5.0,
                        help="stop after this long (0 waits for --frames)")
    parser.add_argument("--frames-dir", "--png", dest="png", default="",
                        help="write frames here, as .rgba and .png")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    started = time.monotonic()
    limit = None if args.seconds <= 0 else started + args.seconds
    sock = connect(args.host, args.port, 10 if limit is None else args.seconds)
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
            sock.settimeout(0.5 if limit is None else max(0.5, limit - time.monotonic()))
            try:
                header = read_exactly(sock, HEADER.size)
            except socket.timeout:
                continue
            except EOFError:
                break
            magic, width, height, stride, fmt, number, ts_ns = HEADER.unpack(header)
            if magic != b"RAW1":
                sys.exit("stream: not a frame header (got %r)" % magic)
            size = stride * height
            if size <= 0 or size > MAX_FRAME_BYTES:
                sys.exit("stream: refusing a %d-byte frame (%dx%d, stride %d)"
                         % (size, width, height, stride))
            pixels = read_exactly(sock, size)
            frame = {"width": width, "height": height, "stride": stride,
                     "format": fmt, "pixels": pixels, "number": number, "ts_ns": ts_ns}
            gap = (ts_ns - stamps[-1]) / 1e6 if stamps else 0.0
            if not args.quiet:
                print("frame %6d  %dx%d  stride %d  %6.1f MB  ts %d ns  (+%.1f ms)"
                      % (number, width, height, stride, size / 1e6, ts_ns, gap))
            numbers.append(number)
            stamps.append(ts_ns)
            count += 1
            if args.png:
                raw = save(frame, number, args.png, "frame-")
                written.append(to_png(raw, frame, args.png))
    finally:
        sock.close()

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


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
