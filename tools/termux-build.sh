#!/bin/sh
# shellcheck shell=bash
# /bin/sh exists on both Termux and Linux; re-exec bash because the script
# below uses bash arrays.  Termux has no /usr/bin/env, so a portable shebang
# cannot be '#!/usr/bin/env bash'.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
# Smoke-builds the library on Termux, the environment this project is developed
# on rather than the ubuntu runner in .github/workflows/ci.yml.
#
#   tools/termux-build.sh
#   OFFLINE=0 tools/termux-build.sh          # allow downloads (first run)
#   GRADLE=./gradlew tools/termux-build.sh   # use the wrapper instead
#   AAPT2=$PREFIX/bin/aapt2 tools/termux-build.sh
#
# It checks the environment first, then runs the same assembleRelease the CI
# Gradle job runs, with the configuration and build caches enabled. It does not
# install the APK, publish anything or touch Maven Central.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if [ -n "${GRADLE:-}" ]; then
    gradle=$GRADLE
elif command -v gradle >/dev/null 2>&1; then
    gradle=gradle
elif [ -x ./gradlew ]; then
    gradle=./gradlew
else
    echo "no gradle on PATH and no ./gradlew; pkg install gradle" >&2
    exit 1
fi

# ANDROID_HOME is enough for AGP; local.properties is the fallback. Do not write
# local.properties from here: it is machine-specific and ignored by git.
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] \
        && [ ! -f local.properties ] && [ -d "${HOME:-}/android-sdk/platforms" ]; then
    export ANDROID_HOME="$HOME/android-sdk"
    echo "using ANDROID_HOME=$ANDROID_HOME (no env or local.properties)" >&2
fi

args=(--warning-mode all --configuration-cache --build-cache)
has_console=0
for arg in "$@"; do
    case $arg in
        --console | --console=*) has_console=1 ;;
    esac
done
[ "$has_console" = 1 ] || args=(--console=plain "${args[@]}")
if [ "${OFFLINE:-1}" = 1 ]; then
    args+=(--offline)
fi

# Termux's Gradle daemon cannot adopt the client's environment (Gradle says
# there is no native integration for it), so a daemon started earlier would not
# see a newly exported ANDROID_HOME. With local.properties the SDK is found
# without the environment; without it, use a one-shot daemon that inherits this
# shell's environment.
if [ ! -f local.properties ] \
        && { [ -n "${ANDROID_HOME:-}" ] || [ -n "${ANDROID_SDK_ROOT:-}" ]; }; then
    args+=(--no-daemon)
fi

# Termux's aapt2 is a bionic build; AGP's downloaded aapt2 is glibc. Pass it on
# the command line when it exists so a fresh checkout needs no global setup,
# while ~/.gradle/gradle.properties can still provide it otherwise.
aapt2=${AAPT2:-${PREFIX:-}/bin/aapt2}
if [ -n "${PREFIX:-}" ] && [ -x "$aapt2" ]; then
    args+=("-Pandroid.aapt2FromMavenOverride=$aapt2")
fi

echo "== termux smoke build: $("$gradle" --version | sed -n 's/^Gradle //p' | head -1) ==" >&2
"$gradle" "${args[@]}" checkBuildEnv checkManifests :assembleRelease "$@"

echo "built $(find build/outputs/aar -name '*.aar' -print -quit)"
