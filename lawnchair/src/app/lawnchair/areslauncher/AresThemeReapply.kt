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
        return "uiModeNight=${if (night) "yes" else "no"}" +
            "|expected=0x${Integer.toHexString(expected)}" +
            // The values the VIEWS would actually get. This is the fork that decides where the
            // in-place path is broken: if these move on a switch, the theme is fine and the defect
            // is that nothing re-reads them; if they do not, `setTheme` never took and no amount of
            // re-inflation would help. Resolving them is the only way to tell those apart --
            // `expected` above is what the theme SHOULD be, not what the theme HOLDS.
            "|colorBackground=${attr(activity, android.R.attr.colorBackground)}" +
            "|textColorPrimary=${attr(activity, android.R.attr.textColorPrimary)}" +
            "|textColorSecondary=${attr(activity, android.R.attr.textColorSecondary)}" +
            // The SAME attribute resolved through the home list's own Context rather than the
            // activity's. onCreateViewHolder builds each row container from `parent.context` (the
            // RecyclerView's), notifyDataSetChanged reuses holders so that Context object persists
            // for the list's whole life, and ItemInflater inflates via `LayoutInflater
            // .from(parent.context)`. If that Context is a ContextThemeWrapper its theme was built
            // once and does not follow the activity -- which would explain re-inflated rows coming
            // back in the old colour. If the two agree, this is NOT the cause and the next
            // hypothesis is Lawnchair's computed palette.
            "|listCtx=${listContext(activity)}" +
            "|listTextColorSecondary=${listAttr(activity, android.R.attr.textColorSecondary)}"
    }

    private fun homeListContext(activity: Activity): android.content.Context? =
        (activity as? com.android.launcher3.Launcher)?.workspace?.aresHomeList?.context

    /** Simple name of the home list's Context, to show whether it is the Activity or a wrapper. */
    private fun listContext(activity: Activity): String {
        val c = homeListContext(activity) ?: return "none"
        val same = c === activity
        return "${c.javaClass.simpleName}${if (same) "(==activity)" else "(WRAPPER)"}"
    }

    /** [attr], but resolved through the home list's Context instead of the activity's. */
    private fun listAttr(activity: Activity, attrId: Int): String {
        val c = homeListContext(activity) ?: return "none"
        val tv = android.util.TypedValue()
        if (!c.theme.resolveAttribute(attrId, tv, true)) return "unresolved"
        return when {
            tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT -> "#%08x".format(tv.data)
            tv.resourceId != 0 ->
                runCatching { "#%08x".format(c.resources.getColor(tv.resourceId, c.theme)) }
                    .getOrElse { "res:0x${Integer.toHexString(tv.resourceId)}" }
            else -> "type${tv.type}"
        }
    }

    /** One theme attribute as `#aarrggbb`, or a reason it could not be read. */
    private fun attr(activity: Activity, attrId: Int): String {
        val tv = android.util.TypedValue()
        if (!activity.theme.resolveAttribute(attrId, tv, true)) return "unresolved"
        return when {
            tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT ->
                "#%08x".format(tv.data)
            tv.resourceId != 0 ->
                runCatching { "#%08x".format(activity.resources.getColor(tv.resourceId, activity.theme)) }
                    .getOrElse { "res:0x${Integer.toHexString(tv.resourceId)}" }
            else -> "type${tv.type}"
        }
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
     * ## THIS DOES NOT WORK YET. Measured 2026-09-03, kept as a recorded narrowing.
     *
     * Every comparison below is against what the RECREATE produces, never against the previous
     * frame. That matters: the first attempt's two captures DID differ by md5, entirely because of
     * one clock digit, and read as success.
     *
     * ```
     *   attempt 1  widget host re-subscribe + LauncherModel.rebindCallbacks()
     *              day vs night 1/576 cells   vs recreate-night 95/576
     *   attempt 2  homeList.adapter.notifyDataSetChanged() directly
     *              day vs night 0/576 cells   vs recreate-night 102/576
     * ```
     *
     * ## Four hypotheses tested and REFUTED, in order
     *
     *  1. *The system relaunches because `uiMode` is missing from `configChanges`.* No: with
     *     `uiMode` declared the activity still recreated 4/4. The recreate is self-inflicted.
     *  2. *The `-night` resources are stale without a recreate, so re-applying is pointless.* No:
     *     `resources.configuration.uiMode` flips to night with no recreate AND with `uiMode` absent,
     *     and `Themes.getActivityThemeRes` follows (`0x7f13001f` -> `0x7f13001d`).
     *  3. *`setTheme` overlays via `Resources.Theme.applyStyle` and never really takes.* No: the
     *     activity theme's own attributes move exactly as they do under a recreate —
     *     `colorBackground` `#fff1f0f6` -> `#ff1a1b20`, `textColorPrimary` `#de000000` -> `#fff1f0f6`.
     *  4. *The rebind never reaches the adapter.* No: instrumented, `onBindViewHolder` ran **28**
     *     times during the in-place path, and non-widget rows take the `removeAllViews()` +
     *     `itemInflater.inflateItem(...)` branch, i.e. they are genuinely re-inflated.
     *
     * ## Where that leaves it
     *
     * Rows are re-inflated, against a theme whose attributes have demonstrably changed, and come
     * back the same colour. So the label colour does not derive from the activity theme attributes
     * that were checked. The changed cells were mapped back to real tiles via `ares-tile-metrics`:
     * the block at `0,928,1016,1624` is widget `701/type4`, and the scattered cells at y>1624 are
     * the LABELS of ordinary icon tiles (`715..722`, Chrome/Photos/Camera/Messages/...). So there
     * are two distinct populations, and only one of them is a widget.
     *
     * Two candidate sources remain, neither yet tested — this project's rule is that a mechanism
     * read off the source is a hypothesis, not a finding:
     *
     *  - Lawnchair's own palette (`ThemeProvider.colorScheme`, `ColorTokens`), which is a computed
     *    Material You scheme rather than an `?android` attribute. `LawnchairLauncher.colorScheme` is
     *    cached at `onCreate` and is NOT refreshed on the `WallpaperThemeManager` path, so anything
     *    resolving through it keeps the old palette across an in-place switch.
     *  - `BubbleTextView`'s own text colour, whichever way it is set.
     *
     * Widgets are a separate problem with a known cause: `onBindViewHolder`'s `reuseHost` branch
     * deliberately reuses a live `AppWidgetHostView` rather than re-inflating, to break a flicker
     * loop, so no adapter-level rebind can ever re-theme one. That is a decision to revisit
     * explicitly, not to work around here.
     *
     * Icon bitmaps did not move in either arm because icon theming is off in this fixture; a device
     * with themed icons also needs the cache regenerated, which is row 61's path.
     */
    /**
     * Bind counter, so "the adapter was told" can be told apart from "the adapter acted".
     *
     * Not volatile and does not need to be: `onBindViewHolder` and [reapply] both run on the UI
     * thread, so this is a plain increment in the bind path rather than a memory barrier.
     */
    private var binds = 0

    @JvmStatic
    fun noteBind() { binds++ }

    private fun reapply(launcher: com.android.launcher3.Launcher) {
        val before = binds
        // Re-bind the grid rows DIRECTLY rather than via the model. `AresHomeAdapter's`
        // onBindViewHolder does `removeAllViews()` then `itemInflater.inflateItem(...)`, so a plain
        // notifyDataSetChanged re-INFLATES each row against the activity theme -- which is measured
        // to hold the new colours by this point. Going through `LauncherModel.rebindCallbacks()`
        // instead was tried first and moved 1 cell of 576; it never reached the adapter.
        val list = launcher.workspace?.aresHomeList
        runCatching { list?.adapter?.notifyDataSetChanged() }
            .onFailure { Log.w(TAG, "home list rebind failed", it) }
        // The app-list pane is deliberately NOT touched yet. It is workspace page 1, absent from the
        // capture this was scoped against, and it holds its own AllAppsStore -- adding it now would
        // put two unproven changes in one measurement. It matters for the owner's report ("app list
        // takes longer than home page to update") and follows once the home path is established.
        Log.i(TAG, "reapply: homeList=${list != null} items=${list?.adapter?.itemCount ?: -1}")
        list?.post {
            Log.i(TAG, "reapply: binds during rebind = ${binds - before}")
        }
    }
}
