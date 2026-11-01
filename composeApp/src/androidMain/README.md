# androidMain

Stub source set. The app targets iOS for v1 (REQUIREMENTS §2). This directory
exists so the structure stays honest: when Android work starts, platform
implementations (MediaLibrary, MediaLoader, SettingsStore, Haptics,
VideoPlayer) are added here and bound in a `AndroidModule` Koin module,
mirroring `iosMain/di/IosModule.kt`.

No `androidTarget()` is configured yet because the Android SDK is not a
prerequisite for this project.
