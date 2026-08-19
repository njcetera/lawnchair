package app.lawnchair.areslauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View

/**
 * The edit-mode wiggle, in one place.
 *
 * Two surfaces run it now — the home grid ([AresHomeListView]) and the apps inside an open folder
 * ([AresFolderEdit]) — and they have to look like the *same* mode, so the amplitude, period and
 * per-item phase offset live here rather than being copied. A folder whose icons wiggled at a
 * visibly different rate from the grid behind it would read as two modes, not one.
 *
 * ## Why the phase is offset per item
 *
 * Every tile rotating in lockstep reads as one animated sheet rather than a set of loose objects.
 * Seeding each animator's play time from the item's *position* (never a random value) breaks that
 * up while staying stable across recycling — a row scrolled off and back on resumes at the phase it
 * would have had, instead of visibly re-syncing with its neighbours.
 */
object AresEditWiggle {

    /**
     * Half-amplitude, in degrees.
     *
     * Small on purpose: enough to read as alive across a screen of tiles, not so much that labels
     * become hard to read or a 4-wide widget's corners sweep into its neighbours.
     */
    private const val DEGREES = 1.4f

    /** One half-cycle. The full period is twice this, since the animator reverses. */
    private const val PERIOD_MS = 180L

    /**
     * Phase offset applied per item index.
     *
     * Deliberately not a divisor of [PERIOD_MS]: a value that divides evenly makes every Nth item
     * share a phase, which reintroduces the visible banding this is meant to avoid.
     */
    private const val PHASE_STEP_MS = 47L

    /**
     * Starts [view] wiggling, seeded to [index]'s phase.
     *
     * Returns null — leaving the view unrotated — when the system's animator scale is off. Items
     * then simply hold the edit-mode scale, which still distinguishes the mode without motion.
     * Callers must cancel the returned animator themselves; see [stop].
     */
    fun start(view: View, index: Int): ObjectAnimator? {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.rotation = 0f
            return null
        }
        val animator = ObjectAnimator.ofFloat(view, View.ROTATION, -DEGREES, DEGREES)
        animator.duration = PERIOD_MS
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.start()
        // Seed the phase *after* start(): currentPlayTime only takes effect on a running animator.
        if (index >= 0) {
            animator.currentPlayTime = (index * PHASE_STEP_MS) % PERIOD_MS
        }
        return animator
    }

    /** Cancels [animator] and puts [view] back upright, so nothing is left tilted. */
    fun stop(view: View, animator: ObjectAnimator?) {
        animator?.cancel()
        view.rotation = 0f
    }
}
