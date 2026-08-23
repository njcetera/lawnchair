package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import com.android.launcher3.ExtendedEditText
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.SearchUiManager
import com.android.launcher3.allapps.search.AllAppsSearchBarController
import com.android.launcher3.search.SearchCallback
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.views.ActivityContext

/**
 * AresLauncher §17 — the app-list pane's collapsed/expanding search affordance.
 *
 * Rests as a circular icon in the bottom-right corner and expands into a full-width input when
 * tapped, keeping the pane sparse in line with §8's Niagara spec. Dismissing collapses it again and
 * clears the filter.
 *
 * ## Why this is a custom view rather than the stock search bar
 *
 * Launcher3 has a "floating search bar" mode ([AresSearchUiDelegate.isSearchBarFloating]) that does
 * the structural half of this properly: the container is re-parented into the `DragLayer`, and the
 * lists and header re-align to the parent top instead of hanging below a top-anchored search box.
 * That part is reused.
 *
 * What it does *not* do in this fork is position the thing. `getFloatingSearchBarRestingMarginBottom`
 * and friends are declared on `ActivityAllAppsContainerView`/`LauncherAllAppsContainerView` and
 * implemented all the way down into `LauncherState`, but **nothing calls them** — the consumer that
 * would apply those margins is part of the Google QSB layer that Lawnchair does not ship. Verified by
 * grep, then on-device: with floating enabled and the stock bar, the container was re-parented but
 * stayed at the top of the screen. So the resting position, the collapse/expand morph, and the IME
 * inset are handled here instead.
 *
 * (This is the second dormant-looking upstream feature in this pane to turn out incomplete rather
 * than merely unstyled — see the A-Z rail note in design/implementation-scope.md §8. Probing before
 * building on top of it is the reason this was cheap to discover.)
 */
class AresSearchContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), SearchUiManager {

    private val searchBarController = AllAppsSearchBarController()

    private lateinit var input: ExtendedEditText
    private lateinit var icon: ImageView

    /**
     * The morphing element. The root spans the pane and never resizes — it is a `BaseDragLayer`
     * child, and that class has its own `LayoutParams` subclass plus an `onLayout` that repositions
     * children, so animating the root's width there did not take effect on-device. Animating this
     * child inside an ordinary `FrameLayout` is reliable.
     */
    private lateinit var pill: View

    private var appsView: ActivityAllAppsContainerView<*>? = null
    /**
     * Derived from the input's visibility rather than tracked in a separate boolean.
     *
     * A boolean drifted out of sync in practice: [resetSearch] can fire while the pill is still
     * visually wide (the container resets search state when All Apps closes), which left the flag
     * reading "collapsed" while the pill stayed expanded — after which every tap took the wrong
     * branch and the affordance could never be closed. Reading the view keeps one source of truth.
     */
    private val expanded: Boolean
        get() = input.isVisible
    private var widthAnimator: ValueAnimator? = null

    /** Bottom inset contributed by the IME, so the affordance rides above the keyboard. */
    private var imeInset = 0

    /**
     * Gates the whole affordance to the app-list pane. Registered while attached; see
     * [onAttachedToWindow].
     *
     * [AresSearchUiDelegate.isSearchBarFloating] re-parents this container into the `DragLayer`, which
     * is what lets it rest in the corner — but the `DragLayer` outlives the pane, so without this the
     * pill floats over the **home screen** too (owner: *"the search ... should only be accessible from
     * the app list page"*). Stock hides a floating search bar by tying it to the all-apps state's
     * visible elements; that path is part of the Google QSB layer this fork does not ship (see the
     * class note), so the gating is done here — visible only in [LauncherState.ALL_APPS].
     */
    private var stateListener: StateManager.StateListener<LauncherState>? = null

    /**
     * True only while the app-list pane is the current/target state. The authority for whether this
     * container may be visible: [setVisibility] refuses external VISIBLE requests while it is false,
     * because the app-list setup shows this container even at rest on the **first run** (before any
     * state transition), and it lives in the `DragLayer` so a stray VISIBLE floats over the home.
     */
    private var gateOpen = false

    /** Drives the render/derender slide; cancelled and replaced on each toggle. */
    private var visAnimator: ValueAnimator? = null
    // Enter: overshoot, so the pill slides in from the right and springs a little past its rest before
    // settling. Exit: Material 3 emphasized-accelerate, a quick firm slide back off the right edge.
    private val overshoot = android.view.animation.OvershootInterpolator(2.5f)
    private val emphasizedAccelerate = android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    private val collapsedSize by lazy {
        resources.getDimensionPixelSize(R.dimen.ares_search_collapsed_size)
    }
    private val marginHorizontal by lazy {
        resources.getDimensionPixelSize(R.dimen.ares_search_margin_horizontal)
    }
    private val marginBottom by lazy {
        resources.getDimensionPixelSize(R.dimen.ares_search_margin_bottom)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        input = findViewById(R.id.ares_search_input)
        icon = findViewById(R.id.ares_search_icon)
        pill = findViewById(R.id.ares_search_pill)

        pill.setOnClickListener { if (!expanded) expand() }
        icon.setOnClickListener { if (expanded) collapse() else expand() }
    }

    /**
     * Positions the affordance in the bottom-right corner. Called once the container has been added
     * to the `DragLayer` (see [AresSearchUiDelegate.onInitializeSearchBar]) — before that its layout
     * params belong to the apps view and setting them here would be overwritten.
     */
    fun applyRestingPosition() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = collapsedSize
        lp.gravity = android.view.Gravity.BOTTOM
        lp.marginStart = marginHorizontal
        lp.marginEnd = marginHorizontal
        lp.bottomMargin = marginBottom
        layoutParams = lp
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Ride above the keyboard while it is up. Using the IME inset directly rather than
        // Insettable, which only carries the stable system-window insets and so would not move when
        // the keyboard opens.
        imeInset = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            0
        }
        translationY = -imeInset.toFloat()
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val launcher = Launcher.getLauncher(context)
        val listener = object : StateManager.StateListener<LauncherState> {
            override fun onStateTransitionStart(toState: LauncherState) {
                // Drive the slide as the transition BEGINS, so it overlaps the pane settling in rather
                // than waiting for it to fully land — owner: "start the animation a little earlier".
                // For a gesture this fires at the release, when the pane is already most of the way in,
                // so it is not shown mid-drag; the first-run clamp still guards the resting home.
                applyState(toState, animate = true)
            }

            override fun onStateTransitionComplete(finalState: LauncherState) {
                // Safety net only: correct the end state if a transition somehow skipped start, and
                // only while nothing is mid-flight, so a normal start-driven slide is never snapped.
                val onAppList = finalState == LauncherState.ALL_APPS
                if (gateOpen != onAppList && visAnimator == null) applyState(finalState, animate = true)
            }
        }
        launcher.stateManager.addStateListener(listener)
        stateListener = listener
        // Attaching in NORMAL (the home screen) must start hidden, with no animation.
        applyState(launcher.stateManager.state, animate = false)
    }

    /**
     * Brings the affordance in line with [state]: shown only on the app list, hidden everywhere else
     * — including the home screen this container floats over now that it lives in the `DragLayer`.
     * Sets [gateOpen] first so [setVisibility] knows whether a VISIBLE is permitted, then fades the
     * pill in or out. When hiding it is also collapsed, so it never reopens mid-morph the next time
     * the pane shows (resetSearch already collapses on close, but a cancelled transition can skip it).
     */
    private fun applyState(state: LauncherState, animate: Boolean) {
        val onAppList = state == LauncherState.ALL_APPS
        gateOpen = onAppList
        animateVisibility(onAppList, animate)
        if (!onAppList && expanded) collapse()
    }

    /**
     * Slides the pill in from the right (show) or out off the right edge then GONE (hide) — the owner
     * asked for a Material-You slide with a small horizontal bounce on entry. Enter overshoots its
     * rest position and settles ([overshoot]); exit is a quick emphasized-accelerate slide off-screen.
     * The `DragLayer` clips at the screen edge, so the pill is hidden by the slide itself — no fade.
     * Writes visibility through [setRawVisibility] (i.e. `super`) so it is not re-clamped; the
     * end-listener no-ops when a later toggle has superseded this animation, so a cancel-to-show never
     * leaves it GONE.
     */
    private fun animateVisibility(show: Boolean, animate: Boolean) {
        val prev = visAnimator
        visAnimator = null
        prev?.cancel()
        // Far enough right to clear the screen edge: the pill's own width plus its side margins.
        val offset = (collapsedSize + marginHorizontal * 2).toFloat()
        alpha = 1f // the slide, not a fade, is the reveal
        if (!animate) {
            translationX = if (show) 0f else offset
            setRawVisibility(if (show) VISIBLE else GONE)
            return
        }
        if (show) {
            setRawVisibility(VISIBLE)
            if (translationX == 0f) return // already settled in place
        } else if (visibility == GONE) {
            return
        }
        val target = if (show) 0f else offset
        val anim = ValueAnimator.ofFloat(translationX, target)
        visAnimator = anim
        anim.apply {
            duration = if (show) ENTER_DURATION_MS else EXIT_DURATION_MS
            // A short lead-in on entry so the slide begins partway through the pane settling rather
            // than the instant the transition starts (owner: at-start was "too fast"). Exit is immediate.
            startDelay = if (show) ENTER_START_DELAY_MS else 0L
            interpolator = if (show) overshoot else emphasizedAccelerate
            addUpdateListener { translationX = it.animatedValue as Float }
            addListener(onEnd = {
                if (visAnimator === anim) {
                    visAnimator = null
                    if (!show) setRawVisibility(GONE)
                }
            })
            start()
        }
    }

    /** `super.setVisibility`, bypassing the [setVisibility] gate-clamp for our own authoritative writes. */
    private fun setRawVisibility(v: Int) = super.setVisibility(v)

    /**
     * Clamps external visibility to the gate. The launcher's app-list setup makes this container
     * VISIBLE even at rest on the **first run** — before any state transition fires, so the state
     * listener has no event to correct it, and being a `DragLayer` child it then floats the pill over
     * the home (owner: verified on a fresh install — the FAB showed on the first-use home). External
     * hides still pass through; only VISIBLE is refused while the gate is closed. Reproduced by
     * `pm clear` then launch: without this the pill sits on the first-use home until All Apps is
     * opened once. Our own writes go through [setRawVisibility], so this never blocks them.
     */
    override fun setVisibility(visibility: Int) {
        super.setVisibility(if (visibility == VISIBLE && !gateOpen) GONE else visibility)
    }

    // region SearchUiManager

    override fun initializeSearch(containerView: ActivityAllAppsContainerView<*>) {
        appsView = containerView
        searchBarController.initialize(
            AresAppSearchAlgorithm(containerView.appsStore),
            input,
            ActivityContext.lookupContext(containerView.context),
            object : SearchCallback<AdapterItem> {
                override fun onSearchResult(query: String, items: ArrayList<AdapterItem>?) {
                    if (items == null) return
                    containerView.setSearchResults(items)
                }

                override fun clearSearchResult() {
                    containerView.onClearSearchResult()
                }
            },
        )

        // Installed *after* initialize, which sets its own listener. The stock one only consumes
        // back when the query is non-empty (otherwise back falls through and closes the pane), but
        // an expanded-and-empty search still has visible state to dismiss, so back should collapse
        // it first and only close the pane on a second press.
        input.setOnBackKeyListener {
            if (expanded) {
                collapse()
                true
            } else {
                false
            }
        }
    }

    /**
     * Also collapses, so leaving the pane entirely (`Launcher` calls this when All Apps closes)
     * never leaves the affordance expanded for the next time it is opened.
     */
    override fun resetSearch() {
        searchBarController.reset()
        collapse()
    }

    override fun getEditText(): ExtendedEditText = input

    override fun preDispatchKeyEvent(keyEvent: KeyEvent) {
        // Nothing: unlike the stock bar this does not grab typing until it has been opened
        // deliberately, so there is no type-to-search behaviour to route here.
    }

    // endregion

    private fun expand() {
        if (expanded) return
        // Flips `expanded` immediately, so a re-entrant call during the animation is a no-op.
        input.isVisible = true
        animateWidthTo(expandedWidth()) {
            input.requestFocus()
            input.showKeyboard()
        }
    }

    private fun collapse() {
        if (!expanded) return
        input.isVisible = false
        input.setText("")
        input.hideKeyboard()
        input.clearFocus()
        searchBarController.reset()
        animateWidthTo(collapsedSize) {}
    }

    /** The pill fills the container, which is already inset by the resting margins. */
    private fun expandedWidth(): Int = width.coerceAtLeast(collapsedSize)

    private fun animateWidthTo(target: Int, onEnd: () -> Unit) {
        widthAnimator?.cancel()
        val start = pill.width
        if (start == target) {
            onEnd()
            return
        }
        widthAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = MORPH_DURATION_MS
            addUpdateListener { animator ->
                val lp = pill.layoutParams
                lp.width = animator.animatedValue as Int
                pill.layoutParams = lp
            }
            addListener(
                onEnd = {
                    widthAnimator = null
                    // Pin the final width explicitly rather than trusting the last animation frame.
                    // If the animator is cancelled, or the device has its animator duration scale
                    // turned down, the closing frames may never be delivered and the pill would be
                    // left stranded mid-morph.
                    val lp = pill.layoutParams
                    lp.width = target
                    pill.layoutParams = lp
                    onEnd()
                },
            )
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stateListener?.let { Launcher.getLauncher(context).stateManager.removeStateListener(it) }
        stateListener = null
        widthAnimator?.cancel()
        widthAnimator = null
        visAnimator?.cancel()
        visAnimator = null
    }

    private companion object {
        const val MORPH_DURATION_MS = 200L

        /** Slide timing: entry gets a little room for its overshoot bounce to read; exit is a quick
         *  flick off the right edge. Both kept short so the pill feels responsive, not laggy. */
        const val ENTER_DURATION_MS = 260L
        const val EXIT_DURATION_MS = 120L

        /** Lead-in before the entry slide, so it starts partway through the pane settling rather than
         *  the instant the transition begins. Tuned by feel between at-start ("too fast") and
         *  at-fully-settled ("too late"). */
        const val ENTER_START_DELAY_MS = 200L
    }
}

/** Mirrors `androidx.core.animation`'s helper without pulling the dependency in for one call. */
private fun ValueAnimator.addListener(onEnd: () -> Unit) {
    addListener(
        object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
        },
    )
}
