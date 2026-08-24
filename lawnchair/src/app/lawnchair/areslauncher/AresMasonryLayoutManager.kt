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

    /**
     * WP folders: vertical breathing room, in px, added around an inline-expanded folder's run so
     * its apps card is not crammed against the tiles above and below (owner request 2026-08-24). It
     * is applied as a pixel shift on the already-packed cells -- NOT by shrinking any cell -- so
     * icon layout inside a tile is untouched (the cell-shrink trap) and drop targeting, which is
     * view-based, stays correct. A gap of this size opens between the folder tile and the first app,
     * and the same again below the last app.
     */
    var expandPadPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    // Row bounds of the current expanded run, in packer-row units, recomputed on every repack.
    // Cells strictly below [padFolderRow] shift down by [expandPadPx]; cells at or below
    // [padAfterRow] shift down by a second [expandPadPx]. Inert (folder row -1) when nothing is
    // expanded.
    private var padFolderRow: Int = -1
    private var padAfterRow: Int = Int.MAX_VALUE

    private var layout: AresPacker.Layout? = null
    private var scrollOffset = 0

    /** Read-only view of [scrollOffset], for the test channel. The §4 grid-jump defect (ledger
     * row 27) is an absolute jump of this value mid-drag, which layout bounds cannot show. */
    internal fun currentScrollOffset(): Int = scrollOffset

    /** Read-only packer cells for the current layout, one per adapter position, for tests. Empty
     * until the first layout pass. Lets a test read where EVERY item packs, on-screen or off. */
    internal fun currentCells(): List<AresPacker.Cell> = layout?.cells ?: emptyList()

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

    /**
     * WP folders Phase 3 #3: supplies the contiguous adapter index range (expanded folder + its
     * spliced children) that [AresPacker] must keep together as one block, or null when no folder is
     * expanded. Set by the host; read fresh on every repack, so it always reflects the live expand
     * state. The itemCount changes on expand/collapse, which already invalidates the layout cache
     * below, so a stale run can never be paired with a live span list.
     */
    var reservedRunProvider: (() -> IntRange?)? = null

    private fun ensureLayout(state: RecyclerView.State): AresPacker.Layout {
        layout?.let { if (it.cells.size == state.itemCount) return it }
        val spans = (0 until state.itemCount).map { spanProvider.getSpan(it) }
        return AresPacker.pack(spans, columns, reservedRunProvider?.invoke()).also {
            layout = it
            computeExpandPadBounds(it)
        }
    }

    /**
     * Derive [padFolderRow]/[padAfterRow] for the current expanded run: the folder tile's row, and
     * the row just past its last child. Called once per repack (the run only changes on
     * expand/collapse, which changes itemCount and forces a repack), so [expandedPad] can be a pure
     * lookup per position.
     */
    private fun computeExpandPadBounds(l: AresPacker.Layout) {
        val run = reservedRunProvider?.invoke()
        if (run == null || run.isEmpty() ||
            run.first !in l.cells.indices || run.last !in l.cells.indices
        ) {
            padFolderRow = -1
            padAfterRow = Int.MAX_VALUE
            return
        }
        padFolderRow = l.cells[run.first].y
        var after = padFolderRow + 1
        for (p in (run.first + 1)..run.last) {
            val h = spanProvider.getSpan(p).h.coerceAtLeast(1)
            after = maxOf(after, l.cells[p].y + h)
        }
        padAfterRow = after
    }

    /** Vertical shift, in px, for [position] under the current expanded-folder padding. */
    private fun expandedPad(position: Int): Int {
        if (padFolderRow < 0 || expandPadPx <= 0) return 0
        val y = layout?.cells?.getOrNull(position)?.y ?: return 0
        var e = 0
        if (y > padFolderRow) e += expandPadPx // gap between the folder tile and its first app
        if (y >= padAfterRow) e += expandPadPx // gap between the last app and the following content
        return e
    }

    // A folder open adds up to two pad bands (above and below its card) to the scrollable height.
    private fun contentHeight(l: AresPacker.Layout): Int =
        l.rows * cellHeight() + (if (padFolderRow >= 0) 2 * expandPadPx else 0)

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
            // Per-tile detail behind the summary line, because the summary alone cannot tell a
            // reflow that is SETTLING from one that is OSCILLATING -- both print the same count and
            // a similar distance. The pair of numbers that distinguishes them is each tile's box
            // against its live displacement, over consecutive passes.
            //
            // That is not hypothetical: it is what identified the widget-swap feedback loop. A 2x2
            // widget held motionless over a 4x3 produced one tile alternating between box
            // `0,1177-260,1409` and `520,945-780,1177` every ~220ms with its displacement flipping
            // `+369,-164` -> `-367,+164` -> `+367,-164`, which reads as a loop at a glance and as
            // "reflow: 5 tile(s), furthest 402px" repeated -- indistinguishable from healthy work --
            // in the summary. See AresHomeReorder.WIDGET_SWAP_HYSTERESIS_DP.
            //
            // Bounded: only fires on a pass that actually moved something, and only for tiles
            // displaced by more than a pixel.
            for (i in 0 until childCount) {
                val c = getChildAt(i) ?: continue
                val rx = AresEditMotion.reflowX(c)
                val ry = AresEditMotion.reflowY(c)
                if (kotlin.math.abs(rx) > 1f || kotlin.math.abs(ry) > 1f) {
                    android.util.Log.d(
                        TAG,
                        "  pos=${getPosition(c)} box=${c.left},${c.top}-${c.right},${c.bottom} " +
                            "displaced=${rx.toInt()},${ry.toInt()} " +
                            "exempt=${c === reflowExempt}",
                    )
                }
            }
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
        val listView = host as? AresHomeListView
        val suspended = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            // The tile in the user's hand is off-limits, exactly as in reflowFromDrawnPositions:
            // ItemTouchHelper owns its follow-translation, and suspendFloatForRepack below would
            // clear its Motion and the child.translationX write would snap it off the finger. A
            // repack CAN overlap a drag — a second finger tapping a × badge fires animateNextLayout
            // mid-drag. (adversarial review, 2026-08-22)
            if (child === reflowExempt) continue
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

            // This animation now owns the tile's translation outright for its duration: it drops any
            // reflow still in flight ([clearReflow]) AND suspends the edit-mode orbit float
            // ([suspendFloatForRepack]). Suspending the orbit is the fix for the owner's Pixel report
            // that repacks "snap to place without animation" — the orbit ([AresEditWiggle]) writes
            // translationX/Y every frame for the whole of edit mode, so without this the
            // ViewPropertyAnimator below is overwritten frame-by-frame and the tile teleports to its
            // new cell instead of sliding. AresEditMotion's header named this the thing to do here if
            // a repack were ever seen not to play; the owner saw exactly that.
            AresEditMotion.clearReflow(child)
            if (listView != null) {
                listView.suspendFloatForRepack(child)
                suspended.add(child)
            }

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
        // Resume each suspended tile's orbit once its animation is over. A single posted callback,
        // not a per-view ViewPropertyAnimator listener: a VPA listener does not fire on cancel and
        // mis-attributes end callbacks across overlapping repacks (a new repack replaces the shared
        // per-view listener), whereas this always fires and the per-tile count in [AresHomeListView]
        // keeps overlaps balanced — a tile suspended by two repacks resumes only after both.
        if (listView != null && suspended.isNotEmpty()) {
            listView.postDelayed(
                { for (c in suspended) listView.resumeFloatAfterRepack(c) },
                LAYOUT_ANIM_MS,
            )
        }
    }

    /**
     * The host, captured so [fill] can ask whether a child's holder may be recycled.
     *
     * Widget holders are `setIsRecyclable(false)` (see [AresHomeAdapter.onCreateViewHolder]) because
     * re-attaching an `AppWidgetHostView` disturbs its sizing and RemoteViews state. Recycling one
     * anyway does not reuse it — the pool refuses a non-recyclable holder — it *destroys* it, and
     * the next scroll back builds a fresh host view and asks the provider to re-render. So those
     * children are kept attached and simply laid out where the packing puts them, on-screen or off.
     * A home grid holds a handful of widgets, so the bound is small and known.
     */
    private var host: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        host = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        host = null
    }

    private fun mayRecycle(child: View): Boolean =
        host?.getChildViewHolder(child)?.isRecyclable ?: true

    /** True when the cell at [position] is on **screen** at the current [scrollOffset]. */
    private fun isVisible(l: AresPacker.Layout, position: Int, ch: Int): Boolean {
        if (position !in l.cells.indices) return false
        val pad = expandedPad(position)
        val top = l.cells[position].y * ch + pad
        val bottom = top + spanProvider.getSpan(position).h.coerceAtLeast(1) * ch
        // Judge against the FULL screen [0, height], not the padding-inset content box: with
        // clipToPadding=false the top/bottom padding is real on-screen scroll space, so a cell moving
        // through it must stay laid out rather than be recycled at the content edge. Recycling at the
        // content edge is what made tiles "de-render" scrolling into a large top/bottom padding
        // (owner) -- they should render across the whole page. A cell is drawn at screen
        // y = paddingTop + cellTop - scrollOffset, so it is on screen when that range overlaps [0, height].
        return paddingTop + bottom - scrollOffset > 0 &&
            paddingTop + top - scrollOffset < height
    }

    /**
     * Attaches, measures and positions every item whose cell rect intersects the viewport, and
     * removes the ones that no longer do.
     *
     * ## Why this reconciles instead of assuming an empty child list
     *
     * The layout path scraps everything first, so on that path the reconciliation is a no-op and
     * every position comes back out of scrap. The **scroll** path must not scrap — see
     * [scrollVerticallyBy] — so it arrives here with children still attached, and those have to be
     * kept rather than requested again: `Recycler.getViewForPosition` only finds a view that is in
     * *scrap*, so asking for a position that is already attached-and-not-scrapped builds a
     * **second** view for the same item. That is what leaves a stale, wrongly-sized tile drawn over
     * its neighbours once the grid is tall enough to recycle.
     */
    private fun fill(recycler: RecyclerView.Recycler, l: AresPacker.Layout) {
        val cw = cellWidth()
        val ch = cellHeight()
        if (cw <= 0 || ch <= 0) return

        // Recycle anything that has left the viewport or whose position no longer exists, and index
        // what is left by position so it is reused rather than duplicated. Backwards, because
        // removeAndRecycleView shifts every later index down.
        val attached = HashMap<Int, View>()
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            val keep = position in l.cells.indices &&
                (isVisible(l, position, ch) || !mayRecycle(child)) &&
                // Two children claiming one position is the corrupt state this whole function
                // exists to prevent. If it is ever reached anyway, keep one and drop the rest --
                // repairing on the next pass beats rendering both on top of each other.
                !attached.containsKey(position)
            if (keep) attached[position] = child else removeAndRecycleView(child, recycler)
        }

        for (position in l.cells.indices) {
            if (!isVisible(l, position, ch) && !attached.containsKey(position)) continue
            val cell = l.cells[position]
            val span = spanProvider.getSpan(position)
            val pad = expandedPad(position)
            val top = cell.y * ch + pad
            val bottom = top + span.h.coerceAtLeast(1) * ch

            val existing = attached[position]
            val view = existing
                ?: recycler.getViewForPosition(position).also { addView(it) }

            // Each tile occupies its WHOLE cell. Separation between tiles is applied inside the
            // widget holders instead (AresHomeAdapter) rather than by shrinking the cell here.
            //
            // Shrinking the cell was the first attempt and it was wrong twice over. The profile
            // sizes an icon's content against the full cell -- 158px icon + 11px padding + 39px
            // label in a 241px cell, about 13px of slack -- so taking 20px out of the cell left
            // the icon hard against the top of its tile, where the edit-mode float carried it into
            // the frost box's edge. And an inset tile is no longer the cell it stands for, which
            // turned every gutter into a dead zone for drop targeting.
            val lp = view.layoutParams as RecyclerView.LayoutParams
            val w = (span.w.coerceIn(1, columns) * cw - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
            val h = (span.h.coerceAtLeast(1) * ch - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
            // Measure only when needed: a freshly added/scrapped view (`existing == null`), one that
            // asked for layout itself (a widget refreshing its RemoteViews sets this), or one whose
            // target size actually changed. During a pure scroll the cell size is unchanged, so
            // re-measuring every visible child each frame is wasted work -- and re-measuring an
            // AppWidgetHostView re-runs its RemoteViews layout, which is the scroll stutter and the
            // "repeated rendering" of widgets on a long list. Views entering via getViewForPosition
            // never land in `attached`, so a real layout pass still measures everything.
            if (existing == null || view.isLayoutRequested ||
                view.measuredWidth != w || view.measuredHeight != h
            ) {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                )
            }

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
        // ⛔ Never call detachAndScrapAttachedViews here. It is a *layout-pass* primitive: the views
        // it detaches go into Recycler.mAttachedScrap, and the only thing that ever drains that is
        // LayoutManager.removeAndRecycleScrapInt, which RecyclerView runs at the end of
        // dispatchLayout. A scroll is not a layout pass, so anything scrapped and not re-requested
        // in the same call is stranded: detached from the view tree, still held as scrap, and still
        // matched by position when a later pass asks for a view. Widget holders are
        // setIsRecyclable(false), so they cannot even be pooled to recover. Measured consequence on
        // the user's device: 12 rendered children against 11 database rows, the extra one a stale
        // tile of a span nothing owned, drawn across its neighbours. It only ever appeared once the
        // grid was tall enough to scroll, which is why every fixture that fit on one screen missed
        // it. [fill] removes and recycles what left the viewport instead, which is legal in a
        // scroll and leaves nothing behind.
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
