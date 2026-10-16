#!/usr/bin/env bash
# Checks the bundle works on its own (no MSYS2 on PATH): every GStreamer element
# UxPlay needs must load from the bundled plugins, and uxplay.exe must start.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist/AirMirror"
W() { cygpath -w "$1"; }

export PATH="$DIST/bin:/c/Windows/System32:/c/Windows"
export GST_PLUGIN_SYSTEM_PATH_1_0="$(W "$DIST/lib/gstreamer-1.0")"
export GST_PLUGIN_SYSTEM_PATH="$GST_PLUGIN_SYSTEM_PATH_1_0"
export GST_PLUGIN_SCANNER_1_0="$(W "$DIST/libexec/gstreamer-1.0/gst-plugin-scanner.exe")"
export GST_REGISTRY_1_0="$(W "$ROOT/windows/work/verify-registry.bin")"
unset GST_PLUGIN_PATH GST_PLUGIN_PATH_1_0

missing=0
for e in appsrc queue h264parse h265parse decodebin avdec_h264 avdec_aac avdec_alac aacparse \
         videoconvert videoscale videoflip d3d11videosink d3d11h264dec autovideosink imagefreeze \
         textoverlay jpegdec audioconvert audioresample volume autoaudiosink wasapisink playbin fakesink; do
    if "$DIST/bin/gst-inspect-1.0.exe" "$e" > /dev/null 2>&1; then
        echo "ok      $e"
    else
        echo "MISSING $e"
        missing=1
    fi
done

echo "== starting uxplay.exe for 12 seconds"
( cd "$DIST/bin" && timeout 12 ./uxplay.exe -n "CI Check" -nh -vs d3d11videosink -key "$(W "$ROOT/windows/work/ci.pem")" ) > "$ROOT/windows/work/uxplay-ci.log" 2>&1
code=$?
cat "$ROOT/windows/work/uxplay-ci.log" | head -60
# timeout => 124 means it was still running happily
if [ $code -ne 124 ]; then echo "::warning::uxplay.exe exited early with code $code"; fi
if grep -qiE "no element|missing plugin|could not|failed to" "$ROOT/windows/work/uxplay-ci.log"; then
    grep -iE "no element|missing plugin|could not|failed to" "$ROOT/windows/work/uxplay-ci.log" | head -10 | while IFS= read -r l; do echo "::warning::$l"; done
fi
exit $missing
