# Building and testing the Gradle build on Termux

This is the on-device counterpart to `.github/workflows/ci.yml`: what a fresh
checkout needs on Termux, how the Gradle build is kept reproducible, and which
warnings come from AGP rather than from this repository.

`build.sh` remains the no-network, no-Gradle build. This page is about
`build.gradle.kts`, which adds module metadata, `publishToMavenLocal` and an IDE
project.

## What was verified here

Verified on 2026-11-24 on Termux aarch64 / Android 15:

| item | value |
|---|---|
| Gradle | 9.9.2 (`pkg install gradle`) |
| AGP | 9.5.2 (from `gradle/libs.versions.toml`) |
| JDK | OpenJDK 21.0.13 |
| SDK | `$HOME/android-sdk`, platform 35, build-tools 35.0.0 |
| aapt2 | Termux 16.0.0.4 via `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` |
| build | `gradle --offline :assembleRelease` — SUCCESSFUL |
| configuration cache | cold 7 s, warm 1 s (`gradle --offline :assembleRelease`) |
| build cache | clean rebuild 2 s with 12 tasks `FROM-CACHE` |

## A fresh checkout

### 1. JDK and Gradle

```bash
pkg install openjdk-21 gradle
java -version      # 17 or newer; 21 is what this project is tested with
gradle --version   # 9.9.2 here; the build needs 9.6 or newer for AGP 9.x
```

### 2. Android SDK

Either export the SDK:

```bash
export ANDROID_HOME="$HOME/android-sdk"
api=$(python3 tools/versions.py compileSdk)
build_tools=$(python3 tools/versions.py buildTools)
sdkmanager "platforms;android-$api" "build-tools;$build_tools"
```

or copy the template and edit the path:

```bash
cp local.properties.example local.properties
$EDITOR local.properties        # sdk.dir=/data/data/com.termux/files/home/android-sdk
```

`local.properties` is machine-specific, ignored by git and must never be
committed. `ANDROID_HOME`/`ANDROID_SDK_ROOT` wins when both are present.

### 3. aapt2

AGP's aapt2 downloads are glibc x86_64/arm64 builds; Termux uses bionic. Point
AGP at Termux's aapt2 in the **user** Gradle properties, not in this repository:

```bash
cat >> ~/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
EOF
```

`tools/termux-build.sh` passes the same property on the command line when it
finds `$PREFIX/bin/aapt2`, so a one-off smoke build works even without that
line.

### 4. Check the environment

```bash
gradle checkBuildEnv
# If ANDROID_HOME is the only SDK source and a daemon predates it, add
# --no-daemon or create local.properties (see "Build" below).
```

The task checks JDK >= 17, `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `sdk.dir`,
`platforms/android-<compileSdk>/android.jar`, and the aapt2 override (when a
Termux prefix is detected). On failure it prints one block of actionable problems
instead of an AGP stack trace, for example:

```
The Android build environment is not ready:
  - no Android SDK: export ANDROID_HOME=/path/to/android-sdk, or copy ...
  - no aapt2 override on Termux; add this to ~/.gradle/gradle.properties ...
See docs/termux-build.md for a working Termux setup.
```

`checkBuildEnv` is not wired into `assembleRelease`, because an x86_64 CI runner
uses AGP's own aapt2 and does not need the override.

### 5. Build

```bash
tools/termux-build.sh
# or, for the same tasks by hand:
gradle --offline checkBuildEnv :assembleRelease \
    :android-control-surface:assembleRelease --configuration-cache --build-cache
gradle --offline :android-control-surface:test   # device-free JVM tests
```

The AARs are at `build/outputs/aar/screenstream-release.aar` and
`android-control-surface/build/outputs/aar/android-control-surface-release.aar`.
The first build on a new machine needs network for AGP and its dependencies; the
control-surface JVM tests also fetch JUnit once (afterwards they run offline).
`OFFLINE=0 tools/termux-build.sh` allows a networked build. After the dependency
cache is warm, `--offline` is the normal path.

Termux's Gradle daemon cannot adopt a client's environment (Gradle reports that
there is no native integration for it), so a daemon started before `ANDROID_HOME`
was exported will not see it. `tools/termux-build.sh` therefore passes
`--no-daemon` when the SDK comes only from `ANDROID_HOME`/`ANDROID_SDK_ROOT` and
there is no `local.properties`. For direct `gradle` commands in that situation,
either create `local.properties` or pass `--no-daemon` (or run `gradle --stop`
first). With `local.properties`, the daemon is fine.

The smoke script also runs on a self-hosted CI runner. Set the repository
variable `TERMUX_RUNNER=true` once a runner labelled `self-hosted, termux` is
registered; the `termux` job in `ci.yml` is skipped until then.

## Versions live in one place

`gradle/libs.versions.toml` owns:

| key | used by |
|---|---|
| `agp` | the `android-library` plugin alias |
| `compileSdk`, `minSdk`, `buildTools` | Gradle, `build.sh`, all CI workflows |
| `java` | Gradle compile options, `javac` and `javadoc` in `build.sh` |
| `projectVersion` | Gradle, `build.sh`, `publish.sh`; must match `client/pyproject.toml` |

`tools/versions.py` reads the file without Gradle or Python 3.11's `tomllib`, so
the Gradle-free scripts use the same numbers. `test/test_client.py` runs that
helper and fails if the catalog and the Python client disagree. `-Pversion=...`
still overrides `projectVersion` for a release or a local publication smoke test.

## Configuration cache and build cache

Both are enabled in `gradle.properties`:

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
```

Measured on the phone, after the dependency cache was warm:

| run | result |
|---|---|
| `rm -rf build .gradle/configuration-cache` then `gradle --offline :assembleRelease --no-build-cache` | BUILD SUCCESSFUL in 7 s, entry stored |
| the same command again | entry reused, BUILD SUCCESSFUL in 1 s |
| `gradle --offline clean :assembleRelease --build-cache` after a populated cache | BUILD SUCCESSFUL in 2 s, 12 tasks `FROM-CACHE` |

`android.builtInKotlin=false` is deliberate. This is a Java-only library, but
AGP 9 otherwise adds a `compileReleaseKotlin` task and a
`kotlinBuildToolsApiClasspath` resolution to every variant. With the
configuration cache enabled and `--offline`, a cold cache store then failed
trying to serialize that Kotlin transform (and resolving
`kotlin-build-tools-impl`). Turning built-in Kotlin off produces a Java-only
task graph, which is what this project actually builds. Re-enable it if Kotlin
sources are ever added.

## The two manifests

`AndroidManifest.xml` at the repository root is canonical: `build.sh` copies it
into the AAR, `package="io.github.mbanucu.screenstream"` included.

AGP 9 takes the package from `namespace` and warns when a source manifest still
has `package=`. Gradle therefore reads `gradle/AndroidManifest.xml`, an otherwise
identical copy without that attribute. The `checkManifests` task, wired into
`preBuild`, fails the build if the two drift. After editing the canonical
manifest:

```bash
sed '/^    package="io.github.mbanucu.screenstream"/d' AndroidManifest.xml \
    > gradle/AndroidManifest.xml
gradle checkManifests
```

## Wrapper and dependency management

`gradle/wrapper/gradle-wrapper.properties` pins both the distribution URL and
its SHA-256:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.2-bin.zip
distributionSha256Sum=971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b
```

That hash was computed from the downloaded `gradle-9.9.2-bin.zip` and matched
`services.gradle.org`'s published `.sha256`. `gradle/actions/wrapper-validation@v4`
in CI checks the checked-in `gradle-wrapper.jar` against Gradle's published
checksums, so neither half of the wrapper can change silently.

This project has **no runtime dependencies**: `classes.jar` is `java.*` and
`android.jar`, and the POM's `<dependencies>` is empty. AGP and the Kotlin
tooling are build-time plugins, pinned by `gradle/libs.versions.toml` and
resolved from `google()`/`mavenCentral()` in `settings.gradle.kts` with
`RepositoriesMode.FAIL_ON_PROJECT_REPOS`. A `dependencyLocking` file would lock
a plugin classpath that is already version-pinned, and the wrapper checksum plus
CI wrapper validation is the boundary that matters for a dependency-free
library. If runtime dependencies are added, enable Gradle dependency locking
(`dependencyLocking { lockAllConfigurations() }`) and/or
`--write-verification-metadata sha256` at that point rather than carrying an
empty lockfile now.

## Warnings left upstream

`gradle --offline :assembleRelease --warning-mode all` is clean for this
repository's Gradle 10 deprecations. What remains:

- **`android.aapt2FromMavenOverride` is experimental.** It is the documented
  Termux workaround; `android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE`
  suppresses only that notice. If AGP ships a bionic aapt2, remove both.
- **Java API deprecation notes from `javac`** (`Bundle.get`, the legacy
  `Intent.getParcelableExtra`, `AppOpsManager.checkOpNoThrow`). These are
  Android API-level compatibility choices for `minSdk 24`, not Gradle 10
  deprecations. Fixing them needs version-guarded replacements, which is a
  source change separate from this build hardening.
- Earlier Gradle 9 runs also reported `Configuration.setVisible(boolean)` as
  removed in Gradle 11. It came from the Kotlin plugin that AGP 9 applied for a
  Java-only project; it is gone with `android.builtInKotlin=false`. If a future
  AGP brings it back, it is upstream: report it rather than suppressing it.

## Publishing smoke test

`publishToMavenLocal` and `publish.sh` can be exercised without uploading:

```bash
gradle --offline publishToMavenLocal -Pversion=0.0.0-smoke
gradle --offline :android-control-surface:publishToMavenLocal -Pversion=0.0.0-smoke
ls ~/.m2/repository/io/github/mbanucu/screenstream/0.0.0-smoke/
ls ~/.m2/repository/io/github/mbanucu/android-control-surface/0.0.0-smoke/
rm -rf ~/.m2/repository/io/github/mbanucu/screenstream/0.0.0-smoke
rm -rf ~/.m2/repository/io/github/mbanucu/android-control-surface/0.0.0-smoke

# The Gradle bundle, signed with a throwaway key and never uploaded:
gnupg=$(mktemp -d)
GNUPGHOME="$gnupg" gpg --batch --passphrase '' --quick-generate-key \
    'smoke <smoke@example.invalid>' ed25519 sign 1d
fpr=$(GNUPGHOME="$gnupg" gpg --list-secret-keys --with-colons |
      awk -F: '/^fpr/ {print $10; exit}')
GNUPGHOME="$gnupg" GRADLE_PUBLISH=1 VERSION=0.0.0-smoke PGP_KEY="$fpr" bash publish.sh
GNUPGHOME="$gnupg" ARTIFACT=android-control-surface VERSION=0.0.0-smoke \
    PGP_KEY="$fpr" bash publish.sh
rm -rf "$gnupg"
```

`publish.sh` prints the portal instructions and exits without uploading unless
`--upload` is passed. Do not pass `--upload` for a smoke test, and never use the
smoke version for a real release.

## Troubleshooting

| symptom | fix |
|---|---|
| `no Android SDK` | export `ANDROID_HOME`, or set `sdk.dir` in `local.properties` |
| `Android platform 35 is not installed` | `sdkmanager "platforms;android-$(python3 tools/versions.py compileSdk)"` |
| `aapt2 ... not executable` | `chmod +x $PREFIX/bin/aapt2`, or fix the override path |
| aapt2 exits with a loader error | the override points at a glibc build; use Termux's bionic `aapt2` |
| `No cached version ... available for offline mode` on a first build | run once with `OFFLINE=0 tools/termux-build.sh` |
| `SDK location not found` although `ANDROID_HOME` is exported | the Gradle daemon did not inherit it; use `tools/termux-build.sh`, add `local.properties`, or pass `--no-daemon` |
| configuration cache "cannot be reused" after editing a build file | expected; the next run stores a new entry |
| `checkManifests` fails | regenerate `gradle/AndroidManifest.xml` from the canonical manifest as shown above |

## See also

[README](../README.md) · [client](client.md) · [app](app.md) · [library](library.md) · [control surface](control-surface.md) · [release](release.md) · [Termux build](termux-build.md) · [limits](limits.md)
