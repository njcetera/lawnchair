package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * Decorates an inline-expanded WP folder: a dark rounded backdrop BEHIND the folder tile (so the
 * open folder's icon reads as the active one) plus a Material-You bar above and below its opened
 * apps (bracketing the folder's contents, the way Windows Phone marked an opened folder). Nothing is
 * drawn when no folder is expanded.
 *
 * The backdrop is behind ONLY the folder icon (owner decision 2026-08-24) -- drawn in [onDraw],
 * beneath the tiles, so the icon paints on top of it and the rest of the home screen is untouched.
 * This was chosen over dimming the whole screen: a full-screen dim can't reach the display edges
 * from a [RecyclerView.ItemDecoration] (it is clipped to the grid, which is inset on some devices).
 *
 * The bars bracket the CHILDREN band, not the folder's whole row: the folder tile usually shares its
 * row with unrelated apps (the packer opens the folder in place -- see [AresPacker]), so a bar above
 * that row would appear to enclose those neighbours too. The top bar sits at the top edge of the
 * first opened app; the bottom bar at the bottom edge of the last. A bar (or the backdrop) for a
 * tile scrolled out of view is simply skipped -- its holder is not attached to measure.
 *
 * It reads the expanded run straight from the adapter on each draw, so it always matches the live
 * expand state and needs no lifecycle of its own.
 */
class AresFolderBounds(
    context: Context,
    private val list: AresHomeListView,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The same Material-You accent the search pill and FAB use, so the bracket reads as one
        // system with the launcher's other Material-You surfaces.
        color = context.getColor(R.color.materialColorPrimary)
        style = Paint.Style.FILL
    }

    // Dark rounded backdrop painted behind the open folder's tile. A plain translucent black reads on
    // any wallpaper; rounded corners keep it from looking like a hard box.
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((BACKDROP_ALPHA * 255).toInt(), 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val density = context.resources.displayMetrics.density
    private val barHeight = 3f * density
    private val barInset = 10f * density // horizontal inset from the content edges
    private val backdropInset = 6f * density // shrink the backdrop inside the tile cell
    private val backdropRadius = context.resources
        .getDimension(R.dimen.ares_edit_cell_outline_radius)
    private val rect = RectF()

    /** The backdrop, drawn BENEATH the tiles so the folder icon sits bright on top of it. */
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange() ?: return
        val folderView = parent.findViewHolderForAdapterPosition(run.first)?.itemView ?: return
        rect.set(
            folderView.left + backdropInset,
            folderView.top + backdropInset,
            folderView.right - backdropInset,
            folderView.bottom - backdropInset,
        )
        c.drawRoundRect(rect, backdropRadius, backdropRadius, backdrop)
    }

    /** The bracket bars, drawn OVER the tiles. */
    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange() ?: return
        val firstChildPos = run.first + 1
        val lastChildPos = run.last
        if (lastChildPos < firstChildPos) return // empty folder: no apps to bracket

        val left = parent.paddingLeft + barInset
        val right = parent.width - parent.paddingRight - barInset
        if (right <= left) return
        parent.findViewHolderForAdapterPosition(firstChildPos)?.let {
            drawBar(c, left, right, it.itemView.top.toFloat())
        }
        parent.findViewHolderForAdapterPosition(lastChildPos)?.let {
            drawBar(c, left, right, it.itemView.bottom.toFloat())
        }
    }

    private fun drawBar(c: Canvas, left: Float, right: Float, centerY: Float) {
        val r = barHeight / 2f
        rect.set(left, centerY - r, right, centerY + r)
        c.drawRoundRect(rect, r, r, paint)
    }

    private companion object {
        /** Opacity of the backdrop behind the folder icon. Owner-tunable. */
        const val BACKDROP_ALPHA = 0.4f
    }
}
