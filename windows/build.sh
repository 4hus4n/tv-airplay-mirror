#!/usr/bin/env bash
# Builds AirMirror for Windows inside an MSYS2 UCRT64 shell.
# Output: dist/AirMirror  (portable folder used by the installer)
set -euo pipefail

UXPLAY_REPO=https://github.com/FDH2/UxPlay
UXPLAY_COMMIT=4e95a9ec013c52d8555d88dbc55abe346e94d56e   # same UxPlay version as the Android app

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/windows/work"
DIST="$ROOT/dist/AirMirror"
PFX=/ucrt64
rm -rf "$WORK" "$DIST"
mkdir -p "$WORK" "$DIST/bin" "$DIST/lib/gstreamer-1.0" "$DIST/libexec/gstreamer-1.0"

echo "== building UxPlay"
git clone -q "$UXPLAY_REPO" "$WORK/uxplay"
git -C "$WORK/uxplay" checkout -q "$UXPLAY_COMMIT"
cmake -S "$WORK/uxplay" -B "$WORK/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DNO_MARCH_NATIVE=ON
ninja -C "$WORK/build"
cp "$WORK/build/uxplay.exe" "$DIST/bin/"

echo "== building tray launcher"
windres "$ROOT/windows/launcher/airmirror.rc" -O coff -o "$WORK/airmirror_res.o" \
        --include-dir "$ROOT/windows/launcher"
gcc -O2 -municode -mwindows -o "$DIST/AirMirror.exe" "$ROOT/windows/launcher/airmirror.c" \
    "$WORK/airmirror_res.o" -static -static-libgcc -lshell32 -ladvapi32 -luser32 -lole32

echo "== bundling GStreamer"
cp "$PFX/bin/gst-inspect-1.0.exe" "$DIST/bin/"
cp "$PFX/libexec/gstreamer-1.0/gst-plugin-scanner.exe" "$DIST/libexec/gstreamer-1.0/"

PLUGINS="coreelements app typefindfunctions playback autodetect
 videoparsersbad audioparsers libav d3d11 d3d12 dxva
 videoconvertscale videoconvert videoscale videofilter imagefreeze pango
 jpeg png level audioconvert audioresample volume wasapi wasapi2 directsound
 debugutilsbad"
for p in $PLUGINS; do
    f="$PFX/lib/gstreamer-1.0/libgst$p.dll"
    if [ -f "$f" ]; then cp "$f" "$DIST/lib/gstreamer-1.0/"; else echo "   (plugin $p not present, skipped)"; fi
done

# copy every DLL the executables and plugins depend on, until nothing new appears
while :; do
    added=0
    while IFS= read -r dll; do
        name="$(basename "$dll")"
        if [ ! -f "$DIST/bin/$name" ]; then
            cp "$dll" "$DIST/bin/"
            added=1
        fi
    done < <(find "$DIST" -iname '*.exe' -o -iname '*.dll' | grep -v AirMirror.exe | while read -r f; do
                 ldd "$f" 2>/dev/null | awk '{print $3}' | grep -i "^$PFX/bin/" || true
             done | sort -u)
    [ $added -eq 0 ] && break
done

cp "$ROOT/LICENSE" "$DIST/LICENSE.txt"
cp "$ROOT/windows/README-Windows.txt" "$DIST/README.txt"
echo "== bundle size: $(du -sh "$DIST" | cut -f1)"
