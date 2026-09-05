package app.lawnchair.areslauncher

import android.os.SystemClock
import android.util.Log
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher

/**
 * Edge auto-scroll for a `DragController` drag hovering over the home list.
 *
 * ## Why this exists
 *
 * The home grid's own reorder is an `ItemTouchHelper` drag, and ITH scrolls the list when the
 * dragged tile is pushed against the viewport edge (`scrollIfNecessary`). A drag that arrives from
 * OUTSIDE the grid — the app list, the widget picker — is a `DragController` drag instead, and the
 * only per-move hook it offers is `Workspace.onDragOver`. Nothing there scrolled the list. Stock
 * never needed to: its workspace is paged horizontally and `SPRING_LOADED` scaled the whole page
 * down to fit. Once [AresDragChrome] dropped that zoom-out the list sits at full scale under the
 * drag, and everything below the fold is unreachable. Owner, 2026-09-04, right after the picker fix
 * reached the Pixel: *"after selecting a widget to drag, the home page doesn't scroll for
 * placement — it's like it's frozen in place."*
 *
 * ## Shape
 *
 * Mirrors ITH rather than inventing a second behaviour: a zone at each end of the list, speed
 * ramping with depth into the zone and with time held there (ITH's 500ms acceleration window),
 * capped per frame; and the same stand-down rule ITH's callback already has here — no scrolling
 * while a dwell candidate is held ([AresFolderDrop.hasCandidate]), because a finger holding still
 * over a tile near the edge is the dwell's whole gesture and scrolling the target away from it was
 * measured to restart the dwell timer forever.
 *
 * Driven by the pointer, not by the drag view: `DragObject.x`/`y` mapped into list space through
 * the same [AresFolderDrop.toListSpace] the drop and the dwell use, so all three agree on where
 * the drag is. Horizontal bounds are checked too — unfolded, a drag over the app-list pane is not
 * over the list and must not scroll it.
 *
 * The frame loop is a `postOnAnimation` runnable that re-arms itself only while the pointer stays
 * in a zone; [stop] is called from `Workspace.onDragExit` and `Workspace.onDragEnd`, so nothing
 * can outlive the drag. The runnable holds the list, not the Launcher.
 */
object AresExternalDragScroll {

    private const val TAG = "AresExternalDragScroll"

    /** Fraction of the list height at each end that counts as the scroll zone. */
    private const val ZONE_FRACTION = 0.15f

    /** Zone never thinner than this, so a short list on a small window still has one. */
    private const val MIN_ZONE_DP = 72f

    /** ITH's cap: item_touch_helper_max_drag_scroll_per_frame is 20dp. */
    private const val MAX_PER_FRAME_DP = 20f

    /** ITH's DRAG_SCROLL_ACCELERATION_LIMIT_TIME_MS. */
    private const val ACCEL_MS = 500L

    private var list: AresHomeListView? = null
    private var direction = 0
    private var depth = 0f
    private var zoneEnteredAt = 0L
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            val target = list
            if (!running || target == null || direction == 0) {
                running = false
                return
            }
            if (AresFolderDrop.hasCandidate()) {
                // Stand down but stay armed: the next onDragOver re-evaluates.
                target.postOnAnimation(this)
                return
            }
            val density = target.resources.displayMetrics.density
            val maxPerFrame = MAX_PER_FRAME_DP * density
            val timeRatio = ((SystemClock.uptimeMillis() - zoneEnteredAt).toFloat() / ACCEL_MS)
                .coerceIn(0f, 1f)
            // Same curve family as ITH (a steep ease-in), so a finger just inside the zone creeps
            // and one pressed against the edge moves at the cap.
            val eased = depth * depth * depth
            val dy = (maxPerFrame * eased * timeRatio).toInt().coerceAtLeast(1) * direction
            // scrollBy through the RecyclerView so the masonry manager's own scrollVerticallyBy
            // (and its clamp at the ends) runs; the offset before/after is the honest "moved".
            val lm = target.layoutManager as? AresMasonryLayoutManager
            val before = lm?.currentScrollOffset() ?: 0
            target.scrollBy(0, dy)
            val moved = (lm?.currentScrollOffset() ?: 0) - before
            if (moved == 0) {
                // Hit the end; nothing more to do until the pointer moves.
                running = false
                return
            }
            target.postOnAnimation(this)
        }
    }

    /** Per-move hook; call from `Workspace.onDragOver`. */
    @JvmStatic
    fun onDragOver(launcher: Launcher, d: DropTarget.DragObject) {
        if (!AresWidgetAdd.isAresHome(launcher)) return
        val grid = launcher.workspace?.aresHomeList ?: return
        val local = AresFolderDrop.toListSpace(launcher, grid, d.x.toFloat(), d.y.toFloat())
        val x = local[0]
        val y = local[1]
        val height = grid.height
        if (height <= 0 || x < 0f || x > grid.width) {
            stop()
            return
        }
        val density = grid.resources.displayMetrics.density
        val zone = maxOf(height * ZONE_FRACTION, MIN_ZONE_DP * density)
        val newDirection: Int
        val newDepth: Float
        when {
            y < zone -> { newDirection = -1; newDepth = ((zone - y) / zone).coerceIn(0f, 1f) }
            y > height - zone -> { newDirection = 1; newDepth = ((y - (height - zone)) / zone).coerceIn(0f, 1f) }
            else -> { stop(); return }
        }
        if (list !== grid || direction != newDirection) {
            zoneEnteredAt = SystemClock.uptimeMillis()
            Log.i(TAG, "zone entered dir=$newDirection y=${y.toInt()} height=$height")
        }
        list = grid
        direction = newDirection
        depth = newDepth
        if (!running) {
            running = true
            grid.postOnAnimation(frame)
        }
    }

    /** Ends any scroll in flight. Safe to call at any time; call from onDragExit and onDragEnd. */
    @JvmStatic
    fun stop() {
        if (running) Log.i(TAG, "stopped")
        running = false
        direction = 0
        depth = 0f
        list?.removeCallbacks(frame)
        list = null
    }
}
