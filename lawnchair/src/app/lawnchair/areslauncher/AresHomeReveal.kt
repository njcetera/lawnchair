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

    // Per-item feel. All one-line tunable.
    private const val START_SCALE = 0.24f        // "tiny" -- how small each item starts
    private const val SLIDE_UP_DP = 46f          // "slide up a bit" -- start this far below its cell
    private const val PER_ITEM_MS = 460f         // one item's own tiny -> slide -> zoom
    private const val STAGGER_MS = 26f           // wave spacing between successive items
    private const val MAX_TOTAL_MS = 1150L       // cap so a full screen never drags on
    private const val SLIDE_PHASE = 0.5f         // item slides up over the first half of its life
    private const val ZOOM_START = 0.15f         // zoom (with bounce) begins a beat after the slide
    private const val BOUNCE_TENSION = 2.6f      // playful overshoot on the zoom-in

    private val slideInterp = DecelerateInterpolator(1.4f)
    private val zoomInterp = OvershootInterpolator(BOUNCE_TENSION)

    private var running: ValueAnimator? = null

    /** Fires the reveal across the home tiles. Safe to call repeatedly; cancels any prior run. */
    @JvmStatic
    fun play(launcher: Launcher) {
        val list = launcher.workspace?.aresHomeList ?: return
        val n = list.childCount
        if (n == 0) return

        running?.cancel()

        val slidePx = SLIDE_UP_DP * list.resources.displayMetrics.density

        // Wave ordering: top-to-bottom, then left-to-right, by on-screen position.
        val children = (0 until n).mapNotNull { list.getChildAt(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))

        // Seat every child at its start frame in the SAME pass, before the first draw, so nothing is
        // briefly seen full-size at its cell.
        for (v in children) {
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.scaleX = START_SCALE
            v.scaleY = START_SCALE
            v.translationY = slidePx
        }

        val staggerSpan = STAGGER_MS * (children.size - 1).coerceAtLeast(0)
        val total = (PER_ITEM_MS + staggerSpan).toLong().coerceAtMost(MAX_TOTAL_MS)
        // If the cap bit, compress the stagger so the last item still gets its full per-item life.
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
                    // Slide up over the first half, then hold at rest.
                    val rise = slideInterp.getInterpolation((local / SLIDE_PHASE).coerceIn(0f, 1f))
                    v.translationY = slidePx * (1f - rise)
                    // Zoom in with a bounce, starting a beat after the slide begins.
                    val z = zoomInterp.getInterpolation(((local - ZOOM_START) / (1f - ZOOM_START)).coerceIn(0f, 1f))
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
