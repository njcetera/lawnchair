package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

    // Scrim dimming the home screen OUTSIDE the open folder's rows (owner 2026-08-24), so the
    // folder's contents read as the foreground. A plain black wash at a low alpha dims uniformly on
    // any wallpaper, light or dark; "slightly" keeps it subtle.
    private val scrim = Paint().apply { color = Color.argb((DIM_ALPHA * 255).toInt(), 0, 0, 0) }

    private val density = context.resources.displayMetrics.density
    private val barHeight = 3f * density
    private val barInset = 10f * density // horizontal inset from the content edges
    private val rect = RectF()

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange() ?: return
        val firstChildPos = run.first + 1
        val lastChildPos = run.last
        if (lastChildPos < firstChildPos) return // empty folder: no apps to bracket

        val folderVh = parent.findViewHolderForAdapterPosition(run.first)
        val firstChildVh = parent.findViewHolderForAdapterPosition(firstChildPos)
        val lastChildVh = parent.findViewHolderForAdapterPosition(lastChildPos)

        // Dim the whole home background around the open folder, the way a normal folder does -- a
        // uniform scrim everywhere EXCEPT a folder-shaped hole (the folder tile itself plus the band
        // of its opened apps), so the folder is lit and its row-neighbours dim too. Drawn BEFORE the
        // bars so the bars sit on top of the scrim edge. Built as the COMPLEMENT of the bright hole
        // (four rects) rather than by erasing, so the tiles under the bright hole keep full brightness.
        val w = parent.width.toFloat()
        val h = parent.height.toFloat()
        val folderView = folderVh?.itemView
        val bandTop = (folderView ?: firstChildVh?.itemView)?.top
        val bandBottom = lastChildVh?.itemView?.bottom
        if (bandTop != null && bandBottom != null) {
            // Above the folder region.
            if (bandTop > 0) {
                rect.set(0f, 0f, w, bandTop.toFloat())
                c.drawRect(rect, scrim)
            }
            // The folder tile's own row: dim the cells on either side of the tile (its neighbours),
            // leaving the tile lit. Only when the folder tile itself is on screen.
            if (folderView != null) {
                rect.set(0f, folderView.top.toFloat(), folderView.left.toFloat(), folderView.bottom.toFloat())
                c.drawRect(rect, scrim)
                rect.set(folderView.right.toFloat(), folderView.top.toFloat(), w, folderView.bottom.toFloat())
                c.drawRect(rect, scrim)
            }
            // Below the opened apps.
            if (bandBottom < parent.height) {
                rect.set(0f, bandBottom.toFloat(), w, h)
                c.drawRect(rect, scrim)
            }
        }

        val left = parent.paddingLeft + barInset
        val right = parent.width - parent.paddingRight - barInset
        if (right <= left) return
        firstChildVh?.let { drawBar(c, left, right, it.itemView.top.toFloat()) }
        lastChildVh?.let { drawBar(c, left, right, it.itemView.bottom.toFloat()) }
    }

    private fun drawBar(c: Canvas, left: Float, right: Float, centerY: Float) {
        val r = barHeight / 2f
        rect.set(left, centerY - r, right, centerY + r)
        c.drawRoundRect(rect, r, r, paint)
    }

    private companion object {
        /** Scrim opacity over the dimmed background, in the ballpark of a normal folder's dim --
         * enough to foreground the open folder while keeping the dimmed tiles legible. Owner-tunable. */
        const val DIM_ALPHA = 0.4f
    }
}
