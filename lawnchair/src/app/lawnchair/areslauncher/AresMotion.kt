package app.lawnchair.areslauncher

import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * Material 3 **Expressive** motion tokens — the shared, reusable vocabulary for AresLauncher's
 * playful animations (owner 2026-08-25, "fun, lively, playful like the other animations we have
 * created"). One place so the folder bloom, the home reveal, and the app-launch zoom all speak the
 * same motion language instead of each hand-rolling its own curve.
 *
 * M3 Expressive splits motion into two spring families:
 *
 *  - **Spatial** springs move *geometry* — position, scale, rotation, corner radius — and
 *    **overshoot then settle** (the bounce). That is exactly the character the fork already reaches
 *    for by hand (the folder pop and icon flow use `OvershootInterpolator(2.0)`/`(2.2)`), so these
 *    are expressed the same proven way rather than as raw CSS cubic-beziers: the published spatial
 *    curves (e.g. default `cubic-bezier(0.38, 1.21, 0.22, 1.0)`) put a control point above y=1 with
 *    a *decreasing* control-x, which `PathInterpolator` rejects as non-monotonic. An
 *    `OvershootInterpolator` gives the same overshoot-and-settle shape, is guaranteed valid, and
 *    matches what is already on screen elsewhere.
 *  - **Effects** springs move *color and opacity* and **do NOT overshoot** — a bounce in alpha
 *    reads as a flicker. Kept as a plain decelerate.
 *
 * Each spatial token comes in three speeds (fast / default / slow); the durations are recommended
 * companions the call site applies (an interpolator carries no duration of its own). Most launch and
 * reflow motion wants DEFAULT; small elements FAST; large surfaces SLOW.
 */
object AresMotion {

    // Spatial: geometry, overshoots and settles. Tension climbs a little with the slower speeds so a
    // larger, longer move lands with a slightly fuller bounce. 2.0 is the fork's established pop.
    @JvmField
    val SPATIAL_FAST: Interpolator = OvershootInterpolator(1.5f)

    @JvmField
    val SPATIAL_DEFAULT: Interpolator = OvershootInterpolator(2.0f)

    @JvmField
    val SPATIAL_SLOW: Interpolator = OvershootInterpolator(2.4f)

    /** Effects: color/opacity. No overshoot (an alpha bounce is a flicker). M3-standard decelerate. */
    @JvmField
    val EFFECTS_DEFAULT: Interpolator = PathInterpolator(0.34f, 0.80f, 0.34f, 1.0f)

    // Recommended companion durations (ms), matching the M3 Expressive spatial speeds.
    const val SPATIAL_FAST_MS = 350L
    const val SPATIAL_DEFAULT_MS = 500L
    const val SPATIAL_SLOW_MS = 650L
}
