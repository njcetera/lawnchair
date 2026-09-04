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
        return true
    }
}
