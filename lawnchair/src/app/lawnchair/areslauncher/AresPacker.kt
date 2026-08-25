package app.lawnchair.areslauncher

/**
 * Greedy first-fit packer for the masonry home grid.
 *
 * This is the whole layout model, and it is deliberately a **pure function with no Android
 * dependency**: `(ordered footprints, column count) -> positions`. See
 * design/requirements-alignment.md §4 and design/scrolling-grid-home.md.
 *
 * Windows Phone Start semantics fall out of one rule -- walk the items in order and place each at
 * the first position its footprint fits, scanning top-to-bottom then left-to-right:
 *
 *  - **No holes.** A hole can only exist if nothing later fits it, and anything that fits is placed
 *    there by construction.
 *  - **Removal compacts upward.** Remove an item, re-run over the shorter list, everything after it
 *    slides into the gap.
 *  - **Insertion pushes down.** Insert mid-sequence and subsequent items repack after it.
 *  - **Height stays proportional to item count**, which was the point: no arrangement of items can
 *    produce a tall sparse grid, so scrolling can never run away.
 *
 * Position is *derived*, never stored. The persisted state is `rank` (the order) plus each item's
 * `(spanX, spanY)` -- there are no x/y coordinates in the database. That is what makes this
 * testable, and it is also why the occupancy-collision class of bug (which once deleted every
 * desktop item -- see component-verification-3.md §2) cannot occur here: a list cannot collide with
 * itself.
 */
object AresPacker {

    /** A footprint in grid cells. Both dimensions are clamped to at least 1 by [pack]. */
    data class Span(val w: Int, val h: Int)

    /** Top-left cell coordinate of a placed item. */
    data class Cell(val x: Int, val y: Int)

    /**
     * Result of a pack: one [Cell] per input span (same order, same size), plus the total number of
     * grid rows consumed, which is what the layout manager needs for its scroll range.
     */
    data class Layout(val cells: List<Cell>, val rows: Int)

    /**
     * Packs [spans] into [columns] columns by greedy first-fit.
     *
     * An item wider than the grid is clamped to the full width rather than dropped -- a widget
     * declaring more columns than the device has would otherwise vanish silently.
     *
     * [reservedRun] (WP folders): a contiguous index range whose FIRST index is an inline-expanded
     * folder tile and whose remaining indices are that folder's spliced children. It is laid out
     * Windows-Phone style -- the folder OPENS IN PLACE (owner decision 2026-08-24): the folder tile
     * keeps its natural collapsed cell, and its children open into the rows directly below it while
     * everything under the folder slides down to make room. Three phases:
     *
     *  1. Pack the collapsed arrangement -- every tile except the children, in order, by ordinary
     *     first-fit. This fixes the folder's own cell (backfill included) and every other tile's.
     *  2. Open a band-height gap directly below the folder's row: slide every tile below the folder
     *     down by the number of child rows. Relative order is preserved, so no overlap is created.
     *  3. Drop the children into that gap, row-major from column 0. The band's trailing cells stay
     *     empty (whitespace); nothing else can occupy them.
     *
     * Because the folder does NOT move, a folder that sits above a widget it out-ranks stays above
     * that widget and pushes the widget DOWN, instead of dropping beneath it (owner report
     * 2026-08-24). A run with any non-1x1 member, or an out-of-range range, is ignored and those
     * items pack individually (safe fallback). A no-run call is byte-for-byte the old behaviour.
     * The rare case of a tall tile straddling the folder's own row falls back to placing the
     * children on their own rows below all existing content.
     *
     * The self-non-collision guarantee is preserved: phase 1 places by checked first-fit, the slide
     * moves a disjoint set of tiles uniformly downward, and the children land only in the vacated
     * band -- so a list still cannot collide with itself (see the class KDoc / row-34).
     */
    @JvmOverloads
    fun pack(spans: List<Span>, columns: Int, reservedRun: IntRange? = null): Layout {
        if (columns <= 0 || spans.isEmpty()) return Layout(emptyList(), 0)

        // Occupancy grid, grown a row at a time as items are placed. Row-major: occupied[y][x].
        val occupied = ArrayList<BooleanArray>()
        val cells = arrayOfNulls<Cell>(spans.size)
        var rows = 0

        fun rowAt(y: Int): BooleanArray {
            while (occupied.size <= y) occupied.add(BooleanArray(columns))
            return occupied[y]
        }

        fun fits(x: Int, y: Int, w: Int, h: Int): Boolean {
            for (dy in 0 until h) {
                val row = rowAt(y + dy)
                for (dx in 0 until w) {
                    if (row[x + dx]) return false
                }
            }
            return true
        }

        fun place(pos: Int, span: Span) {
            val w = span.w.coerceIn(1, columns)
            val h = span.h.coerceAtLeast(1)
            // Scan top-to-bottom, then left-to-right within each row: the first position that fits
            // wins. The scan is bounded because a fresh row is always empty, so an item can never
            // fail to place.
            var placed: Cell? = null
            var y = 0
            while (placed == null) {
                for (x in 0..(columns - w)) {
                    if (fits(x, y, w, h)) {
                        placed = Cell(x, y)
                        break
                    }
                }
                if (placed == null) y++
            }
            for (dy in 0 until h) {
                val row = rowAt(placed.y + dy)
                for (dx in 0 until w) row[placed.x + dx] = true
            }
            cells[pos] = placed
            rows = maxOf(rows, placed.y + h)
        }

        // Is the reserved run usable? It must be in range and every member a 1x1 footprint -- the
        // contiguous-block placement below reasons in single cells, so a wider member would be
        // mis-placed. Anything else falls through to per-item first-fit.
        val runUsable = reservedRun != null && !reservedRun.isEmpty() &&
            reservedRun.first >= 0 && reservedRun.last < spans.size &&
            reservedRun.all { spans[it].w.coerceIn(1, columns) == 1 && spans[it].h.coerceAtLeast(1) == 1 }

        if (!runUsable) {
            // No reserved run: plain greedy first-fit, in order. Byte-for-byte the base behaviour.
            for (i in spans.indices) place(i, spans[i])
            @Suppress("UNCHECKED_CAST")
            return Layout((cells as Array<Cell>).toList(), rows)
        }

        // WP folders "open in place": the folder tile keeps its natural collapsed position (backfill
        // and all), and its children open into the rows DIRECTLY BELOW it, pushing everything under
        // the folder down -- exactly as tapping a folder did on Windows Phone. This is a visual
        // INSERT, not a re-flow to the frontier: a folder whose rank is after a widget it sits above
        // must stay above that widget and shove the widget down, not drop beneath it (owner report
        // 2026-08-24). Done in three phases.
        val runFirst = reservedRun!!.first
        val runLast = reservedRun.last
        val childCount = runLast - runFirst // the folder is runFirst; children are runFirst+1..runLast
        fun isChild(idx: Int) = idx in (runFirst + 1)..runLast

        // Phase 1: pack the COLLAPSED arrangement -- every tile EXCEPT the folder's children, in
        // order, by the same first-fit as always. This fixes the folder's in-place cell and every
        // other tile's (widgets included) collapsed cell.
        for (idx in spans.indices) if (!isChild(idx)) place(idx, spans[idx])

        if (childCount > 0) {
            val fy = cells[runFirst]!!.y
            val bandRows = (childCount + columns - 1) / columns

            // A tall tile that starts on or above the folder's row but reaches the row just beneath
            // it would be sliced by inserting a band there. That arrangement is unusual; fall back to
            // placing the children on their own rows below ALL existing content when it occurs.
            val straddler = spans.indices.any { idx ->
                if (isChild(idx)) return@any false
                val c = cells[idx] ?: return@any false
                c.y <= fy && c.y + spans[idx].h.coerceAtLeast(1) - 1 > fy
            }

            if (straddler) {
                // A tall tile shares the folder's row, so a band sliced directly under the folder
                // would cut it. Open the band just below the folder's ROW BLOCK instead -- past the
                // bottom of every tile sitting on/over the folder's row (the straddling widget) -- and
                // slide the content below that down, so the apps stay right under the folder area
                // rather than dropping to the bottom of the page (owner 2026-08-25). Children then
                // first-fit into the opened rows, flowing around anything still in them.
                var bandStart = fy + 1
                for (idx in spans.indices) {
                    if (isChild(idx)) continue
                    val c = cells[idx] ?: continue
                    val h = spans[idx].h.coerceAtLeast(1)
                    if (c.y <= fy && c.y + h > bandStart) bandStart = c.y + h
                }
                // Slide every non-child tile at/below the band down by the band height.
                for (idx in spans.indices) {
                    if (isChild(idx)) continue
                    val c = cells[idx] ?: continue
                    if (c.y >= bandStart) cells[idx] = Cell(c.x, c.y + bandRows)
                }
                // Rebuild the occupancy grid from the post-slide non-child cells so the child
                // first-fit below cannot collide with anything (incl. a tall tile that extends into
                // the band from above).
                for (row in occupied) row.fill(false)
                for (idx in spans.indices) {
                    if (isChild(idx)) continue
                    val c = cells[idx] ?: continue
                    val w = spans[idx].w.coerceIn(1, columns)
                    val h = spans[idx].h.coerceAtLeast(1)
                    for (dy in 0 until h) {
                        val r = rowAt(c.y + dy)
                        for (dx in 0 until w) r[c.x + dx] = true
                    }
                }
                // First-fit each 1x1 child, scanning from the band's first row.
                for (k in 0 until childCount) {
                    var target: Cell? = null
                    var y = bandStart
                    while (target == null) {
                        for (x in 0 until columns) {
                            if (fits(x, y, 1, 1)) { target = Cell(x, y); break }
                        }
                        if (target == null) y++
                    }
                    rowAt(target.y)[target.x] = true
                    cells[runFirst + 1 + k] = target
                }
            } else {
                // Phase 2: open a band-height gap directly below the folder's row -- slide every tile
                // that sits below the folder down by the band height. Relative order is preserved, so
                // this cannot introduce an overlap.
                for (idx in spans.indices) {
                    if (isChild(idx)) continue
                    val c = cells[idx] ?: continue
                    if (c.y > fy) cells[idx] = Cell(c.x, c.y + bandRows)
                }
                // Phase 3: drop the children into the opened band, row-major from column 0. The
                // band's trailing cells (a partial last row) stay empty -- nothing else can be there,
                // since tiles above are on rows <= fy and tiles below were slid past the band.
                for (k in 0 until childCount) {
                    cells[runFirst + 1 + k] = Cell(k % columns, fy + 1 + k / columns)
                }
            }
        }

        // Recompute the row count from the final cells (the occupancy grid is stale after the slide).
        rows = 0
        for (idx in spans.indices) {
            val c = cells[idx] ?: continue
            val h = if (isChild(idx)) 1 else spans[idx].h.coerceAtLeast(1)
            rows = maxOf(rows, c.y + h)
        }

        @Suppress("UNCHECKED_CAST")
        return Layout((cells as Array<Cell>).toList(), rows)
    }
}
