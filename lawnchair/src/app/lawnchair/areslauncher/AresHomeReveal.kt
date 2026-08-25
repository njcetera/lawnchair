package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.android.launcher3.Launcher

/**
 * SPIKE (owner 2026-08-25, "spike only, don't wire in"): a Material-You, playful HOME REVEAL.
 *
 * When the home appears (the owner's example is swiping up out of an app), each item on the grid
 * starts **tiny**, **slides up** a little, then **zooms in with a bounce** -- and the items fire in a
 * staggered **wave** across the screen (top-left first, cascading down/right). No fade: personality
 * comes from the per-item spring and the wave ordering, not opacity.
 *
 * ## How it is gated
 *
 * [enabled] defaults **false**, so the guarded hook in `LawnchairLauncher.onResume` is inert in a
 * normal build. Flip it with the `ares-home-reveal` channel (`on`/`off`), or fire one reveal on
 * demand (no arg / `play`). See the channel in AresTestInfo.
 *
 * ## What it animates
 *
 * The per-tile children of [AresHomeListView] (the RecyclerView) -- the search pill and every grid
 * tile -- each transformed about its own centre. This is the same surface the edit-mode wiggle and
 * the WP folder fall already scale/translate, so it is known-safe. One shared [ValueAnimator] drives
 * all children; each child reads its own local progress off a stagger offset, so the wave needs no
 * per-child animator to manage. Every child is reset to identity (scale 1, translation 0) when the
 * run ends or is cancelled.
 */
object AresHomeReveal {

    /** When true, the reveal fires on every home appearance (the guarded onResume hook). */
    @JvmField
    var enabled = false

    // Feel. All one-line tunable.
    private const val START_SCALE = 0.28f        // how small each item starts, bunched at the origin
    private const val CLUSTER_Y_FRAC = 0.86f     // origin height: fraction down the list (near bottom)
    private const val PER_ITEM_MS = 500f         // one item's fly-up + zoom
    private const val STAGGER_MS = 24f           // wave spacing between successive items
    private const val MAX_TOTAL_MS = 1200L       // cap so a full screen never drags on
    private const val BOUNCE_TENSION = 2.4f      // playful overshoot as an item lands

    private val moveInterp = DecelerateInterpolator(1.5f)   // the "scroll" -- fly up and settle
    private val zoomInterp = OvershootInterpolator(BOUNCE_TENSION)

    private var running: ValueAnimator? = null

    /** Fires the reveal across the home tiles. Safe to call repeatedly; cancels any prior run. */
    @JvmStatic
    fun play(launcher: Launcher) {
        val list = launcher.workspace?.aresHomeList ?: return
        val n = list.childCount
        if (n == 0) return

        running?.cancel()

        // The origin every item starts bunched at: centre, near the bottom of the list.
        val originX = list.width / 2f
        val originY = list.height * CLUSTER_Y_FRAC

        // Wave ordering: top-to-bottom, then left-to-right, by on-screen position.
        val children = (0 until n).mapNotNull { list.getChildAt(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))

        // Each item's start is the vector from its own cell centre to the shared origin -- so at
        // progress 0 they are all piled small at the origin, and at 1 each has flown to its cell.
        val startDx = FloatArray(children.size)
        val startDy = FloatArray(children.size)
        children.forEachIndexed { i, v ->
            val cx = v.left + v.width / 2f
            val cy = v.top + v.height / 2f
            startDx[i] = originX - cx
            startDy[i] = originY - cy
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = START_SCALE
            v.scaleY = START_SCALE
            v.translationX = startDx[i]
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
                    // Fly up from the bottom origin to the cell (the "scroll" motion).
                    val move = moveInterp.getInterpolation(local)
                    v.translationX = startDx[i] * (1f - move)
                    v.translationY = startDy[i] * (1f - move)
                    // Zoom in with a bounce as it arrives.
                    val z = zoomInterp.getInterpolation(local)
                    val s = START_SCALE + (1f - START_SCALE) * z
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
