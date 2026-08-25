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
    private val cardPad = 8f * density // card grows this far past the app tiles (top + sides)
    // The BOTTOM extends further than the sides so the last app-row's LABEL isn't crowded against the
    // card edge (owner 2026-08-24: "app text a little too close to the bottom of the folder").
    private val cardBottomPad = 20f * density
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
    val expandedBottomPadPx: Int = (cardBottomPad + bottomGap).toInt()

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

    // Material-3 close: as the folder collapses the card fades AWAY with the emphasized-ACCELERATE
    // curve (exiting surface), and is fully gone before its run is removed so it never pops. Driven
    // by [beginExit] off the wall clock; multiplies the entrance factor so a close mid-open fades
    // from wherever the card had reached rather than snapping to full first.
    private val exitEasing = PathInterpolator(0.3f, 0f, 0.8f, 0.15f) // M3 emphasized accelerate
    // Springy scale-pop for the card entrance (owner 2026-08-24), matching the icon flow's bounce.
    private val popEasing = android.view.animation.OvershootInterpolator(2.0f)
    private var collapsingAt = 0L
    private var exitDurMs = EXIT_MS // EXIT_MS scaled by the system animator duration, set in beginExit

    /**
     * Begin the card's fade-out over [durationMs] (the full reverse-cascade length the caller computes
     * from the child count), so the card is gone right as the last icon rises into the folder. Called
     * by [AresHomeListView.onWpFolderCollapsing] on close.
     */
    fun beginExit(durationMs: Float) {
        // Track the system animator duration scale so the card fade stays in lock-step with the child
        // falls (ValueAnimators, which the system scales) at any developer-options scale.
        val scale = try {
            android.provider.Settings.Global.getFloat(
                list.context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (e: Exception) {
            1f
        }
        exitDurMs = (durationMs * scale).coerceAtLeast(1f)
        collapsingAt = SystemClock.uptimeMillis()
        list.postInvalidateOnAnimation()
    }

    /**
     * True when ([x],[y]) in the list's coordinate space falls on the folder TITLE band at the top of
     * the card -- the region a tap should open the rename dialog on (owner 2026-08-24). Recomputes the
     * band from the live run exactly as [onDraw] does, so it can never disagree with what is drawn.
     */
    /**
     * The FULL (unbloomed) card rectangle in the list's coordinate space -- the same left/top/right/
     * bottom [onDraw] uses -- written into [out]. Returns false when nothing is expanded or the run has
     * no on-screen apps. Used by the child-fall animation to keep the flying icons inside the folder
     * background (owner 2026-08-24: "should NOT go beyond the folder background").
     */
    fun cardContentRect(out: RectF): Boolean {
        val run = list.aresAdapter.expandedRunRange() ?: return false
        var minTop = Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE
        var any = false
        for (pos in (run.first + 1)..run.last) {
            val v = list.findViewHolderForAdapterPosition(pos)?.itemView ?: continue
            any = true
            if (v.top.toFloat() < minTop) minTop = v.top.toFloat()
            if (v.bottom.toFloat() > maxBottom) maxBottom = v.bottom.toFloat()
        }
        if (!any) return false
        val left = list.paddingLeft.toFloat()
        val right = (list.width - list.paddingRight).toFloat()
        val top = minTop - cardPad - titleBandPx
        val bottom = maxBottom + cardBottomPad
        if (right <= left || bottom <= top) return false
        out.set(left, top, right, bottom)
        return true
    }

    fun titleBandContains(x: Float, y: Float): Boolean {
        val run = list.aresAdapter.expandedRunRange() ?: return false
        var minTop = Int.MAX_VALUE
        for (pos in (run.first + 1)..run.last) {
            val v = list.findViewHolderForAdapterPosition(pos)?.itemView ?: continue
            if (v.top < minTop) minTop = v.top
        }
        if (minTop == Int.MAX_VALUE) return false
        val left = list.paddingLeft.toFloat()
        val right = (list.width - list.paddingRight).toFloat()
        val bandTop = minTop - cardPad - titleBandPx
        return x in left..right && y in bandTop..(bandTop + titleBandPx)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val run = list.aresAdapter.expandedRunRange()
        if (run == null) {
            runAppearedAt = 0L
            collapsingAt = 0L
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
        // Close: drain the card back out from wherever the entrance had reached (no snap-to-full).
        val exitRaw = if (collapsingAt == 0L) 0f else
            ((now - collapsingAt).toFloat() / exitDurMs).coerceIn(0f, 1f)
        // The CLOSE does NOT fade the card (owner 2026-08-25: "close should be the same as the open
        // but in reverse -- not the fade"). It retracts by BLOOM SCALE into the teardrop tip instead
        // (see `bloom` below, still driven by exitRaw), staying at full opacity until it is small and
        // the run is removed. Open keeps its gentle fade-in via `enter`.
        val alphaF = enter
        card.alpha = Math.round(alphaF * baseCardAlpha)
        titlePaint.alpha = Math.round(alphaF * 255f)

        // Full grid-content WIDTH regardless of app count (owner 2026-08-24). Vertically the card
        // runs from a title band above the first app row down past the last, grown by cardPad.
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        val appsTop = appViews.minOf { it.top }.toFloat()
        val top = appsTop - cardPad - titleBandPx
        val bottom = appViews.maxOf { it.bottom } + cardBottomPad
        if (right <= left || bottom <= top) return

        // BLOOM FROM THE DROP (owner 2026-08-24, chosen over the ripple in an A/B): the card grows OUT
        // OF the teardrop's tip on open and collapses back INTO it on close, so it reads as the drop
        // opening into its apps rather than a panel fading in. The pivot is the tip (bottom-centre of
        // the folder tile), not the card centre; the scale springs from BLOOM_START to 1
        // (OvershootInterpolator) and reverses on exit.
        val folderView = parent.findViewHolderForAdapterPosition(run.first)?.itemView
        val tipX = folderView?.let { it.left + it.width / 2f } ?: ((left + right) / 2f)
        val tipY = folderView?.bottom?.toFloat() ?: top
        val bloomIn = BLOOM_START + (1f - BLOOM_START) * popEasing.getInterpolation(raw)
        val bloom = bloomIn + (BLOOM_START - bloomIn) * exitEasing.getInterpolation(exitRaw)
        val sLeft = tipX + (left - tipX) * bloom
        val sTop = tipY + (top - tipY) * bloom
        val sRight = tipX + (right - tipX) * bloom
        val sBottom = tipY + (bottom - tipY) * bloom

        rect.set(sLeft, sTop, sRight, sBottom)
        val r = cardRadius * bloom
        c.drawRoundRect(rect, r, r, card)

        // The folder title, centred in the band at the top of the (bloomed) card, standing in for the
        // label that the folder icon hides while expanded.
        val title = list.aresAdapter.expandedWpFolderTitle()?.toString()
        if (!title.isNullOrEmpty()) {
            val avail = (sRight - sLeft - 2 * titleHPad).coerceAtLeast(0f)
            val shown = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END)
            val baseline = sTop + titleVPad - titlePaint.ascent()
            c.drawText(shown, 0, shown.length, (sLeft + sRight) / 2f, baseline, titlePaint)
        }

        // Keep the entrance -- and the close fade -- advancing even after the child animators stop
        // invalidating the list.
        if (raw < 1f || (collapsingAt != 0L && exitRaw < 1f)) parent.postInvalidateOnAnimation()
    }

    private companion object {
        const val ENTER_MS = 260f // M3 medium
        const val BLOOM_START = 0.06f // card scale at the tip before it blooms out to full (overshoot)

        // Card fade-out on close. Fully drained before the reverse-fall cascade finishes and the adapter removes the
        // run, so the card is gone by then and never pops.
        const val EXIT_MS = 170f
        // The content is sequenced AFTER the icon morph and the tiles-below reflow (owner 2026-08-24):
        // the gap opens empty, THEN the card + apps unfurl into it. So there is never a card sitting
        // under a sliding tile. Matches WP_CHILD_ENTER_DELAY_MS.
        const val ENTER_DELAY_MS = 240L
    }
}
