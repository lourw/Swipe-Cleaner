#!/bin/sh
# Called from the "Compile Kotlin Framework" build phase in iosApp.xcodeproj.
# Builds the shared ComposeApp framework for whatever SDK/arch Xcode is building.
set -e

if [ "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" = "YES" ]; then
    echo "Skipping Gradle: the Kotlin Multiplatform IDE plugin already built the framework."
    exit 0
fi

cd "$SRCROOT/.."

# Xcode doesn't load your shell profile, so find the JDK pinned in mise.toml, or fall back to the system one.
if [ -z "$JAVA_HOME" ]; then
    for mise in /opt/homebrew/bin/mise "$HOME/.local/bin/mise" /usr/local/bin/mise; do
        if [ -x "$mise" ]; then
            JAVA_HOME="$("$mise" where java 2>/dev/null || true)"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME" ]; then
    echo "error: No JDK found. Install one (see mise.toml) or set JAVA_HOME." >&2
    exit 1
fi
export JAVA_HOME

./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
