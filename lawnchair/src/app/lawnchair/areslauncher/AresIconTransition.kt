package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.theme.color.tokens.DayNightColorToken
import com.android.launcher3.Launcher
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A fuzzy Material-You **sparkle cover** for a live icon re-render (owner 2026-08-27, reworked to a
 * sparkle veil 2026-08-31, replacing the earlier radial-ring sweep).
 *
 * Changing the theme, icon shape or icon pack regenerates the home icons in place. Rather than let
 * them pop in a single visible frame, this drops a full-grid overlay over the home the instant the
 * user taps the control: a near-opaque M3-tinted veil over a frozen snapshot of the old grid, with a
 * field of soft, twinkling sparkles drifting across it. The veil **covers the whole homepage** so the
 * icon switch happens completely hidden behind it, and it **only dissolves away once the change is
 * done** -- for a synchronous change (theme/shape) after a short beat, and for the asynchronous
 * icon-pack reload only when the new icons have actually bound ([playFrozen], driven from
 * `LawnchairLauncher.finishBindingItems`). A safety timeout dissolves it anyway if that signal never
 * arrives, so the grid can never stay covered.
 *
 * **Why scoped to the home grid view.** A launcher window is transparent (the wallpaper is a separate
 * window behind it), so the snapshot base must line up pixel-for-pixel with the live grid; the home
 * grid does not move on a theme/shape/pack change, so it does. The app-list pane (which reflows) is
 * left out and simply updates.
 *
 * A pure visual overlay: not clickable (touches fall through), the snapshot is half-resolution to stay
 * cheap, only one is ever in flight, and everything is wrapped in try/catch so it can never break the
 * change that triggered it.
 */
object AresIconTransition {

    private const val TAG = "AresIconTransition"

    // Feel knobs -- tuned on-device with the owner.
    // Minimum time the sparkle veil stays up before it may dissolve, so even an instant (theme/shape)
    // change gets a deliberate sparkle beat rather than a blink.
    private const val MIN_HOLD_MS = 900L
    private const val FADE_IN_MS = 160L
    private const val FADE_OUT_MS = 550L
    // Extra hold AFTER bind-complete before dissolving, so home-screen widgets (which repaint a beat
    // after finishBindingItems, not on it) finish re-rendering behind the veil (owner 2026-08-31).
    private const val POST_BIND_HOLD_MS = 350L
    // Opacity of the coloured veil (0..255). FULLY opaque (owner
    // 2026-08-31: the cover must not be see-through) -- the home is completely hidden while the change
    // happens, and the new grid appears only when the veil dissolves.
    private const val VEIL_ALPHA = 255
    // How many sparkles scatter across the grid. A mix of M3 four- and six-point "sparkle" STARS (the
    // Material motif) and fine soft dust, so this reads as an expressive shimmer.
    private const val PARTICLE_COUNT = 160
    // Fraction of particles that are M3 sparkle stars (the rest are fine soft dust).
    private const val STAR_FRACTION = 0.22f
    // Base twinkle period; each sparkle varies around it so they never pulse in lockstep.
    private const val TWINKLE_MS = 1700f

    // Material 3 "emphasized" motion curves -- decelerate as the cover arrives, accelerate as it leaves
    // (owner 2026-08-31: a more Material You feel).
    private val EMPHASIZED_IN = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val EMPHASIZED_OUT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    // Safety cap: if the bind-complete signal for an icon-pack reload never arrives, dissolve anyway so
    // the grid can never stay covered. Generous, because a large pack's reload is slow.
    private const val FREEZE_TIMEOUT_MS = 6000L

    // How far the veil feathers to nothing at its BOTTOM edge. Off (0) now that the cover fills the
    // whole screen and the pill floats on top: any feather at the bottom just uncovers a strip over the
    // home bar (owner 2026-08-31). Kept as a knob in case a soft edge is wanted again.
    private const val EDGE_FEATHER_DP = 0f

    // The veil is a DIAGONAL gradient across the three live Monet accent hues, and the sparkles are the
    // vivid accents (+ white highlights). Sourced from Lawnchair's LIVE theme tokens
    // ([ColorTokens].*.resolveColor) -- the wallpaper-derived Material You palette the icons/UI actually
    // use -- NOT the static R.color.materialColor* fallbacks (owner 2026-08-31: the animation must use
    // the real theme colours). [DayNightColorToken] picks a light shade in light theme and a deep shade
    // in dark theme, so the cover reads well either way. Resolved per-cover so it follows the current
    // scheme.
    // The background is a single flowing radial M3 gradient: these accent tones are the stops. They sit
    // at a UNIFORM lightness (all _200 in light theme, all _800 in dark), so only the HUE drifts between
    // rings -- no light/dark jump -- which keeps the colour transitions soft and subtle (owner
    // 2026-08-31). Opaque stops + mirror tiling keep it fully opaque (the switch stays hidden).
    private val VEIL_TOKENS = listOf(
        DayNightColorToken(ColorTokens.Accent1_200, ColorTokens.Accent1_800),
        DayNightColorToken(ColorTokens.Accent2_200, ColorTokens.Accent2_800),
        DayNightColorToken(ColorTokens.Accent3_200, ColorTokens.Accent3_800),
    )
    // Flow feel for the ROUND (radial) gradient, which radiates from the pill (bottom-centre): ring
    // spacing as a fraction of the long side (bigger = broader rings); a small horizontal sway of the
    // origin; and a slow scale pulse so the rings breathe out from the pill and back.
    private const val FLOW_PERIOD_FRAC = 0.72f
    private const val FLOW_SWAY = 0.12f
    private const val FLOW_SWAY_SPEED = 0.5f
    private const val FLOW_PULSE = 0.32f
    private const val FLOW_PULSE_SPEED = 0.95f
    private val SPARKLE_TOKENS = listOf(
        DayNightColorToken(ColorTokens.Accent1_600, ColorTokens.Accent1_200),
        DayNightColorToken(ColorTokens.Accent3_600, ColorTokens.Accent3_200),
        DayNightColorToken(ColorTokens.Accent2_600, ColorTokens.Accent2_200),
        DayNightColorToken(ColorTokens.Accent1_400, ColorTokens.Accent1_100),
    )

    // A soft white dot (radial gradient, fuzzy tail), rendered once and reused for every sparkle; each
    // sparkle just tints and scales it. Lazily built so we never touch graphics off a live path.
    private val softDot: Bitmap by lazy { buildSoftDot() }
    // The crisp four-point Material sparkle star.
    private val star4: Bitmap by lazy { buildSparkleStar(points = 4, innerRatio = 0.16f) }

    // The in-flight overlay, if any. Only one launcher is active at a time, so a single reference is
    // enough to drop a stale overlay from a rapid re-toggle before starting the next.
    private var active: SparkleOverlay? = null
    private var holdTimeout: Runnable? = null
    private var holdTarget: View? = null

    /**
     * Cancel any in-flight sparkle cover, releasing its bitmap. Called on activity destroy (and on
     * edit-mode exit) so a cover still up -- or one waiting for a bind that will never come -- never
     * pins a destroyed activity via a static field (the leak class the carousel guards as F2, nightly
     * 2026-08-28 finding 5). Safe when nothing is up. Main thread.
     */
    fun cancel() {
        clearHoldTimeout()
        val o = active ?: return
        active = null
        teardown(o)
    }

    /**
     * Show the sparkle cover for a live icon change (theme, icon shape, or icon pack). All three route
     * through the same `reloadHelper.reloadIcons()` model reload, so the veil holds -- fully hiding the
     * home -- until [playFrozen] (bind-complete, from `LawnchairLauncher.finishBindingItems`) dissolves
     * it over the finished new grid, and never on a fixed timer (owner 2026-08-31: it must not complete
     * until the theme/shape/pack has actually updated). No-op if a cover is already up, so clicking
     * through packs/shapes keeps the one veil rather than restarting it. A safety timeout dissolves
     * anyway if bind-complete never fires, so the grid can never stay covered.
     */
    fun freeze(launcher: Launcher, target: View) {
        if (active != null) return
        val o = show(launcher, awaitRelease = true) ?: return
        holdTarget = target
        holdTimeout = Runnable { beginFadeOut() }.also { target.postDelayed(it, FREEZE_TIMEOUT_MS) }
    }

    /**
     * Dissolve a [freeze] cover now that the reloaded icons are on the grid. Called from the launcher's
     * bind-complete hook. Respects [MIN_HOLD_MS] so a fast reload still gets a full sparkle beat. No-op
     * unless a cover is up AND it is one awaiting release (so an unrelated bind never cuts a theme/shape
     * reveal short).
     */
    fun playFrozen(launcher: Launcher, target: View?) {
        val o = active ?: return
        if (!o.awaitRelease) return
        clearHoldTimeout()
        val elapsed = SystemClock.uptimeMillis() - o.shownAt
        // Hold at least [POST_BIND_HOLD_MS] past bind-complete (for widgets to repaint), and never less
        // than the [MIN_HOLD_MS] sparkle beat measured from when the cover appeared.
        val wait = (MIN_HOLD_MS - elapsed).coerceAtLeast(0L) + POST_BIND_HOLD_MS
        val t = target ?: o
        holdTarget = t
        holdTimeout = Runnable { beginFadeOut() }.also { t.postDelayed(it, wait) }
    }

    /**
     * Builds a full-screen [SparkleOverlay] filling the ENTIRE drag layer, fades it in and starts its
     * twinkle. The veil is fully opaque, so no snapshot is needed to hide the switch; the caller lifts
     * the settings pill above it (bringToFront) so only the pill stays visible on top (owner
     * 2026-08-31). Returns the overlay, or null if the drag layer has no size yet or anything throws --
     * a cover must never break the change that triggered it. Drops any existing cover first so they
     * never stack.
     */
    private fun show(launcher: Launcher, awaitRelease: Boolean): SparkleOverlay? {
        clearHoldTimeout()
        active?.let { active = null; teardown(it) }

        val dragLayer: BaseDragLayer<*> = launcher.dragLayer ?: return null
        val w = dragLayer.width
        val h = dragLayer.height
        if (w <= 0 || h <= 0) return null
        return try {
            // Cover the ENTIRE screen (owner 2026-08-31: the bottom ~15% -- the pill-bar region -- was
            // left uncovered when we stopped at the pill top). The pill is opaque and lifted above the
            // veil by the caller (bringToFront), so the whole screen animates and only the pill itself
            // stays visible on top.

            val palette = SPARKLE_TOKENS
                .map { PorterDuffColorFilter(opaque(it.resolveColor(launcher)), PorterDuff.Mode.SRC_IN) }
                .toMutableList()
                .apply { add(PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)) }
                .toTypedArray()

            val feather = EDGE_FEATHER_DP * launcher.resources.displayMetrics.density
            val overlay = SparkleOverlay(launcher).apply {
                this.awaitRelease = awaitRelease
                this.veilColors = VEIL_TOKENS.map { opaque(it.resolveColor(launcher)) }.toIntArray()
                this.featherPx = feather
                this.filters = palette
                this.particles = buildParticles(w, h, palette.size)
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
            overlay.animate().alpha(1f).setDuration(FADE_IN_MS).setInterpolator(EMPHASIZED_IN).start()
            overlay.startTwinkle()
            overlay
        } catch (t: Throwable) {
            Log.w(TAG, "sparkle cover failed", t)
            null
        }
    }

    /** Dissolve the current cover (fade out, then tear down). Idempotent while a fade is running. */
    private fun beginFadeOut() {
        val o = active ?: return
        if (o.fading) return
        o.fading = true
        o.animate().alpha(0f).setDuration(FADE_OUT_MS).setInterpolator(EMPHASIZED_OUT).withEndAction {
            if (active === o) active = null
            teardown(o)
        }.start()
    }

    private fun teardown(o: SparkleOverlay) {
        o.stopTwinkle()
        o.animate().cancel()
        (o.parent as? ViewGroup)?.removeView(o)
    }

    private fun clearHoldTimeout() {
        holdTimeout?.let { holdTarget?.removeCallbacks(it) }
        holdTimeout = null
        holdTarget = null
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun buildParticles(w: Int, h: Int, paletteSize: Int): Array<Particle> {
        val rnd = Random(SystemClock.uptimeMillis())
        val minDim = minOf(w, h).toFloat()
        return Array(PARTICLE_COUNT) {
            val isStar = rnd.nextFloat() < STAR_FRACTION
            Particle(
                x = rnd.nextFloat() * w,
                y = rnd.nextFloat() * h,
                // Stars are the visible motif, so a touch larger; dust stays fine.
                baseR = if (isStar) minDim * (0.020f + rnd.nextFloat() * 0.030f)
                else minDim * (0.010f + rnd.nextFloat() * 0.018f),
                colorIdx = rnd.nextInt(paletteSize),
                phase = rnd.nextFloat() * (2f * Math.PI.toFloat()),
                twSpeed = 0.7f + rnd.nextFloat() * 0.9f,
                driftPhase = rnd.nextFloat() * (2f * Math.PI.toFloat()),
                driftSpeed = 0.3f + rnd.nextFloat() * 0.5f,
                driftAmp = minDim * (0.006f + rnd.nextFloat() * 0.012f),
                isStar = isStar,
                spinPhase = rnd.nextFloat() * 360f,
                // Slow rotation, either direction, so the sparkles feel alive without spinning fast.
                spinSpeed = (if (rnd.nextBoolean()) 1f else -1f) * (14f + rnd.nextFloat() * 26f),
            )
        }
    }

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

    /**
     * A soft, glowing Material sparkle star (the M3 "sparkle" motif): a faint radial glow with a
     * blurred [points]-point star on top, pointing up, whose spikes are as thin as [innerRatio] is
     * small. Rendered white; each sparkle tints, scales and rotates it. Baked to a software bitmap so
     * the blur is free at draw time.
     */
    private fun buildSparkleStar(points: Int, innerRatio: Float): Bitmap {
        val d = 96
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val c = d / 2f
        // Faint radial glow behind the star.
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
        // Star: outer points, pinched inner vertices between -> thin sparkle spikes.
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

    private class Particle(
        val x: Float,
        val y: Float,
        val baseR: Float,
        val colorIdx: Int,
        val phase: Float,
        val twSpeed: Float,
        val driftPhase: Float,
        val driftSpeed: Float,
        val driftAmp: Float,
        val isStar: Boolean,
        val spinPhase: Float,
        val spinSpeed: Float,
    )

    /**
     * Draws, into an offscreen layer: an opaque DIAGONAL Material You veil filling the whole overlay
     * (which spans the entire screen down to the pill bar, so the home is fully hidden), then the
     * twinkling sparkle field. Finally only the BOTTOM edge -- the one against the pill bar -- is erased
     * with a soft alpha ramp ([featherPx]) via DST_OUT, so the cover melts into the pill area rather
     * than ending on a hard line; the top and sides run edge-to-edge for full coverage (owner
     * 2026-08-31). The whole view's [alpha] animates the fade-in and the dissolve-out, so on dissolve
     * the veil + sparkles fade together to reveal the live (new) grid.
     */
    private class SparkleOverlay(context: android.content.Context) : View(context) {
        var awaitRelease = false
        var fading = false
        var shownAt = 0L
        var veilColors = IntArray(0)
        var featherPx = 0f
        var filters: Array<PorterDuffColorFilter> = emptyArray()
        var particles: Array<Particle> = emptyArray()

        private var twinkle: ValueAnimator? = null
        private val dot = RectF()
        private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val flowMatrix = Matrix()
        private val dotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val erasePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private var flowShader: RadialGradient? = null
        private var flowPeriod = 0f
        private var edgeBottom: LinearGradient? = null

        init {
            setWillNotDraw(false)
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

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // Only composite into an offscreen layer when we actually feather an edge (the DST_OUT erase
            // needs the veil + sparkles on one layer). With no feather, draw straight to the canvas.
            val f = featherPx
            val feathering = f > 0f && h > 2 * f
            val layer = if (feathering) canvas.saveLayer(0f, 0f, w, h, null) else -1

            // Flowing ROUND M3 gradient radiating FROM THE PILL (bottom-centre): one opaque radial
            // multi-stop gradient (mirror-tiled into concentric rings) whose origin sits at the bottom
            // centre and gently sways, while the whole thing scales in and out -- the colour rings breathe
            // outward from the pill and back. Opaque throughout, so the switch stays hidden.
            if (veilColors.size >= 2) {
                if (flowShader == null) {
                    flowPeriod = maxOf(w, h) * FLOW_PERIOD_FRAC
                    flowShader = RadialGradient(
                        0f, 0f, flowPeriod, veilColors, null, Shader.TileMode.MIRROR,
                    )
                    flowPaint.shader = flowShader
                }
                val ft = (SystemClock.uptimeMillis() - shownAt) / 1000f
                val cx = w * (0.5f + FLOW_SWAY * sin(ft * FLOW_SWAY_SPEED))
                val cy = h // bottom-centre: where the pill sits, so the rings radiate up from it.
                val s = 1f + FLOW_PULSE * sin(ft * FLOW_PULSE_SPEED)
                flowMatrix.setScale(s, s)
                flowMatrix.postTranslate(cx, cy)
                flowShader!!.setLocalMatrix(flowMatrix)
                canvas.drawRect(0f, 0f, w, h, flowPaint)
            } else if (veilColors.isNotEmpty()) {
                canvas.drawColor(veilColors[0])
            }

            // Twinkling sparkle field: rotating M3 four-point sparkles + fine soft dust.
            val soft = softDot
            val s4 = star4
            val t = (SystemClock.uptimeMillis() - shownAt) / TWINKLE_MS
            for (p in particles) {
                val tw = 0.5f + 0.5f * sin(t * p.twSpeed * TWO_PI + p.phase)
                val a = tw * tw            // sharpen the twinkle
                if (a <= 0.02f) continue
                val r = p.baseR * (0.45f + 0.85f * tw)
                val dx = p.driftAmp * sin(t * p.driftSpeed * TWO_PI + p.driftPhase)
                val dy = p.driftAmp * sin(t * p.driftSpeed * TWO_PI + p.driftPhase + HALF_PI)
                val cx = p.x + dx
                val cy = p.y + dy
                dot.set(cx - r, cy - r, cx + r, cy + r)
                dotPaint.colorFilter = filters[p.colorIdx]
                dotPaint.alpha = (a * 255f).toInt().coerceIn(0, 255)
                if (p.isStar) {
                    val save = canvas.save()
                    canvas.rotate(p.spinPhase + t * p.spinSpeed, cx, cy)
                    canvas.drawBitmap(s4, null, dot, dotPaint)
                    canvas.restoreToCount(save)
                } else {
                    canvas.drawBitmap(soft, null, dot, dotPaint)
                }
            }

            // Feather ONLY the bottom edge, when enabled: erase alpha with a ramp full at the very
            // bottom and none [featherPx] up. Top and sides stay hard for full coverage.
            if (feathering) {
                if (edgeBottom == null) {
                    edgeBottom = LinearGradient(0f, h, 0f, h - f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                }
                erasePaint.shader = edgeBottom
                canvas.drawRect(0f, h - f, w, h, erasePaint)
                canvas.restoreToCount(layer)
            }
        }

        private companion object {
            val TWO_PI = 2f * Math.PI.toFloat()
            val HALF_PI = (Math.PI / 2.0).toFloat()
        }
    }
}
