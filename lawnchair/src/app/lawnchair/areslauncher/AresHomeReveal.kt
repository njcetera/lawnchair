package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.android.launcher3.Launcher

/**
 * SPIKE (owner 2026-08-25, "spike only, don't wire in"): a Windows-Phone-style HOME REVEAL.
 *
 * When the home appears -- the owner's example is swiping up out of an app -- the whole home
 * surface starts small and low, rises while zooming toward the viewer "like a wave", overshoots,
 * and settles. This is a proof of concept for the *feel*; it is deliberately NOT part of daily use.
 *
 * ## How it is gated
 *
 * [enabled] defaults **false**, so the guarded hook in `LawnchairLauncher.onResume` (fired once per
 * home appearance, off the first post-resume draw) is inert in a normal build -- the owner's Pixel
 * behaves exactly as before. Two ways to see it:
 *   - flip [enabled] true (via the `ares-home-reveal` channel with arg `on`/`off`) to feel the
 *     intended **every-home-appearance** trigger, or
 *   - fire a single reveal on demand with the `ares-home-reveal` channel (no arg / arg `play`).
 *
 * ## What it animates
 *
 * The [Launcher.getWorkspace] pager -- which in this fork carries the search pill and the whole
 * masonry grid ([AresHomeListView]) as its content. The workspace is the surface Launcher itself
 * already scales for its state transitions, so a scale/translate here rides a known-safe path
 * (no touch-mapping surprises like transforming the DragLayer would risk). The transform is fully
 * reset to identity when the run ends or is cancelled, so nothing is left behind for normal use.
 */
object AresHomeReveal {

    /** When true, the home reveal fires on every home appearance (the guarded onResume hook). */
    @JvmField
    var enabled = false

    private const val DURATION_MS = 640L
    private const val START_SCALE = 0.60f
    private const val START_ALPHA = 0.35f
    private const val DROP_FRAC = 0.16f // start translated DOWN by this fraction of the view height

    // Zoom overshoots past 1 near the end, then settles -- the "bounce toward you". Rise decelerates
    // into rest so the surface glides up and stops without a second wobble fighting the zoom bounce.
    private val zoomInterp = OvershootInterpolator(2.0f)
    private val riseInterp = PathInterpolator(0.16f, 0.84f, 0.24f, 1f)

    private var running: ValueAnimator? = null

    /** Fires the reveal on the home surface. Safe to call repeatedly; cancels any prior run. */
    @JvmStatic
    fun play(launcher: Launcher) {
        val target: View = launcher.workspace ?: return
        val h = target.height
        if (h <= 0 || target.width <= 0) return // not laid out yet -- skip rather than divide by zero

        running?.cancel()

        // Grow from near the bottom-centre so it reads as rising into place, not inflating in situ.
        target.pivotX = target.width / 2f
        target.pivotY = h * 0.92f
        val drop = h * DROP_FRAC

        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            addUpdateListener {
                val p = it.animatedFraction
                val s = START_SCALE + (1f - START_SCALE) * zoomInterp.getInterpolation(p)
                target.scaleX = s
                target.scaleY = s
                target.translationY = drop * (1f - riseInterp.getInterpolation(p))
                target.alpha = START_ALPHA + (1f - START_ALPHA) * p
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = reset(target)
                    override fun onAnimationCancel(animation: android.animation.Animator) = reset(target)
                },
            )
            start()
        }
    }

    private fun reset(target: View) {
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationY = 0f
        target.alpha = 1f
        running = null
    }

    /** Guarded entry for the onResume hook: no-op unless [enabled]. */
    @JvmStatic
    fun maybePlayOnHomeAppear(launcher: Launcher) {
        if (enabled) play(launcher)
    }
}
