package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private const val BOUNCE_TENSION = 2.4f     // playful overshoot on the zoom-out

    private val riseInterp = DecelerateInterpolator(1.5f)            // the grouped rise up
    private val spreadInterp = OvershootInterpolator(BOUNCE_TENSION) // the zoom-out + spread, with bounce

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
        children.forEachIndexed { i, v ->
            cellCx[i] = v.left + v.width / 2f
            cellCy[i] = v.top + v.height / 2f
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = START_SCALE
            v.scaleY = START_SCALE
            v.translationX = clusterX - cellCx[i]
            v.translationY = startClusterY - cellCy[i]
        }

        val staggerSpan = STAGGER_MS * (children.size - 1).coerceAtLeast(0)
        val total = (PER_ITEM_MS + staggerSpan).toLong().coerceAtMost(MAX_TOTAL_MS)
        val stagger = if (PER_ITEM_MS + staggerSpan <= MAX_TOTAL_MS) {
            STAGGER_MS
        } else {
            ((MAX_TOTAL_MS - PER_ITEM_MS) / (children.size - 1).coerceAtLeast(1))
        }

        running = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            addUpdateListener {
                val t = it.animatedValue as Float
                children.forEachIndexed { i, v ->
                    val local = ((t - i * stagger) / PER_ITEM_MS).coerceIn(0f, 1f)
                    if (local <= RISE_PHASE) {
                        // Phase 1: the whole cluster rises vertically, still bunched at centre, small.
                        val u = riseInterp.getInterpolation(local / RISE_PHASE)
                        val clusterY = startClusterY + (risenClusterY - startClusterY) * u
                        v.translationX = clusterX - cellCx[i]
                        v.translationY = clusterY - cellCy[i]
                        v.scaleX = START_SCALE
                        v.scaleY = START_SCALE
                    } else {
                        // Phase 2: zoom OUT -- spread from the risen cluster to the cell and scale up,
                        // both on the overshoot so they bounce into place.
                        val u = spreadInterp.getInterpolation((local - RISE_PHASE) / (1f - RISE_PHASE))
                        v.translationX = (clusterX - cellCx[i]) * (1f - u)
                        v.translationY = (risenClusterY - cellCy[i]) * (1f - u)
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
