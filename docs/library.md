# As a library

The capture is an Android AAR, and the control surface is a second AAR, so an app
or a test framework can embed either instead of driving a port. This page covers
the API, the control-surface base class, the test-framework story, and how the
artifacts are published.

The [front page](../README.md) has the one-line dependency; the app that ships
the same code is in [app.md](app.md), and the provider's frozen contract is
[control-surface.md](control-surface.md).

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

[control-surface.md](control-surface.md) is the contract: the codes, the
`key=value` grammar, the escaping, the extras letters and the schema version. The
Python client's `screenstream_client.control` implements the same grammar and is
tested against the same fixture file. Nothing from this module is on Maven Central
yet; the first publication is a follow-up, and the coordinate above is the
intended one.

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
- **On-device tests** (instrumentation, an Espresso-like framework): depend on
  the AAR and `subscribe` in-process, so a frame costs no socket and no copy.
- **Assertions about time**: `frame.number` and `frame.timestamp_ns` line up with
  `screenrecord`'s metadata track and with `dumpsys`, so a check that passes on
  frame *n* can name the picture of frame *n*. `test/test_device_wire.py` is an
  optional, clearly marked device check of one frame's size and timestamp; it is
  skipped unless `SCREENSTREAM_DEVICE=1` and is not run by the hosted workflow.
  The post-merge `.github/workflows/emulator-e2e.yml` gate is a different check:
  it uses a fresh API 35 AVD and asserts the control reply, the first frame, the
  timestamp order and the MP4 finalisation, not the phone's encoder. What that
  gate does is in [app.md](app.md).

## Publishing: Maven Central, for the library

Maven is the right home for the AAR — it is what a test framework resolves a
dependency from, and Central is the one repository a consumer can use without a
token of their own (GitHub Packages needs authentication even to download a public
package). The app is not a dependency and does not go there.

Neither Gradle nor Maven is needed for this, and neither is installed here: the
Central Portal accepts a zip laid out as a Maven repository, so `publish.sh`
builds, signs and assembles it. One version is 40 files per artifact — the AAR,
POM, sources jar and javadoc jar, each with a `.asc` and four checksums — 163 KB
per version for screenstream. The control surface is published the same way, as
its own deployment, and has to go first because the screenstream POM names it —
the release workflow waits for it to read `PUBLISHED` before uploading
screenstream.

```bash
bash publish.sh            # build, sign, checksum: out/screenstream-<version>-bundle.zip
bash publish.sh --upload   # ... and upload it, with the portal's user token
ARTIFACT=android-control-surface bash publish.sh            # the dependency, same steps
ARTIFACT=android-control-surface bash publish.sh --upload   # ... and upload it first
GRADLE_PUBLISH=1 bash publish.sh --upload   # same, with Gradle's artifacts (.module)
bash publish.sh --status <deploymentId>   # VALIDATED, PUBLISHING, PUBLISHED
bash publish.sh --publish <deploymentId>  # release a validated USER_MANAGED one
bash publish.sh --wait <deploymentId>     # release it when validated, block until PUBLISHED
```

`--wait` is what makes "the dependency goes first" publication order rather than
submission order: it releases a `VALIDATED` deployment, returns only once it reads
`PUBLISHED`, and reports an already-`PUBLISHED` one instead of waiting out its
timeout. `--upload` does the same for the version itself — if Central already has
it, the run prints `deployment: already-published` and uploads nothing, so
re-running a release whose Maven half succeeded (say to retry PyPI) is safe.

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
