package app.lawnchair.areslauncher

import android.view.MotionEvent
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.LauncherState.ALL_APPS
import com.android.launcher3.LauncherState.NORMAL
import com.android.launcher3.states.StateAnimationConfig
import com.android.launcher3.touch.AbstractStateChangeTouchController
import com.android.launcher3.touch.AllAppsSwipeController
import com.android.launcher3.touch.SingleAxisSwipeDetector

/**
 * AresLauncher §9 — horizontal pane navigation between home and the app-list pane.
 *
 * The panes form a left-to-right canvas: `[Discover feed] | [home] | [app list]`. Navigating
 * *rightward* through them (home -> app list) is a physically **leftward** drag, the way turning a
 * page brings the next one in from the right; navigating *leftward* (app list -> home) is a
 * rightward drag.
 *
 * ## Why a TouchController and not the edge-glow mirror
 *
 * The first attempt (`6f84e92c9a`) mirrored `OverlayEdgeEffect` onto `PagedView`'s right edge and
 * did nothing at all on-device, because that path lives inside `PagedView.onTouchEvent` and only
 * runs if the touch reaches `Workspace` — which it did not, and which remains hostage to whatever
 * the home list does with touch. `BaseDragLayer.findControllerToHandleTouch` runs registered
 * controllers *before any child view sees the event*, so a controller is structurally immune to
 * that. See design/gesture-transition-reassessment.md Q1.
 *
 * ## Where the gesture is live
 *
 * Both directions are claimable from anywhere on their respective pane — there is no positional
 * scoping. Opening was initially restricted to a trailing-edge band, but that read as fussy; the
 * user asked for any horizontal drag on the home screen to bring the app list in. Containment is
 * therefore entirely a matter of *axis dominance*, not *position*.
 *
 * ## How this stays out of the way of everything else
 *
 * - **Vertical scrolling** — `SingleAxisSwipeDetector.shouldScrollStart` requires
 *   `|dx| >= max(touchSlop, |dy|)`, so a drag only counts as horizontal when it genuinely
 *   dominates. Scrolling the home list or the app list is unaffected. This carries the whole
 *   burden of coexistence now that the edge band is gone, so it is the interaction most worth
 *   re-testing after any change here.
 * - **The Discover feed** — from `NORMAL` this controller only ever claims *negative* (leftward)
 *   drags, because [getTargetState] returns `fromState` for a positive one and the base class
 *   derives its detectable directions from that. The feed is revealed by a *rightward* drag on
 *   `Workspace`, so that gesture is never intercepted here. This matters: the feed has already been
 *   broken silently once (see design/change-practices.md) and is not visibly testable while the
 *   feed provider is disabled.
 * - **Swipe-up to open the drawer** — stock `AllAppsSwipeController` is vertical and is registered
 *   separately; axis dominance keeps the two from competing.
 *
 * The drag axis and the animation axis are independent: [AbstractStateChangeTouchController.onDrag]
 * feeds an abstract 0..1 progress to `setPlayFraction`, so this horizontal gesture drives the
 * existing *vertical* reveal unchanged. Making the reveal itself horizontal is a separate later
 * increment (design/gesture-transition-reassessment.md Q2) and deliberately not attempted here.
 */
class AresPaneSwipeController(launcher: Launcher) :
    AbstractStateChangeTouchController(launcher, SingleAxisSwipeDetector.HORIZONTAL) {

    override fun canInterceptTouch(ev: MotionEvent): Boolean {
        if (mCurrentAnimation != null) {
            // Mid-transition: keep control so the drag can be reversed.
            return true
        }
        if (AbstractFloatingView.getTopOpenView(mLauncher) != null) {
            return false
        }
        // Both directions work from anywhere on the pane. Opening was originally scoped to a
        // trailing-edge band, but that made the gesture feel fussy -- the user asked for any
        // horizontal drag on the home screen to pull the app list in, matching how closing already
        // worked. Axis dominance (see the class doc) is what keeps this from stealing other
        // gestures, not positional scoping.
        return mLauncher.isInState(NORMAL) || mLauncher.isInState(ALL_APPS)
    }

    override fun getTargetState(
        fromState: LauncherState,
        isDragTowardPositive: Boolean,
    ): LauncherState = when {
        // HORIZONTAL.isPositive == rightward. Leftward from home pulls the app list in.
        fromState == NORMAL && !isDragTowardPositive -> ALL_APPS
        fromState == ALL_APPS && isDragTowardPositive -> NORMAL
        else -> fromState
    }

    override fun initCurrentAnimation(): Float {
        val range = horizontalRange()
        val config = getConfigForStates(mFromState, mToState)
        config.duration = (2 * range).toLong()

        mCurrentAnimation = mLauncher.stateManager.createAnimationToNewWorkspace(mToState, config)

        // Progress runs 0 -> 1 toward mToState, so the multiplier carries the sign of the drag that
        // gets us there: leftward (negative) to open, rightward (positive) to close. onDragEnd also
        // compares signum(velocity) against signum(this) to resolve flings, so the sign matters
        // beyond just the magnitude.
        val totalShift = if (mToState == ALL_APPS) -range else range
        return 1f / totalShift
    }

    override fun getConfigForStates(
        fromState: LauncherState,
        toState: LauncherState,
    ): StateAnimationConfig {
        val config = super.getConfigForStates(fromState, toState)
        config.animProps = config.animProps or StateAnimationConfig.USER_CONTROLLED
        // Reuse stock's interpolator sets wholesale: this phase drives the existing vertical reveal,
        // so the transition should look exactly like the swipe-up one it parallels.
        if (fromState == NORMAL && toState == ALL_APPS) {
            AllAppsSwipeController.applyNormalToAllAppsAnimConfig(mLauncher, config)
        } else if (fromState == ALL_APPS && toState == NORMAL) {
            AllAppsSwipeController.applyAllAppsToNormalConfig(mLauncher, config)
        }
        return config
    }

    /**
     * Drag distance that maps to a full transition. Deliberately *not*
     * [AbstractStateChangeTouchController.getShiftRange], which is the all-apps vertical shift range
     * (height-derived, 300dp on this non-sheet phone) and would badly miscalibrate a horizontal
     * drag.
     */
    private fun horizontalRange(): Float =
        mLauncher.deviceProfile.deviceProperties.widthPx * DRAG_FRACTION_FOR_FULL_TRANSITION

    companion object {
        /** Fraction of screen width a drag must cover to complete a transition. */
        private const val DRAG_FRACTION_FOR_FULL_TRANSITION = 0.5f
    }
}
