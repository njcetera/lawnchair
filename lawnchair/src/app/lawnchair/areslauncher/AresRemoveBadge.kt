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
     * [label] is the item's own name, and is spoken rather than drawn: it turns six identical
     * "Remove" buttons into "Remove Chrome from home screen". Null or blank falls back to the bare
     * label, which is still better than no description at all.
     *
     * The glyph is [R.dimen.ares_remove_badge_size] inside a [R.dimen.ares_widget_resize_touch_size]
     * view, padded out to a comfortable target: the glyph is sized to read against an icon, the
     * touch target to satisfy the 44dp minimum. Growing the glyph to fill the target instead would
     * bury the icon it sits on.
     */
    @JvmOverloads
    fun createBadge(
        container: FrameLayout,
        label: CharSequence?,
        touchSizePx: Int = 0,
        onTap: () -> Unit,
    ): View {
        val res = container.resources
        // A caller may need a smaller target than the grid's. A folder cell is ~83dp, and two
        // 48dp targets side by side need 234px of a 202px cell -- they would overlap by about
        // 52px and which one a tap reached would depend on draw order. See AresFolderEdit.
        val touch = if (touchSizePx > 0) {
            touchSizePx
        } else {
            res.getDimensionPixelSize(R.dimen.ares_widget_resize_touch_size)
        }
        val margin = res.getDimensionPixelSize(R.dimen.ares_widget_resize_margin)
        // Pull the drawn circle into the top-start corner, away from the centre of its own touch
        // target, by padding the two FAR sides. The 48dp view stays fully touchable; only the
        // CENTER-scaled drawable moves. Centred it landed on the app icon rather than beside it.
        val pull = cornerPullFor(res, touch)

        return ImageView(container.context).apply {
            tag = BADGE_TAG
            setPaddingRelative(0, 0, pull, pull)
            // The drawable carries its own backdrop and is composed at the size it should be drawn
            // at, so it is set as the image and rendered CENTER -- at intrinsic size inside the
            // larger touch target -- rather than as a background stretched across the whole view.
            // Painting it as the background made the circle fill all 48dp, which on a 1x1 tile is
            // about as wide as the app icon; see ares_remove_badge.xml.
            setImageResource(R.drawable.ares_remove_badge)
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = if (label.isNullOrBlank()) {
                res.getString(R.string.remove_drop_target_label)
            } else {
                res.getString(R.string.ares_remove_from_home, label)
            }
            AresA11y.describeAsButton(this)
            setOnClickListener { onTap() }
            layoutParams = FrameLayout.LayoutParams(touch, touch).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    /**
     * How far to pull the drawn badge into its corner, in px, for a touch target of [touchPx].
     *
     * `ares_badge_corner_pull` is the *wish*; this is what the target can actually afford. The
     * badge is rendered `CENTER` at its intrinsic 28dp inside the content box left by the padding,
     * so its offset from the corner is `(touch - pull - badge) / 2` — and once `pull` exceeds
     * `touch - badge`, that offset goes **negative** and the circle is drawn outside the view and
     * clipped by it.
     *
     * That is not hypothetical, and it is why this clamp exists rather than a bigger constant. A
     * home tile hosts a 48dp target, which affords 20dp of pull. **A folder cell does not**: its
     * badges are sized to half the cell (91px on a 202px cell, ~37dp), which affords only ~9dp — so
     * the 15dp this used to apply unconditionally already computed a **-7px** offset in every open
     * folder, clipping the top-start of both glyphs. That matches the report that *"the X and !
     * icons in the folder dont render correctly"*, which had been read as a sizing problem.
     *
     * Clamping fixes both surfaces with one rule: the grid gets the full pull it asked for, and a
     * folder gets the most its smaller target allows, which lands the badge flush in the corner
     * instead of over the edge of it.
     */
    internal fun cornerPullFor(res: android.content.res.Resources, touchPx: Int): Int {
        val wish = res.getDimensionPixelSize(R.dimen.ares_badge_corner_pull)
        val drawn = res.getDimensionPixelSize(R.dimen.ares_remove_badge_size)
        return wish.coerceAtMost((touchPx - drawn).coerceAtLeast(0))
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
     *
     * Note this tests the 48dp **touch target**, not the drawn circle, and that stays true however
     * far [cornerPullFor] moves the glyph — the padding shifts what is painted and never the view's
     * own bounds. Reachability is therefore independent of the visual nudge.
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
