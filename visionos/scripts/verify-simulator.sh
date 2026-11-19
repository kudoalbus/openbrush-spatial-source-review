#!/bin/bash
# Source-review acceptance uses only the included synthetic JSON stroke.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
DEVICE=${1:?Pass an isolated visionOS simulator UDID}
BUNDLE=com.kudoalbus.openbrush.spatial
OUT="$ROOT/visionos/.scratch/acceptance"
DERIVED="$ROOT/visionos/.scratch/SimulatorBuild"
mkdir -p "$OUT"
xcrun simctl bootstatus "$DEVICE" -b
xcodebuild -project visionos/OpenBrushSpatial.xcodeproj -scheme OpenBrushSpatial \
    -configuration Debug -sdk xrsimulator -destination "platform=visionOS Simulator,id=$DEVICE" \
    -derivedDataPath "$DERIVED" CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=YES build > "$OUT/build.log" 2>&1
xcrun simctl install "$DEVICE" "$DERIVED/Build/Products/Debug-xrsimulator/OpenBrushSpatial.app"
xcrun simctl terminate "$DEVICE" "$BUNDLE" 2>/dev/null || true
CONTAINER=$(xcrun simctl get_app_container "$DEVICE" "$BUNDLE" data)
REPORT="$CONTAINER/Documents/acceptance-report.json"
rm -f "$REPORT"
xcrun simctl launch "$DEVICE" "$BUNDLE" --acceptance
for attempt in {1..60}; do
    if [ -f "$REPORT" ]; then break; fi
    sleep 1
done
python3 - "$REPORT" <<'PY'
import json
from pathlib import Path
import sys
report = json.loads(Path(sys.argv[1]).read_text())
assert report['passed'] and report['renderedParts'] == 6, report
print('Simulator document edit/save/reopen acceptance passed')
PY
cp "$REPORT" "$OUT/report.json"
cp "$CONTAINER/Documents/acceptance-workspace-v3.json" "$OUT/saved-v3.json"
python3 visionos/scripts/verify-interop.py "$OUT/saved-v3.json"
xcrun simctl terminate "$DEVICE" "$BUNDLE"
xcrun simctl launch "$DEVICE" "$BUNDLE" --reopen-acceptance
