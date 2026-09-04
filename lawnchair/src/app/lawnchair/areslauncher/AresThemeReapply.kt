package app.lawnchair.areslauncher

import android.app.Activity

/**
 * Reads the launcher's theme state, so a theme defect can be measured rather than argued about.
 *
 * ## What this was, and what killed it
 *
 * This file began as an in-place re-theme: skip the `recreate()` a light↔dark switch triggers and
 * re-apply the new theme to the live views instead, to close ledger row 77 (the owner's "about 10
 * seconds" before icons update). It did not work, and the reason it did not work is the finding:
 *
 * ```
 *   activity theme     textColorSecondary  #de000000 -> #ffc5c6d0   moves
 *   home list context  textColorSecondary  #ffffffff -> #ffffffff   FROZEN
 * ```
 *
 * The home list's `Context` is a `ContextThemeWrapper`, not the `Activity`. `onCreateViewHolder`
 * builds every row container from `parent.context`, `notifyDataSetChanged` reuses holders so that
 * `Context` outlives any rebind, and `ItemInflater` inflates through
 * `LayoutInflater.from(parent.context)`. Rows therefore re-inflate against a theme that never
 * changes — which is why an instrumented **28** binds moved **zero** pixels.
 *
 * Re-theming in place would mean re-inflating the panes against a fresh themed context, which is
 * most of what `recreate()` already does, on the surface with this project's worst regression
 * history. The owner's decision (2026-09-03) is to keep the recreate and make it CHEAPER instead —
 * the measured 2.36s is roughly half relaunch-and-scheduling, half model reload, and the second half
 * is the target. The in-place machinery and its `debug.ares.inplace_theme` gate were removed rather
 * than left switched off; the refuted hypotheses are in the ledger and in git history, which is
 * where a dead lever belongs.
 *
 * The probe stays because it is what produced the table above, and any future theme work needs it.
 * `content call --uri content://app.lawnchair.debug.TestInfo --method ares-theme-state --arg x`
 */
object AresThemeReapply {

    /**
     * `uiModeNight=<yes|no>|expected=0x..|colorBackground=#..|...|listCtx=..|listTextColorSecondary=#..`
     *
     * The pairing is the point. `expected` and the activity attributes say what the theme SHOULD be
     * and what it HOLDS; `listCtx` and `listTextColorSecondary` say what the views actually inflate
     * against. Reading only the first set is how three hypotheses survived longer than they should
     * have — the activity theme moves correctly on every path, including the ones where nothing on
     * screen changes.
     */
    @JvmStatic
    fun state(activity: Activity): String {
        val night = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val expected = com.android.launcher3.util.Themes.getActivityThemeRes(activity)
        return "uiModeNight=${if (night) "yes" else "no"}" +
            "|expected=0x${Integer.toHexString(expected)}" +
            "|colorBackground=${attr(activity, android.R.attr.colorBackground)}" +
            "|textColorPrimary=${attr(activity, android.R.attr.textColorPrimary)}" +
            "|textColorSecondary=${attr(activity, android.R.attr.textColorSecondary)}" +
            "|listCtx=${listContext(activity)}" +
            "|listTextColorSecondary=${listAttr(activity, android.R.attr.textColorSecondary)}"
    }

    private fun homeListContext(activity: Activity): android.content.Context? =
        (activity as? com.android.launcher3.Launcher)?.workspace?.aresHomeList?.context

    /** Simple name of the home list's Context, flagged as to whether it is the Activity itself. */
    private fun listContext(activity: Activity): String {
        val c = homeListContext(activity) ?: return "none"
        return "${c.javaClass.simpleName}${if (c === activity) "(==activity)" else "(WRAPPER)"}"
    }

    /** [attr], but resolved through the home list's Context instead of the activity's. */
    private fun listAttr(activity: Activity, attrId: Int): String {
        val c = homeListContext(activity) ?: return "none"
        return resolve(c, attrId)
    }

    /** One theme attribute as `#aarrggbb`, or a reason it could not be read. */
    private fun attr(activity: Activity, attrId: Int): String = resolve(activity, attrId)

    private fun resolve(c: android.content.Context, attrId: Int): String {
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
}
