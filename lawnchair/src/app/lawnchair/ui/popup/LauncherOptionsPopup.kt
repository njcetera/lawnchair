package app.lawnchair.ui.popup

import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.lawnchair.preferences2.PreferenceManager2.Companion.getInstance
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.logging.StatsLogManager.LauncherEvent
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.views.OptionsPopupView.OptionItem
import com.patrykmichalik.opto.core.setBlocking

object LauncherOptionsPopup {
    val DEFAULT_ORDER = listOf(
        LauncherOptionPopupItem("carousel", true),
        LauncherOptionPopupItem("lock", false),
        LauncherOptionPopupItem("edit_mode", false),
        LauncherOptionPopupItem("wallpaper", true),
        LauncherOptionPopupItem("widgets", true),
        LauncherOptionPopupItem("all_apps", true),
        LauncherOptionPopupItem("home_settings", true),
        LauncherOptionPopupItem("sys_settings", false),
        LauncherOptionPopupItem("default_page", false),
    )

    fun restoreMissingPopupOptions(
        launcher: Launcher,
    ) {
        val prefs2 = getInstance(launcher)

        val currentOrder = prefs2.launcherPopupOrder.firstCached()
        val currentOptions = currentOrder.toLauncherOptions()

        // check for missing items in current options; if so, add them
        val missingItems = DEFAULT_ORDER.filter { defaultItem ->
            defaultItem.identifier !in currentOptions.map { it.identifier }
        }

        if (missingItems.isNotEmpty()) {
            prefs2.launcherPopupOrder.setBlocking(
                (currentOptions + missingItems).toOptionOrderString(),
            )
        }
    }

    /**
     * Returns the list of supported actions
     */
    fun getLauncherOptions(
        launcher: Launcher?,
        onLockToggle: (View) -> Boolean,
        onStartSystemSettings: (View) -> Boolean,
        onStartEditMode: (View) -> Boolean,
        onStartAllApps: (View) -> Boolean,
        onStartWallpaperPicker: (View) -> Boolean,
        onStartWidgetsMenu: (View) -> Boolean,
        onStartHomeSettings: (View) -> Boolean,
    ): ArrayList<OptionItem> {
        val prefs2 = getInstance(launcher!!)
        val lockHomeScreen = prefs2.lockHomeScreen.firstCached()
        val optionOrder = prefs2
            .launcherPopupOrder.firstCached().toLauncherOptions()

        val wallpaperResString =
            if (Utilities.existsStyleWallpapers(launcher)) R.string.styles_wallpaper_button_text else R.string.wallpapers
        val wallpaperResDrawable =
            if (Utilities.existsStyleWallpapers(launcher)) R.drawable.ic_palette else R.drawable.ic_wallpaper

        val optionsList = mapOf(
            "lock" to OptionItem(
                launcher,
                if (lockHomeScreen) R.string.home_screen_unlock else R.string.home_screen_lock,
                if (lockHomeScreen) R.drawable.ic_lock_open else R.drawable.ic_lock,
                LauncherEvent.IGNORE,
                onLockToggle,
            ),
            "sys_settings" to OptionItem(
                launcher,
                R.string.system_settings,
                R.drawable.ic_setting,
                LauncherEvent.IGNORE,
                onStartSystemSettings,
            ),
            // AresLauncher (owner 2026-09-12): "Customize" enters the ARES edit mode, not the stock
            // one. `onStartEditMode` goes to LauncherState.EDIT_MODE (Launcher3's home gardening),
            // which is not the mode this fork actually shows -- that is
            // AresHomeListView.enterEditMode(), the same call the item long-press raises, and the
            // one the edit carousel hangs off. Posted so the popup's close animation runs first.
            // enterEditMode()'s mid-gesture guard is safe from a tap: enteredEditModeDuringGesture
            // is cleared at the next ACTION_DOWN before anything reads it.
            "edit_mode" to OptionItem(
                launcher,
                R.string.ares_customize_home,
                R.drawable.enter_home_gardening_icon,
                LauncherEvent.LAUNCHER_SETTINGS_BUTTON_TAP_OR_LONGPRESS,
                ::enterAresEditMode,
            ),
            "all_apps" to OptionItem(
                launcher,
                R.string.all_apps_button_label,
                R.drawable.ic_apps,
                LauncherEvent.LAUNCHER_ALL_APPS_TAP_OR_LONGPRESS,
                onStartAllApps,
            ),
            "wallpaper" to OptionItem(
                launcher,
                wallpaperResString,
                wallpaperResDrawable,
                LauncherEvent.IGNORE,
                onStartWallpaperPicker,
            ),
            "widgets" to OptionItem(
                launcher,
                R.string.widget_button_text,
                SystemShortcut.Widgets.getDrawableId(),
                LauncherEvent.LAUNCHER_WIDGETSTRAY_BUTTON_TAP_OR_LONGPRESS,
                onStartWidgetsMenu,
            ),
            "enterAllApps" to OptionItem(
                launcher,
                R.string.all_apps_button_label,
                R.drawable.ic_apps,
                LauncherEvent.LAUNCHER_ALL_APPS_TAP_OR_LONGPRESS,
                onStartAllApps,
            ),
            "home_settings" to OptionItem(
                launcher,
                R.string.settings_button_text,
                R.drawable.ic_home_screen,
                LauncherEvent.LAUNCHER_SETTINGS_BUTTON_TAP_OR_LONGPRESS,
                onStartHomeSettings,
            ),
            "default_page" to OptionItem(
                launcher,
                R.string.set_default_home_page,
                R.drawable.ic_home_pin,
                LauncherEvent.IGNORE,
                ::setAsDefaultHomePage,
            ),
        )

        val options = ArrayList<OptionItem>()
        // AresLauncher (owner 2026-09-12): "Apps list" becomes "Customize", IN PLACE. The owner saw
        // an "App List" entry in the empty-space long-press menu and called it a problem -- the pane
        // is one swipe away and is a permanent panel unfolded, so a menu entry that jumps to it is a
        // wrong turn out of a home-screen menu. The Ares edit mode is what belongs there, and this
        // menu never offered it (DEFAULT_ORDER ships edit_mode disabled).
        //
        // Substituting at the all_apps SLOT rather than filtering one out and enabling the other
        // does two things at once: the new entry inherits the position the owner already sees it in
        // (last, under Widgets), and it is force-enabled regardless of what the stored
        // launcherPopupOrder says -- the owner's device has a persisted order in which edit_mode is
        // disabled, and restoreMissingPopupOptions only ever ADDS missing identifiers, so editing
        // DEFAULT_ORDER alone would have changed nothing on their phone.
        val aresOrder = optionOrder
            .filter { it.identifier != "edit_mode" }
            .map { if (it.identifier == "all_apps") LauncherOptionPopupItem("edit_mode", true) else it }
        aresOrder
            .filter {
                (it.isEnabled && it.identifier != "carousel")
            }
            // AresLauncher: never show "Home settings" in the empty-space long-press menu. It opens
            // the stock Lawnchair home-settings screen, which conflicts with Ares' own launcher
            // settings and reads as confusing (owner 2026-08-31). Filtered here (the single chokepoint
            // both the carousel and default menu paths funnel through) so it is hidden regardless of
            // what the stored launcherPopupOrder preference says.
            .filter { it.identifier != "home_settings" }
            // Belt and braces for the substitution above: an "all_apps" identifier must never reach
            // the menu, whatever a future stored order contains.
            .filter { it.identifier != "all_apps" }
            .filter {
                if (lockHomeScreen) {
                    it.identifier != "edit_mode" && it.identifier != "widgets"
                } else {
                    true
                }
            }
            .filter { it.identifier != "default_page" || !launcher.workspace.isCurrentPageDefault }
            .mapNotNull { optionsList[it.identifier] }
            .forEach { options.add(it) }

        return options
    }

    /**
     * Enters the Ares edit mode from the empty-space long-press menu (owner 2026-09-12).
     *
     * Deliberately NOT `OptionsPopupView.enterHomeGardening`, which goes to
     * `LauncherState.EDIT_MODE` -- Launcher3's own home-gardening state, which this fork does not
     * render. The mode the owner means is `AresHomeListView.enterEditMode()`: the badges, the
     * chevrons and the edit carousel, the same state an item long-press raises.
     *
     * Posted rather than called inline so the popup's close animation is already under way -- the
     * click handler returning true is what triggers `close(true)`, and edit mode brings the whole
     * grid down to EDIT_MODE_SCALE, which reads badly underneath a menu that is still on screen.
     *
     * Returns true unconditionally so the popup always closes: a false return leaves the menu up
     * with no feedback, and the only way this can no-op is a home list that is not bound yet, in
     * which case leaving the menu open helps nobody.
     */
    private fun enterAresEditMode(v: View): Boolean {
        val launcher = Launcher.getLauncher(v.context)
        val grid = launcher.workspace?.aresHomeList
        grid?.post { grid.enterEditMode() }
        return true
    }

    private fun setAsDefaultHomePage(v: View): Boolean {
        val launcher = Launcher.getLauncher(v.context)
        val currentPage = launcher.workspace.getNextPage()
        launcher.workspace.setDefaultPage(currentPage)
        Toast.makeText(launcher, R.string.default_home_page_set, Toast.LENGTH_SHORT).show()
        return true
    }

    fun getMetadataForOption(identifier: String): LauncherOptionMetadata {
        return when (identifier) {
            "carousel" -> LauncherOptionMetadata(
                label = R.string.wallpaper_quick_picker,
                icon = R.drawable.ic_wallpaper,
                isCarousel = true,
            )

            "lock" -> LauncherOptionMetadata(
                label = R.string.home_screen_lock,
                icon = R.drawable.ic_lock,
            )

            "sys_settings" -> LauncherOptionMetadata(
                label = R.string.system_settings,
                icon = R.drawable.ic_setting,
            )

            // Same label the menu itself uses, so the settings reorder screen names it the way the
            // owner sees it (owner 2026-09-12).
            "edit_mode" -> LauncherOptionMetadata(
                label = R.string.ares_customize_home,
                icon = R.drawable.enter_home_gardening_icon,
            )

            "wallpaper" -> LauncherOptionMetadata(
                label = R.string.styles_wallpaper_button_text,
                icon = R.drawable.ic_palette,
            )

            "widgets" -> LauncherOptionMetadata(
                label = R.string.widget_button_text,
                icon = SystemShortcut.Widgets.getDrawableId(),
            )

            "all_apps" -> LauncherOptionMetadata(
                label = R.string.all_apps_button_label,
                icon = R.drawable.ic_apps,
            )

            "home_settings" -> LauncherOptionMetadata(
                label = R.string.settings_button_text,
                icon = R.drawable.ic_home_screen,
            )

            "default_page" -> LauncherOptionMetadata(
                label = R.string.set_default_home_page,
                icon = R.drawable.ic_home_pin,
            )

            else -> throw IllegalArgumentException("invalid popup option")
        }
    }

    fun migrateLegacyPreferences(
        launcher: Launcher,
    ) {
        val prefs2 = getInstance(launcher)

        val lockHomeScreenButtonOnPopUp = prefs2.lockHomeScreenButtonOnPopUp.firstCached()
        val editHomeScreenButtonOnPopUp = prefs2.editHomeScreenButtonOnPopUp.firstCached()
        val showSystemSettingsEntryOnPopUp = prefs2.showSystemSettingsEntryOnPopUp.firstCached()

        val optionOrder = prefs2.launcherPopupOrder
        val legacyPopupOptionsMigrated = prefs2.legacyPopupOptionsMigrated.firstCached()

        if (!legacyPopupOptionsMigrated) {
            prefs2.legacyPopupOptionsMigrated.setBlocking(true)

            val options = optionOrder.firstCached().toLauncherOptions()

            options.forEachIndexed { index, item ->
                if (item.identifier == "lock") {
                    options[index].isEnabled = lockHomeScreenButtonOnPopUp
                }
                if (item.identifier == "edit_mode") {
                    options[index].isEnabled = editHomeScreenButtonOnPopUp
                }
                if (item.identifier == "sys_settings") {
                    options[index].isEnabled = showSystemSettingsEntryOnPopUp
                }
            }

            optionOrder.setBlocking(options.toOptionOrderString())
        }
    }
}

data class LauncherOptionPopupItem(
    val identifier: String,
    var isEnabled: Boolean,
)

data class LauncherOptionMetadata(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val isCarousel: Boolean = false,
)

fun String.toLauncherOptions(): List<LauncherOptionPopupItem> {
    return this.split("|").map { item ->
        val (identifier, isEnabled) = when {
            item.startsWith("+") -> item.drop(1) to true
            item.startsWith("-") -> item.drop(1) to false
            else -> item to true // Default to enabled if no prefix
        }
        LauncherOptionPopupItem(identifier, isEnabled)
    }
}

fun List<LauncherOptionPopupItem>.toOptionOrderString(): String {
    return this.joinToString("|") {
        if (it.isEnabled) "+${it.identifier}" else "-${it.identifier}"
    }
}
