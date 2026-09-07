package app.lawnchair.areslauncher

import android.util.Log
import com.android.launcher3.Utilities

/**
 * Decides who may write a hosted widget's size options (`OPTION_APPWIDGET_SIZES`, min/max dp).
 *
 * ## The defect this exists for (ledger row 142)
 *
 * Strategy D hosts every desktop widget in the Ares home list, whose boxes come from the masonry
 * layout manager -- NOT from the stock CellLayout grid. Two writers were reporting geometry to the
 * provider:
 *
 *  - `AresHomeAdapter.reportBoxSizeToProvider`: the real on-screen box, once per host view (a
 *    per-host memo stops the per-frame re-report loop measured 2026-08-31).
 *  - `WorkspaceItemProcessor.processWidget` -> `WidgetSizeHandler.updateSizeRangesAsync`: stock's
 *    span x profile-cell math, on EVERY `loadWorkspace`, written whenever its size LIST differs
 *    from the stored one -- and it always differs, because the adapter's legacy report stores an
 *    EMPTY list.
 *
 * So a cold start ended with the adapter's box (its report ran last, on a fresh host), and the first
 * model reload after it -- a theme change, an install, an icon-pack switch -- flipped the options
 * back to stock geometry while the adapter, seeing a reused host it had already reported, said
 * nothing. The provider then rendered for a box it was not in. Measured on the Pixel 2026-09-07:
 * after four Themed-icons toggles the Google Weather widget (4x4, host 792x944 px) drew its content
 * laid out for ~1000 px -- zero-width TextViews and rows overflowing the host -- and stayed that way
 * through the provider's own refresh at 09:23:40 and a return to the Normal state. The system
 * unfroze the weather app for a START_RECEIVER half a second after the first toggle, i.e. the
 * loader's OPTIONS_CHANGED, exactly during `loadWorkspace`.
 *
 * The Ares list is the only thing that knows the box, so it is the only writer. The stock rewrite
 * is declined here, with the decline LOGGED (a guard that quietly stops engaging must be visible in
 * a log -- change-practices, the `supportedBounds` lesson).
 *
 * `setprop debug.ares.loader_widget_sizes 1` restores the stock rewrite: the CONTROL arm of the A/B
 * that proves the fix from one build (the fire branch logs too, so each arm carries proof of its
 * path -- the setprop-before-force-stop trap makes silent arms indistinguishable).
 */
object AresWidgetSizeOwner {

    private const val TAG = "AresWidgetSizeOwner"

    private const val PROP = "debug.ares.loader_widget_sizes"

    // Cached: this runs per widget per load on the loader thread, and getSystemProperty is an
    // uncached reflective call.
    private val stockRewriteEnabled: Boolean by lazy {
        Utilities.getSystemProperty(PROP, "") == "1"
    }

    /** True when the Ares list is the only writer of widget size options (the default). */
    @JvmStatic
    fun listIsSoleWriter(): Boolean = !stockRewriteEnabled

    /**
     * True when the stock loader may overwrite [widgetId]'s size options from its grid spans.
     * Both branches log, once per widget per load.
     */
    @JvmStatic
    fun loaderMayWriteSizes(widgetId: Int, spanX: Int, spanY: Int): Boolean {
        if (stockRewriteEnabled) {
            Log.i(TAG, "stock size-range rewrite APPLIED for widget $widgetId (${spanX}x$spanY): $PROP=1")
            return true
        }
        Log.i(
            TAG,
            "stock size-range rewrite declined for widget $widgetId (${spanX}x$spanY): " +
                "the Ares list reports the real box",
        )
        return false
    }
}
