package app.lawnchair.areslauncher

import android.util.Log
import android.view.MotionEvent
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.model.data.ItemInfo

/**
 * SPIKE (rows 31/32, owner-directed 2026-08-21): hand a folder-exit drag over to the in-grid
 * pipeline the moment it crosses onto the grid.
 *
 * ## The owner's observation, which is the whole design
 *
 * > "putting an app into a folder but not letting go, and then moving it out of the folder and
 * > onto the grid works fine. [...] maybe focus on getting it into the same or similar state
 * > [as] an app being dragged into and then out of the folder without letting go is actively in.
 * > because the latter already works"
 *
 * The into-and-out-without-release flow never leaves `ItemTouchHelper`: the item stays a real
 * grid tile, so it gets the live reflow, the dwell that can CREATE folders, the aim-freeze and
 * the settle for free. A drag that STARTS inside the folder is a `DragController` drag instead —
 * a floating `DragView` over a grid that only cooperates through bolt-on mimicry (the §C4 drop
 * slot, the external dwell feed), and every row-31/32 symptom lives in that bolt-on layer. So:
 * stop mimicking. At the first grid crossing, the item becomes a real adapter entry under the
 * finger and `ItemTouchHelper` drives from there — the two flows converge on the machinery that
 * already works.
 *
 * ## How the gesture keeps flowing
 *
 * The real finger's event stream is locked to the folder's touch chain (locked at DOWN), so the
 * grid never sees it. The `DragController` keeps receiving it, and this object RELAYS: each
 * `Workspace.onDragOver` becomes a synthetic MOVE dispatched into [AresHomeListView], the real
 * drop becomes a synthetic UP, a cancelled drag a synthetic CANCEL. The DragController's own
 * `DragView` is hidden (not cancelled — cancelling would stop the event source), and its drop is
 * consumed as a no-op by [AresHomeDrop.handleExternalDrop] because by then the in-grid pipeline
 * owns the item. Mid-gesture pipeline handoff has precedent here: `AresFolderDrag.DragStarter`
 * is installed mid-stream and synthesises its origin the same way (see D4's history).
 *
 * The model half is the same sequence the drop used to run, just at crossing time instead of
 * release time: `Folder.onDragStart` already took the item out of the `FolderInfo` when the drag
 * began; here the row moves to `CONTAINER_DESKTOP` (legal cell first — the loader deletes what
 * it rejects) and joins the adapter at the finger's index.
 */
object AresFolderExitHandoff : DragController.DragListener {

    private const val TAG = "AresFolderExitHandoff"

    /** The list carrying an active handoff, or null. One drag at a time, like every drag. */
    private var list: AresHomeListView? = null
    private var host: Launcher? = null

    /** True once the real drop was consumed, so [onDragEnd] relays UP rather than CANCEL. */
    private var dropped = false

    /**
     * The drag object this handoff has DECLINED (conversion failed), so its every later move is
     * waved through to the old pipeline instead of retried. Scoped to the specific [DragObject]
     * rather than a process-global boolean: the decline paths below run *before* [addDragListener],
     * so [onDragEnd] — the only reset — never fires for a declined drag. A boolean therefore latched
     * true for the rest of the process and disabled the in-grid pipeline permanently (ledger row 36,
     * state-seam P5). `DragObject` is created fresh per drag (`LauncherDragController` builds a new
     * one in `startDrag`), so `d === declinedFor` only suppresses the drag that was actually
     * declined; the next drag's new object clears the block by identity.
     */
    private var declinedFor: DropTarget.DragObject? = null

    private var downTime = 0L

    /** True while [d]'s drag is owned by the in-grid pipeline. */
    @JvmStatic
    fun isActive(): Boolean = list != null

    /**
     * True while the DWELL's teardown belongs to the in-grid pipeline rather than to
     * `Workspace.onDragEnd`. Outlives [isActive] by the settle: the DragController ends at the
     * real UP, but the in-grid commit runs from `clearView` ~250ms later, and Workspace's
     * always-runs-last `AresFolderDrop.cancel()` was measured wiping the armed target in that
     * gap — the dwell armed, froze, and then committed nothing. The hold is released by a timer
     * comfortably past the settle; every in-grid ending (UP, CANCEL, detach) clears the dwell
     * itself, so the hold only ever suppresses a redundant cancel, never replaces a needed one.
     */
    @JvmStatic
    fun ownsDwellTeardown(): Boolean = list != null || teardownHold

    private var teardownHold = false

    /**
     * Takes over [d]'s drag if it qualifies: an item with a database row, dragged from outside
     * the grid (a folder), now over the grid. Returns true when the caller must not run its own
     * external-drag handling for this move.
     */
    @JvmStatic
    fun maybeTakeOver(
        launcher: Launcher,
        grid: AresHomeListView,
        d: DropTarget.DragObject,
        x: Float,
        y: Float,
    ): Boolean {
        if (list != null) {
            relay(MotionEvent.ACTION_MOVE, x, y)
            return true
        }
        if (d === declinedFor) return false
        val info = d.dragInfo ?: return false
        // Same population as the §C4 slot: an item that already has a row. Everything else
        // (picker widgets, app-list drags) keeps the existing pipeline.
        if (info.id == ItemInfo.NO_ID) return false
        if (!grid.isEditMode()) {
            // The in-grid pipeline is an edit-mode machine; outside it, keep the old path.
            declinedFor = d
            return false
        }

        // Model first: a legal cell, the row moved to the desktop, the adapter entry under the
        // finger. addOrMoveItemInDatabase is the same write the drop-time path used.
        val cell = IntArray(2)
        val screenId = AresWidgetAdd.findFreeCell(launcher, info.spanX, info.spanY, cell, info.id)
        if (screenId == AresWidgetAdd.NO_SCREEN) {
            declinedFor = d
            return false
        }
        val at = grid.dropIndexAt(x, y).coerceIn(0, grid.aresAdapter.itemCount)
        info.rank = at
        launcher.modelWriter.addOrMoveItemInDatabase(
            info,
            Favorites.CONTAINER_DESKTOP,
            screenId,
            cell[0],
            cell[1],
        )
        grid.aresAdapter.addItemAt(info, at)

        // The holder exists only after a layout pass; force one now, synchronously — the finger
        // is mid-flight and the next MOVE arrives in ~8ms.
        grid.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(grid.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(grid.height, android.view.View.MeasureSpec.EXACTLY),
        )
        grid.layout(grid.left, grid.top, grid.right, grid.bottom)
        val holder = grid.findViewHolderForItemId(info.id.toLong())
        if (holder == null) {
            // Could not surface a holder; put the model back the way the old pipeline expects
            // (the item stays desktop-bound — the drop will place it) and decline.
            Log.w(TAG, "handoff declined: no holder for id=${info.id} after layout")
            grid.aresAdapter.removeItems { it === info }
            declinedFor = d
            return false
        }

        list = grid
        host = launcher
        dropped = false
        launcher.dragController.addDragListener(this)

        // Hide the DragController's floating view; the real tile under the finger is the visual
        // now. NOT cancelled: the controller is the event source for the rest of the gesture.
        d.dragView?.alpha = 0f

        // The in-grid pipeline takes the gesture: synthetic DOWN seeds ItemTouchHelper's
        // coordinates, startDrag selects the holder, and the relayed MOVEs drive it from here.
        downTime = android.os.SystemClock.uptimeMillis()
        grid.dispatchSyntheticHandoffEvent(MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        grid.startHandoffDrag(holder)
        Log.i(TAG, "handoff: id=${info.id} joined the grid at $at under the finger")
        return true
    }

    /** Consumes the real drop. The synthetic UP in [onDragEnd] is what actually places. */
    @JvmStatic
    fun consumeDrop(): Boolean {
        if (list == null) return false
        dropped = true
        return true
    }

    private fun relay(action: Int, x: Float, y: Float) {
        val grid = list ?: return
        val now = android.os.SystemClock.uptimeMillis()
        grid.dispatchSyntheticHandoffEvent(action, downTime, now, x, y)
    }

    override fun onDragStart(dragObject: DropTarget.DragObject?, options: DragOptions?) = Unit

    /**
     * Every DragController drag ends exactly once, dropped or not. The relay ends the in-grid
     * drag the same way the finger would have: UP when the drop was consumed, CANCEL otherwise
     * (shade, call, drop over some other target) — which is precisely the distinction the S3
     * latch downstream keys on.
     */
    override fun onDragEnd() {
        val grid = list ?: run { declinedFor = null; return }
        val last = grid.lastHandoffPoint()
        // Hold dwell-teardown ownership through the settle window; see [ownsDwellTeardown].
        teardownHold = true
        grid.postDelayed({ teardownHold = false }, TEARDOWN_HOLD_MS)
        relay(
            if (dropped) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL,
            last[0],
            last[1],
        )
        host?.dragController?.removeDragListener(this)
        list = null
        host = null
        dropped = false
        declinedFor = null
    }

    /** Past the ~250ms settle plus margin; the in-grid ending has cleared the dwell by then. */
    private const val TEARDOWN_HOLD_MS = 800L
}
