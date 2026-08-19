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
     */
    fun pack(spans: List<Span>, columns: Int): Layout {
        if (columns <= 0 || spans.isEmpty()) return Layout(emptyList(), 0)

        // Occupancy grid, grown a row at a time as items are placed. Row-major: occupied[y][x].
        val occupied = ArrayList<BooleanArray>()
        val cells = ArrayList<Cell>(spans.size)
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

        for (span in spans) {
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
            cells.add(placed)
            rows = maxOf(rows, placed.y + h)
        }

        return Layout(cells, rows)
    }
}
