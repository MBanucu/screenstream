# Building and releasing

This page is for a maintainer: the two builds that produce the same libraries,
the APK release, and the order a release goes out in. Publishing to Maven
Central is detailed in [library.md](library.md), the PyPI half in
[client.md](client.md), and the on-device Gradle setup in
[termux-build.md](termux-build.md).

The [front page](../README.md) has the one-line build commands.

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
the client with trusted publishing, described in [client.md](client.md).

What Gradle adds is what a publication wants rather than what a build needs:
module metadata (`.module`, which is how a Gradle consumer resolves the AAR without
an explicit `@aar` — 0.1.0 has none, which is why that coordinate needs one, and
0.2.0 was published from this build to have it), `publishToMavenLocal` for testing
a consumer, and a project an IDE or JitPack can open. It costs the first run about five minutes in
downloads; `build.sh` remains the path with no downloads at all. Two things it
needs on this device are already in place: `android.aapt2FromMavenOverride` in
`~/.gradle/gradle.properties` points AGP at Termux's own aapt2, and
`local.properties` (ignored by git) names the SDK. `local.properties.example` is
the template; [termux-build.md](termux-build.md) has the full setup, the measured
configuration/build-cache times and the upstream warnings that are deliberately
not ours.

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

## Release checklist

One number, one tag, and Maven before PyPI:

1. **Bump the version** in `gradle/libs.versions.toml` (`projectVersion`) and in
   `client/pyproject.toml`. They must agree; `test/test_client.py` fails if they
   do not, and `release.yml` refuses a tag that matches neither.
2. **Tag the same number**: `git tag -a v<version>` and push the tag. The
   release workflow strips the leading `v`, so `v0.2.0` and `0.2.0` are the same
   version.
3. **The Maven job runs first**: `publish.sh` for `android-control-surface`, waits
   for `PUBLISHED`, then the same for `screenstream`. It needs the four Maven
   secrets; without them the run stops at the check instead of uploading.
4. **Then PyPI**: the `pypi` job builds `client/` and uploads with trusted
   publishing. It waits for Maven on purpose, because a PyPI filename can never
   be replaced.
5. **The APK job follows the tag**: `apk.yml` signs with the release keystore and
   attaches `screenstream-<version>.apk` plus its `sha256`.
6. **Re-running is safe**: an already-published Maven version prints
   `deployment: already-published` and uploads nothing, so retrying the PyPI half
   does not spend a version number.

Before the first release, the outside-the-repository setup is: the verified
`io.github.mbanucu` namespace, the signing key on a keyserver, the portal user
token, the PyPI trusted publisher and `pypi` environment, and the three APK
keystore secrets. [library.md](library.md) covers the first three,
[client.md](client.md) the PyPI two, and this page the keystore.
