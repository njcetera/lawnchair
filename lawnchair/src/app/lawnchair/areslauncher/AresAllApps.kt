package app.lawnchair.areslauncher

import android.content.Context
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
        return launcher.resources.getDimensionPixelSize(dimen)
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
        context.resources.getDimensionPixelSize(R.dimen.ares_home_list_top_padding)
}
