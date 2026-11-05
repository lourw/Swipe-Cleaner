#!/usr/bin/env bash
# Definition of done (REQUIREMENTS §8.4). Stops at the first failure.
# Usage: scripts/validate.sh [--full]
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"

# Toolchain: JDK + Xcode via mise when the shell has none.
if [ -z "${JAVA_HOME:-}" ] && command -v mise >/dev/null 2>&1; then
    JAVA_HOME="$(mise where java 2>/dev/null || true)"
    [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi
if [ -z "${DEVELOPER_DIR:-}" ] && [ -d /Applications/Xcode.app/Contents/Developer ]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

FULL=0
[ "${1:-}" = "--full" ] && FULL=1

step() {
    local name="$1"
    shift
    echo
    echo "==> $name"
    if ! "$@"; then
        echo
        echo "VALIDATION FAILED: $name" >&2
        exit 1
    fi
}

step "1/6 Format (ktlintCheck)" ./gradlew ktlintCheck
step "2/6 Static analysis (detekt)" ./gradlew detekt
step "3/6 Hard-rule guards" scripts/check-rules.sh
step "4/6 Compile" ./gradlew :composeApp:compileKotlinIosSimulatorArm64
step "5/6 Schema migrations" ./gradlew :composeApp:verifySqlDelightMigration
step "6/6 Tests" ./gradlew :composeApp:iosSimulatorArm64Test

if [ "$FULL" -eq 1 ]; then
    # Both Xcode configurations must compile: the Release build caught the
    # missing-argument bug WP-6.4 shipped with, which the Debug-only step
    # never sees (Kotlin default parameters are invisible to Swift).
    step "Full: app build, Debug (xcodebuild)" \
        xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
        -destination 'generic/platform=iOS Simulator' -derivedDataPath build/xcode -quiet -configuration Debug build
    step "Full: app build, Release (xcodebuild)" \
        xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
        -destination 'generic/platform=iOS Simulator' -derivedDataPath build/xcode -quiet -configuration Release build
fi

echo
echo "VALIDATION PASSED"
