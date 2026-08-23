package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * AresLauncher §17 — highlights the top search result so it reads as the default selection that the
 * keyboard's search/enter action will launch (owner). Drawn as a rounded tonal pill *behind* the
 * first result row, so the row's icon and (white) label stay on top at full contrast.
 *
 * A decoration rather than a per-row background: the app rows are plain `BubbleTextView`s bound by
 * the stock adapter, which never calls the search adapter provider for them, so there is no bind hook
 * to tag the first row. Drawing in [onDraw] instead re-evaluates the current first child every frame,
 * so it tracks the top result as the query changes and survives recycling with no timing games.
 *
 * The fill is the Monet tertiary tone (the same dark-orange family as the close/enter button), kept
 * translucent so it tints rather than blocks the dimmed wallpaper behind it. [active] gates it to
 * while a query is showing results.
 */
class AresSearchHighlightDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ares_search_highlight)
        alpha = HIGHLIGHT_ALPHA
    }
    private val radius = context.resources.getDimension(R.dimen.ares_search_highlight_radius)
    private val insetHorizontal =
        context.resources.getDimensionPixelSize(R.dimen.ares_search_highlight_inset).toFloat()
    private val rect = RectF()

    /** Set true only while a query is showing results; see AresSearchContainerView. */
    var active = false

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!active) return
        val holder = parent.findViewHolderForAdapterPosition(0) ?: return
        val view = holder.itemView
        val top = view.y
        rect.set(insetHorizontal, top, parent.width - insetHorizontal, top + view.height)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private companion object {
        /** Translucent so the highlight tints the row rather than masking the wallpaper behind it. */
        const val HIGHLIGHT_ALPHA = 165
    }
}
