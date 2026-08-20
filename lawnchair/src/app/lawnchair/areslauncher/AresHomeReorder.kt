package app.lawnchair.areslauncher

import android.graphics.Canvas
import android.util.Log
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.WorkspaceLayoutManager
import com.android.launcher3.model.data.ItemInfo

/**
 * AresLauncher §4 — drag-to-reorder for the masonry home grid.
 *
 * ## Why [ItemTouchHelper] rather than a custom drag controller
 *
 * The obvious objection is that `ItemTouchHelper` is a *list* abstraction and this is a 2D grid.
 * But under the Windows Phone model the grid **is** an ordered list — position is derived by
 * [AresPacker] from `rank`, never stored — so a drop target is a *rank insertion point*, which is
 * exactly what `onMove(from, to)` expresses. Nothing has to be mapped into cell coordinates.
 *
 * What it brings for free is the expensive half: edge auto-scroll during a drag (needed here,
 * because the grid scrolls and a drag must be able to reach off-screen positions), drag elevation
 * and the settle animation. A custom controller would mean reimplementing all of that to arrive at
 * the same place.
 *
 * Two of its defaults are wrong for this model and are overridden below: long-press-to-drag (edit
 * mode owns that now) and the drop-target heuristic (see [Callback.chooseDropTarget]).
 *
 * ## Live reflow falls out of the model
 *
 * [Callback.onMove] reorders the adapter, which notifies a move, which invalidates the packing
 * ([AresMasonryLayoutManager.onItemsMoved] nulls its cached layout). The next layout pass re-runs
 * the packer over the new order, so every other item repacks around the dragged one **continuously
 * while the finger moves** — the signature WP behaviour — with no ghost placeholder and no separate
 * animation code. It is the same pure function called more often.
 */
object AresHomeReorder {

    private const val TAG = "AresHomeReorder"

    /**
     * Writes the list's current visual order to the model as `rank`.
     *
     * ## Why `rank`, and why the item's grid position is deliberately left alone
     *
     * `rank` is a first-class flat ordinal already in the schema, independent of grid coordinates,
     * and already the authoritative order for folders, app pairs and the Hotseat. See
     * design/component-verification-3.md §2.
     *
     * That doc recommended `moveItemInDatabase(item, container, screenId, 0, 0)`, reasoning that
     * cellX/cellY are inert for a list. **That is true for folder contents and false for
     * `CONTAINER_DESKTOP`, and following it deleted the entire home screen on the next reboot.**
     * Folder children live outside the grid, but desktop items are occupancy-checked by the loader:
     * writing every row to cell (0,0) made them collide, and `LoaderCursor` discarded each one:
     *
     * ```
     * cell(0,0) span(1,1) rank=2 ... into cell (0-0:0,0,1,1) already occupied
     * Item position overlap
     * ```
     *
     * So the position is left exactly as the model already had it — each item keeps the distinct,
     * in-bounds cell it was placed at, which the loader already accepts — and only `rank` changes.
     * [com.android.launcher3.model.ModelWriter.updateItemInDatabase] is the right call for that: it
     * writes the item's *current* fields via `ItemInfo.onAddToDatabase`, which includes RANK, so
     * mutating `rank` alone and re-writing leaves container/screen/cellX/cellY untouched.
     *
     * Our rendering never reads cellX/cellY/screenId — [AresPacker] derives every position from
     * order alone — so they remain pure bookkeeping that exists only to keep the loader happy.
     *
     * ## Why writes are logged
     *
     * A discarded model write is **silent** — no exception, just a debug log — so data loss looks
     * exactly like success. A `ModelTask` captures the load generation when it is created and drops
     * itself if a reload intervened; a writer used before the first load completes carries the
     * sentinel `mLoadId = -1` and *every* write through it is doomed. That was the Phase 8 widget
     * failure. Reorder is user-initiated long after the first load, so it is not in that window, but
     * the logging is here so that if it ever does happen it is visible rather than mysterious. See
     * design/model-persistence.md.
     */
    fun persistOrder(launcher: Launcher, items: List<ItemInfo>) {
        // Fetched at write time rather than cached, so a writer is never held across a reload.
        val writer = launcher.modelWriter
        var written = 0

        items.forEachIndexed { index, info ->
            if (info.rank == index) return@forEachIndexed
            if (info.container != Favorites.CONTAINER_DESKTOP) {
                // Only desktop rows belong to this grid's ordering; anything else here would be a
                // bug elsewhere, and rewriting it would corrupt it.
                Log.w(TAG, "skipping non-desktop item id=${info.id} container=${info.container}")
                return@forEachIndexed
            }
            if (info.screenId == WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_ID ||
                info.screenId == WorkspaceLayoutManager.EXTRA_EMPTY_SCREEN_SECOND_ID
            ) {
                // ItemInfo.onAddToDatabase throws on these, which would take down the write pass.
                Log.e(TAG, "skipping item id=${info.id} on sentinel screen ${info.screenId}")
                return@forEachIndexed
            }

            // Only rank changes. Position is deliberately untouched -- see the doc above.
            info.rank = index
            writer.updateItemInDatabase(info)
            written++
        }

        Log.i(TAG, "persistOrder: ${items.size} rows, $written rank writes enqueued")
    }

    /**
     * [ItemTouchHelper.Callback] for the masonry home grid.
     *
     * Movement is four-way because a grid reorders in two axes. Swipe-to-dismiss stays off:
     * removing an item is the long-press menu's job, and a horizontal swipe here would collide with
     * [AresPaneSwipeController], which claims horizontal drags anywhere on the home screen.
     */
    class Callback(
        private val launcher: Launcher,
        private val list: AresHomeListView,
    ) : ItemTouchHelper.Callback() {

        /**
         * False: edit mode starts drags, not long-press.
         *
         * The spec is Windows Phone's persistent edit mode — long-press puts the *whole surface*
         * into an editable state, after which any item can be dragged with a plain touch-and-move.
         * `ItemTouchHelper`'s built-in long-press-to-drag is the Android one-shot model instead, so
         * it is disabled and [AresHomeListView] calls `startDrag` itself.
         */
        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int = makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END,
            0,
        )

        /** The item being dragged, for the dwell's hit-test exclusion and its drop resolution. */
        private var draggedInfo: ItemInfo? = null

        /**
         * Feeds the dragged tile's position to [AresFolderDrop] on every frame of the drag.
         *
         * ## Why this hook and not the obvious ones
         *
         * `ItemTouchHelper` only calls [chooseDropTarget] from `moveIfNecessary`, which runs on
         * `ACTION_MOVE` and bails before ever reaching the callback if the drag has not travelled
         * far enough. So the one thing a dwell needs to observe — the finger holding **still** — is
         * precisely the case those hooks never report. `onChildDraw` runs every frame the list
         * draws, which during edit mode is continuous because the wiggle is a never-ending
         * animator, so the position is sampled whether or not it changed. [AresFolderDrop] decides
         * what counts as movement; this only reports.
         *
         * `left + translationX` rather than the raw touch point: `ItemTouchHelper` keeps
         * `left + translationX == mSelectedStartX + mDx`, so this is the drag position in the
         * list's own coordinates and stays correct across the reflow moving the holder underneath
         * it. It is also the same quantity [chooseDropTarget] scores against, so "what will I swap
         * with" and "what will I drop into" cannot disagree.
         */
        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            // Recover animations for other rows, and the settle after the drop, come through here
            // too; neither is the finger.
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return
            val item = draggedInfo ?: return
            val view = viewHolder.itemView
            AresFolderDrop.onDragPoint(
                list,
                item,
                view.left + view.translationX + view.width / 2f,
                view.top + view.translationY + view.height / 2f,
            )
        }

        /**
         * Picks the drop target by **which item contains the dragged view's centre**, rather than
         * by overlap area.
         *
         * The stock heuristic scores candidates on how much of the dragged view overlaps each one.
         * That is sound when every item is the same size, and misleading here: a 2x2 widget being
         * dragged over 1x1 icons overlaps several of them at once, and the largest overlap is not
         * necessarily the one under the finger. Centre containment matches what the user is aiming
         * at and stays stable as footprints differ.
         *
         * Falls back to the stock choice when the centre is over empty space, so a drag into a gap
         * still resolves to something sensible rather than doing nothing.
         */
        override fun chooseDropTarget(
            selected: RecyclerView.ViewHolder,
            dropTargets: MutableList<RecyclerView.ViewHolder>,
            curX: Int,
            curY: Int,
        ): RecyclerView.ViewHolder? {
            val centreX = curX + selected.itemView.width / 2
            val centreY = curY + selected.itemView.height / 2

            // THE FREEZE, and it has to happen HERE rather than anywhere else in the callback.
            //
            // `moveIfNecessary` calls this and then immediately acts on the answer, all within the
            // ACTION_MOVE that produced it -- before the next draw. So by the time any later hook
            // could look, the reflow has already swapped the folder out from under the finger and
            // there is nothing left to dwell on. Measured as a design consequence rather than a
            // bug: §4's live reflow means a folder is pushed aside by the very drag approaching
            // it, which is the whole reason the interaction needs a dwell at all.
            //
            // So the dwell is fed first and the swap is declined while it is tracking one.
            // Answering "no target" is sufficient -- moveIfNecessary abandons on a null and never
            // reaches onMove. The freeze lasts only while the drag is actually over an eligible
            // tile: move on without stopping and the reflow resumes and catches up in one step, so
            // nothing about ordinary reordering is given up.
            draggedInfo?.let {
                AresFolderDrop.onDragPoint(list, it, centreX.toFloat(), centreY.toFloat())
            }
            if (AresFolderDrop.isFrozen()) return null

            dropTargets.forEach { target ->
                val v = target.itemView
                if (centreX >= v.left && centreX < v.right &&
                    centreY >= v.top && centreY < v.bottom
                ) {
                    return target
                }
            }
            return super.chooseDropTarget(selected, dropTargets, curX, curY)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            return list.aresAdapter.moveItem(from, to)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return

            draggedInfo = viewHolder?.let { list.aresAdapter.itemAt(it.bindingAdapterPosition) }
            list.setReorderInProgress(true)

            // ItemTouchHelper owns this view's translationX/Y until the drop settles, and so does
            // the edit-mode float ([AresEditWiggle]). The float stands down for the duration --
            // both because two writers on one property fight frame-by-frame, and because a lifted
            // tile reads correctly only if it is still.
            viewHolder?.itemView?.let { list.setFloatSuspendedFor(it) }

            // A popup may still be open from the long-press that entered edit mode. Once the finger
            // moves and this becomes a reorder, it is stale.
            AbstractFloatingView.closeAllOpenViews(launcher)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            // Runs first: the superclass clears ItemTouchHelper's own translation, so the float
            // below restarts from rest rather than from wherever the drop settled.
            super.clearView(recyclerView, viewHolder)
            list.setFloatSuspendedFor(null)
            list.setReorderInProgress(false)

            // A dwell that armed resolves as a folder drop instead of a reorder, and it renumbers
            // the grid itself once the item has left it -- so persisting the current order here as
            // well would write ranks that are about to change again.
            val item = draggedInfo
            draggedInfo = null
            val consumed = item != null && AresFolderDrop.commitDrop(launcher, item)
            list.setFolderDropTarget(null)
            if (!consumed) persistOrder(launcher, list.aresAdapter.snapshot())
        }
    }
}
