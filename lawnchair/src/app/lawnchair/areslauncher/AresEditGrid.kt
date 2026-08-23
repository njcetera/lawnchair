package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
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
 * ## Why the outline is a container background, not a decoration
 *
 * The outline belongs to *one item*, and in edit mode an item wiggles and scales. Putting the
 * outline on the holder container makes it a property of the tile, so it rotates and scales with it
 * exactly like the × badge and the resize chevron do. Drawn from a decoration it would sit still
 * while the tile moved underneath it, which is the very complaint (§21) that asked for it:
 * affordances that look detached from the thing they belong to.
 *
 * It rides the container's **background**, not its foreground, so it draws *behind* the icon or
 * widget and behind those badges instead of veiling them — which is what the owner asked for once
 * the fill grew strong. See [AresHomeAdapter.syncCellOutlineFor].
 *
 * ## Note: §21's "outline only, no fill" is superseded
 *
 * That line was written when the outline was specified. The user has since asked for the opposite —
 * *"when in edit mode theres an outline for the widget border. Can we fill that in with a
 * frost/blur effect?"* — so [cellOutline] now carries a frosted fill. The later instruction wins;
 * the reasoning behind the original restraint survives in [cellOutline]'s own documentation, which
 * is why the fill stays a translucent pane rather than an opaque one even after the owner asked for
 * it to be strengthened.
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
     * A rounded **frosted pane** filling an item's allocated cells. No outline.
     *
     * > *"when in edit mode theres an outline for the widget border. Can we fill that in with a
     * > frost/blur effect?"* — then, after seeing it: *"I really like the frost background on
     * > widgets. but can we remove the harsh outline and just keep the blur effect?"*
     *
     * ## §21 has now been reversed twice, and both reversals were deliberate
     *
     * §21 originally specified *outline only, no fill*, on the reasoning that a filled box would
     * compete with the content sitting inside it. The frost request inverted that. This second
     * request drops the stroke as well, so nothing of the original specification survives except
     * the *footprint* it was describing — which is the part that was always the point. Recorded
     * rather than quietly deleted, so the next reader does not restore the stroke believing §21
     * still asks for it. **It does not.** The restraint that produced the original wording lives on
     * in [FROST_FILL_ALPHA]'s deliberately low value.
     *
     * ## Why a scrim and not a blur
     *
     * The obvious reading of "blur" is `View.setRenderEffect(RenderEffect.createBlurEffect(...))`,
     * and it is the wrong tool: that blurs **the view's own content**, so it would make the widget
     * unreadable rather than frosting the surface in front of it. Frost is a *backdrop* effect, and
     * a view has no access to what is painted behind it.
     *
     * What actually reads as frost in a UI is a translucent, slightly graduated veil — which this
     * is. It costs one drawable, works on every device and in every theme, and cannot fail. The
     * expensive alternatives were considered and are not needed for this: reusing Launcher3's
     * `BaseDepthController` blurs the *wallpaper*, which is behind the widget rather than behind
     * the outline; and a genuine window backdrop blur means capturing and re-rendering the surface
     * below, which is both fragile and the most GPU-sensitive thing this design could do.
     *
     * ## The details that make it read as frost rather than as a dim
     *
     * - **A gradient, not a flat wash.** Brighter at the top, thinner at the bottom, which is how a
     *   real frosted panel catches light. Two multiples of one alpha, so there is still one number
     *   to tune.
     * - **Deliberately weak.** [FROST_FILL_ALPHA] is low enough that the job is to make the
     *   footprint read as a *pane* the item sits behind, not to obscure the item.
     * - **No border.** With the stroke gone the edge is defined by the frost's own falloff against
     *   the wallpaper, which is what "frost, not a box" means. The rounded corner stays: it is what
     *   keeps the pane reading as a tile rather than as a rectangle of haze.
     *
     * The colour is the same `?attr/workspaceTextColor` the grid dots use, so the two flip together
     * against the wallpaper and read as one system. **On a dark wallpaper that is a white haze,
     * which is frost; on a light wallpaper it is a dark haze, which is a scrim.** The colour flip
     * has only ever been exercised against a dark wallpaper — see the report.
     *
     * ## Applied to every editable tile, home grid only
     *
     * > *"I also like this so much, I think we should expand it to app icons and folders when I
     * > edit mode? makes sense for it to just be on the home screen edit and not inside folders
     * > when editing."*
     *
     * So the caller ([AresHomeAdapter.syncCellOutlineFor]) no longer restricts it to widgets. It is
     * confined to the home grid by construction rather than by a condition: that adapter serves
     * [AresHomeListView] and nothing else, and an open folder's edit mode is `AresFolderEdit`,
     * which does not go through here at all.
     */
    fun cellOutline(context: Context): Drawable {
        val res = context.resources
        // The frosted fill takes a subtle Material You light green (owner, 2026-08-23) rather than
        // the workspaceTextColor the dots/outlines use, so the pane reads as a faint green tint. The
        // low FROST_FILL_ALPHA keeps it subtle; the grid dots still flip with the wallpaper.
        val colour = context.getColor(R.color.ares_edit_fill_tint)
        // Top-to-bottom so the pane is brightest where light would strike it.
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                withAlpha(colour, FROST_FILL_ALPHA * FROST_TOP_MULTIPLIER),
                withAlpha(colour, FROST_FILL_ALPHA * FROST_BOTTOM_MULTIPLIER),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = res.getDimension(R.dimen.ares_edit_cell_outline_radius)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

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
            // Same subtle Material You light green as the frosted fill (owner, 2026-08-23), so the
            // grid markers read as one system with the tile tint rather than the label colour.
            color = context.getColor(R.color.ares_edit_fill_tint)
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
            // The perimeter columns sit on the grid's outer edges: col 0 at x=0 (paddingLeft is a
            // deliberate 0) and col `columns` at ~the view's right edge, so half of each edge dot
            // falls outside the view bounds and is clipped. Nudge the centre inward by the radius
            // so the whole dot shows. Only X is clamped: it does not scroll, whereas clamping Y
            // would pin an off-screen perimeter row to the viewport edge as the grid scrolls.
            // coerceAtLeast(radius) keeps min <= max for the coerceIn below: a view narrower than
            // two radii (never a real grid, but layout can flash one) would otherwise throw.
            val maxX = (parent.width - radius).coerceAtLeast(radius)
            for (row in firstRow..lastRow) {
                val y = (originY + row * cellH).toFloat()
                for (col in 0..columns) {
                    val x = (originX + col * cellW).toFloat().coerceIn(radius, maxX)
                    c.drawCircle(x, y, radius, paint)
                }
            }
        }

        private companion object {
            /** Faint on purpose — the dots are a hint about structure, not content. */
            const val DOT_ALPHA = 0.30f
        }
    }

    /**
     * The **drop-target ring**: what a folder (or, for §17's create case, an icon) looks like once
     * [AresFolderDrop]'s dwell has elapsed and releasing would drop into it.
     *
     * ## Why this is drawn rather than animated on the tile
     *
     * Every other edit-mode affordance rides on the holder container, deliberately, so it wiggles
     * with its item. This one must not: the tile it marks is already carrying edit mode's scale and
     * [AresEditWiggle]'s float, and a third writer on the same view properties fights them
     * frame-by-frame — the exact failure the float already had to stand down from during a drag.
     * A decoration reads the target's bounds and owns nothing, so there is nothing to fight over
     * and nothing to restore when the drag ends.
     *
     * It also has to work **outside** edit mode: a drag from the app list onto a folder gets the
     * same dwell (§17 — one folder behaviour regardless of where the icon came from), and the grid
     * is not wiggling then. Nothing here depends on the mode being on.
     *
     * Stroked as well as filled, unlike [cellOutline]. The frost is a passive hint about what a
     * cell occupies and must not obscure it; this is an active answer to *"will releasing now put
     * it in here?"*, and at 500ms of held finger the user is waiting for exactly that answer.
     */
    class DropRing(context: Context) : RecyclerView.ItemDecoration() {

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = editColor(context)
            style = Paint.Style.STROKE
            strokeWidth = context.resources
                .getDimension(R.dimen.ares_drop_ring_width)
                .coerceAtLeast(1f)
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = editColor(context)
            style = Paint.Style.FILL
        }

        private val radius = context.resources.getDimension(R.dimen.ares_edit_cell_outline_radius)

        /** The holder container to mark, or null. Set by [AresFolderDrop] through the host. */
        var target: View? = null

        /** 0 = invisible, 1 = fully drawn. Animated by the host so the ring fades in. */
        var progress: Float = 0f

        /**
         * Drawn **over** the items rather than under them, unlike [Dots].
         *
         * The dots describe the grid, so they belong behind its contents. This describes one tile's
         * state and has to be legible against whatever that tile draws — a folder preview is a
         * dense cluster of small icons, and a ring behind it would be almost entirely hidden.
         */
        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val view = target ?: return
            if (progress <= 0f) return
            // Layout bounds, not the drawn ones: the tile is wiggling, and a ring that wobbled with
            // it would read as another moving object rather than as a target that has locked on.
            val inset = stroke.strokeWidth
            // Alpha tracks progress but clamped: the fade-in uses an overshoot interpolator (see
            // AresHomeListView.setFolderDropTarget), so `progress` can ride briefly past 1.
            val a = progress.coerceIn(0f, 1f)
            fill.alpha = (a * FILL_ALPHA * 255).toInt()
            stroke.alpha = (a * STROKE_ALPHA * 255).toInt()
            // Grow the shape from FORM_FROM to full as it arms, scaled about the tile's centre, so
            // it reads as a folder taking shape under the finger rather than a highlight switching
            // on -- the owner's ask, "generate the folder before letting go". With the overshoot
            // interpolator `progress` rides a little past 1 and settles, the same pop the release
            // plays (AresHomeListView.popCreated), so the hold and the drop are one motion.
            val scale = FORM_FROM + (1f - FORM_FROM) * progress
            val cx = (view.left + view.right) / 2f
            val cy = (view.top + view.bottom) / 2f
            val halfW = (view.width / 2f - inset) * scale
            val halfH = (view.height / 2f - inset) * scale
            val l = cx - halfW
            val t = cy - halfH
            val r = cx + halfW
            val b = cy + halfH
            c.drawRoundRect(l, t, r, b, radius, radius, fill)
            c.drawRoundRect(l, t, r, b, radius, radius, stroke)
        }

        private companion object {
            /** Enough to read as "this one", faint enough to leave the folder's preview visible. */
            const val FILL_ALPHA = 0.18f
            const val STROKE_ALPHA = 0.85f

            /** Scale the forming shape starts at; grows to 1 as the dwell arms. Matches the pop. */
            const val FORM_FROM = 0.55f
        }
    }

    /**
     * Strength of the frosted pane. **The one number to tune for the frost.**
     *
     * This is a foreground, so it sits over the tile's own content — which is what makes it read as
     * glass in front of the item rather than a colour behind it, and also why it must stay low. At
     * 0.10 a dense widget stays legible through it; raising it toward 0.2 makes the pane obvious
     * and starts to wash out what is inside. It also veils the × and the chevron by the same
     * amount, which at this strength is not perceptible.
     *
     * Now that every tile carries one, not just widgets, this value is doing more work than before:
     * an app icon is mostly empty cell, so the pane is far more visible behind one than behind a
     * widget that fills its footprint. If the grid reads as busy, lower this rather than
     * reintroducing a per-type condition.
     *
     * Raised 0.10 -> 0.25 on 2026-08-22 (owner, in two steps: *"sometimes I can barely tell it's
     * there"* then *"make it .25f"*): the pane was too faint against some wallpapers. That same
     * session built and tested a real
     * per-tile wallpaper blur on both devices — capture the wallpaper, downscale, blit the slice
     * behind each tile — and **scrapped it**: it renders only on a static wallpaper (a live
     * wallpaper has no still frame to sample), and over a dark wall a blur of mostly-dark is barely
     * different from this scrim anyway. A stronger scrim is the better trade. See the report.
     */
    private const val FROST_FILL_ALPHA = 0.25f

    /** The gradient: brighter at the top, thinner at the bottom, like light across real glass. */
    private const val FROST_TOP_MULTIPLIER = 1.5f
    private const val FROST_BOTTOM_MULTIPLIER = 0.6f
}
