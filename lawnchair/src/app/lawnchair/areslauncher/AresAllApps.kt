package app.lawnchair.areslauncher

import android.content.Context
import android.view.View
import android.view.ViewParent
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.views.ActivityContext

/**
 * Scoping helper for AresLauncher's app-list pane customisations.
 *
 * Launcher3's all-apps stack is shared between two surfaces: the launcher's own app-list pane
 * (`ActivityAllAppsContainerView<Launcher>`, via `LawnchairLauncher`) and the Taskbar's all-apps
 * sheet (`TaskbarAllAppsContainerView extends ActivityAllAppsContainerView<TaskbarOverlayContext>`).
 * Both build their adapter through the same `BaseAllAppsAdapter`/`AllAppsGridAdapter` path, so an
 * unqualified change there silently restyles the Taskbar too.
 *
 * That already happened once: commit b38e70b753 forced `setAppsPerRow(1)` unconditionally, which
 * also collapsed the Taskbar's all-apps sheet to a single column -- unnoticed, in the same class of
 * collateral damage as the Discover-feed regression (see design/niagara-app-list.md §3).
 *
 * Gate every Ares-specific all-apps change on this so the Taskbar keeps stock behaviour.
 */
object AresAllApps {

    /**
     * True only for the launcher's own app-list pane. The Taskbar's all-apps surface uses
     * `TaskbarOverlayContext`, which is not a [Launcher], so it returns false there.
     */
    @JvmStatic
    fun isAresAppListPane(context: ActivityContext?): Boolean = context is Launcher

    /**
     * True when [view] sits inside the UNFOLDED app-list pane.
     *
     * Needed because the pane is a legitimate all-apps surface that the launcher does NOT consider
     * to be in [LauncherState.ALL_APPS]: unfolded, the app list is workspace page 1, so the state is
     * NORMAL. Stock guards that gate all-apps behaviour on `isInState(ALL_APPS)` therefore decline
     * everything here while working perfectly on the folded sheet.
     *
     * Measured 2026-09-04 on emulator-5554, which is what sent the owner's report:
     * `AresLongPress: DECLINED state= ordinal=0 (needs ALL_APPS or OVERVIEW)` on every long-press of
     * an app-list row unfolded, and none folded. Owner: *"unfolded, I can't hold an app in the app
     * list for it's menu to pop up or drag it to add it to the home page ... this works when
     * folded."*
     *
     * Asks the VIEW rather than the state, because "is this touch on the app list" is a question
     * about where the finger landed, and that stays true regardless of which posture or state the
     * launcher believes it is in.
     */
    @JvmStatic
    fun isInAppListPane(view: View?): Boolean {
        var p: ViewParent? = view?.parent
        while (p != null) {
            if (p is AresPanelAllAppsContainerView) return true
            p = p.parent
        }
        return false
    }

    /**
     * Top padding for the app-list pane's recycler, so its first row's icon box lines up with the
     * first icon box on the home grid (§11c).
     *
     * Replaces the stock value, which is `all_apps_search_bar_bottom_padding` -- room reserved
     * under a search bar this design does not draw, since §17 moved the affordance to a collapsed
     * circle in the bottom-right corner. It measured 88px and was the largest single contributor to
     * the misalignment the user reported.
     *
     * Returns the stock [stockPadding] for every surface that is not our pane. The all-apps stack
     * is shared with the Taskbar's all-apps sheet and the secondary-display host, both of which
     * still show a search bar and still need the room; forcing a value here without the gate is the
     * same mistake that once collapsed the Taskbar's sheet to a single column.
     *
     * The derivation of the two dimens, and the measurements behind them, are in
     * `res/values/ares_dimens.xml`.
     */
    @JvmStatic
    fun appListTopPaddingPx(
        context: ActivityContext?,
        isWorkspacePanel: Boolean,
        stockPadding: Int,
    ): Int {
        val launcher = context as? Launcher ?: return stockPadding
        val dimen = if (isWorkspacePanel) {
            R.dimen.ares_app_list_top_padding_panel
        } else {
            R.dimen.ares_app_list_top_padding_sheet
        }
        // UNFOLDED pane (isWorkspacePanel): the pane is extended past its cell up behind the status
        // bar (AresPanelAllAppsContainerView.onMeasure by insets.top + workspacePadding.top), and
        // clipToPadding=false + the host-chain un-clip let scrolled rows flow behind the status bar.
        // The recycler's REST padding, though, must line the first app-list row up with the HOME
        // grid's first row (owner 2026-08-25, "start at the same place as the homepage"): both panes
        // are pages of the same Workspace starting at the same cell top, so the pane needs the SAME
        // total top offset the home grid gives itself -- the extension (to lift back to the cell top)
        // PLUS homeListTopPaddingPx (the home grid's own §11c + ergonomic band). Using only the
        // extension (the earlier scroll-fix value) left the app list a full band higher than home.
        if (isWorkspacePanel) {
            val dp = launcher.deviceProfile
            return dp.insets.top + dp.workspacePadding.top + homeListTopPaddingPx(launcher)
        }
        // NOTE (2026-09-04): the folded-sheet total is NOT finished here. The launcher's own folded
        // app list is re-derived from the home list's live padding so the two start at the same y --
        // see ActivityAllAppsContainerView.aresComputeTopPadding(), which is where the caller's
        // relocated top inset is also in scope. This branch remains the value every other sheet
        // consumer gets, and the fallback before home has laid out.
        return launcher.resources.getDimensionPixelSize(dimen) + ergoTopPaddingPx(launcher)
    }

    /**
     * Top padding for the home grid, the other half of the §11c alignment.
     *
     * A home tile puts its icon box 6px below the cell top; an app-list row centres a smaller icon
     * in a taller row and puts it ~24px below the row top. That ~18px difference has to be added to
     * *home*, because absorbing it on the app-list side would need a negative padding when unfolded
     * -- there both surfaces are pages of the same Workspace and start at the same y, leaving
     * nothing to subtract from.
     */
    @JvmStatic
    fun homeListTopPaddingPx(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.ares_home_list_top_padding) +
            ergoTopPaddingPx(context)

    /**
     * Ergonomic scroll padding added to the TOP of both lists (owner, 2026-08-22): easier one-handed
     * reach and room to scroll. Added on top of each list's §11c alignment padding, and the SAME
     * amount to both, so the cross-pane icon alignment is preserved. See [ergoBottomPaddingPx].
     */
    @JvmStatic
    fun ergoTopPaddingPx(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.ares_list_ergo_top_padding)

    /**
     * Ergonomic scroll padding added to the BOTTOM of both lists: room to scroll the last rows clear
     * of the nav gesture area and, on the app list, the corner search pill. clipToPadding is already
     * false on both, so this is genuine scroll room. Callers gate it to the launcher's own surfaces.
     */
    @JvmStatic
    fun ergoBottomPaddingPx(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.ares_list_ergo_bottom_padding)

    /**
     * §9 readability dim (owner, 2026-08-22): a wallpaper darkening drawn behind the app-list pane's
     * content, so the icons/labels read clearly over any wallpaper. Per the §9 design note in
     * `AllAppsState.getWorkspaceScrimColor`, this lives on the PANE -- it travels in with the pane
     * and leaves with it, like a page of the canvas -- rather than a workspace scrim, which §9 rules
     * out as the "one thing on top of another" cue the one-canvas pan deliberately avoids. The pane's
     * own transition alpha fades it in as the app list swipes in, and out as it swipes away.
     */
    @JvmStatic
    fun appListWallpaperDim(context: Context): Int =
        context.resources.getColor(R.color.ares_app_list_wallpaper_dim, context.theme)
}
