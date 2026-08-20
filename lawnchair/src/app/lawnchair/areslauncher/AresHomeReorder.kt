package app.lawnchair.areslauncher

import android.graphics.Canvas
import android.util.Log
import android.view.View
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
 * while the finger moves** — the signature WP behaviour — with no ghost placeholder.
 *
 * What that gives on its own is the right *positions*, arrived at instantly: a displaced tile
 * appears at its new cell rather than travelling to it. The travel is
 * [AresMasonryLayoutManager.reflowActive], which springs each moved tile from where it was drawn to
 * where the packer just put it. Deliberately a spring rather than a per-change animation — see
 * [AresEditMotion] for why restarting an animator is what produces the snap it is meant to remove.
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

        companion object {
            /**
             * How far the drag must travel toward a tile before that tile is reflowed aside.
             *
             * `1.0` would be "the drag centre has reached the tile's centre", and it has to be a
             * little **past** that: aiming at a tile's middle is exactly how a person expresses
             * "merge with this one", so the displacement must not fire at the point they are
             * aiming for. `1.10` leaves roughly a tenth of a cell of headroom beyond the centre —
             * about 26px on the emulator's grid — which is comfortably more than
             * `AresFolderDrop`'s dwell slop, so a steady finger on the centre dwells rather than
             * shoves.
             *
             * One knob. Lower it and tiles move sooner but the dwell window narrows; raise it and
             * the reflow feels reluctant. `ItemTouchHelper` will not even ask below `0.5`
             * (`moveIfNecessary` returns before calling `chooseDropTarget` until the drag has
             * covered half its own size), so values under that are indistinguishable from `0.5`.
             *
             * **Upper bound is set by the harness, not by taste.** `ares-journeys.ps1`'s
             * `reorder-persists` drags from a point biased 22% left of its tile's centre onto the
             * next tile's exact centre, which lands the drag centre at `1.22` — so anything at or
             * above that stops the journey reordering at all, and the script is not ours to edit.
             */
            const val SWAP_TRAVEL_FRACTION = 1.10f
        }

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
         * by overlap area — and only once the drag has travelled far enough to displace it.
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

            // Hit-test the CELL, not the drawn tile.
            //
            // §23 insets every tile by half a gutter on each side, so `v.left`/`v.right` are inset
            // from the cell the tile occupies and the gutters became dead zones for targeting.
            // That is not a rare miss for a wide item: a full-width widget's centre sits exactly at
            // the grid's horizontal midpoint, which for an even column count is always a tile
            // boundary -- and once a gutter exists, a gap. Measured on a 4-column grid, tiles at
            // 10-250 / 270-510 / 530-770 / 790-1030, a full-width widget's centre is x=520, which
            // is inside NO tile. So dragging a widget over the icons chose no target, fired no
            // onMove, and nothing reflowed: "when moving widgets, they're not moving apps as
            // expected. Apps shouldn't be behind widgets when moving widgets around."
            //
            // Adding the slack back restores the pre-gutter behaviour exactly (flush tiles made
            // x=520 land on a boundary that the >= test already accepted) without widening the
            // target beyond the cell it stands for.
            val slack = list.tileGutterPx() / 2
            dropTargets.forEach { target ->
                val v = target.itemView
                if (centreX >= v.left - slack && centreX < v.right + slack &&
                    centreY >= v.top - slack && centreY < v.bottom + slack
                ) {
                    // THE OTHER HALF OF THE DWELL, and it is here rather than in AresFolderDrop
                    // because it is a statement about reordering, not about folders.
                    //
                    // Creating a folder needs a moment where the target is under the drag and has
                    // NOT been reflowed aside -- otherwise there is nothing to dwell on and nothing
                    // to draw a ring around. The freeze solves that for folder tiles, but it cannot
                    // be used for icons: every tile is a create-a-folder candidate, so freezing on
                    // entry would suspend the reflow across the whole grid and reordering would
                    // stop working (see AresFolderDrop.isFrozen).
                    //
                    // So the threshold moves instead. A tile is displaced when the drag *reaches
                    // its centre*, not the instant it touches its edge, which leaves its leading
                    // half as exactly that moment. A drag that keeps going still displaces it, so
                    // nothing about ordinary reordering is given up -- the swap simply fires half a
                    // tile later than ItemTouchHelper's own 50%-of-travel trigger would have.
                    return if (hasReached(selected, v, centreX, centreY)) target else null
                }
            }
            return super.chooseDropTarget(selected, dropTargets, curX, curY)
        }

        /**
         * True when the drag centre has covered [SWAP_TRAVEL_FRACTION] of the way from the dragged
         * tile's own slot to [target]'s centre.
         *
         * Measured as a **projection onto the line between the two slot centres**, not as a raw
         * distance: masonry reorders in two axes, so "past it" has no meaning without a direction,
         * and the direction that matters is the one the drag is travelling. `selected.itemView`'s
         * `left`/`top` are its *layout* slot — `ItemTouchHelper` expresses the drag as a
         * translation on top of that — so the vector is exactly "from where this tile currently
         * sits to where the tile it is aiming at sits".
         */
        private fun hasReached(
            selected: RecyclerView.ViewHolder,
            target: View,
            centreX: Int,
            centreY: Int,
        ): Boolean {
            val from = selected.itemView
            val fromX = from.left + from.width / 2f
            val fromY = from.top + from.height / 2f
            val toX = target.left + target.width / 2f
            val toY = target.top + target.height / 2f
            val dx = toX - fromX
            val dy = toY - fromY
            val span = dx * dx + dy * dy
            // Coincident slot centres: nothing to travel, so the swap is always allowed. Reachable
            // only if a holder is mid-relayout, and declining would wedge the reorder.
            if (span <= 0f) return true
            val travelled = (centreX - fromX) * dx + (centreY - fromY) * dy
            return travelled >= span * travelFractionFor(selected)
        }

        /**
         * How far past a target's centre the drag must go before that target is displaced.
         *
         * [SWAP_TRAVEL_FRACTION]'s 10% overshoot exists for exactly one reason: it leaves a tile's
         * leading half as a window where the target has NOT yet been reflowed aside, which is the
         * moment dwell-to-create-a-folder needs in order to have something to dwell on.
         *
         * **A widget cannot go into a folder**, so for a widget drag that window buys nothing and
         * costs a great deal. The overshoot is 10% *of the distance to the target's centre*, and a
         * large widget's centre starts far from a small icon's, so the absolute travel it demands
         * is correspondingly large. Measured dragging a full-width 4x4 widget up one row: the swap
         * needed the widget's centre to travel 950px, against 853px to simply arrive at the target
         * centre. Anything short of that displaced nothing at all, which is what the grid not
         * reacting looked like -- "when moving widgets, they're not moving apps as expected. Apps
         * shouldn't be behind widgets when moving widgets around."
         *
         * Reaching the centre is the natural threshold; only the folder case needs more.
         */
        private fun travelFractionFor(selected: RecyclerView.ViewHolder): Float {
            val info = list.aresAdapter.itemAt(selected.bindingAdapterPosition)
            val isWidget = info != null && info.itemType == Favorites.ITEM_TYPE_APPWIDGET
            return if (isWidget) 1f else SWAP_TRAVEL_FRACTION
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
            // the edit-mode float ([AresEditWiggle]) and the live reflow ([AresEditMotion]). Both
            // stand down for the duration -- because two writers on one property fight
            // frame-by-frame, and because a lifted tile reads correctly only if it is still.
            viewHolder?.itemView?.let { list.setFloatSuspendedFor(it) }

            // The visible answer to "I have picked this up": the tile swells slightly. Scale, not
            // translation, so it composes with ItemTouchHelper rather than competing with it --
            // the two write different properties and never meet.
            viewHolder?.itemView?.let { list.setPickedUp(it) }

            // A popup may still be open from the long-press that entered edit mode. Once the finger
            // moves and this becomes a reorder, it is stale.
            AbstractFloatingView.closeAllOpenViews(launcher)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            // Runs first: the superclass clears ItemTouchHelper's own translation, so the float
            // below restarts from rest rather than from wherever the drop settled.
            super.clearView(recyclerView, viewHolder)
            list.setFloatSuspendedFor(null)
            // Back to the mode's resting size -- which is read from the mode, not from a constant,
            // so a drag that outlived edit mode settles at 1.0 rather than snapping to 0.92.
            list.setPickedUp(null)
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
