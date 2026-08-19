package app.lawnchair.areslauncher

import android.view.MotionEvent
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.util.TouchController

/**
 * Wraps a stock [TouchController] so it declines gestures that begin on the scrolling home grid.
 *
 * ## Why this is needed
 *
 * `BaseDragLayer.findControllerToHandleTouch` runs every registered `TouchController` **before any
 * child view sees the event**. A scrolling home surface therefore never receives vertical drags
 * unless the controllers stand aside: a spike measured `ACTION_DOWN` arriving at the grid but
 * **zero** `scrollVerticallyBy` calls, because the MOVE stream was claimed upstream.
 *
 * This is the third instance of the same structural family on this project -- after
 * [AresPaneSwipeController] claiming horizontal drags, and the original DragLayer-overlay defect
 * that swallowed touches Workspace needed. The pattern is now explicit rather than rediscovered.
 *
 * ## Why a wrapper rather than removing a named controller
 *
 * Which controller claims a vertical drag depends on navigation mode: `QuickstepLauncher`
 * assembles a different set for gesture nav than for two/three-button, and the base
 * `AllAppsSwipeController` is not in the Quickstep set at all. Naming one and deleting it would be
 * both fragile and mode-specific. Declining by *gesture origin* is neither.
 *
 * ## Why the app-list pane is covered too
 *
 * When unfolded, panel 1 hosts a persistent [AresPanelAllAppsContainerView] with its own scrolling
 * `AllAppsRecyclerView`. It sits *outside* the home grid's bounds, so a guard scoped only to the
 * grid let stock controllers claim vertical drags there -- and one of them opened `ALL_APPS`,
 * sliding the folded container's sheet in *over* the persistent pane. The user saw that as "a
 * second app list slides in and overlays to scroll" while the pane itself never moved. Both
 * scrolling surfaces therefore have to be declined, not just the grid.
 *
 * ## Why it is scoped to those bounds
 *
 * Edge gestures -- quick switch, nav-bar-to-home, overview -- start in the system gesture zone at
 * the very bottom of the screen, outside the grid. Only gestures starting **inside** the grid are
 * declined, so those keep working. The guard is also limited to [LauncherState.NORMAL]: once the
 * app-list pane is open, the home grid is not the surface being touched.
 *
 * Swipe-up-to-open-drawer is *deliberately* lost on home as a result. That is the decision, not a
 * side effect: vertical scrolls home, horizontal switches panes, matching Windows Phone. The
 * right-edge pane swipe remains the way to the app list. See requirements-alignment.md §4.
 */
class AresHomeScrollGuard(
    private val launcher: Launcher,
    private val delegate: TouchController,
    private val gridProvider: () -> AresHomeListView?,
    private val paneProvider: () -> AresPanelAllAppsContainerView? = { null },
) : TouchController {

    private var declining = false

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            declining = shouldDecline(ev)
        }
        if (declining) return false
        return delegate.onControllerInterceptTouchEvent(ev)
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        if (declining) return false
        return delegate.onControllerTouchEvent(ev)
    }

    /**
     * True when this gesture started inside the home grid while it is the visible surface.
     *
     * Uses raw screen coordinates mapped through the grid's own location on screen, because the
     * event arrives in DragLayer coordinates and the grid sits several parents down.
     */
    private fun shouldDecline(ev: MotionEvent): Boolean {
        if (!launcher.isInState(LauncherState.NORMAL)) return false
        return startedOver(ev, gridProvider()) || startedOver(ev, paneProvider())
    }

    /**
     * True when this gesture started inside [view] while it is a visible, attached surface.
     *
     * Uses raw screen coordinates mapped through the view's own location on screen, because the
     * event arrives in DragLayer coordinates and both surfaces sit several parents down.
     */
    private fun startedOver(ev: MotionEvent, view: android.view.View?): Boolean {
        if (view == null) return false
        if (!view.isAttachedToWindow || view.visibility != android.view.View.VISIBLE) return false

        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x < loc[0] + view.width &&
            y >= loc[1] && y < loc[1] + view.height
    }

    override fun dump(): String =
        "AresHomeScrollGuard(declining=$declining) -> ${delegate.dump()}"

    override fun toString(): String = "AresHomeScrollGuard(${delegate.javaClass.simpleName})"
}
