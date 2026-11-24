#!/usr/bin/env bash
# Post-merge capture smoke test on a real Android runtime.
#
# The emulator-e2e workflow runs this inside a fresh API 35 google_apis x86_64
# AVD. It is also a plain shell script, so the same sequence can be run by hand
# against an attached emulator:
#
#   bash ci/emulator-e2e.sh                 # full capture smoke test
#   bash ci/emulator-e2e.sh --check-device  # only the attached-device preflight
#
# A missing emulator, a refused projection grant or a zero-frame run fails the
# run; none of them is a skip. The assertions are protocol and lifecycle, not
# video quality: an MP4 that ffprobe can read proves the container and the
# finalisation, not a MediaTek encoder or a 120 Hz panel.

set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${EMULATOR_OUT:-$ROOT/out/emulator}"

MODE=run
case "${1:-}" in
    --check-device) MODE=check ;;
    "") ;;
    *)
        echo "emulator e2e: unknown argument: $1" >&2
        exit 2
        ;;
esac

PKG=io.github.mbanucu.screenstream.app
AUTH=content://io.github.mbanucu.screenstream.control
HOST_PORT="${EMULATOR_HOST_PORT:-17200}"
SCALE="${CAPTURE_SCALE:-2}"
DISPLAY_WIDTH="${DISPLAY_WIDTH:-1440}"
DISPLAY_HEIGHT="${DISPLAY_HEIGHT:-3200}"
DISPLAY_DENSITY="${DISPLAY_DENSITY:-560}"
FIRST_FRAME_TIMEOUT_MS="${FIRST_FRAME_TIMEOUT_MS:-60000}"
FRAME_TIMEOUT="${FRAME_TIMEOUT:-30}"
SERIAL=""
CAPTURE_ATTEMPTED=0

die() {
    echo "emulator e2e: $*" >&2
    exit 1
}

log() {
    printf 'emulator e2e: %s\n' "$*" | tee -a "$OUT/run.log"
}

mkdir -p "$OUT"

command -v adb >/dev/null 2>&1 || die "adb is not in PATH"

# The action waits for boot, but adb can still be starting. This is one check,
# not a retry loop: an emulator that is not attached now is a failed run, not a
# green one. A physical phone has a serial like 0123456789ABCDEF, and this gate
# deliberately does not accept it.
adb devices >"$OUT/adb-devices.txt" 2>&1 || die "adb devices failed"
cat "$OUT/adb-devices.txt"
SERIAL="$(awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1; exit }' \
    "$OUT/adb-devices.txt")"
[ -n "$SERIAL" ] \
    || die "no attached emulator-* in the adb device state; a missing emulator fails the run"
log "attached $SERIAL"

if [ "$MODE" = check ]; then
    exit 0
fi

cleanup() {
    local status=$?
    set +e
    if [ -n "$SERIAL" ]; then
        adb -s "$SERIAL" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1
        if [ "$CAPTURE_ATTEMPTED" -eq 1 ]; then
            adb -s "$SERIAL" shell content call --uri "$AUTH" --method stop \
                >>"$OUT/control-stop-cleanup.txt" 2>&1
        fi
        adb -s "$SERIAL" shell appops set --uid "$PKG" PROJECT_MEDIA default \
            >>"$OUT/appops-revoke.txt" 2>&1
        # Clear a package-mode grant an older recipe may have left behind too.
        adb -s "$SERIAL" shell appops set "$PKG" PROJECT_MEDIA default \
            >>"$OUT/appops-revoke.txt" 2>&1
    fi
    if [ "$status" -ne 0 ]; then
        echo "emulator e2e: failed (exit $status); artifacts are under $OUT" >&2
    fi
    exit "$status"
}
trap cleanup EXIT

valid_positive() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        *) [ "$1" -gt 0 ] ;;
    esac
}

valid_positive "$SCALE" || die "CAPTURE_SCALE must be a positive integer, not '$SCALE'"
valid_positive "$DISPLAY_WIDTH" || die "DISPLAY_WIDTH must be a positive integer"
valid_positive "$DISPLAY_HEIGHT" || die "DISPLAY_HEIGHT must be a positive integer"
valid_positive "$DISPLAY_DENSITY" || die "DISPLAY_DENSITY must be a positive integer"

{
    printf 'api='
    adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r'
    printf 'abi='
    adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r'
    adb -s "$SERIAL" shell wm size
    adb -s "$SERIAL" shell wm density
} >"$OUT/device.txt" 2>&1
cat "$OUT/device.txt"

API="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[ "$API" = "35" ] || die "emulator is API '$API', not API 35"
FOUND_SIZE="$(adb -s "$SERIAL" shell wm size | tr -d '\r' \
    | awk -F': ' '/Physical size/ { print $2; exit }')"
FOUND_DENSITY="$(adb -s "$SERIAL" shell wm density | tr -d '\r' \
    | awk -F': ' '/Physical density/ { print $2; exit }')"
EXPECTED_SIZE="${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}"
[ "$FOUND_SIZE" = "$EXPECTED_SIZE" ] \
    || die "display is '${FOUND_SIZE:-unknown}', expected $EXPECTED_SIZE; the profile must be set before boot"
[ "$FOUND_DENSITY" = "$DISPLAY_DENSITY" ] \
    || die "display density is '${FOUND_DENSITY:-unknown}', expected $DISPLAY_DENSITY"
FRAME_WIDTH=$((DISPLAY_WIDTH / SCALE))
FRAME_HEIGHT=$((DISPLAY_HEIGHT / SCALE))
FRAME_SIZE="${FRAME_WIDTH}x${FRAME_HEIGHT}"
log "display $EXPECTED_SIZE at $DISPLAY_DENSITY dpi; capture scale $SCALE is $FRAME_SIZE"

log "building the app"
if ! bash "$ROOT/build.sh" apk >"$OUT/build.txt" 2>&1; then
    cat "$OUT/build.txt" >&2
    die "build.sh apk failed"
fi
[ -s "$ROOT/out/screenstream-app.apk" ] || die "build.sh wrote no out/screenstream-app.apk"

log "installing the app"
if ! adb -s "$SERIAL" install -r "$ROOT/out/screenstream-app.apk" \
        >"$OUT/install.txt" 2>&1; then
    cat "$OUT/install.txt" >&2
    die "adb install failed"
fi
grep -q 'Success' "$OUT/install.txt" \
    || { cat "$OUT/install.txt" >&2; die "adb install did not report Success"; }

# The documented headless route on a test device. A dialog is the human path;
# this gate uses the app-op, verifies it with the provider, and revokes it in
# the EXIT trap. If the platform ignores the op, start answers
# waiting_for_grant and the run fails instead of recording a black video.
log "granting PROJECT_MEDIA for this run"
# project_media is a security app-op, so it is checked by UID. `--uid` sets the
# UID mode; setting only the per-package mode leaves the check at MODE_DEFAULT.
adb -s "$SERIAL" shell appops set --uid "$PKG" PROJECT_MEDIA allow \
    >"$OUT/appops-allow.txt" 2>&1
if ! adb -s "$SERIAL" shell appops get --uid "$PKG" PROJECT_MEDIA \
        >"$OUT/appops-get.txt" 2>&1; then
    cat "$OUT/appops-get.txt" >&2
    die "could not read PROJECT_MEDIA back after setting it"
fi
cat "$OUT/appops-get.txt"
if ! grep -q 'PROJECT_MEDIA: allow' "$OUT/appops-get.txt"; then
    die "PROJECT_MEDIA is not allowed for the app UID; see appops-get.txt"
fi

control_call() {
    local method=$1
    shift
    local reply="$OUT/control-$method.txt"
    printf '%s\n' "+ content call --method $method $*" >>"$OUT/control-requests.txt"
    if ! adb -s "$SERIAL" shell content call --uri "$AUTH" --method "$method" \
            "$@" >"$reply" 2>"$reply.stderr"; then
        cat "$reply" "$reply.stderr" >&2
        die "content call $method failed before it produced a reply"
    fi
    if [ -s "$reply.stderr" ]; then
        cat "$reply.stderr" >>"$OUT/control-stderr.txt"
    fi
    cat "$reply"
}

control_call grant
GRANT_ROUTE="$(python3 "$ROOT/ci/emulator_probe.py" grant \
    --reply "$OUT/control-grant.txt")" \
    || die "the projection grant is not headless; see control-grant.txt"
log "grant route $GRANT_ROUTE"

# start is synchronous: it answers streaming and the port only after the first
# composited frame, so there is no state-file poll and no guessed port.
CAPTURE_ATTEMPTED=1
control_call start --extra port:i:0 --extra encoded_port:i:0 \
    --extra scale:i:"$SCALE" --extra timeout_ms:i:"$FIRST_FRAME_TIMEOUT_MS"
PORT="$(python3 "$ROOT/ci/emulator_probe.py" start \
    --reply "$OUT/control-start.txt" --expect-size "$FRAME_SIZE")" \
    || die "start did not prove a streaming capture; see control-start.txt"
log "streaming on device port $PORT"

adb -s "$SERIAL" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
adb -s "$SERIAL" forward "tcp:$HOST_PORT" "tcp:$PORT" >"$OUT/forward.txt" 2>&1 \
    || { cat "$OUT/forward.txt" >&2; die "adb forward tcp:$HOST_PORT failed"; }

# Record the same capture while the client reads it; the file is small because
# the capture is scaled and only a settings page changes.
control_call record
RECORDING_PATH="$(python3 "$ROOT/ci/emulator_probe.py" record \
    --reply "$OUT/control-record.txt")" \
    || die "record did not open an MP4; see control-record.txt"
log "recording device file $RECORDING_PATH"

log "reading frames after a screen change"
python3 "$ROOT/ci/emulator_probe.py" frames \
    --host 127.0.0.1 --port "$HOST_PORT" --serial "$SERIAL" \
    --out "$OUT" --timeout "$FRAME_TIMEOUT" --limit 5 \
    --expect-size "$FRAME_SIZE" \
    || die "the client did not receive a valid frame after the screen changed"

control_call recording
RECORDING_FILE="$(python3 "$ROOT/ci/emulator_probe.py" recording \
    --reply "$OUT/control-recording.txt")" \
    || die "the recording did not finalise; see control-recording.txt"
log "finalised $RECORDING_FILE"

control_call stop
python3 "$ROOT/ci/emulator_probe.py" stop --reply "$OUT/control-stop.txt" \
    || die "stop did not reach state=stopped; see control-stop.txt"
CAPTURE_ATTEMPTED=0

log "pulling the MP4"
adb -s "$SERIAL" pull "$RECORDING_FILE" "$OUT/recording.mp4" \
    >"$OUT/pull.txt" 2>&1 \
    || { cat "$OUT/pull.txt" >&2; die "adb pull of $RECORDING_FILE failed"; }
[ -s "$OUT/recording.mp4" ] || die "the pulled MP4 is empty"
MP4_BYTES="$(wc -c <"$OUT/recording.mp4" | tr -d ' ')"
log "recording.mp4 is $MP4_BYTES bytes"

if command -v ffprobe >/dev/null 2>&1; then
    if ! ffprobe -v error -select_streams v:0 -count_frames \
            -show_entries stream=codec_name,width,height,nb_read_frames:format=duration \
            -of default=nw=1 "$OUT/recording.mp4" >"$OUT/ffprobe.txt" 2>&1; then
        cat "$OUT/ffprobe.txt" >&2
        die "ffprobe could not read the finalised MP4"
    fi
    cat "$OUT/ffprobe.txt"
    if ! python3 - "$OUT/ffprobe.txt" "$FRAME_SIZE" <<'PY'
import sys

path, expected = sys.argv[1], sys.argv[2]
fields = {}
with open(path, encoding="utf-8") as handle:
    for line in handle:
        key, sep, value = line.strip().partition("=")
        if sep:
            fields[key] = value
try:
    frames = int(fields.get("nb_read_frames", "0"))
except ValueError:
    frames = 0
if frames <= 0:
    raise SystemExit("ffprobe counted no video frames")
if not fields.get("codec_name"):
    raise SystemExit("ffprobe named no video codec")
size = "%sx%s" % (fields.get("width", "?"), fields.get("height", "?"))
if size != expected:
    raise SystemExit("ffprobe reports %s, expected %s" % (size, expected))
duration = fields.get("duration", "")
if duration not in ("", "N/A"):
    try:
        if float(duration) <= 0:
            raise SystemExit("ffprobe reports a zero-length video")
    except ValueError:
        raise SystemExit("ffprobe reports an unreadable duration %r" % duration)
print("ffprobe: %s, %d frame(s), %s, %s s"
      % (fields["codec_name"], frames, size, duration or "unknown"))
PY
    then
        die "ffprobe did not describe a video track with frames at the capture size"
    fi
else
    log "ffprobe is not installed; the MP4 was checked for a non-zero size only"
fi

log "ok"
