package dev.swipe.platform

import dev.swipe.data.Haptics
import dev.swipe.data.SettingsStore
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * UIKit feedback generators behind the [Haptics] interface (§5.6, FR-29).
 * Every entry point checks [SettingsStore.haptics] first, so flipping the
 * setting off silences the app immediately with no wiring changes (FR-29).
 *
 * The generators are created lazily and reused; UIKit recycles their engine
 * handles internally. All four methods must be called from the main thread —
 * true for every caller today, the UI commit paths (WP-2.3) and the delete
 * flow (WP-3.3) all run in compositions or on the main dispatcher.
 */
class IosHaptics(
    private val settings: SettingsStore,
) : Haptics {
    private val selectionGenerator by lazy { UISelectionFeedbackGenerator() }
    private val lightGenerator by lazy { UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight) }
    private val mediumGenerator by lazy { UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium) }
    private val notificationGenerator by lazy { UINotificationFeedbackGenerator() }

    /** Selection tick as a drag crosses a commit threshold (§5.6). */
    override fun selection() {
        if (!settings.haptics.value) return
        selectionGenerator.selectionChanged()
        selectionGenerator.prepare()
    }

    /** Light impact when a Keep or Skip commits (§5.6). */
    override fun impactLight() {
        if (!settings.haptics.value) return
        lightGenerator.impactOccurred()
        lightGenerator.prepare()
    }

    /** Medium impact when a Delete commits (§5.6). */
    override fun impactMedium() {
        if (!settings.haptics.value) return
        mediumGenerator.impactOccurred()
        mediumGenerator.prepare()
    }

    /** Success notification after a confirmed batch delete (FR-26, §6.7). */
    override fun notificationSuccess() {
        if (!settings.haptics.value) return
        notificationGenerator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    }
}
