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

        val l = ensureLayout(state)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll(l))
        detachAndScrapAttachedViews(recycler)
        fill(recycler, l)

        if (animateNextLayout) {
            animateFromPreviousBounds()
            animateNextLayout = false
            previousBounds.clear()
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
     * Animates each item from its pre-layout box to its new one.
     *
     * Movement is expressed as translation, and a *size* change as a scale that starts at the old
     * dimensions and relaxes to 1. Animating the measured size instead would mean re-measuring the
     * child every frame — expensive, and for a widget it would make the provider re-render its
     * RemoteViews repeatedly mid-animation.
     *
     * Scaling is pinned to the top-left pivot so growth reads as the tile extending into the space
     * its neighbours are vacating, rather than expanding symmetrically from its middle and
     * appearing to overlap them on the way.
     *
     * Items with no previous box (scrolled in, or newly added) are left alone: fading them in
     * would draw attention to recycling, which is not what the user did.
     */
    private fun animateFromPreviousBounds() {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            val old = previousBounds[position] ?: continue

            val dx = (old.left - child.left).toFloat()
            val dy = (old.top - child.top).toFloat()
            val newWidth = (child.right - child.left).toFloat()
            val newHeight = (child.bottom - child.top).toFloat()
            val sx = if (newWidth > 0f) old.width() / newWidth else 1f
            val sy = if (newHeight > 0f) old.height() / newHeight else 1f

            val moved = dx != 0f || dy != 0f
            val resized = sx != 1f || sy != 1f
            if (!moved && !resized) continue

            child.pivotX = 0f
            child.pivotY = 0f
            child.translationX = dx
            child.translationY = dy
            // Compose with the edit-mode scale the host may already have applied, rather than
            // overwriting it -- otherwise a resize would pop the tile back to full size.
            val restScaleX = child.scaleX
            val restScaleY = child.scaleY
            child.scaleX = restScaleX * sx
            child.scaleY = restScaleY * sy

            child.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(restScaleX)
                .scaleY(restScaleY)
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
        /**
         * Duration of the repack animation after a resize or removal.
         *
         * Long enough to read as displacement rather than a jump, short enough that a run of
         * removals does not feel like waiting on the launcher.
         */
        const val LAYOUT_ANIM_MS = 200L
    }
}
