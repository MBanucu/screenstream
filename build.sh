#!/usr/bin/env bash
# Builds the screen stream without Gradle: this repository was written on a phone
# whose toolchain has no aarch64 aapt2, so the build is the steps Gradle would
# take, scripted.
#
#   bash build.sh              # library: out/screenstream-<version>.aar, .jar, -sources.jar, .pom
#   bash build.sh apk          # app: out/screenstream-app.apk, installable and driven by adb
#   VERSION=0.2.0 bash build.sh
#
# The artifacts, and what each is for:
#
#   .aar          an Android app or test framework: classes.jar, the manifest with
#                 the service and the activity, and R.txt (empty, no resources)
#   .jar          a test framework that already has android.jar on its classpath
#   -sources.jar  a project that would rather read the four files than trust a binary
#   .pom          the Maven descriptor, so the AAR can be deployed to any repository
#                 as it is (see README, "Maven")
#
# d8 runs over the library jar as a check: an AAR whose classes.jar cannot be
# dexed on its own is not a library, however well it compiles against android.jar.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
mode=${1:-library}
version=${VERSION:-0.1.0}
sources=$here/src/main/java

sdk=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" /usr/lib/android-sdk; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        sdk=$candidate
        break
    fi
done
[ -n "$sdk" ] || { echo "no Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT, ~/android-sdk)" >&2; exit 1; }
api=35
android_jar=$sdk/platforms/android-$api/android.jar
build_tools=$sdk/build-tools/35.0.0
[ -f "$android_jar" ] || { echo "no $android_jar" >&2; exit 1; }
for tool in javac d8 python3; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

work=$here/out/work
out=$here/out
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$out"

# --- java ----------------------------------------------------------------------
echo "== screenstream $version: javac =="
mapfile -t files < <(find "$sources" -name '*.java')
javac -nowarn --release 17 -classpath "$android_jar" -d "$work/classes" "${files[@]}"

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
d8 --lib "$android_jar" --min-api 24 --output "$work/dex" "$out/screenstream-$version.jar"

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
        --min-sdk-version 24 --target-sdk-version "$api" \
        --version-code 1 --version-name "$version"

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
    keystore=$HOME/.android/debug.keystore
    [ -f "$keystore" ] || keytool -genkeypair -keystore "$keystore" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
    apksigner sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --out "$out/screenstream-app.apk" "$work/aligned.apk"
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
        "screenstream-$version-sources.jar" "screenstream-$version.pom"; do
    printf 'built %s (%s bytes)\n' "$out/$artifact" "$(wc -c < "$out/$artifact")"
done
