package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * The edit-mode **float**, in one place.
 *
 * Each tile traces a small, slow orbit around its resting position — a couple of dp of travel over
 * a two-and-a-half second cycle — so the surface reads as *loose* rather than *agitated*. It
 * replaced a ±1.4° / 180ms iOS-style jiggle at the user's request: *"maybe something calmer like a
 * gentle floating in place to indicate its loose"*.
 *
 * Two surfaces run it — the home grid ([AresHomeListView]) and the apps inside an open folder
 * ([AresFolderEdit]) — and they have to look like the *same* mode, so the amplitude, period and
 * per-item phase offset live here rather than being copied. A folder whose icons moved at a visibly
 * different rate from the grid behind it would read as two modes, not one.
 *
 * ## Why one non-reversing sweep rather than a reversing oscillation
 *
 * The jiggle used an `ObjectAnimator` on ROTATION with `REVERSE`, and seeded each item's phase
 * across [CYCLE_MS] — which for a reversing animator is only the *half*-cycle. That was invisible
 * at 180ms, but it does not survive being slowed down: spread over half an oscillation, every tile
 * sweeps the same way at the same time and the stagger reads as a **travelling wave rolling across
 * the grid** — exactly the "one animated sheet" effect the offset exists to prevent. A single
 * non-reversing sweep of the full 0..1 cycle with `RESTART` cannot express that phase error,
 * because the seeded play time *is* the phase.
 *
 * ## Why the phase is offset per item, and why it is seeded from position
 *
 * Every tile moving in lockstep reads as one sheet rather than a set of loose objects. Seeding each
 * animator's play time from the item's *position* (never a random value) breaks that up while
 * staying stable across recycling — a row scrolled off and back on resumes at the phase it would
 * have had, instead of visibly re-syncing with its neighbours.
 *
 * ## Who else owns `translationX/Y` on these views
 *
 * Three other animators write the same two properties, and all three are started *later* than the
 * float, so they win for their duration and the float resumes when they end:
 *
 *  - [AresMasonryLayoutManager.animateFromPreviousBounds] — the 200ms repack after a resize or a
 *    removal, both of which only happen in edit mode.
 *  - `FolderAnimationManager` — animates each icon's translation while a folder opens and closes.
 *  - `ItemTouchHelper` — owns the dragged tile's translation frame-by-frame for the whole drag.
 *
 * The third one is **not** benign, which is why [AresHomeListView.setFloatSuspendedFor] stops the
 * float on the tile being picked up and restarts it on `clearView`. `ItemTouchHelper` writes at
 * *draw* time rather than from an animator callback, so it and the float would trade the property
 * within a frame; and independently of that, a lifted tile should not still be drifting — iOS stops
 * its jiggle on the dragged icon for the same reason.
 */
object AresEditWiggle {

    /**
     * Orbit radius, in dp. The tile traces a circle of twice this across a cycle.
     *
     * Small on purpose: enough to read as buoyant across a screen of tiles, not so much that labels
     * become hard to read or a 4-wide widget visibly encroaches on its neighbours. Tuned by eye
     * against the previous jiggle, whose ±1.4° rotation displaced a 1×1 tile's *corners* by about
     * this much while leaving its centre still — the float moves the whole tile instead, so the
     * same displacement reads considerably more clearly at a fraction of the speed.
     */
    private const val ORBIT_DP = 1.5f

    /**
     * Peak tilt, in degrees, coupled to the vertical half of the orbit.
     *
     * A pure translation reads slightly like sliding; a hint of roll at the top and bottom of the
     * orbit reads like floating. Deliberately a quarter of the old jiggle's amplitude and fourteen
     * times slower, so it cannot be read as shaking.
     */
    private const val TILT_DEGREES = 0.35f

    /** One full orbit. Not a half-cycle: this animator does not reverse. */
    private const val CYCLE_MS = 2500L

    /**
     * Phase offset applied per item index.
     *
     * 953ms of a 2500ms cycle is 137.2° — the golden angle — which is the step that spreads any
     * number of items most evenly around the circle. It is also coprime with [CYCLE_MS], for the
     * same reason the old 47/180 pairing was: a step that divides the cycle evenly gives every Nth
     * item an identical phase, which is the banding the offset exists to avoid.
     */
    private const val PHASE_STEP_MS = 953L

    private const val TAU = 2.0 * Math.PI

    /**
     * Starts [view] floating, seeded to [index]'s phase.
     *
     * Returns null — leaving the view at rest — when the system's animator scale is off ("Remove
     * animations"). Items then simply hold the edit-mode scale, which still distinguishes the mode
     * without motion. Callers must cancel the returned animator themselves; see [stop].
     */
    fun start(view: View, index: Int): ValueAnimator? {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            reset(view)
            return null
        }
        val radius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            ORBIT_DP,
            view.resources.displayMetrics,
        )
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = CYCLE_MS
        animator.repeatMode = ValueAnimator.RESTART
        animator.repeatCount = ValueAnimator.INFINITE
        // Linear, so the orbit is traced at a constant rate. Any easing would give the tile a
        // rest point, which is what makes an oscillation read as a twitch.
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            // animatedFraction, not animatedValue: it is the fraction *within the current
            // iteration*, which is exactly the phase, and it avoids boxing a Float per frame per
            // tile. Interpolation is linear, so the two agree.
            val theta = it.animatedFraction * TAU
            view.translationX = radius * cos(theta).toFloat()
            view.translationY = radius * sin(theta).toFloat()
            view.rotation = TILT_DEGREES * sin(theta).toFloat()
        }
        animator.start()
        // Seed the phase *after* start(): currentPlayTime only takes effect on a running animator.
        if (index >= 0) {
            animator.currentPlayTime = (index * PHASE_STEP_MS) % CYCLE_MS
        }
        return animator
    }

    /** Cancels [animator] and puts [view] back at rest, so nothing is left displaced. */
    fun stop(view: View, animator: ValueAnimator?) {
        animator?.cancel()
        reset(view)
    }

    /**
     * Clears everything the float writes.
     *
     * Separate from [stop] because a view can be left displaced without an animator to cancel —
     * a recycled row, or one whose animator was never created because animations are off.
     */
    fun reset(view: View) {
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
    }
}
