package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * Draws a Material-You bar just above and just below an inline-expanded WP folder's opened apps, so
 * the user can see at a glance which tiles belong to the folder -- the way Windows Phone bracketed
 * an opened folder's contents. Nothing is drawn when no folder is expanded, or when the expanded
 * folder is empty (no apps to bracket).
 *
 * It brackets the CHILDREN band, not the folder's whole row: the folder tile usually shares its row
 * with unrelated apps (the packer opens the folder in place -- see [AresPacker]), so a bar above
 * that row would appear to enclose those neighbours too. The top bar therefore sits at the top edge
 * of the first opened app -- immediately beneath the folder tile that is its header -- and the
 * bottom bar at the bottom edge of the last opened app.
 *
 * An [RecyclerView.ItemDecoration] (onDrawOver) rather than real views: it renders in the list's
 * content coordinates over the tiles, is re-drawn every frame as the list scrolls or reflows, and
 * needs no lifecycle of its own. It reads the expanded run straight from the adapter on each draw,
 * so it always matches the live expand state. A bar for a child that is scrolled out of view is
 * simply skipped (its holder is not attached to measure).
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

    private val density = context.resources.displayMetrics.density
    private val barHeight = 3f * density
    private val barInset = 10f * density // horizontal inset from the content edges
    private val rect = RectF()

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange() ?: return
        val firstChildPos = run.first + 1
        val lastChildPos = run.last
        if (lastChildPos < firstChildPos) return // empty folder: no apps to bracket

        val left = parent.paddingLeft + barInset
        val right = parent.width - parent.paddingRight - barInset
        if (right <= left) return

        parent.findViewHolderForAdapterPosition(firstChildPos)?.let { vh ->
            drawBar(c, left, right, vh.itemView.top.toFloat())
        }
        parent.findViewHolderForAdapterPosition(lastChildPos)?.let { vh ->
            drawBar(c, left, right, vh.itemView.bottom.toFloat())
        }
    }

    private fun drawBar(c: Canvas, left: Float, right: Float, centerY: Float) {
        val r = barHeight / 2f
        rect.set(left, centerY - r, right, centerY + r)
        c.drawRoundRect(rect, r, r, paint)
    }
}
