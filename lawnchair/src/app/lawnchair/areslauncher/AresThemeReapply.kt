package app.lawnchair.areslauncher

import android.app.Activity
import android.util.Log
import com.android.launcher3.Utilities

/**
 * The seam where a theme change stops destroying the launcher and starts being applied in place.
 *
 * ## What this is for (ledger row 77)
 *
 * A light↔dark switch takes ~10s on the owner's Pixel before the icons change. The window is not
 * icon generation — `update AllApps icon cache finished` and `finish icon update` are 19ms apart,
 * at the very end. It is that **the whole launcher activity is destroyed and rebuilt**: measured on
 * emulator-5554, 2.36s from the switch to the last bind, roughly half relaunch-and-scheduling and
 * half model reload.
 *
 * ## Where the rebuild actually comes from, which is NOT where it looked
 *
 * The obvious reading is that `uiMode` is missing from the activity's `configChanges`, so the
 * SYSTEM relaunches it. That was measured on 2026-09-03 and is **wrong**: with `uiMode` declared,
 * the client-side `Local Activity <hash>` from `dumpsys activity top` still changed on 4 of 4 night
 * switches. The rebuild is self-inflicted, from two of the fork's own paths:
 *
 *  - `WallpaperThemeManager.updateTheme()` — a `ComponentCallbacks` on the activity — calls
 *    `recreate()` whenever `Themes.getActivityThemeRes` resolves differently. This is the night path.
 *  - `LawnchairLauncher.updateTheme()` calls `recreate()` when `themeProvider.colorScheme` differs.
 *    This is the launcher's own theme PREFERENCE path.
 *
 * So the manifest flag is neither necessary nor sufficient on its own, and adding it alone would
 * have been dead config that measured as "no change" while looking like a fix.
 *
 * ## Why a sysprop rather than a straight replacement
 *
 * Control and treatment come from IDENTICAL BYTES. The alternative — build the old way, measure,
 * build the new way, measure — has already produced two false A/Bs in this project, because a
 * rebuild in between changes more than the thing under test. `setprop debug.ares.inplace_theme 1`
 * arms the in-place path; unset is the shipped behaviour.
 *
 * **Set the prop BEFORE `am force-stop`, never after** — the framework relaunches the home app the
 * instant force-stop kills it, and a setprop issued after the kill loses the race with the very
 * process it is meant to configure. That has silently produced two identical arms here before.
 *
 * Both branches log. A gate that has quietly stopped engaging is otherwise indistinguishable from a
 * run where its condition never arose, which is exactly how the mid-fold guard stayed broken for
 * weeks (ledger row 69a).
 */
object AresThemeReapply {

    private const val TAG = "AresThemeReapply"

    /** `setprop debug.ares.inplace_theme 1` to skip the recreate and re-theme in place instead. */
    private const val PROP = "debug.ares.inplace_theme"

    /**
     * `uiModeNight=<yes|no>|themeRes=0x..|expected=0x..|agree=<bool>`
     *
     * The question this answers, and it is the one the whole in-place path turns on: when the
     * activity is NOT recreated, does its `Resources` configuration even become night? Everything
     * downstream — re-inflating a widget's RemoteViews, re-binding a list row — resolves `-night`
     * resources against THIS configuration, so if it stays stale then re-applying renders the old
     * theme perfectly and the work is wasted.
     */
    @JvmStatic
    fun state(activity: Activity): String {
        val night = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val expected = com.android.launcher3.util.Themes.getActivityThemeRes(activity)
        return "uiModeNight=${if (night) "yes" else "no"}|expected=0x${Integer.toHexString(expected)}"
    }

    @JvmStatic
    fun enabled(): Boolean = Utilities.getSystemProperty(PROP, "") == "1"

    /**
     * Called from each `recreate()` site. Returns true when it handled the change in place and the
     * caller must NOT recreate.
     *
     * [who] names the call site so the log says which of the two paths fired — they are reached by
     * different triggers (system night vs the launcher's own theme preference) and conflating them
     * is how the wrong one gets fixed.
     */
    @JvmStatic
    fun interceptRecreate(who: String, activity: Activity): Boolean {
        if (!enabled()) {
            Log.i(TAG, "$who: recreating the activity (in-place re-theme is OFF)")
            return false
        }
        if (activity.isDestroyed || activity.isFinishing) {
            Log.i(TAG, "$who: activity already going away, letting the recreate stand")
            return false
        }
        Log.i(TAG, "$who: SKIPPING recreate, applying the theme in place")
        (activity as? com.android.launcher3.Launcher)?.let { reapply(it) }
        return true
    }

    /**
     * Re-applies the new theme to the surfaces a recreate would have rebuilt.
     *
     * ## THIS DOES NOT WORK YET. Measured 2026-09-03, and left in place as a recorded refutation.
     *
     * Two levers were tried here — re-subscribing the widget host, and `LauncherModel
     * .rebindCallbacks()` — on the theory that a model rebind re-inflates the grid rows and a
     * re-subscribe re-inflates the widget's `RemoteViews`. Both run without error. Neither
     * re-themes anything:
     *
     * ```
     *   in-place, day vs night          1 cell of 576 differs   (nothing re-themed)
     *   in-place night vs recreate night   95 cells differ      (still showing the DAY screen)
     * ```
     *
     * The second map is cell-for-cell the same as the recreate's own day-vs-night map, which is the
     * proof: the in-place screen has not moved off the old theme at all. A byte difference in the
     * PNGs is NOT evidence here — the two captures did differ, entirely because of one clock digit,
     * and reading that as success is exactly the trap this comment exists to stop.
     *
     * What that rules out, and what it leaves. A model rebind is not the missing piece: the grid
     * rows come back with the previous theme's colours, so either the rows are not re-inflated or
     * they resolve against a theme that has not actually changed. `Activity.setTheme` OVERLAYS via
     * `Resources.Theme.applyStyle` rather than replacing, so a stale attribute can survive it — that
     * is the next hypothesis to TEST, not to assume. Likewise the widget: `stopListening` /
     * `startListening` does not oblige a provider to push fresh `RemoteViews`.
     *
     * The set of surfaces needing re-apply is known and small (measured over one night switch on the
     * unfolded 2076x2152 display): one widget, tile `701/type4` at `0,928,1016,1624`, plus scattered
     * label-text cells in the grid rows. Icon bitmaps did not move in either arm because icon
     * theming is off in that fixture; a device with themed icons also needs the cache regenerated,
     * which is row 61's path and is deliberately not duplicated here.
     *
     * One thing this did establish, and it removes an assumed blocker: the `-night` resources a
     * re-inflation would resolve against ARE correct by the time this runs. `resources.configuration
     * .uiMode` flips to night without the activity being recreated and without `uiMode` in
     * `configChanges`, and `Themes.getActivityThemeRes` moves with it (`0x7f13001f` -> `0x7f13001d`).
     * So the problem is re-application, not resource resolution.
     */
    private fun reapply(launcher: com.android.launcher3.Launcher) {
        // Widgets first: the round-trip is asynchronous, so starting it early overlaps it with the
        // model rebind rather than serialising the two.
        runCatching {
            launcher.appWidgetHolder.stopListening()
            launcher.appWidgetHolder.startListening()
        }.onFailure { Log.w(TAG, "widget host re-subscribe failed", it) }
        runCatching { launcher.model.rebindCallbacks() }
            .onFailure { Log.w(TAG, "model rebind failed", it) }
    }
}
