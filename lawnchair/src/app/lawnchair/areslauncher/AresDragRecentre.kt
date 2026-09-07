package app.lawnchair.areslauncher

import android.graphics.Point
import android.util.Log
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION
import com.android.launcher3.Utilities
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.views.ActivityContext

/**
 * Ledger row 102: an app-list row dragged by its LABEL rides under the finger.
 *
 * `DragController` registers the drag picture at the finger-to-picture offset of the press and
 * keeps that offset for the whole drag. Right for a grid cell, whose icon is under the finger by
 * construction; wrong for the app-list pane's rows, where the icon is at the left of a ~1000px row
 * and a press anywhere on the label leaves the picture riding far to the left of the finger for
 * the whole drag. Row 98 fixed the ICON press (the picture was centred on the row, not the icon);
 * this is the LABEL press the owner saw next (2026-09-05: "the drag from app list to home still
 * doesn't track my finger correctly").
 *
 * Applied when the drag becomes real ([com.android.launcher3.dragndrop.DragController.callOnDragStart]),
 * not at `startDrag`: with the long-press popup's pre-drag the picture must stay on the icon until
 * the finger commits to a drag, or it would leap to the finger while the popup is still up. The
 * decision itself lives in [com.android.launcher3.dragndrop.DragView.aresRecentreUnderFinger]: a
 * finger already ON the picture keeps stock's exact-press-point registration.
 *
 * Scope note: `AresWidgetAdd.isAresHome` is always true on this fork (Workspace.hasAresHomeList()
 * returns true unconditionally), so the effective gate is "a BubbleTextView dragged from an all-apps
 * container" -- the unfolded pane AND the folded sheet. Measured on the pane only (panel 2026-09-07).
 *
 * `setprop debug.ares.drag_recentre 0` keeps the stock registration, so control and fix come from
 * identical bytes; both branches log so an arm proves which path it took.
 */
object AresDragRecentre {
    private const val TAG = "AresDragRecentre"

    private fun enabled(): Boolean = Utilities.getSystemProperty("debug.ares.drag_recentre", "1") != "0"

    @JvmStatic
    fun onDragStart(
        activity: ActivityContext?,
        options: DragOptions?,
        d: DropTarget.DragObject?,
        lastTouch: Point,
    ) {
        val dv = d?.dragView ?: return
        val info = d.dragInfo ?: return
        val launcher = activity as? Launcher ?: return
        if (d.originalView !is BubbleTextView) return
        if (!AresWidgetAdd.isAresHome(launcher)) return
        if (options == null || options.isAccessibleDrag || options.simulatedDndStartPoint != null) return
        val fromList = info.container == CONTAINER_ALL_APPS || info.container == CONTAINER_ALL_APPS_PREDICTION
        if (!fromList) return
        if (!enabled()) {
            Log.i(TAG, "DECLINED (debug.ares.drag_recentre=0): stock registration kept for '${info.title}'")
            return
        }
        val verdict = dv.aresRecentreUnderFinger(lastTouch.x, lastTouch.y, d)
        Log.i(TAG, "'${info.title}' from the app list: $verdict")
    }
}
