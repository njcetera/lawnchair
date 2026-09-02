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
        val cellHeight = host?.measuredHeight?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightSpec)

        // Extend the pane PAST its workspace cell -- up behind the status bar and down behind the
        // nav/hotseat -- so the app list reaches the physical screen edges and scrolled rows flow
        // behind those bars, exactly the folded full-screen sheet's behaviour (owner 2026-08-25,
        // "reach the top and bottom edge like when it's closed"). The whole ancestor chain
        // (ShortcutAndWidgetContainer/CellLayout/Workspace) is clipChildren=false, and layoutChild
        // positions us straight from lp.x/y, so a negative lp.y lifts the pane above the cell.
        // Extensions equal the recycler's top/bottom padding (AresAllApps), so content RESTS in the
        // cell area and only SCROLLS behind the bars. Use mActivityContext (the resolved Launcher),
        // NOT getContext(), which can be a wrapper and silently yield 0 here.
        val dp = mActivityContext.deviceProfile
        val topExtend = dp.insets.top + dp.workspacePadding.top
        val botExtend = dp.insets.bottom + dp.workspacePadding.bottom
        val height = cellHeight + topExtend + botExtend

        // Mutate the existing lp rather than calling setLayoutParams(), which would trigger a
        // nested requestLayout() from inside a measure pass.
        (layoutParams as? CellLayoutLayoutParams)?.let { lp ->
            lp.isLockedToGrid = false
            lp.x = 0
            lp.y = -topExtend
            lp.width = width
            lp.height = height
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onAttachedToWindow() {
        android.util.Log.i("AresAttach", "PANE ATTACHED w=" + width)
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
        unclipHostChain()
    }

    /**
     * The pane is extended past its workspace cell (see [onMeasure]) so the app list reaches the
     * physical screen edges and scrolled rows flow behind the status bar and nav gesture bar. That
     * only shows if no ANCESTOR scissors it back to the cell. Measured hierarchy above the pane:
     *   ShortcutAndWidgetContainer (clipChildren=TRUE) -> CellLayout (already false) -> Workspace
     *   (clipChildren=true, but spans the FULL screen 0..displayHeight, so its clip is harmless).
     * The `ShortcutAndWidgetContainer` is the sole culprit: its own onAttachedToWindow sets
     * clipChildren/clipToPadding/clipToOutline to `!allowWidgetOverlap`, and that Lawnchair
     * preference is off by default, so it re-clips the pane to the cell area and the list looks
     * "cut off" at the bars (owner 2026-08-25). Clear the clip up to -- but NOT including -- the
     * Workspace: the Workspace must keep clipping so horizontal paging still clips adjacent pages,
     * and its full-screen bounds don't cut the behind-bar content anyway. Scoped to the pane's own
     * host chain (this panel hosts nothing but the pane), so panel 0 / the home grid are untouched.
     * Re-applied on every attach because the parent re-clips itself on each of its own attaches.
     */
    private fun unclipHostChain() {
        var v: android.view.ViewParent? = parent
        while (v is ViewGroup && v !is com.android.launcher3.Workspace<*>) {
            v.clipChildren = false
            v.clipToPadding = false
            v.clipToOutline = false
            v = v.parent
        }
    }

    /**
     * Symmetry the base class does not provide: take this pane's floating affordance back out of
     * the shared DragLayer when the pane goes away, so a folded launcher is not left with an
     * orphaned pill belonging to a pane that no longer exists.
     */
    override fun onDetachedFromWindow() {
        android.util.Log.i("AresAttach", "PANE DETACHED w=" + width)
        super.onDetachedFromWindow()
        releaseSearchPill()
    }

    /**
     * Takes this pane's floating pill back out of the shared DragLayer, for the case where
     * [onDetachedFromWindow] will never run.
     *
     * `Workspace.removeAllWorkspaceScreens` lifts the pane out with `detachViewFromParent` so a
     * rebind does not destroy its laid-out content. That skips the window-attach callbacks entirely,
     * so when the launcher then turns out to be FOLDED -- the pane is not coming back and is quietly
     * dropped rather than re-attached -- [onDetachedFromWindow] never fires and the pill was left
     * stranded on the folded home screen, on top of the folded container's own (owner 2026-09-01;
     * measured as zero PANE DETACHED lines across a fold). Idempotent: removing a view whose parent
     * is already null is a no-op, so the genuine-detach path calling it too is harmless.
     */
    fun releaseSearchPill() {
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
        val src = source.apps ?: return
        if (src.isEmpty()) return
        // Re-sync from the launcher's store whenever this pane is OUT OF STEP with it -- empty
        // (first populate) or stale. This pane is inflated once and reused across fold cycles, and
        // while FOLDED it is detached, so bindAllApplications feeds the launcher's store but SKIPS
        // this pane (getAresAppListPane() is null while detached). On the next UNFOLD the pane must
        // pull the current set, or an app installed/removed while folded leaves the unfolded list
        // stale or empty (owner 2026-08-25, "only when unfolded"). ModelCallbacks.bindAllApplications
        // stays the authoritative feed (real flags + uid map) whenever the pane is attached, so the
        // earlier "only when my store is empty" guard -- which let a stale pane survive -- is replaced
        // by an identity comparison here.
        //
        // Compare by component-key SET, not size: installing one app and uninstalling another while
        // folded leaves the count unchanged, and a size-equality skip would then keep the stale set
        // (adversarial review 2026-08-25, Finding 4). Size is the cheap first gate; only when it
        // matches do we build the key sets.
        val current = appsStore.apps
        if (current != null && current.size == src.size &&
            current.mapTo(HashSet()) { it.toComponentKey() } == src.mapTo(HashSet()) { it.toComponentKey() }
        ) {
            return
        }
        appsStore.setApps(src, 0, emptyMap())
    }
}
