package app.lawnchair.areslauncher

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import com.android.launcher3.DeviceProfile
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.uioverrides.QuickstepLauncher

/**
 * The persistent app-list pane shown in workspace panel 1 on an unfolded foldable.
 *
 * This is the *real* all-apps container, not a lookalike. An earlier revision used a bespoke
 * `AresAppListView`/`AresAppListAdapter` pair reading `AllAppsStore` directly, on the assumption
 * that re-parenting the stock surface would fight `AllAppsTransitionController`. That assumption
 * was wrong: the controller field is `@Nullable`, setter-injected, null-guarded at every use, and
 * set by exactly one caller (`Launcher.setupViews`). The container itself holds no `StateManager`
 * reference at all and reaches its host only through `ActivityContext`. Two shipping subclasses
 * already host it outside the state machine -- `TaskbarAllAppsContainerView` (whose `isInAllApps()`
 * likewise returns a literal `true`) and `SecondaryLauncherAllAppsContainerView`, whose host is not
 * even a `Launcher`. See design/unified-app-list.md.
 *
 * Using the real container is what gives folded and unfolded feature parity *by construction*:
 * both panes are the same class, inflating the same layout, through the same adapter, behind the
 * same [AresAllApps] styling gate -- so search, predictions and (later) section headers exist once
 * and appear in both. The user asked for exactly that: "I would like consistency and feature parity
 * between folded and unfolded."
 */
class AresPanelAllAppsContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ActivityAllAppsContainerView<Launcher>(context, attrs, defStyleAttr) {

    /**
     * A persistent panel is always showing all apps -- there is no closed state to be in. Mirrors
     * `TaskbarAllAppsContainerView`, which returns a literal `true` for the same reason.
     *
     * The flag gates very little: fast-scroller touch interception and one `SearchTransitionController`
     * check. Returning true is correct for a panel that is never dismissed.
     */
    override fun isInAllApps(): Boolean = true

    /**
     * No-op. The bottom-sheet background is chrome for a sheet that slides over the workspace; this
     * pane *is* part of the workspace and sits directly on the wallpaper. `SecondaryLauncherAllAppsContainerView`
     * no-ops this for the same reason.
     */
    override fun updateBackgroundVisibility(deviceProfile: DeviceProfile) {}

    /**
     * This *is* the workspace panel, so the §11c alignment padding it needs is the panel's, not the
     * folded sheet's. See [AresAllApps.appListTopPaddingPx].
     */
    override fun isAresWorkspacePanel(): Boolean = true

    /**
     * Same delegate the folded container uses, so this pane gets the identical §17 collapsed
     * bottom-right affordance rather than the stock top-anchored bar. Using a different delegate
     * here would reintroduce exactly the divergence this class exists to remove.
     */
    override fun createSearchUiDelegate() = AresSearchUiDelegate(this)

    /**
     * No-op, for the same reason as [AresHomeListView.setPadding]: `ShortcutAndWidgetContainer`
     * unconditionally calls `setPadding()` on every non-widget child each measure pass to centre an
     * icon inside its grid cell. That is meaningless for a full-bleed pane and would clobber the
     * container's own padding on every layout.
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // Intentionally empty.
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // ShortcutAndWidgetContainer.onMeasure() calls setMeasuredDimension() before measuring its
        // children, so the parent's dimensions are already resolved here. Size to them and sync the
        // CellLayoutLayoutParams, since layoutChild() positions us from lp.x/y/width/height rather
        // than from our measured size.
        val host = parent as? ViewGroup
        val width = host?.measuredWidth?.takeIf { it > 0 } ?: MeasureSpec.getSize(widthSpec)
        val height = host?.measuredHeight?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightSpec)

        // Mutate the existing lp rather than calling setLayoutParams(), which would trigger a
        // nested requestLayout() from inside a measure pass.
        (layoutParams as? CellLayoutLayoutParams)?.let { lp ->
            lp.isLockedToGrid = false
            lp.x = 0
            lp.y = 0
            lp.width = width
            lp.height = height
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onAttachedToWindow() {
        // The base class adds mSearchContainer to the DragLayer when the search bar is floating,
        // and onDetachedFromWindow never removes it. This pane is detached and re-attached on every
        // fold cycle, so on the second attach that add would throw ("child already has a parent").
        // Drop it first and let the base class re-add it.
        (searchView?.parent as? ViewGroup)?.removeView(searchView)
        super.onAttachedToWindow()
        // Each container constructs its own AllAppsStore, so this one starts empty and would stay
        // empty until the next model bind. Seed it from the launcher's already-populated store on
        // attach; ModelCallbacks.bindAllApplications keeps both in step from then on.
        seedAppsFromLauncher()
        // Same for predictions: this pane has its own FloatingHeaderView and prediction row, and
        // predictions usually arrive before it is attached, so pull the current set now.
        (context as? QuickstepLauncher)?.applyAresPanePredictions()
    }

    /**
     * Symmetry the base class does not provide: take this pane's floating affordance back out of
     * the shared DragLayer when the pane goes away, so a folded launcher is not left with an
     * orphaned pill belonging to a pane that no longer exists.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        (searchView?.parent as? ViewGroup)?.removeView(searchView)
    }

    /**
     * The apps view is created during `Launcher.setupViews()` and can legitimately be absent while
     * this pane is attached early in a bind, so the lookup is nullable by design.
     *
     * Only the app array is copied here. `AllAppsStore`'s model flags and uid map have no
     * accessors, so a faithful copy is impossible from outside; `ModelCallbacks` supplies all three
     * on every real bind, which is why the authoritative feed lives there rather than here.
     */
    private fun seedAppsFromLauncher() {
        val source = (context as? Launcher)?.appsView?.appsStore ?: return
        if (source.apps.isNullOrEmpty() || !appsStore.apps.isNullOrEmpty()) return
        appsStore.setApps(source.apps, 0, emptyMap())
    }
}
