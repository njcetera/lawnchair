package app.lawnchair.areslauncher

import com.android.launcher3.Launcher
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
}
