# screenstream

The phone's screen as **raw frames on a local port** — shipped two ways from the
same sources: an app you install and drive over adb, and a library an app or a
test framework embeds.

Not a recording: images come from the compositor as RGBA_8888, straight out of an
`ImageReader`, and go onto the wire without an encoder or a container. Nothing is
remuxed, nothing is keyframed, so a consumer gets the frame the screen showed, not
a picture of it.

| | |
|---|---|
| Transport | TCP `127.0.0.1:7100` (default; a start may choose or ask for an ephemeral port), any number of consumers |
| Payload | RGBA_8888, `stride * height` bytes per frame |
| Rate | only when the screen changes (VFR) |
| Timing | device presentation time, nanoseconds, elapsed clock |
| Min SDK | 24 |
| Cost | about 18 MB per frame at 1440x3200 — `scale=2` quarters that |

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

## As an app, for adb access

```bash
bash build.sh apk                      # out/screenstream-app.apk
adb install -r out/screenstream-app.apk

# With a screen: tap the icon, or start the UI.
adb shell am start -n io.github.mbanucu.screenstream.app/.StreamUiActivity

# Without one: the library's activity, which asks, starts, and records.
adb shell am start -n io.github.mbanucu.screenstream.app/io.github.mbanucu.screenstream.ScreenStreamActivity \
    --ez record true
adb forward tcp:7200 tcp:7100          # 7200, not 7100: see the note below
python3 client/stream.py --port 7200 --seconds 5 --png out/stream
adb shell am start -n io.github.mbanucu.screenstream.app/io.github.mbanucu.screenstream.ScreenStreamActivity \
    --ez stop true
```

The UI has the two halves: **Start**/**Stop** for the capture, **Record**/**Stop
recording** for the MP4, a half-size switch, and the state the service reports —
frame size, changed frames, port, and how much has been written. Tapping the icon
and starting it from adb are the same thing: the system asks for the display, and
once it is granted the app serves the screen and records it until the capture is
stopped, from the notification, from the UI, with `--ez stop true`, or by
force-stopping the app.

The adb commands go to `ScreenStreamActivity`, not to the UI activity, because
each `am start` makes a fresh instance of it: starting a *running* activity again
is delivered as `onNewIntent`, which is not a path a script can rely on. Adb's
`--ez record true` records into the app's `recordings/` directory; the path is in
the state file. For an unattended run with no tap, and an answer that is only
`streaming` after the first frame, use **Recording headlessly on a fresh device**
below.

**A note on the port.** `adb forward tcp:7100 tcp:7100` collides when the machine
holding adb is the phone itself (Termux, say): the forward's listener and the
stream's listener are the same loopback port, and the capture fails to bind. Use
`7200` on the host side, as above, and `--port 7200` for the client. The control
surface below can instead ask the stream for an ephemeral port with `--extra
port:i:0` and report the one it got.

### Driving it from a script

`am start` is one-shot: it returns before the capture is up, and the port used to
be a constant. The app also exposes a `ContentProvider`, which a shell can call
synchronously and which starts the app on demand:

```bash
AUTH=content://io.github.mbanucu.screenstream.control

adb shell content call --uri $AUTH --method status
adb shell content call --uri $AUTH --method grant
adb shell content call --uri $AUTH --method start \
    --extra port:i:0 --extra encoded_port:i:0
adb shell content call --uri $AUTH --method record
adb shell content call --uri $AUTH --method recording
adb shell content call --uri $AUTH --method stop
```

`status` works from a fresh process, including after a reboot, and says
`state=stopped` without the app having been launched first. `start` takes these
extras:

| extra | default | meaning |
|---|---|---|
| `port:i:N` | `7100` | raw port; `0` asks the kernel for a free one |
| `encoded_port:i:N` | `7101` | H.264 port; `0` asks the kernel for a free one |
| `scale:i:N` | `1` | `2` captures at half size |
| `timeout_ms:i:N` | `20000` | how long to wait for the first frame |

`start` replies only when the first composited frame has arrived (a still screen
still produces those), with `state=streaming` and the port it actually bound. It
answers `state=failed`, a `why` line and `code=400` when the capture or its port
did not come up; a capture that is live but has produced no frame is failed too,
never called streaming. When the missing piece is the grant, the answer is
`state=waiting_for_grant`, `reason=dialog`, and the exact `command=` that would
grant it. Asking for a start while something is already streaming does not make a
second listener: the reply is the running port, once that capture's first frame
has arrived. A bind error names the port that was in the way, so two runs can
follow one another on different ports without either silently reusing 7100.

Every `status` and `start` reply also carries `grant=yes|no|unknown` and
`grant_route=appop|dialog|none|unknown`. `grant=yes` means the projection can be
issued without a tap: a standing app-op or a capture that is already running.
`grant=no` means the system would ask, and `unknown` means this build could not be
asked. A `start` can still answer `state=waiting_for_grant` after `grant=yes`:
some builds allow the app-op but show the dialog anyway. That reply reports the
observed outcome as `grant=no`, `grant_route=dialog`, with `appop=yes` and a
`detail=` line recording that the platform ignored the op. Treat a waiting start
as no usable grant even if `grant` said yes before it. `grant` reports the same
without starting anything, and adds the `command=` that sets the app-op and a
`revoke_command=` for after the run:

```bash
python3 client/control.py grant
```

The reply is a Bundle with `code` (`0` for a successful call, `400` for one that
could not do what was asked) and `data`, one `key=value` line per field in the
state file's vocabulary:

```
Result: Bundle[{code=0, data=state=streaming
port=43127
protocol=2.0
encoded_port=43128
frames_looked=8
frames_changed=3
recording=-
recorded_frames=0
recording_from_ns=0
grant=yes
grant_route=appop
}]
```

`status` includes at least `state`, `port`, `protocol`, `encoded_port`, `frames_looked`,
`frames_changed`, `frames_sent`, `frames_skipped`, `recording`,
`recorded_frames`, `recording_from_ns`, `grant` and `grant_route`. `record`
starts an MP4 in the app's `recordings/` directory and replies with its path in
`recording`; `recording` ends that file, waits for its index and adds
`recording_file=` with the path it wrote. `stop` ends the capture, finalising a
recording first, waits until the reply says `state=stopped`, and adds
`recording_file=` if a recording was being written.

`client/control.py` wraps the call and parses the Bundle:

```bash
python3 client/control.py start --port 0 --encoded-port 0
python3 client/control.py status
```

Only adb shell, root, system and the app itself may call the provider; any other
caller gets `code=403` and an `error=` line. The projection dialog is still the
system's, but there is a headless path: `adb shell appops set --uid <package>
PROJECT_MEDIA allow` before a run, where the platform honours it. The block below
does that, verifies it with `grant=yes`, and revokes it afterwards; a dialog
clicker from an accessibility service remains a fallback, not a requirement. The
intents and the state file are unchanged and remain the debug record; the provider
is the documented path for automation.

The provider's gate, reply format, escaping and `content call` parser are not
screenstream's own any more: they live in the `android-control-surface` library
described below, and `docs/control-surface.md` is the frozen contract (schema 1).
Existing `content call --method ...` output is unchanged, including the keys,
the `code` values and the one-line escaping.

### Recording headlessly on a fresh device

One copy-pasteable run: install, opt into the app-op for this run, start only
after the first frame, record, pull the file, and put the grant back. The
`client/control.py` calls exit non-zero when the provider's `code` is 400, so the
block stops instead of recording a black video.

```bash
set -euo pipefail
PKG=io.github.mbanucu.screenstream.app

bash build.sh apk
adb install -r out/screenstream-app.apk

# Opt-in, per run: let the platform skip the projection dialog. The app does not
# ask for this on install and this block revokes it at the end.
adb shell appops set --uid "$PKG" PROJECT_MEDIA allow
grant=$(python3 client/control.py grant)
printf '%s\n' "$grant"
printf '%s\n' "$grant" | grep -qx 'grant=yes'          # verification

# Start returns only after the first composited frame; port 0 asks for a free one.
python3 client/control.py start --port 0 --encoded-port 0
python3 client/control.py record

sleep 10                                 # the run to record

python3 client/control.py recording      # waits for the MP4 index, prints recording_file=
python3 client/control.py stop
mkdir -p out/recordings
adb pull "/sdcard/Android/data/$PKG/files/recordings/." out/recordings/

# Leave the device asking through the dialog again.
adb shell appops set --uid "$PKG" PROJECT_MEDIA default
```

`grant=yes` is the assertion to make before recording; `grant=no` or
`grant=unknown` is a reason to fail the run rather than start it. If the app-op is
not honoured on a device, `start` returns `state=waiting_for_grant` with
`reason=dialog` and the command that would grant it, and no stream is claimed.

**The fallback is a dialog, not a second APK.** When the app-op route is refused,
a person can tap **Start now**; an unattended run can accept the same dialog with
`adb shell input tap X Y` after finding the button's bounds with `adb shell
uiautomator dump /sdcard/window.xml`, or with an accessibility service wired to
`ScreenStream.setDialogClicker` (the probe's `acceptScreenCaptureDialog`). The
result is `grant=yes` with `grant_route=dialog`. None of that is required when
`grant=yes grant_route=appop` is already the answer.

What it is doing is in `Android/data/io.github.mbanucu.screenstream.app/files/screenstream.txt`:

```
state=streaming          stopped, streaming, failed; waiting_for_grant is a
                         control reply, not a state written here
port=7100                the raw port bound; 7100 unless a start asked for another
size=1440x3200           the frame size, after scale
scale=1
format=rgba8888
frames_looked=            composited frames seen
frames_changed=           frames that differed from the one before
frames_sent=              frames handed to consumers
frames_skipped=           frames dropped because every buffer was in use
clients=                 socket consumers attached
taps=                    in-process consumers attached
recording=               the MP4 being written, or - when there is none
recorded_frames=         frames written to it (or to the last one)
recorded_ms=             its length so far, from the last frame written
recording_from_ns=       the file's zero on the stream's clock: a frame's
                         timestamp minus this is where its picture is in it
encoded_port=            the encoded stream's bound port, or - when it is not serving
encoded_clients=         viewers attached to it
encoded_frames=          samples the encoder produced for them
encoded_bytes=           bytes written to all of them together
encoder=                 the encoder component in use, e.g. c2.mtk.avc.encoder
                         for the phone's hardware one, or - when none is running
```

### The emulator capture gate in CI

`.github/workflows/emulator-e2e.yml` runs `ci/emulator-e2e.sh` on every push to
`main` and on `workflow_dispatch`. It is a post-merge check that a merged change
still brings up a capture on a real Android runtime:

- A fresh API 35 `google_apis` `x86_64` AVD on `ubuntu-24.04`, with the KVM udev
  rule, and a **20-minute** job cap. The screen is set to **1440x3200 at 560
  dpi** (411x914 dp) by `ci/emulator-display-profile.sh` as the action's
  `pre-emulator-launch-script`, so the display exists before boot; the test never
  calls `wm size` under a live capture.
- The script refuses to pass without an `emulator-*` serial in the `device`
  state, builds the APK with `build.sh apk`, and installs it. A missing emulator
  or a physical phone is a failed run, not a skipped one.
- It first takes the documented headless consent route: `appops set --uid
  <package> PROJECT_MEDIA allow`, verifies the UID mode with `appops get`, then
  verifies `grant=yes` through the provider, and revokes it in an `EXIT` trap.
- API 35 still shows the projection dialog after that UID grant. The gate starts
  the capture in the background, finds the dialog's positive button in a
  `uiautomator dump`, and taps it with `adb shell input tap`; it does not use
  fixed coordinates and does not wait for a person. `start` must then prove
  `state=streaming` with a bound port, the expected frame size and at least one
  composited frame. A dump without the button, a dismissed dialog or a
  `waiting_for_grant` reply fails the run.
- `start --port 0 --scale 2` is the **synchronous control reply** from #1: it
  returns only after the first composited frame, with `state=streaming` and the
  port it bound. The script reads that port; it never polls the state file or
  guesses a port.
- The installed Python client connects through `adb forward`, the screen is
  changed (`am start` Settings after the client attached), and the run asserts at
  least one frame, a protocol-2 header whose `stride * height` matches the bytes
  delivered, the expected 720x1600 scaled frame size, strictly increasing frame
  numbers and non-decreasing `timestampNs`.
- `recording` finalises the MP4, `stop` ends the capture, the file is pulled, and
  its non-zero size is checked. The job installs `ffprobe`, which must read a
  video track with at least one frame at the capture size; a local run without
  ffprobe only checks the size. The assertion is the container and the frame,
  not a byte-for-byte H.264 stream.
- Control replies, the frame log, `adb devices` and the MP4 are written under
  `out/emulator/` and uploaded when the job fails.

This is a **protocol and lifecycle gate**, not a MediaTek encoder or panel test.
The AVD uses a software AVC encoder and SwiftShader; it proves the provider
reply, the port announcement, the first frame after a change, the device
timestamps and the MP4 finalisation. It does **not** prove
`c2.mtk.avc.encoder`, the phone's 1440x3200 120 Hz panel, OEM power behaviour, or
output quality; the self-hosted phone remains the check for those. The same
boundary is pinned without a device by `test/test_emulator_e2e.py`: the
fail-closed preflight, the control-reply fixtures and the frame/timestamp
assertions.

## Recording to a file

**Record** (or `--ez record true`) writes an H.264 MP4 next to the stream. It is
the same capture, because it has to be: a projection allows one virtual display,
and a second `createVirtualDisplay` on the same token is refused — it also
invalidates the capture, which is worth knowing before trying it. So the recorder
is fed the frames the stream has already copied and encodes them itself: the video
and the socket carry the same capture, and a picture in the file is a frame that
was also on the stream.

What that costs is the colour conversion the compositor would otherwise do, and it
is visible in the files: the encoder wants YUV, the stream is RGBA, so the
conversion runs on its own thread and drops frames the encoder cannot keep up
with. A still screen records as a still video, and a busy one keeps only the frames
that thread got through.

That is why the file's frame *numbers* are not the stream's. It starts wherever
the capture was when recording began, so its first frame is stream frame n rather
than 0, and the dropped frames mean it holds a subset. What is exact is the time:
each frame carries the stream's own timestamp, shifted so the file starts at zero,
and `recording_from_ns` is that shift. A test that passed on a frame at `ts_ns`
looks for it at `ts_ns - recording_from_ns`.

Measured on this phone, 1440x3200: a 9 s capture wrote 66 frames to a 2.9 MB file,
`ffprobe` reads it as h264 1440x3200, 9.01 s, 66 frames, and a frame from the
middle is not black. The bitrate is ~14 Mbps at that size (about 0.12 bits per
pixel per frame at 30 fps), a keyframe every two seconds. Over a busy two-minute
`STREAM=1 VIDEO=1 samples/e2e.sh` run the compositor offered 1248 changed frames
and the file kept 390 of them (137 s, 2.8 frames a second): readable, and choppy
the way a busy screen at native size is. The conversion is where the time goes,
so a half-size recording is the obvious lever - a quarter of the pixels - though
it is not measured yet.

## Watching it live: the encoded stream

The raw socket is the display itself: at 1440x3200 a changed frame is 18 MB, which
is right for a machine on the far end of a cable and useless for anything else. So
there is a second port, **7101**, carrying the same frames as **H.264 in a
fragmented MP4** — a hundredth of a percent of the bytes, with the screen's own
variable frame rate kept because the encoder is fed the frames' timestamps.

```bash
adb shell am start -n io.github.mbanucu.screenstream.app/io.github.mbanucu.screenstream.ScreenStreamActivity
python3 client/fmp4.py --seconds 10 --out out/live.mp4     # 127.0.0.1:7101
# or, once installed: screenstream-fmp4 --seconds 10 --out out/live.mp4
ffplay out/live.mp4
```

From a machine other than the phone, forward the port to a free local one, for the
same reason as above: `adb forward tcp:7201 tcp:7101`.

- **One encoder, two consumers.** A viewer and the recording are fed by the same
  `MediaCodec` instance, so they cannot show different pictures — the file and the
  stream are the same capture. With no recording running, an encoder is started for
  the viewers, and it stops when the last one leaves: `encoder=-` in the status file
  is what nobody watching looks like.
- **A fragment is playable as it arrives.** A plain MP4 only becomes readable when
  its index is written at the end, which is why the recording is an archive and not
  a live view. Here the index (`moov` + `mvex`) is in the init segment and every
  `moof` carries its own; `ffprobe`, VLC and `ffplay` read the file as it grows.
- **A viewer gets a keyframe.** Attaching requests a sync frame from the encoder and
  the viewer's fragments start there, instead of waiting up to the two-second
  interval. Fragments also close after a second, so a quiet screen does not hold
  the last pictures back.
- **The init segment says when the track's zero was.** A track's times count from its
  first frame, which leaves a captured stream unable to say when that was. The init
  segment therefore carries the device clock's reading for it in a private
  `udta`/`uuid` box, and `client/fmp4.py` prints it as `base_ns`: a frame's device
  time is then `base_ns + pts * 1000`, the same clock the raw stream's frame headers
  use. Readers that do not know the UUID step over the box by size, so `ffprobe`,
  VLC and `ffplay` are unaffected.
- **The encoder is the phone's hardware one** where there is one:
  `c2.mtk.avc.encoder` on this device (H.264, up to 3840x2176). There is no hardware
  VP8 or VP9 here — `c2.android.vp8.encoder` is a software component capped at
  2048x2048 — which is why the encoded side is H.264 and not WebM.

Measured on this phone, 1440x3200: a 10 s capture of scrolling came to 1.0 MB in 36
frames (about 0.8 Mbps), a 12 s capture of the notification shade opening and
closing came to 9.2 MB (about 6 Mbps) because large areas repainted every frame,
and both decode to real screenshots. That is the range to plan for: tens of
kilobytes a second on a quiet screen, a few megabits on a busy one, against 155 MB/s
for the raw socket.

## Twitch, and what streaming still needs

Recording is the video half of an RTMP publish, and nothing more than that: there
is no RTMP client here. Streaming to Twitch means:

1. **FLV muxing of the tags this encoder already produces.** `MediaMuxer` cannot
   write FLV, so the output would go to a small tag writer instead: an FLV header,
   then one video tag per access unit with its timestamp. That part is a few
   hundred lines and testable offline by writing a file and playing it.
2. **An RTMP client.** Handshake (C0/C1/C2), `connect`/`createStream`/`publish`
   over AMF0, then chunked audio and video messages on a socket. That is the part
   that is genuinely new, and the part that needs a real stream key to verify
   against Twitch's ingest — not something to ship untested.
3. **An audio track.** Twitch's ingest expects AAC audio alongside H.264 video;
   video-only streams are rejected. On Android that means `AudioPlaybackCapture`
   (API 29+) for the device's sound or the microphone, encoded with `MediaCodec`
   AAC and interleaved with the video tags.

The design already fits: the recorder's drain loop is where the encoded access
units appear, so a second sink (an RTMP writer instead of `MediaMuxer`) can be fed
from the same place without touching the capture.

## As a library, for apps and test frameworks

The artifact is a normal Android AAR: `classes.jar`, the manifest that declares
the service and the activity, no resources. Gradle merges the manifest, so a
consumer adds **no declarations of its own** — the permissions, the service and
the activity all come with the dependency.

```bash
bash build.sh    # out/screenstream-0.2.0.aar (+ .jar, sources, javadoc, .pom)
                 # and the dependency, out/android-control-surface-0.2.0.*
```

```kotlin
// build.gradle.kts of the consuming app
android { ... }
dependencies {
    implementation(files("libs/screenstream-0.2.0.aar"))
    // or from Maven Central, which resolves the AAR on its own since 0.2.0:
    //   implementation("io.github.mbanucu:screenstream:0.2.0")
}
```

The API is small, and the process boundary is the point — a consumer either sits
in the same process as the capture or talks to the port:

```java
// Granting, if the app wants to ask itself instead of using ScreenStreamActivity:
MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
startActivityForResult(manager.createScreenCaptureIntent(), REQUEST);
// ... in onActivityResult:
ScreenStream.start(this, data, /* scale */ 1);

// Attaching without a socket: every changed frame, straight from the compositor,
// on the capture thread. The frame must be released, and its buffer is shared with
// the socket consumers rather than copied for each of them.
ScreenStream.subscribe(frame -> {
    try {
        // frame.pixels, frame.width, frame.height, frame.stride,
        // frame.number, frame.timestampNs
    } finally {
        frame.release();
    }
});

ScreenStream.live();          // is a capture running in this process?
ScreenStream.frameNumber();   // changes since the capture began
ScreenStream.stop(this);

// The same capture, as a file: H.264/MP4, real timestamps, VFR.
ScreenStream.record(new File(dir, "run.mp4"));
ScreenStream.recording();          // is a file being written?
ScreenStream.recordedFrames();
ScreenStream.recordedMilliseconds();
ScreenStream.stopRecording();      // finalises the file; the index is written here
```

A screen that is *not changing* is the last frame the source sent, still on the
display — a VFR stream cannot say that again, so a consumer that starts on a still
screen should sample the screen itself once and label what it saw with
`frameNumber()`.

## The control surface, as a library

The provider base and its ordered-broadcast twin are a separate AAR, so another
app gets the same gate and reply format without copying screenstream's provider:

```kotlin
dependencies {
    implementation("io.github.mbanucu:android-control-surface:0.2.0")
    // or, from a checkout, build it with: bash build.sh control-surface
}
```

```java
import io.github.mbanucu.control.*;

public final class MyProvider extends io.github.mbanucu.control.ControlProvider {
    @Override protected String usage() { return "status, enable"; }

    @Override protected Reply handle(String method, Args extras) {
        if ("status".equals(method)) return Reply.keyValue(0, "state", "ready");
        if ("enable".equals(method)) return Reply.keyValue(0, "result", "on");
        return null;                 // 400 unknown method, with the usage above
    }
}

public final class MyReceiver extends io.github.mbanucu.control.ControlReceiver {
    @Override protected String action() { return "com.example.CONTROL"; }
    @Override protected Reply handle(String method, Args extras) {
        return sameDispatchAsTheProvider(method, extras);
    }
    @Override protected ControlToken token() {
        return ControlToken.atFile(new File(getFilesDir(), "control.token"));
    }
}
```

The base supplies `code`/`data`, `Reply.keyValue` and `Reply.oneLine`, the
root/system/shell/self allowlist, the optional token (minted only by the
shell-only `token` method, verified in constant time for an app uid) and the
`content call` extras parser. `Args.intValue`/`booleanValue` accept the typed
extra the platform sends and a string a script wrote by hand.

`ControlToken`, `UidGate`, `Reply` and `ContentCall` are plain `java.*`, so the
device-free tests for them run on a build machine:

```bash
gradle --offline :android-control-surface:test
```

`docs/control-surface.md` is the contract: the codes, the `key=value` grammar,
the escaping, the extras letters and the schema version. The Python client's
`screenstream_client.control` implements the same grammar and is tested against
the same fixture file. Nothing from this module is on Maven Central yet; the
first publication is a follow-up, and the coordinate above is the intended one.

## From a test framework

The socket is the language-agnostic edge, and for most frameworks it is all they
need: connect, read a header, read `stride * height` bytes, decide, repeat.
`client/screenstream_client` is the reference consumer, packaged so a harness
can import it; the protocol stays deliberately small enough to re-implement in
any language a test harness is written in.

- **Host-side tests** (anything driving the phone over adb): `content call` to the
  control provider for a synchronous start and the bound port, then `adb forward`
  and connect to that port; no Android dependency at all. `client/control.py`
  parses the reply, and `test/test_control.py` checks that parsing without a phone.
- **Offline wire tests**: `test/test_client.py` and `test/test_fmp4.py` build
  frames and a fragmented MP4 in memory and cover a frame split across TCP
  reads, two frames in one read, a zero-length or partial header, a truncated
  fragment, the `base_ns` box, and the protocol-version refusal. They run in CI
  with every other test: `python3 -m unittest discover -s test -v`.
- **On-device tests** (instrumentation, an Espresso-like framework): depend on the
  AAR and `subscribe` in-process, so a frame costs no socket and no copy.
- **Assertions about time**: `frame.number` and `frame.timestamp_ns` line up with
  `screenrecord`'s metadata track and with `dumpsys`, so a check that passes on
  frame *n* can name the picture of frame *n*. `test/test_device_wire.py` is an
  optional, clearly marked device check of one frame's size and timestamp; it is
  skipped unless `SCREENSTREAM_DEVICE=1` and is not run by the hosted workflow.
  The post-merge `.github/workflows/emulator-e2e.yml` gate is a different check:
  it uses a fresh API 35 AVD and asserts the control reply, the first frame, the
  timestamp order and the MP4 finalisation, not the phone's encoder.

## Two builds

`build.sh` is the build this repository was written for: it needs no network and
no Gradle, and it is what made the published 0.1.0. `build.gradle.kts` is the same
library for Gradle, and it works on this phone too — Gradle 9.9.2 with AGP 9.5.2,
which is the AGP line that runs on Gradle 9.6+ (8.x dies on a Gradle internal API
that was removed there):

```bash
gradle :assembleRelease :android-control-surface:assembleRelease
gradle :android-control-surface:test   # device-free JVM tests for the surface
gradle checkBuildEnv             # JDK, SDK, compileSdk and aapt2, in one message
tools/termux-build.sh            # the same build, with the checks and caches
```

Versions and SDK levels live in `gradle/libs.versions.toml`; `build.sh`,
`publish.sh` and the CI workflows read the same file through
`tools/versions.py`, so AGP 9.5.2, compileSdk 35 and the project version are not
copied between scripts. The control surface is `:android-control-surface`, a
Gradle project and a separate AAR; screenstream depends on it, which is why the
root POM names it.

The two produce the same libraries, checked rather than assumed: for
screenstream and for the control surface, the Gradle AAR and the `build.sh` jar
expose the same `javap` API, with the manifests' permissions, activity and
service intact. The class bytes differ because the two invoke javac differently,
and the Gradle AARs carry `aar-metadata.properties` and no embedded dependency.

`build.sh` builds the control surface first, then screenstream against it, and
writes both sets of artifacts:

```bash
bash build.sh                 # out/screenstream-* and out/android-control-surface-*
bash build.sh control-surface # only the AAR/friends, for a consumer's prototype
bash build.sh apk             # both source trees in the app's dex
```

`./gradlew` is checked in, so a machine with nothing but a JDK can build it: the
wrapper downloads Gradle 9.9.2 itself, and `distributionSha256Sum` in
`gradle-wrapper.properties` pins the archive. CI validates the wrapper JAR as
well. There is also CI — `.github/workflows/ci.yml` runs the host-side tests
(control, wire format and fMP4) and the device-free control-surface JVM tests,
installs the client and checks both console scripts, builds both ways on ubuntu
and then fails if either artifact exposes a different API between the two builds
(compared with `javap`, not by trusting that they compile). It has an opt-in
self-hosted Termux job (repository variable `TERMUX_RUNNER=true`) and
`tools/termux-build.sh` for the phone itself. `.github/workflows/release.yml`
publishes a release. Its Maven job runs `publish.sh` itself, once for
`android-control-surface` and then for `screenstream` (the dependency has to
exist first), rather than a second publishing path, given the four secrets
`SIGNING_KEY`, `SIGNING_PASSWORD`, `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD` — which do not exist in this repository yet, so the
first release run stops at the step that checks for them. Its PyPI job publishes
the client with trusted publishing, described under "Publishing: PyPI" above.

What Gradle adds is what a publication wants rather than what a build needs:
module metadata (`.module`, which is how a Gradle consumer resolves the AAR without
an explicit `@aar` — 0.1.0 has none, which is why that coordinate needs one, and
0.2.0 was published from this build to have it), `publishToMavenLocal` for testing
a consumer, and a project an IDE or JitPack can open. It costs the first run about five minutes in
downloads; `build.sh` remains the path with no downloads at all. Two things it
needs on this device are already in place: `android.aapt2FromMavenOverride` in
`~/.gradle/gradle.properties` points AGP at Termux's own aapt2, and
`local.properties` (ignored by git) names the SDK. `local.properties.example` is
the template; `docs/termux-build.md` has the full setup, the measured
configuration/build-cache times and the upstream warnings that are deliberately
not ours.

## Publishing: Maven Central, for the library

Maven is the right home for the AAR — it is what a test framework resolves a
dependency from, and Central is the one repository a consumer can use without an
token of their own (GitHub Packages needs authentication even to download a public
package). The app is not a dependency and does not go there.

Neither Gradle nor Maven is needed for this, and neither is installed here: the
Central Portal accepts a zip laid out as a Maven repository, so `publish.sh`
builds, signs and assembles it. One version is 40 files per artifact — the AAR,
POM, sources jar and javadoc jar, each with a `.asc` and four checksums — 163 KB
per version for screenstream. The control surface is published the same way, as
its own deployment, and has to go first because the screenstream POM names it.

```bash
bash publish.sh            # build, sign, checksum: out/screenstream-<version>-bundle.zip
bash publish.sh --upload   # ... and upload it, with the portal's user token
ARTIFACT=android-control-surface bash publish.sh            # the dependency, same steps
ARTIFACT=android-control-surface bash publish.sh --upload   # ... and upload it first
GRADLE_PUBLISH=1 bash publish.sh --upload   # same, with Gradle's artifacts (.module)
bash publish.sh --status <deploymentId>   # VALIDATED, PUBLISHING, PUBLISHED
bash publish.sh --publish <deploymentId>  # release a validated USER_MANAGED one
```

0.1.0 went out on 2026-11-22 that way (deployment `607463de`) from `build.sh`, and
Central had it about four minutes later. 0.2.0 is the same library published from
the Gradle layout instead, so the bundle carries the `.module` as a fifth artifact:
`GRADLE_PUBLISH=1 VERSION=0.2.0 bash publish.sh --upload`, which runs
`publishAllPublicationsToOutRepoRepository` and signs what Gradle wrote. Both
releases verify against the key below; 0.2.0 does not change the API.
`android-control-surface` has **not** been published yet: the module builds, its
tests run, `publish.sh` can assemble and sign it, and this change deliberately
stops there. `publishToMavenLocal` is enough to try a consumer against it.

The token is read from `CENTRAL_USERNAME`/`CENTRAL_PASSWORD` if they are set, and
otherwise from the `central` server in `~/.m2/settings.xml` — the same place a
`mvn deploy` on the same machine keeps it, so it is stored once and never typed
into a shell where the history would keep a copy.

Three things have to be true outside this repository, and none of them are
repeated per version:

- **The namespace.** `io.github.mbanucu` verified in the Central Portal; signing in
  with GitHub normally verifies it outright. It is verified — the sibling
  `android-accessible-terminal` 0.2.0 is published under it.
- **The signing key.** Central checks signatures against public keyservers, so the
  public half has to be there. Two keys exist for this account, and only one can
  sign on this device: `2FDFEB31D3D8DE78F3411F97ED7C9674D046992C`
  (`MBanucu screenstream`, no passphrase, on `keyserver.ubuntu.com`). The older
  `2C40010ACF08C13650B3CDD5B7B693555C9FAD64` is passphrase-protected, and that
  passphrase exists only as the `SIGNING_PASSWORD` secret in the
  `android-accessible-terminal` repository — a GitHub secret is write-only, so it
  cannot be read back, and that key can no longer sign anything locally. Pass
  `PGP_KEY=` to choose a key; `publish.sh` verifies every signature locally before
  packing, because a bundle that fails validation at the portal spends a version
  number to learn that.
- **The portal token.** Account → Generate User Token in the portal; that is the
  username and password above, in the environment or in `settings.xml`. The
  signing key has a passphrase, and a shell this is piped through cannot answer
  gpg's prompt: run `publish.sh` in a terminal (pinentry asks), or pass
  `PGP_PASSPHRASE=` to sign without asking.

A version number on Central is permanent: 0.1.0 can be followed by 0.2.0 but never
replaced. The upload validates and then waits (`USER_MANAGED`) for someone to press
Publish, so a mistake is caught before it is permanent.

Two related things this does not do. The sibling artifact carries Gradle module
metadata (`.module`); this bundle has only the POM, whose `packaging` is `aar` — so
a Gradle consumer may need the explicit `@aar` in the dependency, as shown above.
And JitPack still builds from a tag with Gradle, which this repository deliberately
does not use, so JitPack is not a route here.

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

Until those exist the `pypi` job fails at the upload, after the Maven and APK
jobs have done their own work. That is deliberate: it is better than storing a
PyPI token as a secret or uploading from an unverified identity, and the
version check happens before anything is built.

## Releasing the app: the APK on GitHub

The app is not a Maven artifact, so its download is a GitHub release asset: the
workflow `.github/workflows/apk.yml` builds the APK for the tag being released,
signs it, and attaches it with a `sha256` beside it.

```bash
git tag -a v0.2.1 -m "screenstream 0.2.1"
git push origin v0.2.1
gh release create v0.2.1 --title "screenstream 0.2.1" --notes "..."   # the job follows
# or, for a release that already exists: gh workflow run apk -f tag=v0.2.1
```

**The signing key is the part that matters.** Android refuses to install an APK
signed with a different key over an installed app, so a release signed with a
debug key can never update the app it shipped: one key is generated once, kept
offline, and its keystore is in the repository's secrets. Three of them, in
settings → Secrets and variables → Actions:

| secret | what it is |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks`, one line |
| `RELEASE_STORE_PASSWORD` | that keystore's password, and its key's |
| `RELEASE_KEY_ALIAS` | the key's alias, `screenstream` below |

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 \
    -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD" \
    -alias screenstream -keyalg RSA -keysize 4096 -validity 10000 \
    -dname 'CN=screenstream,O=MBanucu,C=DE'
base64 -w0 release.jks > release.jks.b64
```

A PKCS12 keystore ties the key's password to the store's, which is why there is
one secret for both. Keep `release.jks` somewhere it survives this repository: a
lost key means no future release can update an install, only replace it.

`versionCode` is derived from the tag — `v0.2.1` becomes `201`
(`major*10000 + minor*100 + patch`) — because a package manager orders updates by
that integer, and the app had none. A tag that is not `major.minor.patch`, or
whose minor or patch is past 99, fails the job instead of inventing a code.

A local `bash build.sh apk` is unchanged and still signs with this machine's
debug key; `KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `VERSION_CODE` are
what the release job passes to sign with the real one. The first release-signed
APK cannot install over an install from `build.sh` — uninstall once, then updates
work.

## Consent

Capturing the display is not a permission an app can hold: the system asks every
time. Three ways to answer it, in decreasing order of honesty:

1. **A person taps "Start now".** What the app does by default.
2. **An accessibility service clicks it.** `ScreenStream.setDialogClicker(runnable)`
   — how the app this was extracted from accepts the dialog, the same interaction a
   screen-reader user performs.
3. **An app-op, opted into per run.** `adb shell appops set --uid <package>
   PROJECT_MEDIA allow` makes the system answer without a dialog. The `--uid`
   is required because project_media is a security app-op, checked by UID rather
   than by package. Verified here with accessibility off. It is not a documented
   third-party API, and it is persistent: an app with it can start capturing
   after any cold start with no user involvement. The headless recipe above sets
   it immediately before a run and resets it with `appops set --uid <package>
   PROJECT_MEDIA default` afterwards;
   the app never sets it and installs with no grant. `grant` reports whether it is
   in force and `status` reports `grant=yes|no|unknown`, so a harness can refuse to
   record blind. A standing grant is fine for a test phone, wrong for anything
   shipped: while it is set, anything able to start the activity can capture the
   screen with no tap and no visible consent.

## Limits

- One capture per process, on the fixed port 7100.
- Frames are the display, not one app's window — a dialog in front is in the frame.
- No audio, no file output: this is a source, and a recorder is a different tool.
- `scale=2` is the compositor's, not a downscale afterwards, so it is cheap; there is
  nothing between full size and half size.

## Follow-ups for the control surface

The library module is extracted and screenstream is built on it, but issue #6 is
not finished:

- **Publish a version.** `io.github.mbanucu:android-control-surface` builds,
  tests and signs locally; no version of it is on Maven Central yet, so the
  coordinate in the snippet above is the intended one and the screenstream POM
  that names it cannot resolve from Central until it exists.
- **Migrate wireless-adb-keeper.** Its `ControlProvider`, `ControlReceiver` and
  `ControlToken` are the same code; move it onto the AAR and delete the copies.
- **One client.** Point `keeperctl.sh` at the shared Python client
  (`client/screenstream_client`) instead of its own `sed`/`awk` reply parser,
  and have adb-harness drive screenstream through the same client.
- **Token for screenstream itself.** The base supports one; screenstream
  deliberately configures none, so its existing replies and callers are
  unchanged. Turning it on is a separate decision.
