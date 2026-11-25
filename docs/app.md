# The app, on the phone

The app is the capture: install it, grant the projection once per run, and it
serves the screen on a loopback port and can write an H.264 MP4 beside it. This
page is for someone using a phone, including the headless, scripted paths over
adb.

The [front page](../README.md) has the one-line install; the socket and the
Python side are in [client.md](client.md), the embeddable AAR is in
[library.md](library.md), and the provider's frozen contract is
[control-surface.md](control-surface.md).

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
described in [library.md](library.md), and
[control-surface.md](control-surface.md) is the frozen contract (schema 1).
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
- A content-provider call cannot launch the consent activity from the
  background on API 35; logcat reports `Background activity launch blocked`.
  The gate therefore also starts `ScreenStreamActivity` explicitly with adb and
  the same scale/port extras, which is the documented `am start` path. If the
  platform still asks for consent, the gate finds the dialog's positive button
  in a `uiautomator dump` and taps it with `adb shell input tap`; it does not
  use fixed coordinates and does not wait for a person. `start` must then prove
  `state=streaming` with a bound port, the expected frame size and at least one
  composited frame. A failed activity launch, a dump without the button, a
  dismissed dialog or a `waiting_for_grant` reply fails the run.
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
