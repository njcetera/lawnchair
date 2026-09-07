package app.lawnchair.areslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import com.android.launcher3.Utilities
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.theme.color.tokens.DayNightColorToken
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A **per-tile Material You sparkle** for a live icon re-render (owner 2026-08-31: sparkles on each
 * tile, not a full-screen veil).
 *
 * Changing the theme, icon shape or icon pack regenerates the home icons in place over a model reload.
 * On the tap, a single transparent overlay is placed over the drag layer; it draws a small cluster of
 * soft, twinkling four-point M3 sparkle stars **over each icon tile**, reading the tiles' live positions
 * from the home list every frame (so it survives the grid rebinding tiles mid-reload). The sparkles
 * play across the WHOLE update and then, once the entire home has finished updating ([playFrozen], from
 * `LawnchairLauncher.finishBindingItems`, plus a beat for widgets), they all **resolve together** --
 * one uniform fade-out. A safety timeout resolves anyway if bind-complete never arrives.
 *
 * **Two layers, two signals (ledger row 104).** Unfolded, the app-list pane sits beside the grid and
 * its icons regenerate in the same reload -- but LATER: the loader binds the workspace (bind-complete
 * fires [playFrozen]) and only then loads and binds all apps. So the pane's icons get their own
 * covers (the ICON of each visible row, not the ~1000px row) and their own resolve, [playFrozenPane]
 * from `bindAllApplications`. Before this the pane swapped bare while the grid sparkled (owner
 * 2026-09-06: "the animation during theme change or icon update doesn't apply to the app list
 * icons which can be slightly jarring"). Folded, there is no pane and the second layer is empty.
 *
 * A pure decorative overlay: not clickable (touches fall through), only one is ever in flight, and
 * everything is wrapped in try/catch so it can never break the change that triggered it. Colours come
 * from Lawnchair's LIVE Monet palette ([ColorTokens]), so the sparkles track the real theme.
 */
object AresIconTransition {

    private const val TAG = "AresIconTransition"

    // Feel knobs. Trimmed 2026-09-07 for ledger row 139 (owner: "these should both update really
    // fast so the animation isn't so long"): 700/460/350 -> 400/320/150. Measured on the Pixel the
    // floor was ~0.35 s of the home's 1.7 s; the pane's time is the all-apps reload, not this.
    private const val MIN_HOLD_MS = 400L        // min sparkle beat even for an instant change
    private const val FADE_IN_MS = 220L
    private const val FADE_OUT_MS = 320L        // the unified resolve
    private const val POST_BIND_HOLD_MS = 150L  // let widgets repaint before resolving
    private const val FREEZE_TIMEOUT_MS = 6000L // safety: resolve even if bind-complete never fires
    // Safety for the PANE layer, from show: all apps bind after the workspace and, on a device with
    // hundreds of apps, seconds after it (ledger row 77: loadAllApps ~46x slower on the Pixel), so
    // the pane must not fall to the 6 s global safety -- that would lift its covers before its icons
    // change, the exact defect row 104 fixed (panel review 2026-09-07). Generous on purpose: a pane
    // cover that stays a few seconds too long is a beat; one that lifts too early is a bare swap.
    private const val PANE_SAFETY_MS = 20000L
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

    // Volatile: written on the main thread, read by the loader thread in [loaderMaySkipIdleWait].
    @Volatile
    private var active: TileSparkleOverlay? = null
    private var holdTimeout: Runnable? = null
    private var holdTarget: View? = null
    private var paneTimeout: Runnable? = null

    /**
     * True while a sparkle overlay is mounted on the drag layer (between [freeze] and its teardown).
     * Read by the `ares-icon-transition` test channel to prove the overlay actually appears on the
     * current device/posture, rather than silently failing to construct (null drag layer, zero-size,
     * a color-resolve throw) — the "the animation isn't happening" class.
     */
    val isShowing: Boolean get() = active != null

    /**
     * Row 139: whether `LoaderTask.waitForIdle` may return at once. Stock will not start
     * `loadAllApps` until the main looper goes idle ("let the workspace settle"); after a home rebind
     * on the Pixel that wait was 0.80–0.92 s in every measured run, and while a transition is showing
     * the sparkle already covers both surfaces — the owner is waiting on the all-apps bind, not on a
     * settled home. `setprop debug.ares.loader_break 1` restores the wait (the control arm of the
     * A/B). Called on the LOADER thread, hence `active` is volatile. Both branches log.
     */
    @JvmStatic
    fun loaderMaySkipIdleWait(): Boolean {
        if (keepLoaderBreak) {
            Log.i(TAG, "loader idle wait kept: debug.ares.loader_break=1")
            return false
        }
        val showing = active != null
        Log.i(TAG, if (showing) "loader idle wait skipped: transition showing" else "loader idle wait kept: no transition")
        return showing
    }

    private val keepLoaderBreak: Boolean by lazy {
        Utilities.getSystemProperty("debug.ares.loader_break", "") == "1"
    }

    /**
     * One line for the test channel: whether an overlay is up and how many covers each layer drew on
     * its last frame (`pane=` is 0 folded, where there is no pane). Proves the PANE layer mounts.
     */
    fun summary(): String {
        val o = active ?: return "showing=false"
        return "showing=true|home=${o.home.snapCount}|pane=${o.pane.snapCount}|paneAttached=${o.pane.list != null}"
    }

    /** The overlay is a DragLayer child of the activity it was built for; drop it when that activity dies. */
    fun onLauncherDestroyed(launcher: Launcher) {
        if (active?.context === launcher) {
            Log.i(TAG, "released with its activity (onDestroy)")
            cancel()
        }
    }

    /** Cancel any in-flight sparkle overlay (activity destroy / edit-mode exit). Safe when idle. */
    fun cancel() {
        clearHoldTimeout()
        clearPaneTimeout()
        val o = active ?: return
        active = null
        teardown(o)
    }

    /**
     * Start the per-tile sparkles for a live icon change. [target] is the home list (the tiles to
     * sparkle); the app-list pane, if attached, is covered as a second layer. They play until
     * [playFrozen] / [playFrozenPane] (bind-complete) resolve them, or a safety timeout does.
     * If sparkles are already up, the existing overlay is RE-ARMED for the new change: every layer
     * goes back to fully covering, the clock restarts and the new change's own bind-complete resolves
     * it. It used to be a no-op, which was fine while the overlay lived ~2 s; once the pane layer
     * waited for the (slow, on the Pixel) all-apps bind, a second change inside that window got no
     * fresh cover and its icons swapped bare (owner 2026-09-07: "subsequent changes aren't
     * consistently triggering the animation", ledger row 141).
     */
    fun freeze(launcher: Launcher, target: View) {
        val list = target as? ViewGroup ?: return
        active?.let { o ->
            Log.i(TAG, "re-armed the existing overlay for a new change, ${SystemClock.uptimeMillis() - o.shownAt}ms after its show")
            o.rearm()
            armSafeties(o, target)
            return
        }
        val o = show(launcher, list) ?: return
        armSafeties(o, target)
    }

    /** The home safety (FREEZE_TIMEOUT_MS from now) and, with a pane layer, the pane safety (PANE_SAFETY_MS). */
    private fun armSafeties(o: TileSparkleOverlay, target: View) {
        clearHoldTimeout()
        clearPaneTimeout()
        holdTarget = target
        holdTimeout = Runnable { beginFadeOut() }.also { target.postDelayed(it, FREEZE_TIMEOUT_MS) }
        if (o.pane.list != null) {
            paneTimeout = Runnable {
                Log.w(TAG, "pane safety fired ${PANE_SAFETY_MS}ms after show: all apps never bound")
                o.resolve(o.pane)
            }.also { o.postDelayed(it, PANE_SAFETY_MS) }
        }
    }

    /**
     * The home has finished updating: resolve the HOME covers (fade all out together). Respects
     * [MIN_HOLD_MS] and adds [POST_BIND_HOLD_MS] for widgets. No-op if nothing is up. The pane layer
     * waits for [playFrozenPane], with a bounded safety of its own so it can never be left up.
     */
    fun playFrozen(launcher: Launcher, target: View?) {
        val o = active ?: return
        clearHoldTimeout()
        val wait = resolveDelay(o)
        val t = target ?: o
        holdTarget = t
        holdTimeout = Runnable { o.resolve(o.home) }.also { t.postDelayed(it, wait) }
    }

    /**
     * All apps have (re)bound -- the pane's rows now carry the regenerated icons: resolve the PANE
     * covers. Called from `LawnchairLauncher.bindAllApplications`; a no-op with nothing up or no
     * pane layer, so the folded sheet's binds cost nothing.
     */
    fun playFrozenPane(launcher: Launcher) {
        val o = active ?: return
        if (o.pane.list == null) return
        Log.i(TAG, "pane bind-complete ${SystemClock.uptimeMillis() - o.shownAt}ms after show (all apps bound)")
        clearPaneTimeout()
        paneTimeout = Runnable { o.resolve(o.pane) }.also { o.postDelayed(it, resolveDelay(o)) }
    }

    private fun resolveDelay(o: TileSparkleOverlay): Long {
        val elapsed = SystemClock.uptimeMillis() - o.shownAt
        return (MIN_HOLD_MS - elapsed).coerceAtLeast(0L) + POST_BIND_HOLD_MS
    }

    /** Places a transparent [TileSparkleOverlay] over the whole drag layer and fades its sparkles in. */
    private fun show(launcher: Launcher, list: ViewGroup): TileSparkleOverlay? {
        clearHoldTimeout()
        clearPaneTimeout()
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

            val overlay = TileSparkleOverlay(launcher).apply {
                home.list = list
                // The app-list pane's live RecyclerView while the pane is attached (unfolded); null
                // folded. Its icons regenerate in the same reload, one bind later (see the class doc).
                pane.list = paneRecyclerView(launcher)
                this.filters = filters
                this.veilColors = VEIL_TOKENS.map { opaque(it.resolveColor(launcher)) }.toIntArray()
                // Match the edit-mode tile background (same rounded-cell radius).
                this.cornerPx = launcher.resources.getDimension(R.dimen.ares_edit_cell_outline_radius)
                this.blurPx = EDGE_BLUR_DP * launcher.resources.displayMetrics.density
                this.shownAt = SystemClock.uptimeMillis()
                this.onAllResolved = { o -> if (active === o) active = null; teardown(o) }
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

    private fun paneRecyclerView(launcher: Launcher): RecyclerView? = try {
        val pane = launcher.workspace?.aresAppListPaneForModelFeed
        pane?.takeIf { it.isAttachedToWindow }?.activeRecyclerView
    } catch (t: Throwable) {
        null
    }

    /**
     * Global safety: bind-complete never came for the HOME layer -- resolve it. The pane keeps its
     * own, longer safety (PANE_SAFETY_MS), because all apps legitimately bind well after this point.
     */
    private fun beginFadeOut() {
        val o = active ?: return
        Log.w(TAG, "home safety fired ${FREEZE_TIMEOUT_MS}ms after show: bind-complete never came")
        o.resolve(o.home)
    }

    private fun teardown(o: TileSparkleOverlay) {
        o.stopTwinkle()
        o.animate().cancel()
        o.release()
        (o.parent as? ViewGroup)?.removeView(o)
    }

    private fun clearHoldTimeout() {
        holdTimeout?.let { holdTarget?.removeCallbacks(it) }
        holdTimeout = null
        holdTarget = null
    }

    private fun clearPaneTimeout() {
        paneTimeout?.let { active?.removeCallbacks(it) }
        paneTimeout = null
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
     * Transparent overlay that draws a twinkling four-point-star cluster over each icon tile of its
     * two [Layer]s -- the home grid ([home]) and the app-list pane ([pane]) -- every frame, mapping
     * tile positions through each list's drag-layer origin. The whole view's alpha fades the sparkles
     * in on show; each layer's own alpha fades it out on ITS resolve, so a layer's covers all clear
     * together and the two layers can clear at different times (they are re-rendered at different
     * times). Once every populated layer has resolved, [onAllResolved] tears the overlay down.
     */
    private class TileSparkleOverlay(context: android.content.Context) : View(context) {

        /**
         * One list's covers. The home layer covers whole tile cells (children are tile containers);
         * the pane layer covers the ICON of each [BubbleTextView] row, since a Niagara row is mostly
         * label and the label does not change.
         */
        inner class Layer(val isPane: Boolean) {
            var list: ViewGroup? = null
            var layerAlpha = 1f
            var fading = false
            var done = false
            var fade: ValueAnimator? = null

            // Per-tile layout snapshot, refreshed each fully-populated frame and held across the brief
            // window where a reload has cleared the RecyclerView (see onDraw). Packed as SNAP_STRIDE
            // floats per tile: cx, cy, halfX, halfY, scaleX, scaleY, rotation, particleCount, idBase,
            // corner radius.
            var snap = FloatArray(0)
            var snapCount = 0
            var refUnit = 0f
            var refArea = 0f
            var wasHolding = false
            var holdStartMs = 0L
            var originX = 0f
            var originY = 0f
        }

        val home = Layer(isPane = false)
        val pane = Layer(isPane = true)
        private val layers = arrayOf(home, pane)

        var shownAt = 0L
        var filters: Array<PorterDuffColorFilter> = emptyArray()
        var veilColors = IntArray(0)
        var cornerPx = 0f
        var blurPx = 0f
        var onAllResolved: ((TileSparkleOverlay) -> Unit)? = null

        private var twinkle: ValueAnimator? = null
        private val dot = RectF()
        private val cover = RectF()
        private val iconRect = Rect()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val flowMatrix = Matrix()
        private var flowShader: RadialGradient? = null
        private var flowPeriod = 0f

        /** Reused by [rebuildSnapshot] so the per-frame origin re-read allocates nothing. */
        private val originScratch = IntArray(2)

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

        /** Fade one layer's covers out together; when every populated layer is done, [onAllResolved]. */
        fun resolve(layer: Layer) {
            if (layer.list == null || layer.done) {
                checkAllResolved()
                return
            }
            if (layer.fading) return
            layer.fading = true
            Log.i(
                TAG,
                "resolving ${if (layer.isPane) "pane" else "home"} layer: ${layer.snapCount} covers, " +
                    "${SystemClock.uptimeMillis() - shownAt}ms after show",
            )
            layer.fade = ValueAnimator.ofFloat(layer.layerAlpha, 0f).apply {
                duration = FADE_OUT_MS
                addUpdateListener {
                    layer.layerAlpha = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        layer.done = true
                        checkAllResolved()
                    }
                })
                start()
            }
        }

        private fun checkAllResolved() {
            if (layers.all { it.list == null || it.done }) {
                val cb = onAllResolved
                onAllResolved = null
                cb?.invoke(this)
            }
        }

        /**
         * Row 141: a new change landed while this overlay is up. Put every populated layer back to a
         * full cover (a fade in flight is cancelled WITHOUT its end listener, which would otherwise
         * mark the layer done and tear the overlay down) and restart the clock.
         */
        fun rearm() {
            for (l in layers) {
                if (l.list == null) continue
                l.fade?.let { it.removeAllListeners(); it.removeAllUpdateListeners(); it.cancel() }
                l.fade = null
                l.fading = false
                l.done = false
                l.layerAlpha = 1f
            }
            shownAt = SystemClock.uptimeMillis()
            alpha = 1f
            invalidate()
        }

        /** Drop every list reference and stop the fades; called from teardown. */
        fun release() {
            onAllResolved = null
            for (l in layers) {
                l.fade?.cancel()
                l.fade = null
                l.list = null
            }
        }

        /**
         * Refresh [Layer.snap] from the live children. Packs SNAP_STRIDE floats per drawable tile and
         * derives [Layer.refUnit] from a representative app tile (so particles stay a consistent
         * absolute size on icons and widgets alike). Only ever called when the list is populated; the
         * caller holds the previous snapshot across a reload's momentary empty frame.
         */
        private fun rebuildSnapshot(layer: Layer, list: ViewGroup, rv: RecyclerView?) {
            // Re-read the list's drag-layer origin every snapshot rather than trusting the one cached
            // in show(). Tile positions are mapped as origin + container.x/y, so a cached origin is
            // only correct while the list stays put -- and a model reload now REPARENTS it (Workspace
            // .removeAllWorkspaceScreens lifts it out of the page being destroyed and re-attaches it
            // into the freshly built one). A stale origin shifts every cover by the same delta, which
            // is the covers sliding off their tiles midway through the animation (owner 2026-09-01).
            (context as? Launcher)?.dragLayer?.let { dl ->
                // ZERO IT FIRST. getDescendantCoordRelativeToSelf takes `coord` as an IN/OUT point --
                // "the coordinate that we want mapped" (Utilities.getDescendantCoordRelativeToAncestor)
                // -- and transforms it in place rather than resetting it. A REUSED scratch array must
                // be reset or each frame re-maps the previous result and the origin runs away
                // unbounded (measured 2026-09-01: 20 -> 280 -> 820 -> 2020 over four frames, which is
                // the covers sliding off the screen).
                originScratch[0] = 0
                originScratch[1] = 0
                dl.getDescendantCoordRelativeToSelf(list, originScratch)
                layer.originX = originScratch[0].toFloat()
                layer.originY = originScratch[1].toFloat()
            }
            if (layer.isPane) rebuildPaneSnapshot(layer, list) else rebuildHomeSnapshot(layer, list, rv)
        }

        private fun rebuildHomeSnapshot(layer: Layer, list: ViewGroup, rv: RecyclerView?) {
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
            layer.refUnit = refUnit
            layer.refArea = refArea

            val n = list.childCount
            if (layer.snap.size < n * SNAP_STRIDE) layer.snap = FloatArray(n * SNAP_STRIDE)
            val snap = layer.snap
            var idx = 0
            for (i in 0 until n) {
                val container = list.getChildAt(i) as? ViewGroup ?: continue
                container.getChildAt(0) ?: continue
                val cw = container.width
                val ch = container.height
                if (cw <= 0 || ch <= 0) continue
                val left = layer.originX + container.x
                val top = layer.originY + container.y
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
                // Cover the WHOLE cell, matching the edit-mode tile background's radius.
                snap[b + 9] = cornerPx
                idx++
            }
            layer.snapCount = idx
        }

        /**
         * The pane's rows are [BubbleTextView]s laid out horizontally (icon left, label right); cover
         * the ICON's bounds only. Section headers and anything else in the list are skipped. The
         * particle reference comes from the HOME layer when it has one (drawn first every frame), so
         * stars are the same absolute size on both sides of the hinge.
         */
        private fun rebuildPaneSnapshot(layer: Layer, list: ViewGroup) {
            val n = list.childCount
            if (layer.snap.size < n * SNAP_STRIDE) layer.snap = FloatArray(n * SNAP_STRIDE)
            val snap = layer.snap
            var refUnit = home.refUnit
            var refArea = home.refArea
            var idx = 0
            for (i in 0 until n) {
                val row = list.getChildAt(i) as? BubbleTextView ?: continue
                if (row.visibility != VISIBLE || row.width <= 0 || row.height <= 0) continue
                row.getIconBounds(iconRect)
                val cw = iconRect.width()
                val ch = iconRect.height()
                if (cw <= 0 || ch <= 0) continue
                if (refUnit <= 0f) {
                    refUnit = min(cw, ch) / 2f
                    refArea = (cw * ch).toFloat()
                }
                val left = layer.originX + row.x + iconRect.left
                val top = layer.originY + row.y + iconRect.top
                val b = idx * SNAP_STRIDE
                snap[b] = left + cw / 2f
                snap[b + 1] = top + ch / 2f
                snap[b + 2] = cw / 2f
                snap[b + 3] = ch / 2f
                snap[b + 4] = row.scaleX
                snap[b + 5] = row.scaleY
                snap[b + 6] = row.rotation
                snap[b + 7] = (PARTICLES_PER_REF * (cw * ch) / refArea).roundToInt().coerceIn(4, PARTICLES_MAX).toFloat()
                // All-apps adapters have no stable ids; the row's component is stable across a rebind.
                val key = (row.tag as? ItemInfo)?.targetComponent?.hashCode() ?: i
                snap[b + 8] = (key and 0xFFFF).toFloat()
                // An icon, not a cell: round it like the icon rather than the edit cell.
                snap[b + 9] = min(cornerPx, min(cw, ch) / 2f * 0.55f)
                idx++
            }
            layer.refUnit = refUnit
            layer.refArea = refArea
            layer.snapCount = idx
        }

        override fun onDraw(canvas: Canvas) {
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

            for (layer in layers) {
                val list = layer.list ?: continue
                if (layer.done || layer.layerAlpha <= 0.002f) continue
                // A fold lifts the pane out of the window without a detach callback (temp detach),
                // so its RecyclerView still answers isAttachedToWindow while its drag-layer origin
                // no longer resolves -- covers would be drawn at a broken origin on the folded
                // display (panel review 2026-09-07, F1). The attached-only accessor is null exactly
                // then: drop the pane layer rather than draw it.
                if (layer.isPane && (context as? Launcher)?.workspace?.aresAppListPane == null) {
                    Log.i(TAG, "pane left the window mid-sparkle; pane layer dropped")
                    layer.list = null
                    layer.done = true
                    checkAllResolved()
                    continue
                }
                drawLayer(canvas, layer, list)
            }
        }

        private fun drawLayer(canvas: Canvas, layer: Layer, list: ViewGroup) {
            val star = star4
            val dust = softDot
            val rv = list as? RecyclerView

            // Capture the tile layout from the LIVE list while it is populated; otherwise HOLD the last
            // snapshot. The 2nd+ theme/pack reload rebinds the RecyclerView by clearing and repopulating
            // it, so for a frame or two mid-animation childCount drops (measured: 15->11, 11->7) -- and
            // without this bridge the opaque covers vanish for that frame and the icon swap flashes
            // through (owner: "second change flickers once midway"). Re-snapshot every fully-populated
            // frame so the covers keep tracking the edit-mode wiggle.
            //
            // The hold is TIME-BOUNDED: a rebind can briefly attach MORE children than steady state
            // (disappearing + incoming tiles overlap), which would latch snapCount too high and hold
            // forever. So if the drop persists past HOLD_BRIDGE_MAX_MS it is the real new count, not a
            // rebind gap -- adopt it by re-snapshotting from the live list.
            val live = list.childCount
            val now = SystemClock.uptimeMillis()
            val wantHold = layer.snapCount > 0 && live < layer.snapCount
            if (wantHold && !layer.wasHolding) layer.holdStartMs = now
            layer.wasHolding = wantHold
            val holding = wantHold && (now - layer.holdStartMs) < HOLD_BRIDGE_MAX_MS
            if (!holding) rebuildSnapshot(layer, list, rv)
            if (layer.snapCount == 0) return

            val la = layer.layerAlpha
            flowPaint.alpha = (VEIL_ALPHA * la).toInt().coerceIn(0, 255)
            val t = (SystemClock.uptimeMillis() - shownAt) / TWINKLE_MS
            val refUnit = layer.refUnit
            val snap = layer.snap

            for (j in 0 until layer.snapCount) {
                val b = j * SNAP_STRIDE
                val sCx = snap[b]
                val sCy = snap[b + 1]
                val sHalfX = snap[b + 2]
                val sHalfY = snap[b + 3]
                val count = snap[b + 7].toInt()
                val idBase = snap[b + 8]
                val corner = snap[b + 9]

                // Cover the tile's rect, rounded to its radius. Outset by the blur radius so the
                // fully-opaque core reaches the tile edge (widgets fill their cell to the edge, so an
                // inset left their rim showing). The blur then feathers a little past that; kept small
                // (EDGE_BLUR_DP) so it doesn't reach into a neighbour.
                cover.set(sCx - sHalfX - blurPx, sCy - sHalfY - blurPx, sCx + sHalfX + blurPx, sCy + sHalfY + blurPx)

                // Apply the tile's live scale + rotation (edit mode scales tiles down and wiggles them),
                // so the cover lines up exactly with the shrunken tile rather than the layout bounds.
                val tileSave = canvas.save()
                canvas.rotate(snap[b + 6], sCx, sCy)
                canvas.scale(snap[b + 4], snap[b + 5], sCx, sCy)

                // Opaque gradient cover -> the icon/widget swap underneath is hidden.
                canvas.drawRoundRect(cover, corner, corner, flowPaint)

                for (k in 0 until count) {
                    val seed = idBase * 131.7f + k * 37.13f
                    val ox = hash(seed + 1.3f) * 2f - 1f
                    val oy = hash(seed + 5.7f) * 2f - 1f
                    val ph = hash(seed + 9.1f) * TAU
                    val sp = 0.6f + hash(seed + 13.5f) * 0.6f
                    val tw = 0.5f + 0.5f * sin(t * sp * TAU + ph)
                    val a = tw * tw * la
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

            /** Floats per tile in the layout snapshot: cx, cy, halfX, halfY, sx, sy, rot, count, idBase, corner. */
            const val SNAP_STRIDE = 10

            /** Max time to hold a stale snapshot across a reload's rebind gap before adopting the live
             *  (smaller) list -- bounds the hold so a transient child overshoot can't latch it forever. */
            const val HOLD_BRIDGE_MAX_MS = 300L

            /** Cheap deterministic hash -> [0,1), for scattering particles per tile without arrays. */
            fun hash(x: Float): Float {
                val s = sin(x) * 43758.547f
                return s - floor(s)
            }
        }
    }
}
