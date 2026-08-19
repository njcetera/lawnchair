package app.lawnchair.areslauncher

import android.view.MotionEvent
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState.ALL_APPS
import com.android.launcher3.LauncherState.NORMAL
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayTouchProxy

/**
 * Drives the NORMAL<->ALL_APPS state transition continuously from a right-edge overscroll drag,
 * mirroring how [app.lawnchair.nexuslauncher.OverlayCallbackImpl] drives the Discover feed reveal
 * from the left edge. Wired to [com.android.launcher3.Workspace]'s right edge glow instead of the
 * left one the feed uses, so the app-list pane opens as a right-edge swipe (per AresLauncher's
 * §9 spec) rather than the stock swipe-up gesture, which is untouched and still works.
 *
 * scrollProgress arrives unbounded (raw accumulated pull distance, not clamped to [0,1] by the
 * caller), so it's normalized against [DRAG_DISTANCE_FOR_FULL_OPEN] here.
 */
class AppListPaneOverlay(private val launcher: Launcher) : LauncherOverlayTouchProxy {

    private var isDragging = false
    private var lastVerticalProgress = 1f

    override fun onFlingVelocity(velocity: Float) {
        // Not used for the commit decision in v1; release-time progress threshold is sufficient.
    }

    override fun onOverlayMotionEvent(ev: MotionEvent, scrollProgress: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!launcher.isInState(NORMAL)) return
                isDragging = true
                launcher.allAppsController.setProgress(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return
                val openFraction = (scrollProgress / DRAG_DISTANCE_FOR_FULL_OPEN).coerceIn(0f, 1f)
                lastVerticalProgress = 1f - openFraction
                launcher.allAppsController.setProgress(lastVerticalProgress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return
                isDragging = false
                val target = if (lastVerticalProgress <= COMMIT_THRESHOLD) ALL_APPS else NORMAL
                launcher.stateManager.goToState(target)
            }
        }
    }

    companion object {
        // Fraction of screen width a full drag needs to cover to fully reveal the app-list pane.
        // Tuned by feel on-device; not derived from a hard spec value.
        private const val DRAG_DISTANCE_FOR_FULL_OPEN = 1.0f
        private const val COMMIT_THRESHOLD = 0.5f
    }
}
