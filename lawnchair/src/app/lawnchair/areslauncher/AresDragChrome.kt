package app.lawnchair.areslauncher

import com.android.launcher3.Launcher
import com.android.launcher3.allapps.ActivityAllAppsContainerView

/**
 * Whether a drag should bring stock Launcher3's workspace drag *presentation* with it.
 *
 * ## What "the chrome" is, and why it does not fit
 *
 * Stock presents a drag as: `SPRING_LOADED` (which scales the workspace down so its CellLayout
 * pages read as cards you are making room in), an extra empty page appended on the right, and the
 * `DropTargetBar` — the Cancel / Uninstall pills across the top.
 *
 * Every part of that describes a paged CellLayout grid. Strategy D does not have one: the home
 * screen is a single vertical tile list, there are no pages to make room in and nothing to append
 * a page to, so the zoom-out is showing the user a model the launcher does not use. The owner put
 * it plainly on 2026-09-04, seeing it on the unfolded app-list drag: *"the windows that pop up
 * during the drag ... it's from the old grid system and not the tile system."*
 *
 * ## This predicate is a UNION of specific cases, not a general rule
 *
 * [AresFolderDrag.isFolderDrag] carries a warning worth repeating: an earlier version of its
 * comment justified suppression with a claim broad enough to cover every drag in the launcher,
 * while the predicate only gated folder-sourced ones. Reading a general claim off a narrow gate is
 * how someone concludes something false about the paths it never touched.
 *
 * So the cases are listed, each with its own reason, and adding one is a deliberate act:
 *
 *  - **Folder-sourced** ([AresFolderDrag.isFolderDrag]) — the folder is open on top of the
 *    workspace and the bar's hit rect reaches into it, so a live Remove target sits behind an open
 *    folder. That was a real data-loss defect.
 *  - **App-list-sourced** (here) — owner decision 2026-09-04. Nothing is lost by dropping the bar:
 *    removal from the home screen is the tile's own × badge, and uninstall is already in the
 *    long-press popup as `LawnchairShortcut.UNINSTALL`, so the gesture was a second route to a
 *    function that has a first one.
 *
 * Still NOT covered, deliberately: the **widget-picker** drag. It is the third path panel finding
 * R7 named, the owner's decision was about the app list, and widening a product decision past what
 * was actually decided is the exact move the warning above exists to prevent.
 *
 * ## Consistency, not exception
 *
 * The home grid's own reorder ([AresHomeReorder]) never enters `SPRING_LOADED` — it is an
 * `ItemTouchHelper` drag that never reaches `Workspace.onDragStart` at all. So after this, the
 * only drag on the home surface that still raises stock chrome is the widget picker, and
 * suppressing it here is the consistent behaviour rather than a special case.
 */
object AresDragChrome {

    /**
     * True when [dragSource] is one of this launcher's app lists — either posture.
     *
     * Both postures are covered on purpose even though the owner reported the unfolded one. The
     * home surface being dragged ONTO is the same vertical tile list either way, so the chrome is
     * equally wrong folded; gating on posture would make the drag look different for no reason the
     * user could name.
     *
     * Matching on the base container catches both the folded sheet and
     * `AresPanelAllAppsContainerView` (the unfolded pane) without naming either — and note that
     * `launcher.appsView` is the FOLDED sheet even while the unfolded pane is what was touched,
     * so a `dragSource === launcher.appsView` identity test would have missed the very case that
     * was reported.
     */
    private fun isAppListDrag(dragSource: Any?): Boolean =
        dragSource is ActivityAllAppsContainerView<*>

    /**
     * Control switch for the APP-LIST arm only, so a one-build A/B can compare the owner's reported
     * behaviour against the fix from identical bytes. `debug.ares.drag_chrome=0` restores the
     * pre-fix presentation. Read once: an arm that can change under a running process measures a
     * mixture of both.
     *
     * Deliberately does NOT gate the folder arm. That one is a shipped fix for a data-loss defect
     * (a live Remove target behind an open folder); putting it behind a switch would make the
     * control arm reintroduce a real bug rather than just an unwanted animation.
     */
    private val APP_LIST_ARM =
        !"0".equals(android.os.SystemProperties.get("debug.ares.drag_chrome", "1"))

    /** True when stock's SPRING_LOADED / empty-page / drop-target-bar presentation must not run. */
    @JvmStatic
    fun suppressesStockChrome(launcher: Launcher?, dragSource: Any?): Boolean {
        if (launcher == null || !AresWidgetAdd.isAresHome(launcher)) return false
        if (AresFolderDrag.isFolderDrag(launcher, dragSource)) return true
        if (!isAppListDrag(dragSource)) return false
        if (!APP_LIST_ARM) {
            // Log the DECLINE, not just the fire. This guard's whole effect is the ABSENCE of
            // chrome, so a version that had quietly stopped engaging would be indistinguishable in
            // a log from a run where no app-list drag ever happened.
            android.util.Log.i("AresDragChrome", "DECLINED (debug.ares.drag_chrome=0) app-list drag")
            return false
        }
        android.util.Log.i("AresDragChrome", "suppressing stock chrome for app-list drag")
        return true
    }
}
