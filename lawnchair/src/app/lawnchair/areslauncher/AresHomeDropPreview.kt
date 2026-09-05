package app.lawnchair.areslauncher

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Utilities
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragOptions

/**
 * Makes the home grid open a space for an item that is still being held (§C4), and moves that
 * space around exactly the way an in-grid reorder moves a lifted tile.
 *
 * ## The gap this closes
 *
 * The grid has exactly one reflow, and it is driven by `ItemTouchHelper`:
 * [AresHomeReorder.Callback.onMove] calls `AresHomeAdapter.moveItem`, RecyclerView animates, and a
 * tile visibly slides aside as the finger approaches it. That pipeline only exists for a drag that
 * *starts* on the grid.
 *
 * A drag that arrives from outside — an app out of an open folder, an app from the app list, a
 * widget from the picker — is a `DragController` drag, with the item in a `DragView` and nothing in
 * the adapter to move. Nobody was asking the grid to make room, so it did not. Measured on
 * emulator-5554 before this file existed, dragging an app out of a folder onto the grid:
 *
 * ```
 * edit  : FolderIcon@20,195 | DoubleShadowBubbleTextView@280,195 | DoubleShadowBubbleTextView@540,195
 * mid   : FolderIcon@20,195 | DoubleShadowBubbleTextView@280,195 | DoubleShadowBubbleTextView@540,195
 * after : FolderIcon@20,195 | DoubleShadowBubbleTextView@280,195 | DoubleShadowBubbleTextView@540,195 | DoubleShadowBubbleTextView@800,195
 * ```
 *
 * Byte-identical while held; the fourth tile appears only on release. The owner's report exactly:
 * *"the home screen does not readjust around the app until release."*
 *
 * ## Why an adapter entry rather than a drawn preview
 *
 * The grid's position model is an **ordered sequence** — `rank` plus a footprint, no stored x/y
 * (§4) — and the packer is a pure function of it. So there is no way to express "leave a space
 * here" other than by putting something in the sequence. Drawing a ghost rectangle would be a
 * second, parallel layout model that agrees with the packer only by luck, which is the failure mode
 * this project has hit repeatedly.
 *
 * So the space is a real adapter entry that renders nothing — [AresHomeAdapter.showDropSlot] —
 * carrying the held item's footprint and container.
 *
 * ## Why ItemTouchHelper drives it, not a hit-test (row 97, second report)
 *
 * The first version moved the slot itself: on every `onDragOver` it hit-tested the finger point
 * ([AresHomeListView.dropIndexAt]) and slid the slot to that index. That is a second reorder
 * rule, and it felt like one. Owner, 2026-09-04 on the Pixel, holding a widget from the picker:
 * *"the preview for the widget placement isn't great … it should be just like moving an icon or
 * widget around the home page."* The in-grid feel comes from things this file would have had to
 * copy: a move threshold that scales with the lifted item, a coverage rule for widgets with travel
 * hysteresis ([AresHomeReorder.Callback.chooseDropTarget]), the dwell freeze, and edge scrolling
 * paced by the lifted item's own position. Copying them is exactly the parallel-model mistake the
 * section above refuses for layout.
 *
 * So instead the slot is *lifted*: once its holder is laid out, this dispatches a synthetic
 * `ACTION_DOWN` inside it and calls `ItemTouchHelper.startDrag` on it, then relays every
 * subsequent `onDragOver` position as a synthetic `ACTION_MOVE`. From there the in-grid callback
 * owns the reflow with no knowledge that the finger is really on the `DragLayer` — the slot is a
 * lifted tile that happens to draw nothing, and the tiles part around it the way they part around
 * a real one. The drop ([take]) or the drag's end ([clear]) sends the matching `UP`/`CANCEL`, which
 * is how the in-grid drag ends too. `AresHomeListView.dispatchSyntheticEvent` is the relay the
 * §25 live-create seam already uses for the same purpose.
 *
 * The callback treats the slot specially in four places, all keyed on
 * [AresHomeAdapter.isDropSlot]: it never feeds the in-grid dwell (the EXTERNAL dwell,
 * [AresFolderDrop.onExternalDragOver], is the one tracking this drag), it does not close open
 * floating views when the slot is picked up, and on release it neither commits a folder drop nor
 * persists — [AresHomeDrop] owns what happens to the real item. Everything else — threshold,
 * coverage, hysteresis, freeze, edge scroll — is the in-grid code, untouched.
 *
 * ## Scope
 *
 * Every `DragController` drag over the grid. The first version took only items that already had a
 * database row; its stated reasons did not survive reading (the slot has its own id, so an
 * `AppInfo`'s `NO_ID` collides with nothing, and a `PendingAddWidgetInfo` carries exactly the spans
 * the gap needs), and what the exclusion cost was measured on the owner's Pixel: a widget landed
 * where it was released while nothing moved until release. A picker drag whose configure activity
 * is later cancelled leaves no trace: the slot is taken at the drop before the add is requested.
 */
object AresHomeDropPreview : DragController.DragListener {

    private const val TAG = "AresHomeDropPreview"

    /** How many animation frames to wait for the slot's holder before giving up on the lift. */
    private const val START_ATTEMPTS = 6

    private var list: AresHomeListView? = null

    /** Held only to deregister in [onDragEnd]; never used to reach anything else. */
    private var host: Launcher? = null

    /** True from the moment `ItemTouchHelper` took the slot until the matching UP/CANCEL. */
    private var driving = false

    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var startAttempts = 0

    /**
     * True while the slot is a lifted `ItemTouchHelper` item. [AresExternalDragScroll] stands down
     * then: ItemTouchHelper's own edge scroll is running, paced by the slot's position, the same
     * one an in-grid drag gets.
     */
    @JvmStatic
    fun isDrivingItemTouchHelper(): Boolean = driving

    /**
     * Updates the gap for a `DragController` drag now over the grid.
     *
     * Called from `Workspace.onDragOver`, which is the only per-move hook such a drag offers —
     * the same one [AresFolderDrop.onExternalDragOver] uses, and for the same reason.
     */
    @JvmStatic
    fun onExternalDragOver(launcher: Launcher, d: DropTarget.DragObject) {
        val item = d.dragInfo ?: return
        if (!AresWidgetAdd.isAresHome(launcher)) return
        val grid = launcher.workspace?.aresHomeList ?: return

        // The pointer ItemTouchHelper sees is the DRAG VIEW's centre, not the finger. In an in-grid
        // drag the lifted tile IS what the finger holds, so the two coincide; here the picture on
        // the finger is a DragView with its own registration offset (an app-list icon measured
        // ~375px right of the finger on emulator-5554 unfolded), and the hole has to follow the
        // picture the user is looking at, not a point they cannot see. The DOWN lands at the slot's
        // own centre (see startDrive), so the slot's visual centre tracks this point exactly.
        val finger = AresFolderDrop.toListSpace(launcher, grid, d.x.toFloat(), d.y.toFloat())
        val anchor = dragViewCentre(launcher, grid, d) ?: finger
        lastX = anchor[0]
        lastY = anchor[1]

        if (list === grid) {
            // Already open. Once lifted, every position is a synthetic MOVE and ItemTouchHelper
            // decides what (if anything) to displace -- including declining while the dwell has
            // the reflow frozen, which chooseDropTarget already does for an in-grid drag. Before
            // the lift, the pending start reads lastX/lastY on its own.
            if (driving) {
                grid.dispatchSyntheticEvent(
                    MotionEvent.ACTION_MOVE, downTime, SystemClock.uptimeMillis(), lastX, lastY,
                )
            }
            return
        }

        // Do not OPEN under a frozen reflow: a dwell aiming at a tile must not have the target
        // shoved aside by a hole appearing beside it. Once the freeze lifts the next move opens it.
        if (AresFolderDrop.isFrozen()) return

        clear()
        list = grid
        host = launcher
        // Idempotent by contract: DragController holds an ArrayList and this is only reached
        // when `list` was null or a different grid, both of which run through clear() first.
        launcher.dragController.addDragListener(this)
        val index = grid.dropIndexAt(anchor[0], anchor[1])
        val widget = item.itemType == Favorites.ITEM_TYPE_APPWIDGET ||
            item.itemType == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET
        grid.aresAdapter.showDropSlot(
            index,
            item.spanX.coerceAtLeast(1),
            item.spanY.coerceAtLeast(1),
            widget,
        )
        startAttempts = 0
        // The holder exists only after the next layout pass; lift it then.
        grid.postOnAnimation(startDrive)
    }

    /**
     * The drag view's visual centre in [grid]'s coordinates, or null when there is no drag view.
     * `translationX/Y` is how `DragView.move` positions it (including its animated shift), and a
     * scale about the centre leaves the centre where it is, so no scale term is needed.
     */
    private fun dragViewCentre(
        launcher: Launcher,
        grid: AresHomeListView,
        d: DropTarget.DragObject,
    ): FloatArray? {
        val dv = d.dragView ?: return null
        // Fresh array: mapCoordInSelfToDescendant maps IN PLACE and never zeroes.
        val coord = floatArrayOf(
            dv.left + dv.translationX + dv.width / 2f,
            dv.top + dv.translationY + dv.height / 2f,
        )
        Utilities.mapCoordInSelfToDescendant(grid, launcher.dragLayer, coord)
        return coord
    }

    /**
     * Lifts the slot once its holder is laid out.
     *
     * The synthetic DOWN lands at the slot's own CENTRE, for two reasons: `ItemTouchHelper` keeps
     * the lifted view at `start + (pointer - down)`, so with the pointer being the drag view's
     * centre the slot's visual centre sits exactly under the picture from the first MOVE on; and
     * [AresHomeListView.onInterceptTouchEvent] arms its empty-space long-press on a DOWN that hits
     * no child, which a still finger would then fire as a popup mid-drag.
     *
     * Proof of path is `isReorderInProgress()`, which `onSelectedChanged` sets synchronously inside
     * `startDrag`; a refused lift (no drag flag, holder not a child) is logged, and the gap then
     * simply stays where it opened rather than failing silently.
     */
    private val startDrive = object : Runnable {
        override fun run() {
            val grid = list ?: return
            val position = grid.aresAdapter.dropSlotIndex()
            val holder = if (position >= 0) grid.findViewHolderForAdapterPosition(position) else null
            val v = holder?.itemView
            if (v == null || v.parent !== grid || v.width == 0 || v.height == 0) {
                if (++startAttempts <= START_ATTEMPTS) {
                    grid.postOnAnimation(this)
                } else {
                    Log.w(TAG, "slot holder not laid out after $START_ATTEMPTS frames; gap stays static")
                }
                return
            }
            val downX = (v.left + v.right) / 2f
            val downY = (v.top + v.bottom) / 2f
            downTime = SystemClock.uptimeMillis()
            grid.dispatchSyntheticEvent(MotionEvent.ACTION_DOWN, downTime, downTime, downX, downY)
            grid.startSlotDrag(holder)
            driving = grid.isReorderInProgress()
            Log.i(
                TAG,
                "ItemTouchHelper ${if (driving) "took" else "REFUSED"} the slot at index $position " +
                    "(down=${downX.toInt()},${downY.toInt()} anchor=${lastX.toInt()},${lastY.toInt()})",
            )
            if (driving) {
                grid.dispatchSyntheticEvent(
                    MotionEvent.ACTION_MOVE, downTime, SystemClock.uptimeMillis(), lastX, lastY,
                )
            } else {
                // A refused lift must not leave the DOWN half-open in the RecyclerView (velocity
                // tracker, scroll pointer, gesture detector) until some later real DOWN resets it
                // (nightly review 2026-09-05, F4). Same downTime, so it cancels THIS gesture.
                grid.dispatchSyntheticEvent(
                    MotionEvent.ACTION_CANCEL, downTime, SystemClock.uptimeMillis(), downX, downY,
                )
            }
        }
    }

    /**
     * State-seam P5 / ledger S5: a fold recreates the Launcher mid-drag, and if the drag's terminal
     * callback never reaches this singleton it would keep the OLD activity's grid, a lifted slot
     * and a listener on a dead controller until the next drag's `clear()` healed it against a
     * detached RecyclerView (nightly review 2026-09-05, F6). Mirrors [AresFolderPreview]'s hook;
     * a no-op when nothing is in flight or when the drag belongs to another activity.
     */
    @JvmStatic
    fun onLauncherDestroyed(launcher: Launcher) {
        if (host === launcher) clear()
    }

    /**
     * Closes the gap and reports the index it held, or -1 when there was none.
     *
     * Called first thing in [AresHomeDrop.handleExternalDrop] so the slot can never be present
     * while anything persists, and so the item lands **where the gap was** — which is what the user
     * has been watching for the whole drag, and is a stricter answer than re-deriving a drop index
     * from the release point. The lift ends with a synthetic UP first, the same event that ends an
     * in-grid drag; the callback's clearView sees the slot and leaves persistence to the caller.
     */
    @JvmStatic
    fun take(): Int {
        endDrive(MotionEvent.ACTION_UP)
        val at = list?.aresAdapter?.clearDropSlot() ?: -1
        detach()
        return at
    }

    /** Closes the gap without reading it. Safe at any time, and idempotent. */
    @JvmStatic
    fun clear() {
        endDrive(MotionEvent.ACTION_CANCEL)
        list?.aresAdapter?.clearDropSlot()
        detach()
    }

    private fun endDrive(action: Int) {
        val grid = list ?: return
        grid.removeCallbacks(startDrive)
        if (!driving) return
        driving = false
        grid.dispatchSyntheticEvent(action, downTime, SystemClock.uptimeMillis(), lastX, lastY)
    }

    private fun detach() {
        list = null
        host?.dragController?.removeDragListener(this)
        host = null
    }

    override fun onDragStart(dragObject: DropTarget.DragObject?, options: DragOptions?) = Unit

    /**
     * The backstop, and the reason this is a `DragListener` at all.
     *
     * A drop on the grid is handled by [AresHomeDrop], but most drags end some other way: cancelled
     * by a call or the shade, released over the drop-target bar, refused over the app-list pane,
     * dropped back into the folder it came from, or committed into a folder by a dwell.
     * `DragController` calls this for every one of them, so there is no outcome that can leave a
     * hole in the grid or a lifted slot in `ItemTouchHelper`.
     */
    override fun onDragEnd() = clear()
}
