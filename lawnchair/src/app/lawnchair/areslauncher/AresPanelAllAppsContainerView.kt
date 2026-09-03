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
     * Whether the base class's window registrations (the device-profile change listener and the
     * cross-window blur listener) are currently live for this pane.
     *
     * `Workspace.removeAllWorkspaceScreens` lifts this pane out with `detachViewFromParent`, which
     * nulls the parent WITHOUT dispatching `onDetachedFromWindow`, and the unfold then re-adds it
     * with a real `addView` that DOES dispatch `onAttachedToWindow`. So the registrations were made
     * once per unfold and released never: measured 2026-09-01 on emulator-5554, three fold cycles
     * produced 3 `PANE ATTACHED` and 0 `PANE DETACHED`, i.e. one extra registration per fold, each
     * capturing the Launcher through the blur lambda. Adversarial review 2026-09-01, finding 1.
     */
    private var windowRegistered = false

    override fun onAttachedToWindow() {
        android.util.Log.i("AresAttach", "PANE ATTACHED w=" + width + " dpListeners=" + paneListenerCount())
        // Balance any registrations still live from the previous attach. Without this they
        // accumulate one per fold cycle (see [windowRegistered]).
        releaseWindowRegistrations()
        // The base class adds mSearchContainer to the DragLayer when the search bar is floating,
        // and onDetachedFromWindow never removes it. This pane is detached and re-attached on every
        // fold cycle, so on the second attach that add would throw ("child already has a parent").
        // Drop it first and let the base class re-add it.
        (searchView?.parent as? ViewGroup)?.removeView(searchView)
        super.onAttachedToWindow()
        windowRegistered = true
        android.util.Log.i("AresAttach", "PANE REGISTERED dpListeners=" + paneListenerCount())
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
        android.util.Log.i("AresAttach", "PANE DETACHED w=" + width + " dpListeners=" + paneListenerCount())
        super.onDetachedFromWindow()
        windowRegistered = false
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
     * Releases the base class's window-scoped registrations (the device-profile change listener and
     * the cross-window blur listener) for the same reason [releaseSearchPill] exists: on a fold the
     * pane is lifted out with `detachViewFromParent`, so `onDetachedFromWindow` never runs and the
     * registrations made by the previous attach are never taken back.
     *
     * Measured on emulator-5554 2026-09-01: three fold cycles produced 3 `PANE ATTACHED` and 0
     * `PANE DETACHED`, i.e. three live registration sets for one pane, each holding the Launcher
     * through the blur listener's lambda. Idempotent -- the listener-list removal is a no-op when
     * absent and the blur listener is null-guarded -- so both the genuine-detach path and the
     * folded-drop path may call it. Adversarial review 2026-09-01, finding 1.
     */
    fun releaseWindowRegistrations() {
        if (!windowRegistered) return
        windowRegistered = false
        aresReleaseWindowRegistrations()
        android.util.Log.i("AresAttach", "PANE REGISTRATIONS RELEASED dpListeners=" + paneListenerCount())
    }

    /**
     * How many device-profile change listeners the activity currently holds for a pane of this
     * class. The leak this guards against is invisible in `meminfo` (it counts Views and Activities,
     * not registrations), so this is the number to watch: it must stay at most 1 across any number
     * of fold cycles.
     */
    private fun paneListenerCount(): Int =
        mActivityContext.onDeviceProfileChangeListeners.count { it is AresPanelAllAppsContainerView }

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
