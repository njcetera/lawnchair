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

    /** Filled pill for plain app rows: a translucent tonal fill behind the row. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.ares_search_highlight)
        alpha = HIGHLIGHT_ALPHA
    }

    /**
     * Outline ring for rich rows (web/contact/settings…), which already render their own background.
     * A stroke indicates selection without laying a second fill over that surface. Opaque (the
     * colour's own alpha) so the ring reads clearly against the row's tint.
     */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.ares_search_highlight)
        strokeWidth =
            context.resources.getDimension(R.dimen.ares_search_highlight_stroke)
    }
    private val radius = context.resources.getDimension(R.dimen.ares_search_highlight_radius)
    private val insetHorizontal =
        context.resources.getDimensionPixelSize(R.dimen.ares_search_highlight_inset).toFloat()
    private val rect = RectF()

    /**
     * Adapter position of the row to highlight — the first launchable result — or -1 for none.
     * Set from AresSearchContainerView; usually 0 (top app), but a query yielding only rich results
     * highlights the first real row beneath its section header rather than the header itself.
     */
    var activePosition = -1

    /**
     * When true the active row is a rich result with its own background, so it is highlighted with
     * an outline ring rather than a filled pill (which would double up on that background). Set from
     * AresSearchContainerView alongside [activePosition].
     */
    var activeAsOutline = false

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (activePosition < 0) return
        val holder = parent.findViewHolderForAdapterPosition(activePosition) ?: return
        val view = holder.itemView
        val top = view.y
        if (activeAsOutline) {
            // Inset by half the stroke so the ring sits fully inside the row bounds.
            val half = strokePaint.strokeWidth / 2f
            rect.set(
                insetHorizontal + half,
                top + half,
                parent.width - insetHorizontal - half,
                top + view.height - half,
            )
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        } else {
            rect.set(insetHorizontal, top, parent.width - insetHorizontal, top + view.height)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
    }

    private companion object {
        /** Translucent so the highlight tints the row rather than masking the wallpaper behind it. */
        const val HIGHLIGHT_ALPHA = 165
    }
}
