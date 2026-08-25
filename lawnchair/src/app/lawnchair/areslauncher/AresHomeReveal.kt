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
 * When the home appears after leaving an app, the tiles SLIDE UP from the bottom edge of the screen
 * (small, in a staggered wave), then EXPAND into full size with a bounce once they arrive. No fade.
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
    private const val START_SCALE = 0.30f       // size while sliding up, before the expand
    private const val PER_ITEM_MS = 540f        // one item's slide + expand
    private const val STAGGER_MS = 22f          // wave spacing between successive items
    private const val MAX_TOTAL_MS = 1250L      // cap so a full screen never drags on
    private const val SLIDE_PHASE = 0.60f       // fraction of an item's life spent sliding up
    private const val EXPAND_START = 0.45f      // expand begins here (slight overlap with the slide)
    private const val BOUNCE_TENSION = 2.3f     // playful overshoot on the expand

    private val slideInterp = DecelerateInterpolator(1.6f)
    private val zoomInterp = OvershootInterpolator(BOUNCE_TENSION)

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
                    // Slide up from the bottom edge first (small), settling by SLIDE_PHASE.
                    val rise = slideInterp.getInterpolation((local / SLIDE_PHASE).coerceIn(0f, 1f))
                    v.translationY = startDy[i] * (1f - rise)
                    // THEN expand with a bounce, once it has (mostly) arrived.
                    val ez = ((local - EXPAND_START) / (1f - EXPAND_START)).coerceIn(0f, 1f)
                    val s = START_SCALE + (1f - START_SCALE) * zoomInterp.getInterpolation(ez)
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
