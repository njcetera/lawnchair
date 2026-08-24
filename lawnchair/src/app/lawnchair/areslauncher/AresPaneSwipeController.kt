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
 * - **Swipe-up to open the drawer** — *there is no such gesture on home any more*, so there is
 *   nothing here to coexist with. This bullet used to claim the two merely stayed out of each
 *   other's way via axis dominance, which contradicted [AresHomeScrollGuard]'s own comment.
 *   **Settled at runtime on 2026-08-20**, folded emulator, launcher focused and `mState:Normal`:
 *   two upward swipes starting inside the grid (`540,1400 → 540,500` and `540,1900 → 540,900`)
 *   both left `mState:Normal`, while a leftward drag on the same screen reached `mState:AllApps`.
 *   The guard's version is the correct one — vertical scrolls home, horizontal switches panes
 *   (§4/§SWIPE-UP IS DROPPED), and this controller is the *only* route to the app list from home.
 *   Anything reasoning about residual risk here (e.g. `2d83117925`) must not assume a vertical
 *   fallback exists.
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
        // Unfolded, home and the app list are both already on screen as workspace panels 0 and 1
        // (design/foldable-dual-pane.md), so there is no pane to swipe *to*. Letting the gesture
        // run anyway would slide the full-screen ALL_APPS sheet over a layout that already shows
        // the app list. Keyed off the workspace's own panel count rather than a separate fold
        // check, so it can never disagree with what is actually laid out.
        val workspace = mLauncher.workspace
        if (workspace != null && workspace.panelCount > 1) {
            return false
        }
        // §4: a reorder drag owns the gesture until it settles. This controller claims horizontal
        // drags anywhere on home, and TouchControllers run before any child view sees the event, so
        // without this a sideways wobble while dragging a row would pull the app-list pane in
        // mid-reorder. ItemTouchHelper's own requestDisallowInterceptTouchEvent doesn't cover it:
        // that suppresses ancestor onInterceptTouchEvent, not BaseDragLayer's controller dispatch.
        if (workspace != null && workspace.isAresReorderInProgress()) {
            return false
        }
        // §4: while the grid is in edit mode a horizontal drag means "move this item", so this
        // controller stands down for the whole mode -- not just once a reorder is under way. The
        // check above cannot cover it: controllers run before any child view sees the event, so
        // claiming the gesture here starves the grid of the moves that would have *started* the
        // reorder, and dragging an icon sideways did nothing at all (verified on device). The pane
        // is still reachable while editing by leaving the mode first, which is a tap or Back.
        if (workspace != null && workspace.isAresEditMode()) {
            return false
        }
        // Live-create ends the in-grid drag with a synthetic UP to form the folder, but the REAL
        // finger is still down. In the ~200ms gap between that UP and the folder opening, edit-mode,
        // a floating-view-open, and reorder-in-progress are ALL momentarily false, so the guards above
        // miss it and this controller claims the still-down finger (measured on the fold 2026-08-23:
        // "CLAIMED MID-GESTURE" the instant the drag ended). Once claimed, the grid never sees the
        // pull-out, so the drag-out-to-destroy continuation is unreachable. Stand down for the whole
        // live-create window (arming + the few seconds the just-opened folder is held) so the finger
        // stays with the grid where the continuation can catch it.
        if (AresFolderDrop.isLiveArming() || AresFolderDrop.recentLiveCreate()) {
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

    /**
     * No spring on the app list as it settles (§2).
     *
     * Stock attaches `addSpringFromFlingUpdateListener` to the settle animation whenever the target
     * is ALL_APPS. That is right for the gesture it was written for: all-apps arrives on a
     * **vertical** swipe-up, and a sheet springing as it comes to rest is the whole feel of it.
     *
     * This controller brings the same surface in on a **horizontal** Pivot pan, and there the
     * spring is a non-sequitur — the pane tracks the finger sideways and then wobbles on an axis
     * the gesture never used. Reported as *"when I swipe from the home page to the app list, theres
     * this subtle bounce annimation the app list does. I dont like that"*.
     *
     * Only the spring is dropped. The interpolators stay stock, deliberately, so the reveal itself
     * still looks like the transition it parallels.
     */
    override fun aresWantsAllAppsSpring(): Boolean = false

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
        /**
         * Fraction of screen width a drag must cover to complete a transition.
         *
         * **1.0 means the surface tracks the finger exactly**, and that is the point: §2 asks for a
         * continuous Windows Phone Pivot pan, where the content is attached to the fingertip.
         *
         * This was 0.5, which completed the whole transition in half a screen of travel — so the
         * panes moved at *twice* finger speed and the gesture read as a parallax slide rather than
         * a drag. Reported as *"the scroll is moving faster than my finger giving a parallax
         * effect. It should accurately track my finger."*
         *
         * A full screen of travel is not a burden in practice: `onDragEnd` still resolves flings by
         * velocity, so a quick flick completes the switch without dragging the whole way across.
         */
        private const val DRAG_FRACTION_FOR_FULL_TRANSITION = 1.0f
    }
}
