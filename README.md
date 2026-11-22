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
adb shell am start -n io.github.mbanucu.screenstream.app/io.github.mbanucu.screenstream.ScreenStreamActivity
adb forward tcp:7100 tcp:7100
python3 client/stream.py --seconds 5 --png out/stream
adb shell am start -n io.github.mbanucu.screenstream.app/io.github.mbanucu.screenstream.ScreenStreamActivity --ez stop true
```

The app has no code of its own: its manifest is a launcher over the library's
consent activity. Tapping the icon and starting it from adb are the same thing —
the system asks for the display, and once it is granted the app serves the screen
until the capture is stopped, from the notification, with `--ez stop true`, or by
force-stopping the app.

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
```

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
