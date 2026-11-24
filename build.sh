#!/usr/bin/env bash
# Builds the screen stream without Gradle: this repository was written on a phone
# whose toolchain has no aarch64 aapt2, so the build is the steps Gradle would
# take, scripted.
#
#   bash build.sh              # library: out/screenstream-<version>.aar, .jar, -sources.jar,
#                              #          -javadoc.jar, .pom
#   bash build.sh apk          # app: out/screenstream-app.apk, installable and driven by adb
#   VERSION=x.y.z bash build.sh
#
# The APK is signed with the debug key unless a release key is given; a released
# APK has to be signed with the same key as the one it updates, so a release job
# passes all four of these and a local build passes none of them:
#
#   KEYSTORE           path to the keystore (default ~/.android/debug.keystore)
#   KEYSTORE_PASSWORD  its store password (default "android")
#   KEY_ALIAS          the key inside it (default androiddebugkey)
#   KEY_PASSWORD       that key's password (default "android")
#   VERSION_CODE       the integer a package manager orders releases by (default 1)
#
# The artifacts, and what each is for:
#
#   .aar          an Android app or test framework: classes.jar, the manifest with
#                 the service and the activity, and R.txt (empty, no resources)
#   .jar          a test framework that already has android.jar on its classpath
#   -sources.jar  a project that would rather read the four files than trust a binary
#   -javadoc.jar  required by Maven Central next to every artifact; the API docs,
#                 generated from the same four files
#   .pom          the Maven descriptor, so the AAR can be deployed to any repository
#                 as it is (see README, "Publishing"; publish.sh signs and uploads it)
#
# d8 runs over the library jar as a check: an AAR whose classes.jar cannot be
# dexed on its own is not a library, however well it compiles against android.jar.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
mode=${1:-library}
command -v python3 >/dev/null || { echo "missing python3" >&2; exit 1; }
# The version catalog is the one place these numbers live; tools/versions.py
# reads it without Gradle or Python 3.11's tomllib.
toml_value() { python3 "$here/tools/versions.py" "$1"; }
version=${VERSION:-$(toml_value projectVersion)}
version_code=${VERSION_CODE:-1}
sources=$here/src/main/java
api=$(toml_value compileSdk)
min_sdk=$(toml_value minSdk)
java=$(toml_value java)

sdk=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" /usr/lib/android-sdk; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        sdk=$candidate
        break
    fi
done
[ -n "$sdk" ] || { echo "no Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT, ~/android-sdk)" >&2; exit 1; }
android_jar=$sdk/platforms/android-$api/android.jar
build_tools=$sdk/build-tools/$(toml_value buildTools)
[ -f "$android_jar" ] || { echo "no $android_jar" >&2; exit 1; }
for tool in javac; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done
# d8 ships with the build-tools and is not always on PATH (a CI image installs the
# package and stops there), so it is looked for in both places, like aapt2 below.
d8=$(command -v d8 || true)
if [ -z "$d8" ] && [ -x "$build_tools/d8" ]; then
    d8=$build_tools/d8
fi
[ -n "$d8" ] || { echo "missing d8" >&2; exit 1; }

work=$here/out/work
out=$here/out
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$out"

# --- java ----------------------------------------------------------------------
echo "== screenstream $version: javac =="
mapfile -t files < <(find "$sources" -name '*.java')
if [ "$mode" = apk ] && [ -d "$here/app/src" ]; then
    # The app's own classes are compiled with the library's: it is one dex, the
    # way a Gradle app's dex is its code plus the AAR's.
    mapfile -t app_files < <(find "$here/app/src" -name '*.java')
    files+=("${app_files[@]}")
fi
javac -nowarn --release "$java" -classpath "$android_jar" -d "$work/classes" "${files[@]}"

python3 - "$work/classes" "$out/screenstream-$version.jar" <<'EOF'
import os, sys, zipfile
classes, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(classes):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, classes))
EOF

echo "== screenstream $version: d8 (does this jar stand on its own?) =="
"$d8" --lib "$android_jar" --min-api "$min_sdk" --output "$work/dex" "$out/screenstream-$version.jar"

if [ "$mode" = apk ]; then
    # --- app -------------------------------------------------------------------
    # The app is a manifest over the library: no code of its own, so the build is
    # the library's dex plus a launcher pointing at the library's activity.
    for tool in aapt2 zipalign apksigner; do
        command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
    done
    echo "== screenstream app: aapt2 link =="
    aapt2 link -o "$work/base.apk" \
        -I "$android_jar" \
        --manifest "$here/app/AndroidManifest.xml" \
        --min-sdk-version "$min_sdk" --target-sdk-version "$api" \
        --version-code "$version_code" --version-name "$version"

    echo "== screenstream app: package =="
    python3 - "$work/base.apk" "$work/dex/classes.dex" "$work/unaligned.apk" <<'EOF'
import sys, zipfile
base, dex, out = sys.argv[1:4]
with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, 'w') as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    dst.write(dex, 'classes.dex')
EOF

    zipalign -f 4 "$work/unaligned.apk" "$work/aligned.apk"
    keystore=${KEYSTORE:-$HOME/.android/debug.keystore}
    store_password=${KEYSTORE_PASSWORD:-android}
    key_alias=${KEY_ALIAS:-androiddebugkey}
    # One password is the common case: a PKCS12 keystore ties the key's password
    # to the store's, and apksigner refuses two that differ.
    key_password=${KEY_PASSWORD:-$store_password}
    if [ -z "${KEYSTORE:-}" ]; then
        # The debug key is a convenience for a local build, made on this machine
        # and never the one a release is signed with.
        [ -f "$keystore" ] || keytool -genkeypair -keystore "$keystore" -storepass android \
            -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
            -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
    fi
    [ -f "$keystore" ] || { echo "no keystore at $keystore" >&2; exit 1; }
    apksigner sign --ks "$keystore" --ks-pass "pass:$store_password" \
        --key-pass "pass:$key_password" --ks-key-alias "$key_alias" \
        --out "$out/screenstream-app.apk" "$work/aligned.apk"
    rm -rf "$work"
    echo "built $out/screenstream-app.apk ($(wc -c < "$out/screenstream-app.apk") bytes)"
    exit 0
fi

# --- library packaging ---------------------------------------------------------
echo "== screenstream $version: aar =="
mkdir -p "$work/aar"
cp "$here/AndroidManifest.xml" "$work/aar/AndroidManifest.xml"
cp "$out/screenstream-$version.jar" "$work/aar/classes.jar"
: > "$work/aar/R.txt"
python3 - "$work/aar" "$out/screenstream-$version.aar" <<'EOF'
import os, sys, zipfile
root, aar = sys.argv[1], sys.argv[2]
# An AAR is a zip of exactly these: a library with no resources still needs the
# empty R.txt for tools that look for it.
with zipfile.ZipFile(aar, 'w', zipfile.ZIP_DEFLATED) as z:
    for name in ('AndroidManifest.xml', 'classes.jar', 'R.txt'):
        z.write(os.path.join(root, name), name)
EOF

python3 - "$sources" "$out/screenstream-$version-sources.jar" <<'EOF'
import os, sys, zipfile
root, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
EOF

# --- javadoc -------------------------------------------------------------------
# Maven Central refuses a component without a javadoc jar, and the AAR's sources
# reference android.*, so the docs need android.jar on the classpath as well.
echo "== screenstream $version: javadoc =="
mkdir -p "$work/javadoc"
javadoc -quiet -Xdoclint:none --release "$java" -classpath "$android_jar" \
    -windowtitle "screenstream $version" -doctitle "screenstream $version" \
    -d "$work/javadoc" "${files[@]}"
python3 - "$work/javadoc" "$out/screenstream-$version-javadoc.jar" <<'EOF'
import os, sys, zipfile
root, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
EOF

cat > "$out/screenstream-$version.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.github.mbanucu</groupId>
    <artifactId>screenstream</artifactId>
    <version>$version</version>
    <packaging>aar</packaging>
    <name>screenstream</name>
    <description>The phone's screen as raw RGBA frames on a local port, for apps and for tests.</description>
    <url>https://github.com/MBanucu/screenstream</url>
    <licenses>
        <license>
            <name>GNU General Public License v3.0 or later</name>
            <url>https://www.gnu.org/licenses/gpl-3.0-standalone.html</url>
        </license>
    </licenses>
    <developers>
        <developer>
            <id>MBanucu</id>
            <name>MBanucu</name>
            <url>https://github.com/MBanucu</url>
        </developer>
    </developers>
    <scm>
        <connection>scm:git:https://github.com/MBanucu/screenstream.git</connection>
        <developerConnection>scm:git:ssh://git@github.com/MBanucu/screenstream.git</developerConnection>
        <url>https://github.com/MBanucu/screenstream</url>
    </scm>
    <!-- No dependencies: everything here is android.jar and java.*. -->
    <dependencies />
</project>
POM

rm -rf "$work"
for artifact in "screenstream-$version.aar" "screenstream-$version.jar" \
        "screenstream-$version-sources.jar" "screenstream-$version-javadoc.jar" \
        "screenstream-$version.pom"; do
    printf 'built %s (%s bytes)\n' "$out/$artifact" "$(wc -c < "$out/$artifact")"
done
