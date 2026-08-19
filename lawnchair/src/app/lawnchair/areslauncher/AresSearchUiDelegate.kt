package app.lawnchair.areslauncher

import android.view.View
import app.lawnchair.search.LawnchairSearchAdapterProvider
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.search.AllAppsSearchUiDelegate
import com.android.launcher3.allapps.search.SearchAdapterProvider
import com.android.launcher3.views.ActivityContext

/**
 * AresLauncher §17 — search UI delegate for the launcher's app-list pane.
 *
 * Stock/Lawnchair anchor the search input at the *top* of the pane. AresLauncher wants it collapsed
 * into a small affordance at the bottom-right that expands into an input when tapped, so the pane
 * stays sparse (design/implementation-scope.md §17 "SPEC REFINED", §8's Niagara spec).
 *
 * Launcher3 already ships the framework for a bottom-anchored search bar — [isSearchBarFloating]
 * moves the container out of this view and into the `DragLayer`, re-aligns the lists and header to
 * the parent top instead of below the search box, keeps the fast scroller clear of it, and insets it
 * by the IME. `LauncherAllAppsContainerView` implements the resting-margin and
 * pill-when-unfocused hooks on top of that by delegating to `LauncherState`. Rather than hand-roll a
 * floating affordance, this turns that framework on.
 *
 * ## Scoping
 *
 * This delegate is reachable only from [app.lawnchair.allapps.views.SearchContainerView], which is
 * inflated solely by `res/layout/all_apps.xml` — the launcher's own pane. The Taskbar
 * (`TaskbarAllAppsContainerView`) and secondary displays (`SecondaryLauncherAllAppsContainerView`)
 * construct the stock [com.android.launcher3.allapps.search.AllAppsSearchUiDelegate] instead and are
 * unaffected. See [AresAllApps] for why that separation is load-bearing here.
 */
class AresSearchUiDelegate(private val appsView: ActivityAllAppsContainerView<*>) :
    AllAppsSearchUiDelegate(appsView) {

    /**
     * Replicated from `LawnchairSearchUiDelegate`, which is `final` and so cannot be extended.
     * Duplicating this one line keeps Lawnchair's richer search-results adapter while leaving that
     * file untouched — preferable to widening its visibility for a single consumer.
     */
    override fun createMainAdapterProvider(): SearchAdapterProvider<*> =
        LawnchairSearchAdapterProvider(ActivityContext.lookupContext(appsView.context), appsView)

    /**
     * Anchors the search container to the bottom of the screen, floating above the pane.
     *
     * Nothing in this fork returned true for this before, so the path was unexercised — the same
     * situation as the A-Z letter rail, which turned out to be structurally unfinished rather than
     * merely dormant (design/implementation-scope.md §8). Treated as a probe first and verified
     * on-device before anything was built on top of it.
     */
    override fun isSearchBarFloating(): Boolean = true

    /**
     * Inflates the Ares affordance instead of the stock/Lawnchair top-anchored bar.
     *
     * `res/layout/search_container_all_apps.xml` is shared with the Taskbar's all-apps surface, so
     * it is left alone and an Ares-owned layout is used here.
     */
    override fun inflateSearchBar(): View =
        layoutInflater.inflate(R.layout.ares_search_container, mAppsView, false)

    /**
     * Fired immediately after `ActivityAllAppsContainerView.onAttachedToWindow` adds the container to
     * the `DragLayer`, which is the first moment its layout params belong to their final parent —
     * setting the resting position any earlier would be overwritten.
     */
    override fun onInitializeSearchBar() {
        (mAppsView.searchUiManager as? AresSearchContainerView)?.applyRestingPosition()
    }
}
