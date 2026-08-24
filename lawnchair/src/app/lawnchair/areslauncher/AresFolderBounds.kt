package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.util.resolveFolderPreviewColor

/**
 * Decorates an inline-expanded WP folder with a single rounded Material-You card BEHIND its opened
 * apps, tinted to the folder icon's own colour (the closed-folder preview colour, so the card, the
 * folder circle and the downward pointer all read as one surface). The card groups the spilled apps
 * as the contents of the folder above them. Nothing is drawn when no folder is expanded.
 *
 * (Earlier iterations bracketed the apps with two accent bars, and before that dimmed the rest of
 * the home; both dropped by owner decision -- the card replaces the bars, 2026-08-24.)
 *
 * Drawn in [onDraw] (beneath the item views) so the app icons sit ON the card. An
 * [RecyclerView.ItemDecoration]: it reads the expanded run from the adapter on each draw, so it
 * always matches the live expand state and needs no lifecycle of its own. A tile scrolled out of
 * view is simply skipped -- its holder is not attached to measure.
 */
class AresFolderBounds(
    context: Context,
    private val list: AresHomeListView,
) : RecyclerView.ItemDecoration() {

    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The closed-folder preview colour -- exactly what the folder circle and the expanded
        // pointer are filled with -- so the open folder and its apps are visibly one surface.
        color = resolveFolderPreviewColor(context)
        style = Paint.Style.FILL
    }

    private val density = context.resources.displayMetrics.density
    private val cardRadius = 24f * density
    private val cardPad = 8f * density // grow the card a little past the app tiles
    private val rect = RectF()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange() ?: return

        val appViews = ArrayList<View>()
        for (pos in (run.first + 1)..run.last) {
            parent.findViewHolderForAdapterPosition(pos)?.itemView?.let { appViews.add(it) }
        }
        if (appViews.isEmpty()) return

        // Tight bounding box of the opened app tiles, grown by cardPad and clamped to the grid's
        // content width so the card never runs under the screen edge.
        val minLeft = parent.paddingLeft.toFloat()
        val maxRight = (parent.width - parent.paddingRight).toFloat()
        val left = (appViews.minOf { it.left } - cardPad).coerceAtLeast(minLeft)
        val right = (appViews.maxOf { it.right } + cardPad).coerceAtMost(maxRight)
        val top = appViews.minOf { it.top } - cardPad
        val bottom = appViews.maxOf { it.bottom } + cardPad
        if (right <= left || bottom <= top) return

        rect.set(left, top, right, bottom)
        c.drawRoundRect(rect, cardRadius, cardRadius, card)
    }
}
