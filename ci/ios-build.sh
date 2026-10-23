#!/bin/sh
# Builds and tests the iOS client on a macOS runner.
#
# Emits ::error annotations for compiler failures, because workflow logs are not
# always retrievable from the API and an annotation is not truncated away.
#
# The dependency graph is not resolved here: `Package.resolved` is committed and
# the build must use exactly those versions, or stop. A build that silently
# resolves newer packages is reproducible only by accident.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/ios"

annotate_failures() {
    file=$1
    # Compiler and test failures, plus the runtime messages a crash leaves behind:
    # a fatal error's text is not part of an "error:" line, and the workflow log is
    # not always retrievable.
    grep -E "(error:|xcodebuild: error:|Fatal error:|SWIFT TASK CONTINUATION MISUSE|signal code)" "$file" |
        head -n 12 | while IFS= read -r line; do
            echo "::error title=ios-build::$line"
        done
}

# ------------------------------------------------------------------ resolutions
if [ ! -f Package.resolved ]; then
    echo "::error title=ios-resolutions::ios/Package.resolved is not committed; the build would take whatever versions are newest today."
    exit 1
fi

echo "== swift package resolve (committed resolutions only) =="
if ! swift package resolve --only-use-versions-from-resolved-file >/tmp/resolve.log 2>&1; then
    annotate_failures /tmp/resolve.log
    tail -n 40 /tmp/resolve.log
    exit 1
fi
# The resolve above must not have needed to change anything: a diff here means the
# committed file does not describe a resolvable graph with this toolchain.
if ! git -C "$ROOT" diff --quiet -- ios/Package.resolved; then
    echo "::error title=ios-resolutions::swift package resolve changed ios/Package.resolved; the committed versions are not the ones this toolchain resolves."
    git -C "$ROOT" --no-pager diff -- ios/Package.resolved
    exit 1
fi
echo "::notice title=ios-resolutions::the committed Package.resolved graph resolved unchanged"

echo "== swift test (AnthraciteKit) =="
if ! swift test --only-use-versions-from-resolved-file --package-path . >/tmp/swift-test.log 2>&1; then
    annotate_failures /tmp/swift-test.log
    tail -n 40 /tmp/swift-test.log
    exit 1
fi
tail -n 5 /tmp/swift-test.log
echo "::notice title=ios-swift-tests::AnthraciteKit unit tests passed"

# The macOS image's simulator set changes between releases, and a destination
# pinned by device name then fails the whole job with "Unable to find a device
# matching the provided destination specifier" — which says nothing about the
# code. Pick an available iPhone from the image instead, and report which one was
# used: a passing run against an unnamed simulator is not evidence anyone can
# repeat.
simulator_destination() {
    python3 -c '
import json, subprocess, sys
devices = json.loads(subprocess.run(
    ["xcrun", "simctl", "list", "devices", "available", "--json"],
    capture_output=True, text=True, check=True).stdout)["devices"]
for runtime in sorted(devices, reverse=True):
    for device in devices[runtime]:
        if device.get("isAvailable") and device.get("name", "").startswith("iPhone"):
            print("%s|%s|%s" % ("id=" + device["udid"], device.get("name", ""), runtime))
            sys.exit(0)
sys.exit("no available iPhone simulator on this runner")
'
}

SIMULATOR_INFO=$(simulator_destination) || exit 1
DESTINATION=${SIMULATOR_INFO%%|*}
REST=${SIMULATOR_INFO#*|}
SIMULATOR_NAME=${REST%%|*}
SIMULATOR_RUNTIME=${REST#*|}
echo "::notice title=ios-simulator::using $DESTINATION on $SIMULATOR_NAME ($SIMULATOR_RUNTIME)"

echo "== xcodegen =="
if ! xcodegen --version >/tmp/xcodegen-version.log 2>&1; then
    echo "::error title=ios-toolchain::xcodegen is not installed; the project cannot be generated."
    exit 1
fi
XCODEGEN_VERSION=$(head -n 1 /tmp/xcodegen-version.log)
xcodegen generate --spec project.yml

# ---------------------------------------------------------------- build evidence
SWIFT_VERSION=$(swift --version 2>&1 | head -n 1)
XCODE_VERSION=$(xcodebuild -version 2>&1 | tr '\n' ' ')
{
    echo "ios build evidence"
    echo "swift: $SWIFT_VERSION"
    echo "xcode: $XCODE_VERSION"
    echo "xcodegen: $XCODEGEN_VERSION"
    echo "simulator: $SIMULATOR_NAME ($SIMULATOR_RUNTIME) $DESTINATION"
    echo "resolved packages:"
    swift package show-dependencies --format json 2>/dev/null |
        python3 -c '
import json, sys
def walk(node, depth):
    name = node.get("name") or node.get("identity")
    version = node.get("version")
    revision = (node.get("revision") or "")[:12]
    print("%s%s %s %s" % ("  " * depth, name, version or "-", revision))
    for child in node.get("dependencies", []):
        walk(child, depth + 1)
try:
    walk(json.load(sys.stdin), 0)
except Exception as error:
    print("  (dependency graph could not be printed: %s)" % error)
'
} >/tmp/ios-build-evidence.txt 2>&1
cat /tmp/ios-build-evidence.txt
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### iOS build evidence"
        echo '```'
        cat /tmp/ios-build-evidence.txt
        echo '```'
    } >>"$GITHUB_STEP_SUMMARY"
fi

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
