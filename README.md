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

## Install

One line per surface; each page starts from the same place.

| surface | install |
|---|---|
| Python client | `pip install screenstream-client` |
| The app | `bash build.sh apk && adb install -r out/screenstream-app.apk` |
| The library | `implementation("io.github.mbanucu:screenstream:0.2.0")` |

## Where to go

| page | audience | what it covers |
|---|---|---|
| [docs/client.md](docs/client.md) | Python user | the wire format, the client, PyPI publishing |
| [docs/app.md](docs/app.md) | phone user | install, adb and the control provider, recording, the live H.264 view, Twitch, consent |
| [docs/library.md](docs/library.md) | app developer | the AAR API, the control-surface library, test frameworks, Maven publishing |
| [docs/control-surface.md](docs/control-surface.md) | integrator | the frozen control contract, schema 1 |
| [docs/release.md](docs/release.md) | maintainer | the two builds, the APK release, a release checklist |
| [docs/termux-build.md](docs/termux-build.md) | on-device builder | Gradle on Termux, measured build times |
| [docs/limits.md](docs/limits.md) | everyone | the limits, and the follow-ups around the control surface |

## Consent

Capturing the display is not a permission an app can hold: the system asks every
time. The app's default is a person tapping **Start now**. The accessibility-service
clicker and the per-run `PROJECT_MEDIA` app-op are opt-in automation: the app never
sets the app-op and installs with no grant, and a standing grant is fine for a test
phone but wrong for anything shipped — while it is set, anything able to start the
activity can capture the screen with no tap and no visible consent. The three
answers, and how the headless recipes use and revoke the app-op, are in
[docs/app.md](docs/app.md).
