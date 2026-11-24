# screenstream-client

The Python client for [screenstream](https://github.com/MBanucu/screenstream):
the phone's screen as raw RGBA frames on a local port, and the same capture as
H.264 in a fragmented MP4 on a second one. No Android, no device, no runtime
dependency beyond the standard library (ffmpeg is used only when a PNG is
wanted).

```bash
pip install screenstream-client
# or install it in an isolated environment:
pipx install screenstream-client
```

The commands keep the flags of the scripts they replace, so an existing
`python3 client/stream.py --port 7200 --seconds 5 --png out/stream` becomes:

```bash
screenstream-stream --port 7200 --seconds 5 --frames-dir out/stream
screenstream-fmp4 --seconds 10 --out out/live.mp4
```

As a library:

```python
from screenstream_client import connect, read_fmp4, save_png

with connect("127.0.0.1", 7100) as link:
    frame = link.wait_for_change(timeout=5)          # None on a still screen
    print(frame.number, frame.timestamp_ns, frame.size)
    save_png(frame, "out/frame.png")

    for number, timestamp_ns, size, pixels in link.frames():
        ...
```

`read_fmp4` parses an init segment, a saved file, or bytes. `base_ns` gives the
device clock's reading for the track's zero from the `udta`/`uuid` box, so a
frame's device time is `base_ns + pts * 1000`.

## Protocol version

This client speaks screenstream protocol **2.x**. A stream whose major version
is different is refused with a sentence that names both versions, not read as
if the fields still meant the same thing. Protocol 1 (magic `RAW1`, the
unversioned 36-byte header) is still accepted for an app installed before the
version existed.

The distribution and the AAR share the version in `pyproject.toml`; it must
equal `build.gradle.kts` and `build.sh`, and the test suite checks that.

## The control surface

`screenstream_client.control` is the shared parser for the `content call` wire
format: `parse_call` for a command, `parse_result` for the
`Result: Bundle[{code=..., data=...}]` reply, `parse_fields` for the
`key=value` body and `parse_extra` for `name:type:value`. Its
`SCHEMA_VERSION` must equal the Java library's
`ControlContract.SCHEMA_VERSION`; `test/test_control.py` reads both and the
shared fixture, so the Java and Python grammars cannot drift silently.
`client/control.py` is the adb command-line wrapper over it.

The contract is `docs/control-surface.md` in the repository.

## License

GPL-3.0-or-later, as the rest of screenstream.
