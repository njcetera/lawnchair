package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * Decorates an inline-expanded WP folder with a Material-You bar above and below the opened apps,
 * bracketing the folder's contents so the run reads as one grouped block. Nothing is drawn when no
 * folder is expanded.
 *
 * (A background dim of the rest of the home was tried across several iterations and dropped by owner
 * decision 2026-08-24 -- it never read well against the busy grid. The bracket bars stay.)
 *
 * An [RecyclerView.ItemDecoration]: it reads the expanded run from the adapter on each draw, so it
 * always matches the live expand state and needs no lifecycle of its own. A tile scrolled out of
 * view is simply skipped -- its holder is not attached to measure.
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

        val appViews = ArrayList<View>()
        for (pos in (run.first + 1)..run.last) {
            parent.findViewHolderForAdapterPosition(pos)?.itemView?.let { appViews.add(it) }
        }
        if (appViews.isEmpty()) return

        val left = parent.paddingLeft + barInset
        val right = parent.width - parent.paddingRight - barInset
        if (right <= left) return

        val top = appViews.minOf { it.top }.toFloat()
        val bottom = appViews.maxOf { it.bottom }.toFloat()
        drawBar(c, left, right, top)
        drawBar(c, left, right, bottom)
    }

    private fun drawBar(c: Canvas, left: Float, right: Float, centerY: Float) {
        val r = barHeight / 2f
        rect.set(left, centerY - r, right, centerY + r)
        c.drawRoundRect(rect, r, r, paint)
    }
}
