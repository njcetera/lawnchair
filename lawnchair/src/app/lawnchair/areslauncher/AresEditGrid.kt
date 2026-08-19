package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * The edit-mode grid overlay: **corner dots** showing where things can go, and a **cell outline**
 * showing what an item currently occupies (§14 and §21).
 *
 * The two were specified separately and are built together on purpose — they are one visual
 * language, read at the same moment, and they have to agree. Both take their geometry from
 * [AresMasonryLayoutManager]'s own cell metrics rather than from a constant, so they stay aligned
 * when the column count or cell height changes (folded versus unfolded, most obviously), and both
 * take their colour from `?attr/workspaceTextColor` — the attribute the home labels already resolve
 * against the wallpaper, so light and dark walls are handled without a second mechanism.
 *
 * ## Why the dots are an ItemDecoration
 *
 * [Dots.onDraw] renders in the list's content coordinates, **beneath** the items, and is re-drawn
 * on every scroll. A static overlay would desynchronise the moment the grid scrolled, because the
 * scroll offset is not a whole number of cells.
 *
 * ## Why the outline is a foreground, not a decoration
 *
 * The outline belongs to *one item*, and in edit mode an item wiggles and scales. Putting the
 * outline in the holder container's foreground makes it a property of the tile, so it rotates and
 * scales with it exactly like the × badge and the resize chevron do. Drawn from a decoration it
 * would sit still while the tile moved underneath it, which is the very complaint (§21) that asked
 * for it: affordances that look detached from the thing they belong to.
 */
object AresEditGrid {

    /**
     * The colour both overlays draw in, at full opacity.
     *
     * Resolved from the theme rather than hardcoded: the home surface sits on the user's wallpaper,
     * and `workspaceTextColor` is what Launcher3 already flips between light and dark for it.
     */
    private fun editColor(context: Context): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.workspaceTextColor, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else {
            Color.WHITE
        }
    }

    /**
     * A stroke-only rounded rectangle marking an item's allocated cells.
     *
     * No fill, deliberately: this sits *over* the item (a widget's own content, most of the time),
     * so anything but an outline would obscure what it is describing. The stroke is faint for the
     * same reason the wiggle is small — it is a hint about the grid, not a selection highlight.
     */
    fun cellOutline(context: Context): Drawable {
        val res = context.resources
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = res.getDimension(R.dimen.ares_edit_cell_outline_radius)
            setStroke(
                res.getDimensionPixelSize(R.dimen.ares_edit_cell_outline_width).coerceAtLeast(1),
                withAlpha(editColor(context), OUTLINE_ALPHA),
            )
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Draws a dot at every cell corner of the packed grid, beneath the items.
     *
     * Corner dots rather than cell borders, per the user's own suggestion: borders would compete
     * with the icons sitting inside them, and this is meant to be a hint about structure. The dot
     * set is `(columns + 1) × (rows + 1)`, so the outer edges of the grid are marked too, and only
     * the rows crossing the viewport are drawn.
     *
     * [progress] is animated by the host between 0 and 1 so the dots fade in and out with the mode
     * alongside the wiggle, rather than appearing abruptly.
     */
    class Dots(
        context: Context,
        private val masonry: AresMasonryLayoutManager,
    ) : RecyclerView.ItemDecoration() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = editColor(context)
            style = Paint.Style.FILL
        }

        private val radius =
            context.resources.getDimension(R.dimen.ares_edit_grid_dot_radius).coerceAtLeast(1f)

        /** 0 = hidden, 1 = fully faded in. Set by the host's edit-mode animator. */
        var progress: Float = 0f

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (progress <= 0f) return
            val columns = masonry.columns
            val cellW = masonry.resolvedCellWidthPx()
            val cellH = masonry.resolvedCellHeightPx()
            if (columns <= 0 || cellW <= 0 || cellH <= 0) return

            // Row 0 of the grid, in the list's own coordinates. Matches the layout manager's own
            // convention exactly (paddingTop + row * cellHeight - scrollOffset), so a dot always
            // lands on the corner of a real cell rather than near it.
            val originY = parent.paddingTop - masonry.scrollOffsetPx()
            val originX = parent.paddingLeft

            // Only the rows that actually cross the viewport, so a tall grid costs no more to draw
            // than a short one.
            val rows = masonry.rowCount()
            val firstRow = (((-originY).toFloat() / cellH).toInt() - 1).coerceAtLeast(0)
            val lastRow = (((parent.height - originY).toFloat() / cellH).toInt() + 1)
                .coerceAtMost(rows)
            if (lastRow < firstRow) return

            paint.alpha = (progress * DOT_ALPHA * 255).toInt()
            for (row in firstRow..lastRow) {
                val y = (originY + row * cellH).toFloat()
                for (col in 0..columns) {
                    c.drawCircle((originX + col * cellW).toFloat(), y, radius, paint)
                }
            }
        }

        private companion object {
            /** Faint on purpose — the dots are a hint about structure, not content. */
            const val DOT_ALPHA = 0.30f
        }
    }

    /** Matching restraint for the outline; it sits over a widget's own content. */
    private const val OUTLINE_ALPHA = 0.35f
}
