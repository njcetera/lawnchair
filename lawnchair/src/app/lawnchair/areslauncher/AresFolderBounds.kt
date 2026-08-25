package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.util.resolveFolderPreviewColor
import com.android.launcher3.R

/**
 * Decorates an inline-expanded WP folder with a single rounded Material-You card BEHIND its opened
 * apps, tinted to the folder icon's own colour (the closed-folder preview colour, so the card, the
 * folder circle and the downward pointer all read as one surface), and the folder's TITLE centred in
 * a band at the top of that card. While a folder is expanded its under-icon label is hidden
 * (FolderIcon.setAresHidePreviewItems) and this title stands in for it (owner 2026-08-24). Nothing is
 * drawn when no folder is expanded.
 *
 * The card spans the full grid content width regardless of app count and reserves a title band at
 * its top. The vertical space this needs -- title band + card padding + a breathing gap above, and a
 * gap below -- is published as [expandedTopPadPx]/[expandedBottomPadPx] so the layout manager can
 * reserve exactly that much around the run; the card geometry here and the reserved space there are
 * therefore derived from the same constants and cannot drift.
 *
 * Drawn in [onDraw] (beneath the item views) so the app icons sit ON the card; the title lands in the
 * empty band above them. An [RecyclerView.ItemDecoration]: it reads the expanded run from the adapter
 * on each draw, so it always matches the live expand state and needs no lifecycle of its own. A tile
 * scrolled out of view is simply skipped -- its holder is not attached to measure.
 */
class AresFolderBounds(
    context: Context,
    private val list: AresHomeListView,
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density

    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The closed-folder preview colour -- exactly what the folder circle and the expanded
        // pointer are filled with -- so the open folder and its apps are visibly one surface.
        color = resolveFolderPreviewColor(context)
        style = Paint.Style.FILL
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.materialColorOnSurface)
        textAlign = Paint.Align.CENTER
        textSize = 14f * context.resources.displayMetrics.scaledDensity
    }

    private val cardRadius = 24f * density
    private val cardPad = 8f * density // card grows this far past the app tiles
    private val titleVPad = 7f * density // above and below the title text within its band
    private val titleHPad = 16f * density // keep the title clear of the card's rounded corners
    // Small gap between the folder tile and the card: the teardrop pointer is drawn low in its tile
    // (near its bottom edge), so a tight gap here makes it read as pointing INTO the card below it.
    private val topGap = 6f * density
    private val bottomGap = 12f * density // breathing gap below the card

    /** Height of the title band at the top of the card, sized to the title text plus its padding. */
    private val titleBandPx = (titlePaint.descent() - titlePaint.ascent()) + 2 * titleVPad

    /**
     * Vertical space the layout manager must reserve ABOVE the opened apps: the breathing gap, then
     * the card's own top padding and the title band the card draws into. Consumed by
     * [AresMasonryLayoutManager.expandPadTopPx].
     */
    val expandedTopPadPx: Int = (topGap + cardPad + titleBandPx).toInt()

    /** Vertical space to reserve BELOW the opened apps: the card's bottom padding and a gap. */
    val expandedBottomPadPx: Int = (cardPad + bottomGap).toInt()

    private val rect = RectF()

    // Material-3 "fade through" for the card as the folder opens: the surface fades in with the
    // emphasized-decelerate curve, and starts just AFTER the tiles below begin sliding away
    // ([ENTER_DELAY_MS]) so it is still near-transparent while a tile slides across its region --
    // which is what stopped the card and its apps looking like they collide with the reflow (owner
    // 2026-08-24, "elements overlapping during the transition"). Timed off the wall clock: the run
    // first seen resets the clock; run gone resets it so a later open replays.
    private val enterEasing = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // M3 emphasized decelerate
    private val baseCardAlpha = Color.alpha(card.color)
    private var runAppearedAt = 0L

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange()
        if (run == null) {
            runAppearedAt = 0L
            return
        }

        val appViews = ArrayList<View>()
        for (pos in (run.first + 1)..run.last) {
            parent.findViewHolderForAdapterPosition(pos)?.itemView?.let { appViews.add(it) }
        }
        if (appViews.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        if (runAppearedAt == 0L) runAppearedAt = now
        val raw = ((now - runAppearedAt - ENTER_DELAY_MS).toFloat() / ENTER_MS).coerceIn(0f, 1f)
        val enter = enterEasing.getInterpolation(raw)
        card.alpha = Math.round(enter * baseCardAlpha)
        titlePaint.alpha = Math.round(enter * 255f)

        // Full grid-content WIDTH regardless of app count (owner 2026-08-24). Vertically the card
        // runs from a title band above the first app row down past the last, grown by cardPad.
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        val appsTop = appViews.minOf { it.top }.toFloat()
        val top = appsTop - cardPad - titleBandPx
        val bottom = appViews.maxOf { it.bottom } + cardPad
        if (right <= left || bottom <= top) return

        rect.set(left, top, right, bottom)
        c.drawRoundRect(rect, cardRadius, cardRadius, card)

        // The folder title, centred in the band at the top of the card, standing in for the label
        // that the folder icon hides while expanded.
        val title = list.aresAdapter.expandedWpFolderTitle()?.toString()
        if (!title.isNullOrEmpty()) {
            val avail = (right - left - 2 * titleHPad).coerceAtLeast(0f)
            val shown = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END)
            val baseline = top + titleVPad - titlePaint.ascent()
            c.drawText(shown, 0, shown.length, (left + right) / 2f, baseline, titlePaint)
        }

        // Keep the entrance advancing even after the child fades stop invalidating the list.
        if (raw < 1f) parent.postInvalidateOnAnimation()
    }

    private companion object {
        const val ENTER_MS = 300f // M3 medium-2
        const val ENTER_DELAY_MS = 50L // let the reflow lead; content follows
    }
}
