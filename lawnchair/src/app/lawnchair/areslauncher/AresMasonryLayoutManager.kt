package app.lawnchair.areslauncher

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Lays the home grid out by running [AresPacker] and positioning children at the resulting cells.
 *
 * ## Why a custom LayoutManager
 *
 * [androidx.recyclerview.widget.GridLayoutManager] flows in *rows* and sizes each row to its
 * tallest item, so a small icon can never sit *beneath* a taller neighbour -- it leaves dead space
 * instead. True Windows Phone interlocking needs real 2D packing, which is what this does.
 * `StaggeredGridLayoutManager` is not a substitute either: it reorders items to fill gaps, which
 * breaks the ordered model outright. See design/scrolling-grid-home.md §7.1.
 *
 * ## Division of labour
 *
 * All the placement logic lives in [AresPacker], which is pure and has no Android dependency. This
 * class only translates cells into pixels, scrolls, and recycles. Keeping those apart is what makes
 * the interesting half testable.
 *
 * ## Scroll and recycling
 *
 * The host ([AresHomeListView]) is measured `EXACTLY` to the workspace page height. That is not a
 * constraint on content -- an exact height on a scrolling list *defines the viewport*, which is the
 * normal contract. Verified on device: 40 items laid out to 8800px inside a 1773px viewport with
 * scroll offset/range/extent all correct, and 9 views attached for 40 items.
 *
 * On scroll this detaches and re-fills rather than incrementally adding and removing at the edges.
 * That is a deliberate simplification: a home screen holds tens of items, not thousands, so the
 * cost is trivial, and it removes a whole class of edge-case bugs in edge bookkeeping. Views still
 * recycle -- scrapped views are handed straight back by the recycler.
 */
class AresMasonryLayoutManager(
    private val spanProvider: SpanProvider,
) : RecyclerView.LayoutManager() {

    /** Supplies each item's footprint in grid cells. Implemented by the adapter. */
    fun interface SpanProvider {
        fun getSpan(position: Int): AresPacker.Span
    }

    /** Grid columns. Set by the host from the device profile; re-layout happens on change. */
    var columns: Int = 4
        set(value) {
            val clamped = value.coerceAtLeast(1)
            if (field != clamped) {
                field = clamped
                layout = null
                requestLayout()
            }
        }

    /** Height of one grid cell in px. Defaults to square cells when left at 0. */
    var cellHeightPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var layout: AresPacker.Layout? = null
    private var scrollOffset = 0

    /**
     * The scale items rest at when nothing is animating; the host keeps it in step with edit mode,
     * which shrinks every tile slightly.
     *
     * Supplied rather than sampled from `child.scaleX` because the sample is only correct when no
     * animation is in flight. A repack landing mid-animation would read a transient value and treat
     * it as the resting size, so each quick chevron tap would leave the tile a little smaller than
     * the last.
     */
    var restScale: Float = 1f

    /**
     * Set for one layout pass to animate items from where they were to where packing puts them.
     *
     * Off by default, so scrolling and recycling stay instant — animating those would smear the
     * grid every frame. It is switched on only for a *discrete* change the user caused (a resize,
     * a removal), where the point is to show the cause and effect: this item grew, so these
     * neighbours moved aside.
     */
    private var animateNextLayout = false

    /**
     * Pre-layout bounds by adapter position, captured when [animateNextLayout] is set.
     *
     * Keyed by position rather than view because packing may hand a position to a different view,
     * and it is the *item's* movement the animation is describing.
     */
    private val previousBounds = mutableMapOf<Int, android.graphics.Rect>()

    /**
     * True while a drag is in flight, which turns on the **live reflow** (§4).
     *
     * The host sets it alongside `setReorderInProgress`. Every layout pass taken while it is on
     * springs each tile the packer moved from where it was drawn to where it now sits, instead of
     * letting it appear there. Off outside a drag, so scrolling, recycling and binding stay
     * instant — springing those would smear the whole grid.
     */
    var reflowActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) previousDrawnLeft.clear()
        }

    /**
     * The tile `ItemTouchHelper` is dragging, which the reflow must never touch.
     *
     * It writes that view's `translationX/Y` every frame to keep it under the finger; a spring on
     * the same property would fight it within the frame. Set by
     * [AresHomeListView.setFloatSuspendedFor], which is the same moment and the same reason the
     * float stands down.
     */
    var reflowExempt: View? = null

    /**
     * Where each attached child was **drawn** at the top of the current layout pass.
     *
     * Keyed by view, unlike [previousBounds], and that difference is the whole point. A repack
     * after a resize describes *positions* moving; a reorder describes *this item* moving, and
     * under a reorder the item at position 5 after the pass is not the one that was there before
     * it. Stable ids mean the recycler hands the same `View` back for the same item, so the view is
     * the identity to key on.
     */
    private val previousDrawnLeft = mutableMapOf<View, Float>()
    private val previousDrawnTop = mutableMapOf<View, Float>()

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )

    override fun canScrollVertically(): Boolean = true

    override fun isAutoMeasureEnabled(): Boolean = false

    // Predictive animations would require supplying pre-layout state for items that move as a
    // result of a repack -- a repack can move everything, so the payoff is poor and the complexity
    // real. Revisit alongside drag-to-reorder, where animating the shuffle is the point.
    override fun supportsPredictiveItemAnimations(): Boolean = false

    private val usableWidth: Int
        get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)

    private fun cellWidth(): Int = if (columns > 0) usableWidth / columns else 0

    private fun cellHeight(): Int = if (cellHeightPx > 0) cellHeightPx else cellWidth()

    private fun ensureLayout(state: RecyclerView.State): AresPacker.Layout {
        layout?.let { if (it.cells.size == state.itemCount) return it }
        val spans = (0 until state.itemCount).map { spanProvider.getSpan(it) }
        return AresPacker.pack(spans, columns).also { layout = it }
    }

    private fun contentHeight(l: AresPacker.Layout): Int = l.rows * cellHeight()

    private fun maxScroll(l: AresPacker.Layout): Int =
        (contentHeight(l) - (height - paddingTop - paddingBottom)).coerceAtLeast(0)

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) {
            removeAndRecycleAllViews(recycler)
            layout = null
            scrollOffset = 0
            return
        }
        if (animateNextLayout) capturePreviousBounds()
        // Never both: the repack animation is an exclusive owner of the tiles it touches, and it
        // cannot overlap a drag anyway (its only triggers are affordance taps).
        val reflow = reflowActive && !animateNextLayout
        if (reflow) captureDrawnPositions()

        val l = ensureLayout(state)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll(l))
        detachAndScrapAttachedViews(recycler)
        fill(recycler, l)

        if (animateNextLayout) {
            animateFromPreviousBounds()
            animateNextLayout = false
            previousBounds.clear()
        } else if (reflow) {
            reflowFromDrawnPositions()
        }
    }

    /**
     * Requests that the next layout pass animate rather than snap.
     *
     * Call immediately before [invalidatePacking] for a user-initiated change. It is a one-shot
     * flag: it clears itself after the pass, so a scroll landing right afterwards is unaffected.
     */
    fun animateNextLayout() {
        animateNextLayout = true
    }

    private fun capturePreviousBounds() {
        previousBounds.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            previousBounds[position] = android.graphics.Rect(
                child.left,
                child.top,
                child.right,
                child.bottom,
            )
        }
    }

    /**
     * Records where every attached child is currently **drawn**, before the pass moves it.
     *
     * `left` plus the reflow's own displacement, and deliberately **not** plus the float's orbit:
     * the orbit is a continuous oscillation about the resting position, so folding it in would bake
     * a couple of dp of wobble into the spring's start value on every repack. The reflow term is
     * what makes retargeting exact — a tile already halfway to its last destination starts the next
     * spring from halfway, not from the box it never reached.
     *
     * Taken at the top of the layout pass, which is what keeps **scrolling** out of the reflow: an
     * auto-scroll during a drag goes through `scrollVerticallyBy`, so by the time a layout pass
     * runs the children have already moved with it and the measured delta is zero.
     */
    private fun captureDrawnPositions() {
        previousDrawnLeft.clear()
        previousDrawnTop.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            previousDrawnLeft[child] = child.left + AresEditMotion.reflowX(child)
            previousDrawnTop[child] = child.top + AresEditMotion.reflowY(child)
        }
    }

    /**
     * Springs every child the packing moved from where it was drawn to where it now sits.
     *
     * A child with no recorded position scrolled in during the pass and is left where it landed —
     * springing it in from off-screen would draw attention to recycling, which is not something the
     * user did. The dragged tile is skipped outright; see [reflowExempt].
     */
    private fun reflowFromDrawnPositions() {
        var moved = 0
        var furthest = 0f
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            if (child === reflowExempt) continue
            val oldLeft = previousDrawnLeft[child] ?: continue
            val oldTop = previousDrawnTop[child] ?: continue
            val dx = oldLeft - child.left
            val dy = oldTop - child.top
            if (dx == 0f && dy == 0f) continue
            AresEditMotion.displaceTo(child, dx, dy)
            moved++
            furthest = maxOf(furthest, kotlin.math.hypot(dx, dy))
        }
        previousDrawnLeft.clear()
        previousDrawnTop.clear()
        // One line per packing change during a drag, which is the granularity of "the user moved
        // something". A reflow that silently does nothing is indistinguishable from one that is too
        // fast to see, and that ambiguity has cost this project a verification pass before.
        if (moved > 0) {
            android.util.Log.d(TAG, "reflow: $moved tile(s), furthest ${furthest.toInt()}px")
        }
    }

    /**
     * Animates each item from its pre-layout box to its new one.
     *
     * Movement is expressed as translation, and a *size* change as a scale that starts at the old
     * dimensions and relaxes to [restScale]. Animating the measured size instead would mean
     * re-measuring the child every frame — expensive, and for a widget it would make the provider
     * re-render its RemoteViews repeatedly mid-animation.
     *
     * Items with no previous box (scrolled in, or newly added) are left alone: fading them in
     * would draw attention to recycling, which is not what the user did.
     *
     * ## ⛔ Never move the scale pivot here
     *
     * An earlier revision set `pivotX = 0f; pivotY = 0f` so growth would read as the tile extending
     * into the space its neighbours were vacating. **That silently killed the resize chevron after
     * its first use**, and it took a runtime bisect to see why:
     *
     *  - `View` treats an explicitly-set pivot as sticky, and nothing here put it back — so every
     *    tile that ever moved or resized kept a top-left pivot for the rest of its life.
     *  - Edit mode holds a 0.92 scale on the same container, which about a top-left pivot draws the
     *    tile shifted up and left of its layout box instead of concentrically inside it.
     *  - `AresHomeListView`'s edit-mode touch listener hit-tested the affordances in *untransformed*
     *    coordinates while the framework dispatched through the child's matrix, so the two answers
     *    diverged by that shift. Measured on the emulator: tapping where the chevron was drawn got
     *    the tap swallowed as a tile tap, and tapping where it was laid out fell through to the
     *    widget and **launched its app**. On a tall tile the two regions stopped overlapping at all
     *    and the chevron became unreachable — the "resize gets stuck at the largest size" report.
     *
     * Anchoring the start box by its **centre** instead needs no pivot at all, and is exact at both
     * ends: the tile starts covering precisely the old box and settles on precisely the new one.
     * (The old top-left version started 4% off, because the resting scale is centred.)
     * `AresHomeListView` also hit-tests through the child matrix now, so a transform here can no
     * longer desync the affordances — but leaving no transform state behind is the reason it cannot
     * come back.
     */
    private fun animateFromPreviousBounds() {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            val old = previousBounds[position] ?: continue

            val newWidth = (child.right - child.left).toFloat()
            val newHeight = (child.bottom - child.top).toFloat()
            if (newWidth <= 0f || newHeight <= 0f) continue

            val sx = old.width() / newWidth
            val sy = old.height() / newHeight
            val dx = old.exactCenterX() - (child.left + newWidth / 2f)
            val dy = old.exactCenterY() - (child.top + newHeight / 2f)

            val moved = dx != 0f || dy != 0f
            val resized = sx != 1f || sy != 1f
            if (!moved && !resized) continue

            // This animation is an exclusive owner of the tile's translation for its duration, so
            // any reflow still in flight on it is dropped rather than left summing underneath.
            // Stated rather than assumed: the two cannot overlap today (a repack is triggered only
            // by an affordance tap, and a gesture starting on an affordance never becomes a drag),
            // and this is what keeps that true if a future path breaks the assumption.
            AresEditMotion.clearReflow(child)

            child.translationX = dx
            child.translationY = dy
            // Compose with the edit-mode scale the host applies, rather than overwriting it --
            // otherwise a resize would pop the tile back to full size. Read from [restScale] and
            // never from `child.scaleX`: a second repack landing inside this animation would read a
            // mid-flight value and adopt it as the resting size, so a run of quick chevron taps
            // would shrink the tile a little further each time.
            child.scaleX = restScale * sx
            child.scaleY = restScale * sy

            child.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(restScale)
                .scaleY(restScale)
                .setDuration(LAYOUT_ANIM_MS)
                .start()
        }
    }

    /** Attaches, measures and positions every item whose cell rect intersects the viewport. */
    private fun fill(recycler: RecyclerView.Recycler, l: AresPacker.Layout) {
        val cw = cellWidth()
        val ch = cellHeight()
        if (cw <= 0 || ch <= 0) return

        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + (height - paddingTop - paddingBottom)

        for (position in l.cells.indices) {
            val cell = l.cells[position]
            val span = spanProvider.getSpan(position)
            val top = cell.y * ch
            val bottom = top + span.h.coerceAtLeast(1) * ch
            if (bottom <= viewportTop || top >= viewportBottom) continue

            val view = recycler.getViewForPosition(position)
            addView(view)

            val lp = view.layoutParams as RecyclerView.LayoutParams
            val w = span.w.coerceIn(1, columns) * cw - lp.leftMargin - lp.rightMargin
            val h = span.h.coerceAtLeast(1) * ch - lp.topMargin - lp.bottomMargin
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), View.MeasureSpec.EXACTLY),
            )

            val left = paddingLeft + cell.x * cw
            layoutDecoratedWithMargins(
                view,
                left,
                paddingTop + top - scrollOffset,
                left + span.w.coerceIn(1, columns) * cw,
                paddingTop + bottom - scrollOffset,
            )
        }
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        if (childCount == 0 || dy == 0) return 0
        val l = ensureLayout(state)
        val limit = maxScroll(l)
        val target = (scrollOffset + dy).coerceIn(0, limit)
        val consumed = target - scrollOffset
        if (consumed == 0) return 0
        scrollOffset = target
        detachAndScrapAttachedViews(recycler)
        fill(recycler, l)
        return consumed
    }

    override fun scrollToPosition(position: Int) {
        val l = layout ?: return
        if (position !in l.cells.indices) return
        scrollOffset = (l.cells[position].y * cellHeight()).coerceIn(0, maxScroll(l))
        requestLayout()
    }

    // Scroll metrics drive the scrollbar and any fling/overscroll affordance. Reporting them in
    // pixels (rather than item counts) keeps the thumb proportional with mixed footprints.
    override fun computeVerticalScrollRange(state: RecyclerView.State): Int =
        layout?.let { contentHeight(it) } ?: 0

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int = scrollOffset

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int =
        (height - paddingTop - paddingBottom).coerceAtLeast(0)

    // Any structural change invalidates the packing: positions are derived from order, so an
    // insert or removal anywhere can move everything after it.
    override fun onItemsChanged(recyclerView: RecyclerView) { layout = null }

    override fun onItemsAdded(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        layout = null
    }

    override fun onItemsRemoved(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        layout = null
    }

    override fun onItemsMoved(recyclerView: RecyclerView, from: Int, to: Int, itemCount: Int) {
        layout = null
    }

    override fun onItemsUpdated(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        layout = null
    }

    /**
     * Drops the cached packing so the next layout pass re-runs [AresPacker].
     *
     * For changes that alter an item's **footprint without altering the list** — a widget resize is
     * the only one — where the adapter's `notifyItemChanged` is the wrong tool. Widget holders are
     * `setIsRecyclable(false)`, so a rebind cannot reuse the existing holder: RecyclerView builds a
     * second one and the first is left attached, which was observed directly as **four host views
     * for two widgets**, one of them still drawn at the pre-resize size.
     *
     * Invalidating the packing and asking for a layout instead keeps the existing host view and
     * simply hands it a new box, which is both correct and much cheaper than re-creating a widget.
     */
    fun invalidatePacking() {
        layout = null
        requestLayout()
    }

    /** Total grid rows in the current packing; 0 before the first layout. Exposed for diagnostics. */
    fun rowCount(): Int = layout?.rows ?: 0

    /** Content height in px for the current packing; 0 before the first layout. */
    fun contentHeightPx(): Int = layout?.let { contentHeight(it) } ?: 0

    /** Current vertical scroll offset in px. */
    fun scrollOffsetPx(): Int = scrollOffset

    /**
     * Resolved cell size in px, *after* the square-cell fallback — the same numbers [fill] places
     * children with.
     *
     * Exposed so [AresEditGrid] can draw the grid the items actually snap to rather than a
     * decorative approximation of it. Reading `columns` and `cellHeightPx` and re-deriving the
     * width would be a second implementation of `cellWidth()`, free to drift from this one. Both
     * are 0 until the list has been measured.
     */
    fun resolvedCellWidthPx(): Int = cellWidth()

    /** @see resolvedCellWidthPx */
    fun resolvedCellHeightPx(): Int = cellHeight()

    private companion object {
        const val TAG = "AresMasonry"

        /**
         * Duration of the repack animation after a resize or removal.
         *
         * Long enough to read as displacement rather than a jump, short enough that a run of
         * removals does not feel like waiting on the launcher.
         */
        const val LAYOUT_ANIM_MS = 200L
    }
}
