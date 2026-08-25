package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher

/**
 * SPIKE (owner 2026-08-25, "spike only, don't wire in"): a Material-You, playful HOME REVEAL.
 *
 * When the home appears after leaving an app, the tiles rise up from the bottom edge of the screen
 * (small, straight up their own column) WHILE zooming in, landing full-size with a bounce -- a
 * staggered wave. The rise (entrance from the bottom) and the zoom happen together. No fade.
 *
 * Only fires on a real home APPEARANCE from another app -- not while already on the launcher (the
 * `wasStopped` gate in LawnchairLauncher). Any open folder is closed first, so nothing animates on
 * top of an open folder.
 *
 * Gated by [enabled] (default false); flip via the `ares-home-reveal` channel.
 */
object AresHomeReveal {

    /** When true, the reveal fires on every home appearance (the guarded onResume hook). */
    @JvmField
    var enabled = false

    // Feel. All one-line tunable.
    private const val START_SCALE = 0.26f       // size while bunched at the cluster, before zoom-out
    private const val PER_ITEM_MS = 600f        // one item's rise + zoom-out
    private const val STAGGER_MS = 20f          // wave spacing between successive items
    private const val MAX_TOTAL_MS = 1300L      // cap so a full screen never drags on
    private const val CLUSTER_X_FRAC = 0.5f     // items bunch to this x (screen centre)
    private const val START_Y_FRAC = 0.82f      // the cluster starts this far down the screen
    private const val RISEN_Y_FRAC = 0.50f      // and rises to here before it zooms out
    private const val RISE_PHASE = 0.42f        // fraction of an item's life spent rising (grouped)
    // Per-item variation (like the folder open's wpRnd) so the spread reads organic, not in lockstep.
    private const val PACE_JITTER = 0.16f       // ±16% on each item's duration
    private const val BOUNCE_MIN = 1.8f         // per-item settle overshoot, low end
    private const val BOUNCE_SPAN = 1.4f        // ...to BOUNCE_MIN+SPAN, seeded per item
    private const val BOW_X_MIN = 0.35f         // Bézier control fraction along x (curve, per item)
    private const val BOW_X_SPAN = 0.45f
    private const val BOW_Y_MIN = 0.12f         // ...and along y, so the path arcs rather than straight
    private const val BOW_Y_SPAN = 0.42f

    private val riseInterp = DecelerateInterpolator(1.5f)            // the grouped rise up

    /** Deterministic 0..1 hash per item index + salt (the folder open's wpRnd, standalone here). */
    private fun rnd(i: Int, salt: Float): Float {
        val x = kotlin.math.sin(i * 12.9898f + salt) * 43758.5453f
        return x - kotlin.math.floor(x)
    }

    /** OvershootInterpolator's curve, inlined so each item can carry its own [tension]. */
    private fun overshoot(t: Float, tension: Float): Float {
        val u = t - 1f
        return u * u * ((tension + 1f) * u + tension) + 1f
    }

    private var running: ValueAnimator? = null

    /** Fires the reveal across the home tiles. Safe to call repeatedly; cancels any prior run. */
    @JvmStatic
    fun play(launcher: Launcher) {
        val list = launcher.workspace?.aresHomeList ?: return
        // No folder (inline or overlay) should stay open across a reveal (owner 2026-08-25).
        AbstractFloatingView.closeAllOpenViews(launcher, false)
        if (list.aresAdapter.collapseWpFolderImmediate()) {
            // A folder was open: let its structural collapse + relayout settle one frame, then reveal.
            list.post { playInner(list) }
        } else {
            playInner(list)
        }
    }

    private fun playInner(list: AresHomeListView) {
        val n = list.childCount
        if (n == 0) return
        running?.cancel()

        val clusterX = list.width * CLUSTER_X_FRAC
        val startClusterY = list.height * START_Y_FRAC
        val risenClusterY = list.height * RISEN_Y_FRAC

        // Wave ordering: top-to-bottom, then left-to-right, by on-screen position.
        val children = (0 until n).mapNotNull { list.getChildAt(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))

        // Every item is BUNCHED at one cluster point (screen-centre, low) at the start -- grouped,
        // small. It then rises vertically as a group, then zooms OUT (spreads to its own cell +
        // scales up with a bounce). Cell centres captured so the offsets can be computed each frame.
        val cellCx = FloatArray(children.size)
        val cellCy = FloatArray(children.size)
        // Per-item variation seeds (deterministic) -- pace, settle overshoot, and Bézier bow -- so the
        // spread reads organic and fluid like the folder-open fall, not a rigid formation.
        val pace = FloatArray(children.size)
        val tension = FloatArray(children.size)
        val bowX = FloatArray(children.size)
        val bowY = FloatArray(children.size)
        children.forEachIndexed { i, v ->
            cellCx[i] = v.left + v.width / 2f
            cellCy[i] = v.top + v.height / 2f
            pace[i] = 1f + (rnd(i, 12.9898f) * 2f - 1f) * PACE_JITTER
            tension[i] = BOUNCE_MIN + rnd(i, 78.233f) * BOUNCE_SPAN
            bowX[i] = BOW_X_MIN + rnd(i, 3.17f) * BOW_X_SPAN
            bowY[i] = BOW_Y_MIN + rnd(i, 41.7f) * BOW_Y_SPAN
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = START_SCALE
            v.scaleY = START_SCALE
            v.translationX = clusterX - cellCx[i]
            v.translationY = startClusterY - cellCy[i]
        }

        val budget = PER_ITEM_MS * (1f + PACE_JITTER) // the slowest-paced item's full duration
        val staggerSpan = STAGGER_MS * (children.size - 1).coerceAtLeast(0)
        val total = (budget + staggerSpan).toLong().coerceAtMost(MAX_TOTAL_MS)
        val stagger = if (budget + staggerSpan <= MAX_TOTAL_MS) {
            STAGGER_MS
        } else {
            ((MAX_TOTAL_MS - budget) / (children.size - 1).coerceAtLeast(1))
        }

        running = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            addUpdateListener {
                val t = it.animatedValue as Float
                children.forEachIndexed { i, v ->
                    val local = ((t - i * stagger) / (PER_ITEM_MS * pace[i])).coerceIn(0f, 1f)
                    if (local <= RISE_PHASE) {
                        // Phase 1: the whole cluster rises vertically, still bunched at centre, small.
                        val u = riseInterp.getInterpolation(local / RISE_PHASE)
                        val clusterY = startClusterY + (risenClusterY - startClusterY) * u
                        v.translationX = clusterX - cellCx[i]
                        v.translationY = clusterY - cellCy[i]
                        v.scaleX = START_SCALE
                        v.scaleY = START_SCALE
                    } else {
                        // Phase 2: zoom OUT along a CURVED path with a per-item settle spring. A
                        // quadratic Bézier carries each tile from the risen cluster (P0) to its cell
                        // (P2 = 0,0) through a per-item control point (P1) so it arcs rather than sliding
                        // straight; u overshoots past 1 (per-item tension) then springs back = bounce.
                        val u = overshoot(((local - RISE_PHASE) / (1f - RISE_PHASE)).coerceIn(0f, 1f), tension[i])
                        val p0x = clusterX - cellCx[i]
                        val p0y = risenClusterY - cellCy[i]
                        val ctrlX = p0x * bowX[i]
                        val ctrlY = p0y * bowY[i]
                        val omu = 1f - u
                        v.translationX = omu * omu * p0x + 2f * omu * u * ctrlX
                        v.translationY = omu * omu * p0y + 2f * omu * u * ctrlY
                        val s = START_SCALE + (1f - START_SCALE) * u
                        v.scaleX = s
                        v.scaleY = s
                    }
                }
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = reset(children)
                    override fun onAnimationCancel(animation: android.animation.Animator) = reset(children)
                },
            )
            start()
        }
    }

    private fun reset(children: List<View>) {
        for (v in children) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        }
        running = null
    }

    /** Guarded entry for the onResume hook: no-op unless [enabled]. */
    @JvmStatic
    fun maybePlayOnHomeAppear(launcher: Launcher) {
        if (enabled) play(launcher)
    }
}
