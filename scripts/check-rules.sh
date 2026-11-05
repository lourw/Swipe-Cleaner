#!/usr/bin/env bash
# Grep guards for the AGENTS.md hard rules (REQUIREMENTS §8.3).
# Prints every violation as file:line and exits non-zero if there are any.
set -uo pipefail
cd "$(dirname "$0")/.."

SRC=composeApp/src

# report runs at the end of a pipeline, i.e. in a subshell, so failures are recorded in a file.
FAILED="$(mktemp)"
trap 'rm -f "$FAILED"' EXIT

# report <rule>: reads matches on stdin; prints and records a failure if there are any.
report() {
    local matches
    matches="$(cat)"
    if [ -n "$matches" ]; then
        echo "✗ $1"
        printf '%s\n' "$matches" | sed 's/^/    /'
        echo x >>"$FAILED"
    fi
}

# main_kt [find args...]: NUL-separated .kt files in production source sets (src/*Main).
main_kt() {
    find "$SRC" -path "$SRC/*Main/*" -name '*.kt' -not -path '*/build/*' "$@" -print0 2>/dev/null
}

# grep_files <regex>: grep NUL-separated files from stdin. /dev/null keeps grep from reading
# stdin when there are no files and forces file names in the output.
grep_files() {
    xargs -0 grep -nE "$1" /dev/null 2>/dev/null
}

# 1. Layering (§6.1): commonMain never imports platform.*
main_kt -path "$SRC/commonMain/*" | grep_files '^import platform\.' |
    report "commonMain must not import platform.* (REQUIREMENTS §6.1)"

# 2. Design tokens (§5.2): no hex colors outside ui/theme/
main_kt -not -path '*/ui/theme/*' | grep_files 'Color\(0x' |
    report "Hex colors belong in ui/theme/ only (REQUIREMENTS §5.2)"

# 3. Design tokens and gesture spec (§5.5, §5.6): no raw dp/sp numbers outside ui/theme/ and SwipeSpec.kt.
#    0.dp is allowed.
main_kt -not -path '*/ui/theme/*' -not -name 'SwipeSpec.kt' |
    grep_files '(^|[^A-Za-z0-9_.])(([1-9][0-9]*(\.[0-9]+)?|0\.[0-9]+)\.(dp|sp)|0\.sp)([^A-Za-z0-9_]|$)' |
    report "Raw dp/sp values belong in ui/theme/ or SwipeSpec.kt (REQUIREMENTS §5.5, §5.6)"

# 4. Deletion safety (NFR-5): PhotoKit's delete APIs are only touched by PhotoKitMediaLibrary.
main_kt -not -name 'PhotoKitMediaLibrary.kt' | grep_files '(deleteAssets|performChanges)' |
    report "Only PhotoKitMediaLibrary.kt may call deleteAssets/performChanges (NFR-5)"

# 5. Deletion safety (NFR-5): only CommitDeletionsUseCase calls MediaLibrary.delete.
main_kt -path "$SRC/commonMain/*" -not -name 'CommitDeletionsUseCase.kt' -not -name 'MediaLibrary.kt' |
    xargs -0 grep -lE '\bMediaLibrary\b' /dev/null 2>/dev/null | grep -v '^/dev/null$' | tr '\n' '\0' |
    grep_files '\.delete\(' |
    report "Only CommitDeletionsUseCase may call MediaLibrary.delete (NFR-5)"

# 6. Privacy (NFR-6): no networking or analytics code.
NETWORK='(^import (io\.ktor|okhttp3|platform\.Foundation\.NSURLSession|platform\.Foundation\.NSURLConnection))'
ANALYTICS='(firebase|crashlytics|sentry|amplitude|mixpanel|segment\.analytics|bugsnag|datadog|appcenter)'
{
    find "$SRC" -name '*.kt' -not -path '*/build/*' -print0 | grep_files "$NETWORK|^import .*$ANALYTICS"
    grep -niE "$ANALYTICS" /dev/null gradle/libs.versions.toml composeApp/build.gradle.kts 2>/dev/null
} | report "No network or analytics code (NFR-6)"

# 7. Signing stays local (hard rule 8): no team ID or personal bundle ID in files git would commit.
#    Covers tracked files and untracked files that aren't ignored, so it works before the first commit.
git ls-files -z --cached --others --exclude-standard -- iosApp |
    xargs -0 grep -nE '(DEVELOPMENT_TEAM *= *"?[A-Z0-9]{10}|^ *TEAM_ID *= *[A-Z0-9]+|PRODUCT_BUNDLE_IDENTIFIER *= *"?[a-z])' /dev/null 2>/dev/null |
    grep -v 'Local.xcconfig.example' |
    report "Signing values belong in iosApp/Configuration/Local.xcconfig (AGENTS.md rule 8)"

git ls-files -z --cached --others --exclude-standard -- iosApp/Configuration/Config.xcconfig |
    xargs -0 grep -nE '^ *BUNDLE_ID *=' /dev/null 2>/dev/null | grep -vE '= *dev\.swipe\.cleaner *$' |
    report "Config.xcconfig must keep the placeholder BUNDLE_ID (AGENTS.md rule 8)"

# 8. One expect/actual only (AGENTS.md rule 5): VideoPlayer is the sanctioned exception.
main_kt -not -name 'VideoPlayer.kt' -not -name 'VideoPlayerView.kt' |
    grep_files '^(public |internal |private )?(expect|actual) ' |
    report "Only VideoPlayer.kt/VideoPlayerView.kt may declare expect/actual (AGENTS.md rule 5)"

# 9. Safe areas (§5.1 principle 5, WP-6.4): every full-screen surface keeps its inset
#    modifiers. The headless CMP test window reports zero insets, so SafeAreaBoundsTest
#    cannot pin a removed .statusBarsPadding()/.navigationBarsPadding() — this static
#    guard is the regression net (REQUIREMENTS §8.3).
#    The 13 surfaces: Home, Albums, Months, Onboarding, Denied, Queue, QueueItemViewer,
#    Settings, Summary, DeleteSuccess and the shared EmptyState clear both bars; the
#    deck splits its insets (TopBar clears only the status bar inside its scrim,
#    SwipeScreen and ActionBar only the Home Indicator); the debug gallery's first
#    heading clears the status bar. Allowlist: card-interior components (CardMetadata,
#    DeckMotion, VideoScrubber) ride inside the already-inset card, ScreenHeader takes
#    its insets from the screens hosting it, and CommitLatencyTrace is not UI.
safe_area_surfaces() {
    find "$SRC/commonMain/kotlin/dev/swipe/ui/screens" -name '*.kt' -print0
    printf '%s\0' "$SRC/commonMain/kotlin/dev/swipe/ui/components/DebugComponentGallery.kt"
}
safe_area_violations() {
    local file need modifier
    while IFS= read -r -d '' file; do
        case "$(basename "$file")" in
            CardMetadata.kt | DeckMotion.kt | VideoScrubber.kt | ScreenHeader.kt | CommitLatencyTrace.kt)
                continue ;;
            TopBar.kt | DebugComponentGallery.kt) need='statusBarsPadding' ;;
            SwipeScreen.kt | ActionBar.kt) need='navigationBarsPadding' ;;
            *) need='statusBarsPadding navigationBarsPadding' ;;
        esac
        for modifier in $need; do
            # Strip line comments first: a commented-out modifier still contains
            # the call text, and commenting one out is exactly this guard's
            # regression (SafeAreaBoundsTest cannot see the insets at all).
            sed 's://.*::' "$file" | grep -q "\.$modifier()" ||
                echo "$file: removed .$modifier()"
        done
    done < <(safe_area_surfaces)
}
safe_area_violations |
    report "Full-screen surfaces must keep their safe-area inset modifiers (REQUIREMENTS §5.1 principle 5)"

if [ -s "$FAILED" ]; then
    echo "check-rules: $(wc -l <"$FAILED" | tr -d ' ') rule(s) violated"
    exit 1
fi
echo "✓ check-rules: all hard-rule guards pass"
