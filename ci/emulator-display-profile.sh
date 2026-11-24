#!/usr/bin/env bash
# Sets the AVD's display before the emulator boots.
#
# `wm size` / `wm density` during a run would change the surface under an
# already-started capture, which is not the test. This script runs as the
# emulator action's `pre-emulator-launch-script`: the AVD exists, the emulator
# has not started, and the values below become the logical display the app sees.
#
#   DISPLAY_WIDTH=1440 DISPLAY_HEIGHT=3200 DISPLAY_DENSITY=560 \
#       bash ci/emulator-display-profile.sh screenstream-api35
#
# The target phone is 1440x3200 at 560 dpi, which is 411x914 dp. The emulator
# matches the logical size; it does not match the SoC, the encoder or 120 Hz.

set -Eeuo pipefail

AVD_NAME="${AVD_NAME:-${1:-screenstream-api35}}"
WIDTH="${DISPLAY_WIDTH:-1440}"
HEIGHT="${DISPLAY_HEIGHT:-3200}"
DENSITY="${DISPLAY_DENSITY:-560}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
CONFIG="$AVD_HOME/$AVD_NAME.avd/config.ini"

die() {
    echo "emulator display: $*" >&2
    exit 1
}

[ -f "$CONFIG" ] || die "no AVD config at $CONFIG (ANDROID_AVD_HOME=$AVD_HOME)"
command -v python3 >/dev/null 2>&1 || die "python3 is not in PATH"

# A dynamic skin with skin.path=_no_skin is how avdmanager represents a device
# profile whose screen is set by hw.lcd.*. Keep that shape; only the numbers
# change, so the emulator and the device frame agree on the resolution.
python3 - "$CONFIG" "$WIDTH" "$HEIGHT" "$DENSITY" <<'PY'
import re
import sys

path, width, height, density = sys.argv[1:]
wanted = {
    "hw.lcd.width": width,
    "hw.lcd.height": height,
    "hw.lcd.density": density,
    "skin.dynamic": "yes",
    "skin.name": "%sx%s" % (width, height),
    "skin.path": "_no_skin",
}
lines = open(path, encoding="utf-8").read().splitlines()
seen = set()
out = []
for line in lines:
    match = re.match(r"\s*([^=]+?)\s*=", line)
    key = match.group(1) if match else None
    if key in wanted:
        out.append("%s=%s" % (key, wanted[key]))
        seen.add(key)
    else:
        out.append(line)
for key, value in wanted.items():
    if key not in seen:
        out.append("%s=%s" % (key, value))
open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")
PY

for expected in "hw.lcd.width=$WIDTH" "hw.lcd.height=$HEIGHT" \
        "hw.lcd.density=$DENSITY" "skin.dynamic=yes" \
        "skin.name=${WIDTH}x${HEIGHT}" "skin.path=_no_skin"; do
    grep -qx "$expected" "$CONFIG" \
        || die "$CONFIG does not contain $expected after it was written"
done

echo "emulator display: $AVD_NAME set to ${WIDTH}x${HEIGHT} at ${DENSITY} dpi"
