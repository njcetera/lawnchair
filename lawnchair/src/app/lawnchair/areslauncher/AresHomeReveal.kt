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
    private const val START_SCALE = 0.30f       // size at the bottom edge, before it zooms in
    private const val PER_ITEM_MS = 560f        // one item's rise + zoom
    private const val STAGGER_MS = 22f          // wave spacing between successive items
    private const val MAX_TOTAL_MS = 1250L      // cap so a full screen never drags on
    private const val BOUNCE_TENSION = 2.3f     // playful overshoot as it lands

    private val riseInterp = DecelerateInterpolator(1.6f)          // the entrance -- fly up and settle
    private val zoomInterp = OvershootInterpolator(BOUNCE_TENSION) // the zoom-in, concurrent with the rise

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

        val bottomEdge = list.height.toFloat()

        // Wave ordering: top-to-bottom, then left-to-right, by on-screen position.
        val children = (0 until n).mapNotNull { list.getChildAt(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))

        // Each item starts with its centre at the bottom edge (so it slides straight up its column,
        // no horizontal drift), small. translationX stays 0 the whole time.
        val startDy = FloatArray(children.size)
        children.forEachIndexed { i, v ->
            val cy = v.top + v.height / 2f
            startDy[i] = bottomEdge - cy
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = START_SCALE
            v.scaleY = START_SCALE
            v.translationX = 0f
            v.translationY = startDy[i]
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
                    // Rise up from the bottom edge AND zoom in at the same time -- the entrance and the
                    // zoom are concurrent, landing with a bounce.
                    v.translationY = startDy[i] * (1f - riseInterp.getInterpolation(local))
                    val s = START_SCALE + (1f - START_SCALE) * zoomInterp.getInterpolation(local)
                    v.scaleX = s
                    v.scaleY = s
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
