#!/bin/sh
# Xcode Cloud runs this after cloning. Its machines have no JDK, and environment
# variables set here don't reach the build, so install the JDK pinned in mise.toml
# where scripts/xcode-build-kotlin.sh can find it through mise.
set -e

brew install mise

cd "$CI_PRIMARY_REPOSITORY_PATH"
mise trust mise.toml
mise install java

# Signing stays out of git (AGENTS.md hard rule 8). Set SWIPE_TEAM_ID and
# SWIPE_BUNDLE_ID as Xcode Cloud workflow environment variables.
if [ -n "$SWIPE_TEAM_ID" ] && [ -n "$SWIPE_BUNDLE_ID" ]; then
    printf 'TEAM_ID = %s\nBUNDLE_ID = %s\n' "$SWIPE_TEAM_ID" "$SWIPE_BUNDLE_ID" \
        > iosApp/Configuration/Local.xcconfig
fi
