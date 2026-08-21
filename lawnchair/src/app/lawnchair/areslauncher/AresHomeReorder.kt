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

            /**
             * How much of an icon a dragged **widget** must cover before that icon is displaced.
             *
             * The feel target is *"as soon as the app is covered by the widget, it should move"*.
             * Half is where "covered" starts to read as covered rather than grazed; going much
             * lower makes a widget shove a whole row aside while it is still only clipping the
             * edge of it, and 1.0 would mean an icon stays put until it is completely buried.
             *
             * This is the knob for that feel. It has nothing to do with [SWAP_TRAVEL_FRACTION],
             * which is a *distance* threshold for icon drags; this is an *area* one.
             */
            const val WIDGET_COVER_FRACTION = 0.5f

            /**
             * How far the drag must travel between two widget swaps, in dp.
             *
             * Hysteresis, and it exists because the coverage rule is otherwise a **feedback loop**.
             * [chooseDropTarget]'s widget branch measures overlap against the targets' LIVE layout
             * bounds, and a swap is precisely what changes those bounds — so covering A swaps A
             * away, which slides B under the widget, which covers B, which swaps back. Nothing
             * damps it, so it runs for as long as the widget is held.
             *
             * Measured on emulator-5554 with a 2x2 widget held motionless over a 4x3, animators
             * ON: one tile alternating between box `0,1177-260,1409` and `520,945-780,1177` every
             * ~220ms, its reflow displacement flipping `+369,-164` -> `-367,+164` -> `+367,-164`,
             * five tiles thrown ~402px on every flip. That is the owner's report of *"rendering
             * issues when holding one widget over another"* -- the grid was not corrupt, it was
             * oscillating.
             *
             * Keyed to the DRAG's travel rather than to time, because the pathological case is a
             * finger that is not moving at all: the tiles move, the finger does not, so any
             * positive threshold breaks the loop while leaving a deliberate sweep untouched. This
             * is the same shape as the folder's reorder alarm ([AresEditMotion.FOLDER_REORDER_DELAY_MS]),
             * which exists because `Folder.onDragOver` re-arms on every change of target.
             *
             * 24dp is a little under a quarter of a cell, so a sweep across a row still displaces
             * continuously and only a *stationary* widget is held still.
             */
            const val WIDGET_SWAP_HYSTERESIS_DP = 24f
        }

        /** Where the dragged tile sat when the last widget swap was committed; NaN before any. */
        private var lastSwapX = Float.NaN
        private var lastSwapY = Float.NaN

        /**
         * The **item** the dragged widget last swapped with, or null.
         *
         * Refusing to nominate this again is what actually breaks the loop, and travel alone does
         * not. Measured with the travel guard alone at 24dp and a slow sweep: one tile still
         * alternating between box `520,945-780,1177` and `0,1177-260,1409`, displacement flipping
         * `-519,231` -> `+519,-231` -> `-518,231`, merely slower (~700-850ms a lap instead of
         * ~350ms). Of course it did: the cycle is A -> B -> A, and a finger that is still moving
         * keeps paying the travel toll on every lap.
         *
         * The loop exists because a swap MOVES THE BOUNDS the next decision is measured against --
         * covering A swaps A away, which slides B under the widget, which covers B, which swaps
         * back. Blocking the immediate return leg is the smallest thing that cannot cycle.
         *
         * **Identity, not an index.** This held the dragged item's old adapter position until an
         * adversarial review traced what that actually guards. [AresHomeAdapter.moveItem] is
         * remove-then-insert, so only for an ADJACENT swap does the old position end up holding the
         * item we just swapped with. For `from=0, to=2` the list goes `[W,X,A] -> [X,A,W]`: the
         * guarded index 0 now holds X, a bystander, while the real return-leg target A sits
         * unguarded at 1. So it blocked a legitimate swap and let the one it exists for through.
         *
         * Non-adjacent is the normal case here, not an edge: [chooseDropTarget]'s widget branch
         * scans every attached child and returns the most-covered one anywhere on the grid, and
         * [getMoveThreshold] is 0.02 for widgets, so it is asked almost immediately.
         *
         * An identity also survives the reindexing that a `notifyItemMoved` or an external adapter
         * change (`bindItemsUpdated`, `PackageUpdatedTask`) does underneath a live drag, which an
         * index does not -- it was left pointing at whatever slid into that slot.
         *
         * Residual, deliberately left: this is one deep, so a genuine 3-cycle
         * (`[W,X,A] -> [X,A,W] -> [X,W,A] -> [W,X,A]`) can still alternate targets past it. That
         * needs the finger to keep travelling, where the hysteresis above applies, and closing it
         * properly means remembering the last N targets -- more state than is worth adding without
         * a device to measure it on.
         */
        private var lastSwapTarget: ItemInfo? = null

        /** The dragged tile's current top-left, sampled by [chooseDropTarget] for [onMove]. */
        private var curDragX = 0
        private var curDragY = 0

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
        /**
         * How far a drag must travel before `ItemTouchHelper` will even look for a swap target.
         *
         * Stock returns 0.5, and it is a fraction **of the dragged view's own size** — so the
         * larger the thing you pick up, the further you must move it before anything reacts. That
         * is defensible in a uniform list, where every row is the same height, and wrong here: a
         * 4x2 widget is 464px tall, so nothing could move until the drag had travelled 232px, by
         * which point the widget had entirely covered the row beneath it.
         *
         * This gate sits UPSTREAM of [chooseDropTarget], which is why the coverage rule there
         * appeared to do nothing. Measured on the emulator, dragging a 4x2 widget down over a row
         * of icons: displacement fired between 200px and 250px of travel both before and after the
         * coverage rule was added -- 232px, exactly half the widget's height, every time.
         *
         * A widget therefore asks almost immediately and lets the coverage rule decide.
         * Icons keep stock's 0.5: on a 1x1 tile half a tile's travel is a small, deliberate
         * movement, and lowering it there would make ordinary reordering twitchy.
         */
        override fun getMoveThreshold(viewHolder: RecyclerView.ViewHolder): Float {
            val info = list.aresAdapter.itemAt(viewHolder.bindingAdapterPosition)
            return if (info?.itemType == Favorites.ITEM_TYPE_APPWIDGET) {
                0.02f
            } else {
                super.getMoveThreshold(viewHolder)
            }
        }

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

            // A DRAGGED WIDGET displaces on COVERAGE, not on its centre arriving.
            //
            // Everything below tests where the drag's *centre* is, which is right for a 1x1 tile
            // and wrong for a large one. A 4x4 widget's leading edge covers an icon long before its
            // centre gets anywhere near that icon's centre, so the icon sat underneath the widget
            // for most of the drag and only jumped aside at the very end: "imo, I think as soon as
            // the app is covered by the widget, it should move."
            //
            // `curX`/`curY` are the dragged view's current top-left including the drag translation,
            // so its rectangle is known here without consulting the view's own transform. A target
            // is displaced once the widget covers [WIDGET_COVER_FRACTION] of it -- an area test, so
            // a wide widget grazing a tall icon and a tall widget grazing a wide one behave the
            // same. The most-covered target wins, and the rest follow on subsequent frames as the
            // reflow settles, which is what makes a sweep push a whole row rather than one tile.
            val draggedIsWidget = draggedInfo?.itemType == Favorites.ITEM_TYPE_APPWIDGET
            if (draggedIsWidget) {
                curDragX = curX
                curDragY = curY
                // Hysteresis. Nominate nothing until the drag has actually travelled since the last
                // swap, or the coverage rule oscillates against the bounds its own swap moved.
                // See [WIDGET_SWAP_HYSTERESIS_DP].
                if (!lastSwapX.isNaN()) {
                    val density = list.resources.displayMetrics.density
                    val need = WIDGET_SWAP_HYSTERESIS_DP * density
                    if (kotlin.math.hypot(curX - lastSwapX, curY - lastSwapY) < need) return null
                }
                val dragRight = curX + selected.itemView.width
                val dragBottom = curY + selected.itemView.height
                var best: RecyclerView.ViewHolder? = null
                var bestCover = 0f

                // Deliberately NOT `dropTargets`. That list is `ItemTouchHelper.findSwapTargets`'
                // output, and its pre-filter assumes a uniform list: for a downward drag it will
                // not nominate a target until the dragged view's own bounds have passed it. A 4x2
                // widget passes a 1x1 icon only once it has *entirely* covered it, so the coverage
                // test below never saw those icons and the grid appeared to wait for the centres to
                // meet after all.
                //
                // Measured on the emulator, dragging a 4x2 widget down over a row of icons:
                // covered 25% - nothing; 51% - nothing; 77% - nothing; 103% - displaced. The
                // threshold was never being applied, because the candidates never arrived.
                //
                // Walking the attached children instead asks the question the masonry grid actually
                // has -- "what is underneath this thing" -- rather than the one a uniform list asks.
                for (i in 0 until list.childCount) {
                    val v = list.getChildAt(i) ?: continue
                    if (v === selected.itemView) continue
                    val holder = list.getChildViewHolder(v) ?: continue
                    if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) continue
                    // The return leg of the A -> B -> A cycle. See [lastSwapTarget].
                    val info = list.aresAdapter.itemAt(holder.bindingAdapterPosition)
                    if (info != null && info === lastSwapTarget) continue
                    val overlapW = minOf(dragRight, v.right) - maxOf(curX, v.left)
                    val overlapH = minOf(dragBottom, v.bottom) - maxOf(curY, v.top)
                    // Normalised by the SMALLER of the two, not by the target.
                    //
                    // Dividing by the target's area alone made the rule directional, and the
                    // failure was arithmetic rather than a matter of feel: the overlap can never
                    // exceed the dragged item's own area, so whenever the dragged widget is smaller
                    // than half the target, `cover` cannot reach [WIDGET_COVER_FRACTION] AT ALL.
                    // Reported as *"moving a smaller widget struggles to move a larger widget out of
                    // the way"*, and on the owner's own grid the two cases are exactly that: a 2x2
                    // dragged onto a 4x3 tops out at 4/12 = 33% -- impossible, not merely hard --
                    // while a 2x4 onto the same 4x3 tops out at 67%, which is why that one moved
                    // only after near-total overlap and read as "struggles".
                    //
                    // min() keeps the verified direction untouched: a big widget over a small icon
                    // still divides by the icon, which is the behaviour measured for §24 and the one
                    // the owner signed off ("moving a large widgetr around now moves the apps").
                    // What changes is only the case that could not succeed. The rule now reads the
                    // same in both directions -- "half of the smaller thing is buried" -- which is
                    // also the honest statement of "as soon as the app is covered by the widget".
                    val area = minOf(
                        v.width.toLong() * v.height,
                        selected.itemView.width.toLong() * selected.itemView.height,
                    ).toFloat()
                    if (overlapW > 0 && overlapH > 0 && area > 0f) {
                        val cover = (overlapW * overlapH) / area
                        if (cover >= WIDGET_COVER_FRACTION && cover > bestCover) {
                            bestCover = cover
                            best = holder
                        }
                    }
                }
                return best
            }

            // A tile's bounds ARE its cell, which is what makes this containment test sound.
            //
            // Worth stating because it briefly stopped being true: insetting every tile to create
            // a gutter left `v.left`/`v.right` narrower than the cell, and the gaps became dead
            // zones. For a wide item that is a certainty rather than a rare miss -- a full-width
            // widget's centre sits at the grid's horizontal midpoint, which for an even column
            // count is a tile boundary, and once inset, a gap. Measured then: tiles at 10-250 /
            // 270-510 / 530-770 / 790-1030 against a widget centre of x=520, inside no tile at
            // all, so nothing was ever chosen and nothing reflowed. Separation now lives inside
            // the widget holders instead, leaving every tile the full cell it stands for.
            dropTargets.forEach { target ->
                val v = target.itemView
                if (centreX >= v.left && centreX < v.right &&
                    centreY >= v.top && centreY < v.bottom
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
            // Icons and folders only: a dragged widget never reaches here, it took the coverage
            // branch in chooseDropTarget. The 10% overshoot is specifically what leaves a tile's
            // leading half un-reflowed so dwell-to-create-a-folder has something to dwell on, and
            // a widget cannot go into a folder, so it was paying for a window it could not use.
            return travelled >= span * SWAP_TRAVEL_FRACTION
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            // Read BEFORE the move: afterwards `to` holds the dragged item, not the target.
            val targetInfo = list.aresAdapter.itemAt(to)
            val moved = list.aresAdapter.moveItem(from, to)
            // Arm the hysteresis from where the drag actually was when this swap committed, so the
            // next one needs real travel rather than another lap of the feedback loop.
            if (moved && draggedInfo?.itemType == Favorites.ITEM_TYPE_APPWIDGET) {
                lastSwapX = curDragX.toFloat()
                lastSwapY = curDragY.toFloat()
                lastSwapTarget = targetInfo
            }
            return moved
        }

        /**
         * Deliberately empty, overriding a stock default that jumps the grid mid-drag.
         *
         * `ItemTouchHelper.moveIfNecessary` calls this after every successful [onMove]. The base
         * implementation (`ItemTouchHelper.java:1952-1984`) takes the `ViewDropHandler` path only
         * for layout managers that implement it -- [AresMasonryLayoutManager] does not -- and
         * otherwise, for a vertically scrolling list, calls
         * `recyclerView.scrollToPosition(toPos)` when the target sits at either edge.
         *
         * Two things make that wrong here rather than merely unnecessary. Our padding is
         * `0, top, 0, 0`, so the bottom test reduces to `child.bottom >= height` -- true for the
         * partially visible bottom row of any grid taller than the viewport, and true from much
         * higher up beside a tall widget. And [AresMasonryLayoutManager.scrollToPosition] is an
         * ABSOLUTE jump that puts the target's row at the top of the viewport, not the small
         * keep-it-visible nudge the stock default assumes. It is also computed against the
         * pre-move packing and a pre-move index, because `onItemsMoved` does not run until the next
         * layout pass.
         *
         * The result was a third, undamped feedback path into the loop this class already fights:
         * the jump changes every child's top and bottom, so [chooseDropTarget]'s coverage test is
         * re-evaluated against wholly different bounds -- and neither [lastSwapTarget] nor the
         * travel hysteresis damps it, since `curDragX/Y` do not move when the GRID scrolls.
         *
         * Dropping it loses nothing: `ItemTouchHelper.scrollIfNecessary` already does edge
         * auto-scroll during a drag, on its own schedule.
         *
         * Invisible on the three-tile fixture, where `maxScroll == 0` makes `scrollToPosition` a
         * no-op -- which is why the measurements behind the earlier swap fixes never saw it.
         */
        override fun onMoved(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            fromPos: Int,
            target: RecyclerView.ViewHolder,
            toPos: Int,
            x: Int,
            y: Int,
        ) = Unit

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return

            draggedInfo = viewHolder?.let { list.aresAdapter.itemAt(it.bindingAdapterPosition) }
            lastSwapX = Float.NaN
            lastSwapY = Float.NaN
            lastSwapTarget = null
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
            // Hysteresis is per-drag: the next one must start unarmed or its first swap is blocked.
            lastSwapX = Float.NaN
            lastSwapY = Float.NaN
            lastSwapTarget = null
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
