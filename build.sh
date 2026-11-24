#!/usr/bin/env bash
# Builds screenstream and its control-surface library without Gradle: this
# repository was written on a phone whose toolchain has no aarch64 aapt2, so the
# build is the steps Gradle would take, scripted.
#
#   bash build.sh                    # both libraries: out/screenstream-<version>.* and
#                                    #                  out/android-control-surface-<version>.*
#   bash build.sh control-surface    # only the control-surface library
#   bash build.sh apk                # app: out/screenstream-app.apk, installable and driven by adb
#   VERSION=x.y.z bash build.sh
#
# A library build writes, per artifact, the .aar, .jar, -sources.jar,
# -javadoc.jar and .pom. The control surface is a dependency of screenstream, so
# its artifact is built first and the screenstream POM names it.
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
#   .aar          an Android app or test framework: classes.jar, the manifest, R.txt
#   .jar          a test framework that already has android.jar on its classpath
#   -sources.jar  a project that would rather read the sources than trust a binary
#   -javadoc.jar  required by Maven Central next to every artifact
#   .pom          the Maven descriptor; publish.sh signs and uploads it
#
# d8 runs over each library jar as a check: an AAR whose classes.jar cannot be
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
control_sources=$here/android-control-surface/src/main/java
control_manifest=$here/android-control-surface/AndroidManifest.xml
control_artifact=android-control-surface
screenstream_artifact=screenstream
api=$(toml_value compileSdk)
min_sdk=$(toml_value minSdk)
java=$(toml_value java)

case "$mode" in
    library|control-surface|apk) ;;
    *) echo "usage: bash build.sh [library|control-surface|apk]" >&2; exit 2 ;;
esac

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
mkdir -p "$work/classes" "$work/control-classes" "$work/dex" "$out"

# --- helpers -------------------------------------------------------------------
# A jar of everything under a classes directory.
jar_from() {
    python3 - "$1" "$2" <<'EOF'
import os, sys, zipfile
classes, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(classes):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, classes))
EOF
}

# An AAR of exactly the four entries a library with no resources needs.
aar_from() {
    local jar=$1 manifest=$2 aar=$3 staged=$work/aar
    rm -rf "$staged"
    mkdir -p "$staged"
    cp "$manifest" "$staged/AndroidManifest.xml"
    cp "$jar" "$staged/classes.jar"
    : > "$staged/R.txt"
    python3 - "$staged" "$aar" <<'EOF'
import os, sys, zipfile
root, aar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar, 'w', zipfile.ZIP_DEFLATED) as z:
    for name in ('AndroidManifest.xml', 'classes.jar', 'R.txt'):
        z.write(os.path.join(root, name), name)
EOF
}

sources_jar() {
    python3 - "$1" "$2" <<'EOF'
import os, sys, zipfile
root, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
EOF
}

# Maven Central refuses a component without a javadoc jar; the sources reference
# android.* (and screenstream the control surface), so both are on the classpath.
javadoc_jar() {
    local src=$1
    local classpath=$2
    local jar=$3
    local title=$4
    local docs=$work/javadoc-$(basename "$src")
    mapfile -t jfiles < <(find "$src" -name '*.java')
    rm -rf "$docs"
    mkdir -p "$docs"
    javadoc -quiet -Xdoclint:none --release "$java" -classpath "$classpath" \
        -windowtitle "$title $version" -doctitle "$title $version" \
        -d "$docs" "${jfiles[@]}"
    python3 - "$docs" "$jar" <<'EOF'
import os, sys, zipfile
root, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
EOF
}

# A POM for one artifact; the optional third argument is a dependency block.
write_pom() {
    local artifact=$1 description=$2 dependencies=$3
    cat > "$out/$artifact-$version.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.github.mbanucu</groupId>
    <artifactId>$artifact</artifactId>
    <version>$version</version>
    <packaging>aar</packaging>
    <name>$artifact</name>
    <description>$description</description>
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
    <dependencies>
$dependencies
    </dependencies>
</project>
POM
}

dependency_on_control() {
    cat <<DEP
        <dependency>
            <groupId>io.github.mbanucu</groupId>
            <artifactId>$control_artifact</artifactId>
            <version>$version</version>
            <scope>compile</scope>
        </dependency>
DEP
}

# --- the control-surface library -----------------------------------------------
compile_control() {
    mapfile -t control_files < <(find "$control_sources" -name '*.java')
    echo "== $control_artifact $version: javac =="
    javac -nowarn --release "$java" -classpath "$android_jar" \
        -d "$work/control-classes" "${control_files[@]}"
    jar_from "$work/control-classes" "$out/$control_artifact-$version.jar"
    echo "== $control_artifact $version: d8 (does this jar stand on its own?) =="
    "$d8" --lib "$android_jar" --min-api "$min_sdk" --output "$work/dex" \
        "$out/$control_artifact-$version.jar"
}

package_control() {
    echo "== $control_artifact $version: aar =="
    aar_from "$out/$control_artifact-$version.jar" "$control_manifest" \
        "$out/$control_artifact-$version.aar"
    sources_jar "$control_sources" "$out/$control_artifact-$version-sources.jar"
    echo "== $control_artifact $version: javadoc =="
    javadoc_jar "$control_sources" "$android_jar:$out/$control_artifact-$version.jar" \
        "$out/$control_artifact-$version-javadoc.jar" "$control_artifact"
    write_pom "$control_artifact" \
        "A uid-gated, token-capable ContentProvider control surface with a frozen key=value reply format." \
        "        <!-- No dependencies: everything here is android.jar and java.*. -->"
    for artifact in "$control_artifact-$version.aar" "$control_artifact-$version.jar" \
            "$control_artifact-$version-sources.jar" "$control_artifact-$version-javadoc.jar" \
            "$control_artifact-$version.pom"; do
        printf 'built %s (%s bytes)\n' "$out/$artifact" "$(wc -c < "$out/$artifact")"
    done
}

# --- screenstream --------------------------------------------------------------
compile_screenstream() {
    mapfile -t files < <(find "$sources" -name '*.java')
    echo "== $screenstream_artifact $version: javac =="
    javac -nowarn --release "$java" \
        -classpath "$android_jar:$out/$control_artifact-$version.jar" \
        -d "$work/classes" "${files[@]}"
    jar_from "$work/classes" "$out/$screenstream_artifact-$version.jar"
    echo "== $screenstream_artifact $version: d8 (does this jar stand on its own?) =="
    "$d8" --lib "$android_jar" --min-api "$min_sdk" \
        --classpath "$out/$control_artifact-$version.jar" \
        --output "$work/dex" "$out/$screenstream_artifact-$version.jar"
}

package_screenstream() {
    echo "== $screenstream_artifact $version: aar =="
    aar_from "$out/$screenstream_artifact-$version.jar" "$here/AndroidManifest.xml" \
        "$out/$screenstream_artifact-$version.aar"
    sources_jar "$sources" "$out/$screenstream_artifact-$version-sources.jar"
    echo "== $screenstream_artifact $version: javadoc =="
    javadoc_jar "$sources" \
        "$android_jar:$out/$control_artifact-$version.jar" \
        "$out/$screenstream_artifact-$version-javadoc.jar" "$screenstream_artifact"
    write_pom "$screenstream_artifact" \
        "The phone's screen as raw RGBA frames on a local port, for apps and for tests." \
        "$(dependency_on_control)"
    for artifact in "$screenstream_artifact-$version.aar" \
            "$screenstream_artifact-$version.jar" \
            "$screenstream_artifact-$version-sources.jar" \
            "$screenstream_artifact-$version-javadoc.jar" \
            "$screenstream_artifact-$version.pom"; do
        printf 'built %s (%s bytes)\n' "$out/$artifact" "$(wc -c < "$out/$artifact")"
    done
}

if [ "$mode" = apk ]; then
    # --- app -------------------------------------------------------------------
    # The app is a manifest over the library. The control surface is compiled in
    # the same dex, the way a Gradle app's dex is its code plus its AARs.
    mapfile -t control_files < <(find "$control_sources" -name '*.java')
    mapfile -t files < <(find "$sources" -name '*.java')
    if [ -d "$here/app/src" ]; then
        mapfile -t app_files < <(find "$here/app/src" -name '*.java')
        files+=("${app_files[@]}")
    fi
    echo "== screenstream $version: javac (app, library and control surface) =="
    javac -nowarn --release "$java" -classpath "$android_jar" \
        -d "$work/classes" "${control_files[@]}" "${files[@]}"
    jar_from "$work/classes" "$work/app.jar"

    echo "== screenstream $version: d8 =="
    "$d8" --lib "$android_jar" --min-api "$min_sdk" --output "$work/dex" "$work/app.jar"

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

if [ "$mode" = control-surface ]; then
    compile_control
    package_control
    rm -rf "$work"
    exit 0
fi

# --- both libraries ------------------------------------------------------------
compile_control
package_control
compile_screenstream
package_screenstream
rm -rf "$work"
