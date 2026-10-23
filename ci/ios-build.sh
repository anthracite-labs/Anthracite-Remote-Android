#!/bin/sh
# Builds and tests the iOS client on a macOS runner.
#
# Emits ::error annotations for compiler failures, because workflow logs are not
# always retrievable from the API and an annotation is not truncated away.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/ios"

annotate_failures() {
    file=$1
    grep -E "(error:|xcodebuild: error:)" "$file" | head -n 10 | while IFS= read -r line; do
        echo "::error title=ios-build::$line"
    done
}

echo "== swift test (AnthraciteKit) =="
if ! swift test --package-path . >/tmp/swift-test.log 2>&1; then
    annotate_failures /tmp/swift-test.log
    tail -n 40 /tmp/swift-test.log
    exit 1
fi
tail -n 5 /tmp/swift-test.log
echo "::notice title=ios-swift-tests::AnthraciteKit unit tests passed"

# The macOS image's simulator set changes between releases, and a destination
# pinned by device name then fails the whole job with "Unable to find a device
# matching the provided destination specifier" — which says nothing about the
# code. Pick an available iPhone from the image instead.
simulator_destination() {
    python3 -c '
import json, subprocess, sys
devices = json.loads(subprocess.run(
    ["xcrun", "simctl", "list", "devices", "available", "--json"],
    capture_output=True, text=True, check=True).stdout)["devices"]
for runtime in sorted(devices, reverse=True):
    for device in devices[runtime]:
        if device.get("isAvailable") and device.get("name", "").startswith("iPhone"):
            print("id=" + device["udid"])
            sys.exit(0)
sys.exit("no available iPhone simulator on this runner")
'
}

DESTINATION=$(simulator_destination) || exit 1
echo "::notice title=ios-simulator::using $DESTINATION"

echo "== xcodegen =="
xcodegen generate --spec project.yml

echo "== build for the simulator =="
if ! xcodebuild -project AnthraciteRemote.xcodeproj -scheme AnthraciteRemote \
    -destination "$DESTINATION" \
    -derivedDataPath /tmp/dd build >/tmp/xcodebuild.log 2>&1; then
    annotate_failures /tmp/xcodebuild.log
    tail -n 60 /tmp/xcodebuild.log
    exit 1
fi
echo "::notice title=ios-build::the app built for iOS Simulator"

echo "== UI tests on the simulator =="
xcodebuild test -project AnthraciteRemote.xcodeproj -scheme AnthraciteRemote \
    -destination "$DESTINATION" \
    -derivedDataPath /tmp/dd \
    -resultBundlePath /tmp/ios-uitests.xcresult \
    >/tmp/xcodebuild-test.log 2>&1 || {
        grep -E "(Test Case .* failed|error:)" /tmp/xcodebuild-test.log | head -n 10 | while IFS= read -r line; do
            echo "::error title=ios-uitests::$line"
        done
        tail -n 60 /tmp/xcodebuild-test.log
        exit 1
    }
grep -E "Test Suite .* (passed|failed)" /tmp/xcodebuild-test.log | tail -n 3
echo "::notice title=ios-uitests::simulator UI smoke tests passed"
