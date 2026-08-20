package app.lawnchair.areslauncher

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo

/**
 * The remove (×) affordance shown on every home item while the grid is in edit mode.
 *
 * ## What it does, and deliberately does not do
 *
 * Tapping × removes the item from the **home screen**. It does **not** uninstall the app. iOS
 * conflates the two behind one control; a launcher must not, because the home screen is a view onto
 * the installed set rather than the set itself — the app stays installed and stays in the app list.
 * Uninstall remains in the long-press context popup, where it already lives and already confirms.
 *
 * For a widget, removal also releases the allocated `appWidgetId` via
 * [com.android.launcher3.model.ModelWriter.deleteWidgetInfo], so repeated add/remove cycles cannot
 * leak host ids. Non-widgets go through `deleteItemFromDatabase`.
 *
 * ## Why this rides on the holder container
 *
 * Same reason as the resize chevron, and it is not a style choice. For a widget the item view is an
 * `AppWidgetHostView` hosting the provider's RemoteViews, and **our own child inside it is removed
 * the next time the provider pushes an update** — so on any self-refreshing widget (a clock, most
 * obviously) the badge would silently vanish. Attaching to the container instead also means the
 * edit-mode transform applies to it for free, because every edit-mode visual is applied at the
 * container level ([AresHomeListView.applyEditModeVisual]) — so the badge wiggles with its item
 * rather than sitting still over a moving icon.
 *
 * Everything a later pass is likely to want to adjust — placement, size, glyph — is in this file.
 */
object AresRemoveBadge {

    /** Tag on the badge view, so the host can hit-test it without a resource id. */
    const val BADGE_TAG = "ares_remove_badge"

    /**
     * Builds the × overlay for a home cell.
     *
     * Sits in the top-start corner, inside the item's own bounds. The grid is packed with no gaps,
     * so an affordance hanging outside the cell would overlap a neighbour — and the resize chevron
     * already owns bottom-end, so the two never collide even on a 1×1 tile.
     *
     * The glyph is [R.dimen.ares_remove_badge_size] inside a [R.dimen.ares_widget_resize_touch_size]
     * view, padded out to a comfortable target: the glyph is sized to read against an icon, the
     * touch target to satisfy the 44dp minimum. Growing the glyph to fill the target instead would
     * bury the icon it sits on.
     */
    fun createBadge(container: FrameLayout, onTap: () -> Unit): View {
        val res = container.resources
        val touch = res.getDimensionPixelSize(R.dimen.ares_widget_resize_touch_size)
        val margin = res.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)

        return ImageView(container.context).apply {
            tag = BADGE_TAG
            // The drawable carries its own backdrop and is composed at the size it should be drawn
            // at, so it is set as the image and rendered CENTER -- at intrinsic size inside the
            // larger touch target -- rather than as a background stretched across the whole view.
            // Painting it as the background made the circle fill all 48dp, which on a 1x1 tile is
            // about as wide as the app icon; see ares_remove_badge.xml.
            setImageResource(R.drawable.ares_remove_badge)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = res.getString(R.string.remove_drop_target_label)
            setOnClickListener { onTap() }
            layoutParams = FrameLayout.LayoutParams(touch, touch).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    /**
     * True when [x],[y] fall on the badge.
     *
     * The host's edit-mode touch listener consumes taps on items so tiles stay inert, which would
     * otherwise swallow the badge's click before it fired. It asks this first and declines to
     * consume when the answer is yes — the same arrangement the chevron uses.
     *
     * **[x],[y] must be in [container]'s own coordinate space, mapped through the container's
     * transform**, for the reason spelled out on [AresWidgetResize.isPointOnChevron]:
     * `AresHomeListView.toChildLocal` produces them, and subtracting `container.left` alone does
     * not, because edit mode scales the container.
     */
    fun isPointOnBadge(container: View, x: Float, y: Float): Boolean {
        val badge = container.findViewWithTag<View>(BADGE_TAG) ?: return false
        if (badge.visibility != View.VISIBLE) return false
        val bounds = Rect()
        badge.getHitRect(bounds)
        return bounds.contains(x.toInt(), y.toInt())
    }

    /**
     * Removes [info] from the home screen, releasing a widget's host id where applicable.
     *
     * The writer is fetched here rather than cached: one obtained before the first load completes
     * carries the sentinel `mLoadId = -1` and every write through it is **silently discarded** — no
     * exception, just a debug log, so data loss looks exactly like success. A removal is
     * user-initiated long after the first load so it is not in that window, but fetching at the
     * point of use keeps it out of the trap by construction. See design/model-persistence.md.
     *
     * No repack is done here. The caller removes the item from the adapter, and the packer derives
     * every position from the resulting order — so compaction is not a separate step to remember,
     * it is what the next layout pass already computes.
     */
    fun removeFromHome(launcher: Launcher, info: ItemInfo) {
        val writer = launcher.modelWriter
        if (info is LauncherAppWidgetInfo) {
            writer.deleteWidgetInfo(info, launcher.appWidgetHolder, "removed by user from home grid")
        } else {
            writer.deleteItemFromDatabase(info, "removed by user from home grid")
        }
    }
}
