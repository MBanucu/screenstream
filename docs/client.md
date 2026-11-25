# The Python client

`screenstream-client` is the reference consumer of the wire format: it connects
to the raw port, reads frames, and can save them as PNG or a fragmented MP4. This
page is for someone driving a capture from Python, on a build machine or on the
phone itself.

The [front page](../README.md) has the one-line install and the project's
properties; [the app](app.md) is what produces the stream, and
[the library](library.md) is the Android side.

## The wire format

Every frame is a 40-byte big-endian header, then its pixels:

```
magic "RAW2"   4 bytes
major          u16   protocol major version, 2 today
minor          u16   protocol minor version
width          u32
height         u32
row stride     u32   bytes per row in the payload, padding included
format         u32   1 = RGBA_8888, 4 bytes per pixel
frame number   u64   changed frames since the capture began
timestamp      u64   nanoseconds, the device's elapsed clock
```

The **major version is the compatibility rule**: a 2.x stream keeps this
layout, and a client refuses another major with a sentence that names both
versions, before it reads fields that may have moved. Protocol 1 was the
36-byte `RAW1` header an app built before the version served; the Python client
still accepts it, and new streams are always `RAW2`. The state file carries the
same version as `protocol=2.0`.

Three properties are deliberate, and they are what make this usable as a test
source rather than a screencast:

- **Raw.** The display's pixels at the display's own stride. No codec to drift, no
  colour conversion to disagree with `screencap`.
- **VFR.** Every composited frame is compared with the one before it on a sample
  grid; only the ones that differ are numbered and sent. A still screen costs
  nothing, a blinking caret costs two frames, and a **gap between two frame numbers
  is idle time, not a lost frame** — the same rule `screenrecord` applies to its
  video.
- **Timed on the device.** `Image.getTimestamp()` is the presentation time on the
  elapsed clock, which is also the clock `screenrecord` writes into its per-frame
  metadata. A frame number here and a frame in a recording are comparable numbers.

A slow consumer loses its own frames (newest wins) and is disconnected if it
stalls for two seconds; it never slows the screen down.

## The Python client

`client/` is the installable `screenstream-client` distribution. It has no
runtime dependencies beyond the standard library; ffmpeg is used only when a
frame is written as a PNG.

```bash
pip install screenstream-client
# or, from a checkout, without installing:
python3 client/stream.py --port 7200 --seconds 5 --png out/stream

screenstream-stream --port 7200 --seconds 5 --frames-dir out/stream
screenstream-fmp4 --seconds 10 --out out/live.mp4
```

The commands keep the flags of the scripts they replace: `screenstream-stream`
takes `--host`, `--port`, `--frames`, `--seconds`, `--frames-dir`/`--png` and
`--quiet`; `screenstream-fmp4` takes `--host`, `--port`, `--seconds` and
`--out`. `python3 client/stream.py` and `python3 client/fmp4.py` remain thin
wrappers over the same code.

As a library:

```python
from screenstream_client import connect, read_fmp4, save_png

with connect("127.0.0.1", 7100) as link:
    frame = link.wait_for_change(5.0)        # None when a still screen sends nothing
    print(frame.number, frame.timestamp_ns, frame.size)
    save_png(frame, "out/frame.png")

    for number, timestamp_ns, size, pixels in link.frames():
        ...
```

`connect(host, port)` retries while the capture is starting and returns a
connection; `frames()` yields `(number, timestamp_ns, size, pixels)` until the
source closes; `wait_for_change(timeout, since=frame_number)` returns the next
frame or `None`; `read_fmp4(source)` yields the complete boxes of an init
segment or a saved file. An unknown protocol major raises a `ProtocolError`
whose message is a sentence.

## Publishing: PyPI, for the client

The client and the AAR are one release with one version. `client/pyproject.toml`
carries the same number as `build.gradle.kts` and `build.sh`;
`test/test_client.py` fails if they disagree, and the release workflow refuses a
tag whose version is not the one in `pyproject.toml`. The three files and the tag
move together, so a release has one number for the AAR, the APK and
`screenstream-client`, not three independent versions.

```bash
pip install -e client              # from a checkout, for development
python3 -m build client --outdir dist
```

The `pypi` job in `.github/workflows/release.yml` uses PyPI **trusted
publishing**, so there is no token secret: it builds `client/` and uploads the
wheel and sdist with `pypa/gh-action-pypi-publish`. Two pieces of setup live
outside this repository and cannot be verified from a checkout:

- A trusted publisher on PyPI for the project `screenstream-client`: owner
  `MBanucu`, repository `screenstream`, workflow `release.yml`, environment
  `pypi`.
- An environment named `pypi` in the repository's settings; the job names it so
  a release can require approval before it uploads.

Until those exist the `pypi` job fails at the upload, after the Maven job has
done its own work (the APK is a separate workflow and still runs alongside). That
is deliberate: it is better than storing a PyPI token as a secret or uploading
from an unverified identity, and the version check happens before anything is
built. The client waits for Maven because a PyPI filename can never be replaced:
a failed AAR release must not burn a client version that has nothing to install
against. The Maven side is in [library.md](library.md), and the order of a whole
release is in [release.md](release.md).

## See also

[README](../README.md) · [client](client.md) · [app](app.md) · [library](library.md) · [control surface](control-surface.md) · [release](release.md) · [Termux build](termux-build.md) · [limits](limits.md)
