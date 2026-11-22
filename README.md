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
| Transport | TCP `127.0.0.1:7100`, any number of consumers |
| Payload | RGBA_8888, `stride * height` bytes per frame |
| Rate | only when the screen changes (VFR) |
| Timing | device presentation time, nanoseconds, elapsed clock |
| Min SDK | 24 |
| Cost | about 18 MB per frame at 1440x3200 — `scale=2` quarters that |

## The wire format

Every frame is a 36-byte big-endian header, then its pixels:

```
magic "RAW1"   4 bytes
width          u32
height         u32
row stride     u32   bytes per row in the payload, padding included
format         u32   1 = RGBA_8888, 4 bytes per pixel
frame number   u64   changed frames since the capture began
timestamp      u64   nanoseconds, the device's elapsed clock
```

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
the state file.

**A note on the port.** `adb forward tcp:7100 tcp:7100` collides when the machine
holding adb is the phone itself (Termux, say): the forward's listener and the
stream's listener are the same loopback port, and the capture fails to bind. Use
`7200` on the host side, as above, and `--port 7200` for the client.

What it is doing is in `Android/data/io.github.mbanucu.screenstream.app/files/screenstream.txt`:

```
state=streaming          stopped, streaming, failed
port=7100
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
```

## Recording to a file

**Record** (or `--ez record true`) writes an H.264 MP4 next to the stream. It is
the same capture, because it has to be: a projection allows one virtual display,
and a second `createVirtualDisplay` on the same token is refused — it also
invalidates the capture, which is worth knowing before trying it. So the recorder
is fed the frames the stream has already copied and encodes them itself, which has
one property worth having: the video and the socket carry the same frames, so a
frame number in a test answer and a picture in the file are the same moment.

What that costs is the colour conversion the compositor would otherwise do. The
encoder wants YUV, the stream is RGBA, so the conversion runs on its own thread and
drops frames the encoder cannot keep up with. The file is therefore VFR like the
stream — it gets a frame when the screen changed, and the timestamps are the real
ones, shifted so the file starts at zero. A still screen records as a still video.

Measured on this phone, 1440x3200: a 9 s capture wrote 66 frames to a 2.9 MB file,
`ffprobe` reads it as h264 1440x3200, 9.01 s, 66 frames, and a frame from the
middle is not black. The bitrate is ~14 Mbps at that size (about 0.12 bits per
pixel per frame at 30 fps), a keyframe every two seconds.

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
bash build.sh                          # out/screenstream-0.1.0.aar (+ .jar, sources, .pom)
```

```kotlin
// build.gradle.kts of the consuming app
android { ... }
dependencies {
    implementation(files("libs/screenstream-0.1.0.aar"))
    // or, once it is in a repository: implementation("io.github.mbanucu:screenstream:0.1.0@aar")
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

## From a test framework

The socket is the language-agnostic edge, and for most frameworks it is all they
need: connect, read a header, read `stride * height` bytes, decide, repeat.
`client/stream.py` is a reference consumer in about 150 lines — the protocol is
deliberately small enough to re-implement in any language a test harness is
written in.

- **Host-side tests** (anything driving the phone over adb): `adb forward`, connect
  to `127.0.0.1:7100`, no Android dependency at all.
- **On-device tests** (instrumentation, an Espresso-like framework): depend on the
  AAR and `subscribe` in-process, so a frame costs no socket and no copy.
- **Assertions about time**: `frame.number` and `frame.timestampNs` line up with
  `screenrecord`'s metadata track and with `dumpsys`, so a check that passes on
  frame *n* can name the picture of frame *n*.

## Maven: for the library, not the app

Yes for the AAR — it is exactly what Maven repositories carry (an `aar` plus a
`pom`), and it is how a test framework would depend on this. `build.sh` writes
both, so they can go into any repository:

```bash
mvn deploy:deploy-file \
    -Durl=https://maven.pkg.github.com/MBanucu/screenstream \
    -DrepositoryId=github \
    -Dfile=out/screenstream-0.1.0.aar \
    -DpomFile=out/screenstream-0.1.0.pom
# or a local/Nexus repository, same two files
```

Two things Maven is *not* good for here:

- **The APK.** Maven repositories can hold any file, but nobody resolves an app as
  a dependency. The app belongs on a GitHub release (`out/screenstream-app.apk`),
  or on F-Droid if it should be installable without a cable.
- **Building it.** JitPack builds a repository with Gradle; this one builds with
  `build.sh` because it was written on a phone with no aarch64 aapt2. Publishing to
  Maven Central from here is therefore the "deploy the two files" route, not
  JitPack's build-from-tag route. Adding a Gradle build file would make JitPack and
  Central's Gradle publishing work; that is the natural next step for this
  repository, and it changes nothing about the sources.

## Consent

Capturing the display is not a permission an app can hold: the system asks every
time. Three ways to answer it, in decreasing order of honesty:

1. **A person taps "Start now".** What the app does by default.
2. **An accessibility service clicks it.** `ScreenStream.setDialogClicker(runnable)`
   — how the app this was extracted from accepts the dialog, the same interaction a
   screen-reader user performs.
3. **An app-op.** `adb shell appops set <package> PROJECT_MEDIA allow` makes the
   system answer without a dialog. Verified here with accessibility off. It is not a
   documented third-party API, and it is persistent: an app with it can start
   capturing after any cold start with no user involvement. Fine for a test phone,
   wrong for anything shipped.

## Limits

- One capture per process, on the fixed port 7100.
- Frames are the display, not one app's window — a dialog in front is in the frame.
- No audio, no file output: this is a source, and a recorder is a different tool.
- `scale=2` is the compositor's, not a downscale afterwards, so it is cheap; there is
  nothing between full size and half size.
