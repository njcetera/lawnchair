package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.theme.color.tokens.DayNightColorToken
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.folder.FolderIcon
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A **per-tile Material You sparkle** for a live icon re-render (owner 2026-08-31: sparkles on each
 * tile, not a full-screen veil).
 *
 * Changing the theme, icon shape or icon pack regenerates the home icons in place over a model reload.
 * On the tap, a single transparent overlay is placed over the home grid; it draws a small cluster of
 * soft, twinkling four-point M3 sparkle stars **over each icon tile**, reading the tiles' live positions
 * from the home list every frame (so it survives the grid rebinding tiles mid-reload). The sparkles
 * play across the WHOLE update and then, once the entire home has finished updating ([playFrozen], from
 * `LawnchairLauncher.finishBindingItems`, plus a beat for widgets), they all **resolve together** --
 * one uniform fade-out. A safety timeout resolves anyway if bind-complete never arrives.
 *
 * A pure decorative overlay: not clickable (touches fall through), only one is ever in flight, and
 * everything is wrapped in try/catch so it can never break the change that triggered it. Colours come
 * from Lawnchair's LIVE Monet palette ([ColorTokens]), so the sparkles track the real theme.
 */
object AresIconTransition {

    private const val TAG = "AresIconTransition"

    // Feel knobs.
    private const val MIN_HOLD_MS = 700L        // min sparkle beat even for an instant change
    private const val FADE_IN_MS = 220L
    private const val FADE_OUT_MS = 460L        // the unified resolve
    private const val POST_BIND_HOLD_MS = 350L  // let widgets repaint before resolving
    private const val FREEZE_TIMEOUT_MS = 6000L // safety: resolve even if bind-complete never fires
    private const val TWINKLE_MS = 1600f

    // Each tile is covered by an opaque flowing M3 gradient (so the icon/widget swap is hidden), with
    // twinkling sparkle stars + fine dust on top. The particle COUNT scales with the tile's area (so a
    // big widget gets proportionally more, keeping the density uniform, owner 2026-08-31): this many for
    // an app-sized tile, of which ~STAR_FRAC are stars and the rest dust. Positions/phases are placed
    // procedurally per tile (a cheap deterministic hash), so any count spreads without repeating.
    private const val PARTICLES_PER_REF = 14
    private const val STAR_FRAC = 0.3f
    private const val PARTICLES_MAX = 120

    // Live Monet accents (+ white) for the sparkles.
    private val SPARKLE_TOKENS = listOf(
        DayNightColorToken(ColorTokens.Accent1_600, ColorTokens.Accent1_200),
        DayNightColorToken(ColorTokens.Accent3_600, ColorTokens.Accent3_200),
        DayNightColorToken(ColorTokens.Accent2_600, ColorTokens.Accent2_200),
        DayNightColorToken(ColorTokens.Accent1_400, ColorTokens.Accent1_100),
    )

    // The per-tile cover fill: a single flowing radial M3 gradient (uniform-lightness accents so hue
    // drifts subtly), shared across all tiles and windowed to each -- opaque, so the swap is hidden.
    private const val VEIL_ALPHA = 255
    private val VEIL_TOKENS = listOf(
        DayNightColorToken(ColorTokens.Accent1_200, ColorTokens.Accent1_800),
        DayNightColorToken(ColorTokens.Accent2_200, ColorTokens.Accent2_800),
        DayNightColorToken(ColorTokens.Accent3_200, ColorTokens.Accent3_800),
    )
    private const val FLOW_PERIOD_FRAC = 0.72f
    private const val FLOW_SWAY = 0.12f
    private const val FLOW_SWAY_SPEED = 0.5f
    private const val FLOW_PULSE = 0.32f
    private const val FLOW_PULSE_SPEED = 0.95f
    // Slight softening of the per-tile cover edges (owner 2026-08-31). Needs a software layer to render.
    // Kept small so the edge doesn't feather transparent enough to see the icon behind it.
    private const val EDGE_BLUR_DP = 3f

    private val star4: Bitmap by lazy { buildSparkleStar(points = 4, innerRatio = 0.16f) }
    // Fine fuzzy dust speckles that sit among the stars.
    private val softDot: Bitmap by lazy { buildSoftDot() }

    private var active: TileSparkleOverlay? = null
    private var holdTimeout: Runnable? = null
    private var holdTarget: View? = null

    /** Cancel any in-flight sparkle overlay (activity destroy / edit-mode exit). Safe when idle. */
    fun cancel() {
        clearHoldTimeout()
        val o = active ?: return
        active = null
        teardown(o)
    }

    /**
     * Start the per-tile sparkles for a live icon change. [target] is the home list (the tiles to
     * sparkle). They play until [playFrozen] (bind-complete) resolves them, or a safety timeout does.
     * No-op if sparkles are already up, so clicking through packs/shapes keeps the one field.
     */
    fun freeze(launcher: Launcher, target: View) {
        if (active != null) return
        val list = target as? ViewGroup ?: return
        show(launcher, list) ?: return
        holdTarget = target
        holdTimeout = Runnable { beginFadeOut() }.also { target.postDelayed(it, FREEZE_TIMEOUT_MS) }
    }

    /**
     * The home has finished updating: resolve the sparkles (fade all out together). Respects
     * [MIN_HOLD_MS] and adds [POST_BIND_HOLD_MS] for widgets. No-op if nothing is up.
     */
    fun playFrozen(launcher: Launcher, target: View?) {
        val o = active ?: return
        clearHoldTimeout()
        val elapsed = SystemClock.uptimeMillis() - o.shownAt
        val wait = (MIN_HOLD_MS - elapsed).coerceAtLeast(0L) + POST_BIND_HOLD_MS
        val t = target ?: o
        holdTarget = t
        holdTimeout = Runnable { beginFadeOut() }.also { t.postDelayed(it, wait) }
    }

    /** Places a transparent [TileSparkleOverlay] over the whole drag layer and fades its sparkles in. */
    private fun show(launcher: Launcher, list: ViewGroup): TileSparkleOverlay? {
        clearHoldTimeout()
        active?.let { active = null; teardown(it) }

        val dragLayer: BaseDragLayer<*> = launcher.dragLayer ?: return null
        val w = dragLayer.width
        val h = dragLayer.height
        if (w <= 0 || h <= 0) return null
        return try {
            val filters = SPARKLE_TOKENS
                .map { PorterDuffColorFilter(opaque(it.resolveColor(launcher)), PorterDuff.Mode.SRC_IN) }
                .toMutableList()
                .apply { add(PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)) }
                .toTypedArray()

            // The home list's top-left in drag-layer coordinates, so tile positions map into the overlay.
            val xy = intArrayOf(0, 0)
            dragLayer.getDescendantCoordRelativeToSelf(list, xy)

            val overlay = TileSparkleOverlay(launcher).apply {
                this.listRef = list
                this.originX = xy[0].toFloat()
                this.originY = xy[1].toFloat()
                this.filters = filters
                this.veilColors = VEIL_TOKENS.map { opaque(it.resolveColor(launcher)) }.toIntArray()
                // Match the edit-mode tile background (same rounded-cell radius).
                this.cornerPx = launcher.resources.getDimension(R.dimen.ares_edit_cell_outline_radius)
                this.blurPx = EDGE_BLUR_DP * launcher.resources.displayMetrics.density
                this.shownAt = SystemClock.uptimeMillis()
                isClickable = false
                isFocusable = false
                alpha = 0f
            }
            val lp = BaseDragLayer.LayoutParams(w, h).apply {
                customPosition = true
                x = 0
                y = 0
            }
            dragLayer.addView(overlay, lp)
            active = overlay
            overlay.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            overlay.startTwinkle()
            overlay
        } catch (t: Throwable) {
            Log.w(TAG, "tile sparkles failed", t)
            null
        }
    }

    /** Resolve: fade all sparkles out together, then tear down. Idempotent while resolving. */
    private fun beginFadeOut() {
        val o = active ?: return
        if (o.fading) return
        o.fading = true
        o.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            if (active === o) active = null
            teardown(o)
        }.start()
    }

    private fun teardown(o: TileSparkleOverlay) {
        o.stopTwinkle()
        o.animate().cancel()
        o.listRef = null
        (o.parent as? ViewGroup)?.removeView(o)
    }

    private fun clearHoldTimeout() {
        holdTimeout?.let { holdTarget?.removeCallbacks(it) }
        holdTimeout = null
        holdTarget = null
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun buildSoftDot(): Bitmap {
        val d = 64
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = d / 2f
        val shader = RadialGradient(
            c, c, c,
            intArrayOf(Color.WHITE, (0x66FFFFFF).toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        Canvas(bmp).drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        return bmp
    }

    /** A soft, glowing four-point Material sparkle star, baked to a bitmap; each sparkle tints/rotates it. */
    private fun buildSparkleStar(points: Int, innerRatio: Float): Bitmap {
        val d = 96
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val c = d / 2f
        cv.drawCircle(
            c, c, c,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    c, c, c,
                    intArrayOf((0x33FFFFFF).toInt(), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        val outer = c * 0.94f
        val inner = c * innerRatio
        val verts = points * 2
        val path = Path()
        for (i in 0 until verts) {
            val rr = if (i % 2 == 0) outer else inner
            val ang = Math.PI / points * i - Math.PI / 2.0
            val px = c + rr * cos(ang).toFloat()
            val py = c + rr * sin(ang).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        cv.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                maskFilter = BlurMaskFilter(d * 0.03f, BlurMaskFilter.Blur.NORMAL)
            },
        )
        return bmp
    }

    /**
     * Transparent overlay that draws a twinkling four-point-star cluster over each icon tile of
     * [listRef], every frame, mapping tile positions through the cached list origin. The whole view's
     * [alpha] fades the sparkles in on show and out on resolve, so they all clear together.
     */
    private class TileSparkleOverlay(context: android.content.Context) : View(context) {
        var listRef: ViewGroup? = null
        var originX = 0f
        var originY = 0f
        var fading = false
        var shownAt = 0L
        var filters: Array<PorterDuffColorFilter> = emptyArray()
        var veilColors = IntArray(0)
        var cornerPx = 0f
        var blurPx = 0f

        private var twinkle: ValueAnimator? = null
        private val dot = RectF()
        private val cover = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val flowMatrix = Matrix()
        private var flowShader: RadialGradient? = null
        private var flowPeriod = 0f

        // Per-tile layout snapshot, refreshed each fully-populated frame and held across the brief
        // window where a reload has cleared the RecyclerView (see onDraw). Packed as SNAP_STRIDE floats
        // per tile: cx, cy, halfX, halfY, scaleX, scaleY, rotation, particleCount, idBase.
        private var snap = FloatArray(0)
        private var snapCount = 0

        /** Reused by [rebuildSnapshot] so the per-frame origin re-read allocates nothing. */
        private val originScratch = IntArray(2)
        private var snapRefUnit = 0f
        private var wasHolding = false
        private var holdStartMs = 0L

        init {
            setWillNotDraw(false)
            // BlurMaskFilter (the per-tile edge blur) is ignored on the hardware canvas.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun startTwinkle() {
            twinkle = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { invalidate() }
                start()
            }
        }

        fun stopTwinkle() {
            twinkle?.cancel()
            twinkle = null
        }

        /**
         * Refresh [snap] from the live grid children. Packs SNAP_STRIDE floats per drawable tile and
         * derives [snapRefUnit] from a representative app tile (so particles stay a consistent absolute
         * size on icons and widgets alike). Only ever called when the grid is populated; the caller
         * holds the previous snapshot across a reload's momentary empty frame.
         */
        private fun rebuildSnapshot(list: ViewGroup, rv: RecyclerView?) {
            // Re-read the list's drag-layer origin every snapshot rather than trusting the one cached
            // in show(). Tile positions are mapped as origin + container.x/y, so a cached origin is
            // only correct while the list stays put -- and a model reload now REPARENTS it (Workspace
            // .removeAllWorkspaceScreens lifts it out of the page being destroyed and re-attaches it
            // into the freshly built one). A stale origin shifts every cover by the same delta, which
            // is the covers sliding off their tiles midway through the animation (owner 2026-09-01).
            (context as? Launcher)?.dragLayer?.let { dl ->
                // ZERO IT FIRST. getDescendantCoordRelativeToSelf takes `coord` as an IN/OUT point --
                // "the coordinate that we want mapped" (Utilities.getDescendantCoordRelativeToAncestor)
                // -- and transforms it in place rather than resetting it. show() gets away with a bare
                // call because it passes a freshly allocated intArrayOf(0, 0) every time; a REUSED
                // scratch array must be reset or each frame re-maps the previous result and the origin
                // runs away unbounded (measured 2026-09-01: 20 -> 280 -> 820 -> 2020 over four frames,
                // which is the covers sliding off the screen).
                originScratch[0] = 0
                originScratch[1] = 0
                dl.getDescendantCoordRelativeToSelf(list, originScratch)
                originX = originScratch[0].toFloat()
                originY = originScratch[1].toFloat()
            }
            // One reference size for ALL particles, taken from a representative app tile.
            var refUnit = 0f
            var refArea = 0f
            for (i in 0 until list.childCount) {
                val c = list.getChildAt(i) as? ViewGroup ?: continue
                val ch0 = c.getChildAt(0) ?: continue
                if (ch0 is BubbleTextView || ch0 is FolderIcon) {
                    refUnit = min(c.width, c.height) / 2f
                    refArea = (c.width * c.height).toFloat()
                    break
                }
            }
            if (refUnit <= 0f) {
                val u = 40f * resources.displayMetrics.density
                refUnit = u
                refArea = (2f * u) * (2f * u)
            }
            snapRefUnit = refUnit

            val n = list.childCount
            if (snap.size < n * SNAP_STRIDE) snap = FloatArray(n * SNAP_STRIDE)
            var idx = 0
            for (i in 0 until n) {
                val container = list.getChildAt(i) as? ViewGroup ?: continue
                container.getChildAt(0) ?: continue
                val cw = container.width
                val ch = container.height
                if (cw <= 0 || ch <= 0) continue
                val left = originX + container.x
                val top = originY + container.y
                val b = idx * SNAP_STRIDE
                snap[b] = left + cw / 2f
                snap[b + 1] = top + ch / 2f
                snap[b + 2] = cw / 2f
                snap[b + 3] = ch / 2f
                snap[b + 4] = container.scaleX
                snap[b + 5] = container.scaleY
                snap[b + 6] = container.rotation
                // Count scales with the tile's area, so density is uniform on icons and big widgets.
                snap[b + 7] = (PARTICLES_PER_REF * (cw * ch) / refArea).roundToInt().coerceIn(4, PARTICLES_MAX).toFloat()
                // Seed on the tile's STABLE item id, not the child index -- when the reload rebinds tiles
                // the indices shuffle, which would otherwise make every sparkle jump (looks like a reset).
                snap[b + 8] = ((rv?.findContainingViewHolder(container)?.itemId ?: i.toLong()) and 0xFFFFL).toFloat()
                idx++
            }
            snapCount = idx
        }

        override fun onDraw(canvas: Canvas) {
            val list = listRef ?: return
            if (filters.isEmpty()) return
            val w = width.toFloat()
            val h = height.toFloat()

            // One flowing radial M3 gradient, shared across all tiles (origin bottom-centre, breathing).
            if (flowShader == null && veilColors.size >= 2) {
                flowPeriod = maxOf(w, h) * FLOW_PERIOD_FRAC
                flowShader = RadialGradient(0f, 0f, flowPeriod, veilColors, null, Shader.TileMode.MIRROR)
                flowPaint.shader = flowShader
                if (blurPx > 0f) flowPaint.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            val ft = (SystemClock.uptimeMillis() - shownAt) / 1000f
            flowShader?.let {
                val gx = w * (0.5f + FLOW_SWAY * sin(ft * FLOW_SWAY_SPEED))
                val gs = 1f + FLOW_PULSE * sin(ft * FLOW_PULSE_SPEED)
                flowMatrix.setScale(gs, gs)
                flowMatrix.postTranslate(gx, h)
                it.setLocalMatrix(flowMatrix)
            }
            flowPaint.alpha = VEIL_ALPHA

            val star = star4
            val dust = softDot
            val rv = list as? RecyclerView

            // Capture the tile layout from the LIVE grid while it is populated; otherwise HOLD the last
            // snapshot. The 2nd+ theme/pack reload rebinds the RecyclerView by clearing and repopulating
            // it, so for a frame or two mid-animation childCount drops (measured: 15->11, 11->7) -- and
            // without this bridge the opaque covers vanish for that frame and the icon swap flashes
            // through (owner: "second change flickers once midway"). Re-snapshot every fully-populated
            // frame so the covers keep tracking the edit-mode wiggle.
            //
            // The hold is TIME-BOUNDED: a rebind can briefly attach MORE children than steady state
            // (disappearing + incoming tiles overlap), which would latch snapCount too high and hold
            // forever. So if the drop persists past HOLD_BRIDGE_MAX_MS it is the real new count, not a
            // rebind gap -- adopt it by re-snapshotting from the live grid.
            val live = list.childCount
            val now = SystemClock.uptimeMillis()
            val wantHold = snapCount > 0 && live < snapCount
            if (wantHold && !wasHolding) holdStartMs = now
            wasHolding = wantHold
            val holding = wantHold && (now - holdStartMs) < HOLD_BRIDGE_MAX_MS
            if (!holding) rebuildSnapshot(list, rv)
            if (snapCount == 0) return

            val t = (SystemClock.uptimeMillis() - shownAt) / TWINKLE_MS
            val refUnit = snapRefUnit

            for (j in 0 until snapCount) {
                val b = j * SNAP_STRIDE
                val sCx = snap[b]
                val sCy = snap[b + 1]
                val sHalfX = snap[b + 2]
                val sHalfY = snap[b + 3]
                val count = snap[b + 7].toInt()
                val idBase = snap[b + 8]

                // Cover the WHOLE cell (matching the edit-mode tile background), rounded to its radius.
                // Outset by the blur radius so the fully-opaque core reaches the tile edge (widgets fill
                // their cell to the edge, so an inset left their rim showing). The blur then feathers a
                // little past that; kept small (EDGE_BLUR_DP) so it doesn't reach into a neighbour.
                cover.set(sCx - sHalfX - blurPx, sCy - sHalfY - blurPx, sCx + sHalfX + blurPx, sCy + sHalfY + blurPx)

                // Apply the tile's live scale + rotation (edit mode scales tiles down and wiggles them),
                // so the cover lines up exactly with the shrunken tile rather than the layout bounds.
                val tileSave = canvas.save()
                canvas.rotate(snap[b + 6], sCx, sCy)
                canvas.scale(snap[b + 4], snap[b + 5], sCx, sCy)

                // Opaque gradient cover -> the icon/widget swap underneath is hidden.
                canvas.drawRoundRect(cover, cornerPx, cornerPx, flowPaint)

                for (k in 0 until count) {
                    val seed = idBase * 131.7f + k * 37.13f
                    val ox = hash(seed + 1.3f) * 2f - 1f
                    val oy = hash(seed + 5.7f) * 2f - 1f
                    val ph = hash(seed + 9.1f) * TAU
                    val sp = 0.6f + hash(seed + 13.5f) * 0.6f
                    val tw = 0.5f + 0.5f * sin(t * sp * TAU + ph)
                    val a = tw * tw
                    if (a <= 0.04f) continue
                    val px = sCx + ox * sHalfX * 0.92f
                    val py = sCy + oy * sHalfY * 0.92f
                    if (hash(seed + 21.9f) < STAR_FRAC) {
                        // Coloured four-point star.
                        val r = refUnit * (0.16f + 0.12f * hash(seed + 3.3f)) * (0.55f + 0.75f * tw)
                        dot.set(px - r, py - r, px + r, py + r)
                        paint.colorFilter = filters[k % filters.size]
                        paint.alpha = (a * 255f).toInt().coerceIn(0, 255)
                        val save = canvas.save()
                        canvas.rotate(ph * 57f + t * (18f + 10f * hash(seed + 7.7f)), px, py)
                        canvas.drawBitmap(star, null, dot, paint)
                        canvas.restoreToCount(save)
                    } else {
                        // Fine white dust speck.
                        val r = refUnit * 0.06f * (0.6f + 0.9f * tw)
                        dot.set(px - r, py - r, px + r, py + r)
                        paint.colorFilter = null
                        paint.alpha = (a * 210f).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(dust, null, dot, paint)
                    }
                }

                canvas.restoreToCount(tileSave)
            }
        }

        private companion object {
            val TAU = 2f * Math.PI.toFloat()

            /** Floats per tile in the layout snapshot: cx, cy, halfX, halfY, sx, sy, rot, count, idBase. */
            const val SNAP_STRIDE = 9

            /** Max time to hold a stale snapshot across a reload's rebind gap before adopting the live
             *  (smaller) grid -- bounds the hold so a transient child overshoot can't latch it forever. */
            const val HOLD_BRIDGE_MAX_MS = 300L

            /** Cheap deterministic hash -> [0,1), for scattering particles per tile without arrays. */
            fun hash(x: Float): Float {
                val s = sin(x) * 43758.547f
                return s - floor(s)
            }
        }
    }
}
