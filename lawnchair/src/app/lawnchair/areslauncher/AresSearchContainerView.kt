package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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

    /** The current top search result — the default the keyboard's search action launches. */
    /** Adapter position of the highlighted top (first launchable) result, or -1 when there is none. */
    private var topResultPosition: Int = -1

    /**
     * Public read of [topResultPosition] for `SearchItemDecorator`, which draws the rich-row
     * selection highlight. Stock's quick-launch focus never flags web-suggestion rows, so the
     * decorator keys the focus tint off our own top-result index instead.
     */
    val highlightedResultPosition: Int get() = topResultPosition

    /** The highlighted top result itself, kept so [launchTopResult] can route apps vs. rich results. */
    private var topResultItem: AdapterItem? = null

    /** Draws the top-result highlight on the search recycler; toggled from the search callback. */
    private var highlightDecoration: AresSearchHighlightDecoration? = null

    /** Guards the one-shot contacts/media permission request; see [maybeRequestSearchPermissions]. */
    private var requestedSearchPerms = false
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

    /** The collapsed search glyph's XML tint, restored on collapse; see [onFinishInflate]. */
    private var defaultIconTint: android.content.res.ColorStateList? = null

    // The resting icon padding (ares_search_icon_padding, 16dp) captured from XML. The expanded
    // close button's background is an <inset> drawable, and an inset reports its 8dp inset as View
    // padding — so setBackgroundResource() silently clobbers the icon's padding to 8dp (a 40dp
    // glyph in the 56dp target) and setting background=null on collapse never restores it, leaving
    // the resting magnifier oversized too. We re-assert this captured value after every background
    // change so the glyph stays 24dp in both states: at rest a 24dp magnifier, expanded a 24dp ✕
    // centred in the 40dp tonal circle — the correct M3 icon-button proportion.
    private var defaultIconPadding = 0

    /** Material fast-out-slow-in for the expand/collapse width morph. */
    private val morphInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // The pill recolours across the morph: collapsed it is the high-emphasis M3 colorPrimary (the
    // fob reads as a FAB/action), expanded it settles to the calm neutral surfaceContainerHigh so a
    // full-width bright fill does not shout behind the text field (owner). Animated with an
    // ArgbEvaluator alongside the width morph via the pill's backgroundTint.
    private val collapsedPillColor by lazy { context.getColor(R.color.materialColorPrimary) }
    private val expandedPillColor by lazy { context.getColor(R.color.materialColorSurfaceContainerHigh) }
    private var pillColorAnimator: ValueAnimator? = null

    /** Last known IME bottom inset (px), so [onEnd] of the insets animation can settle to it. */
    private var lastImeBottom = 0

    /**
     * True while the IME is mid-animation (open or close). During that window the per-frame
     * [androidx.core.view.WindowInsetsAnimationCompat] callback owns [translationY], and
     * [onApplyWindowInsets] — which is dispatched once with the *final* insets — must not also set
     * it, or the bar would snap to its end position and then be re-animated (the "pops in" glitch).
     */
    private var imeAnimating = false

    /**
     * True only while [collapse] is running its own teardown. The IME-hide auto-collapse (see the
     * insets follower's `onEnd`) must ignore the keyboard drop that [collapse] itself triggers, or a
     * button-tap close would fire collapse() a second time mid-morph.
     */
    private var collapsing = false

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

    /** Snug gap kept between the bar and the top of the keyboard while the IME is up. */
    private val imeGap by lazy {
        resources.getDimensionPixelSize(R.dimen.ares_search_ime_gap)
    }

    private val tmpLoc = IntArray(2)

    /**
     * Sits the bar [imeGap] above the top of the keyboard while it is up, and at its resting spot
     * (translationY 0) when it is down.
     *
     * Anchors to the bar's *actual* window position rather than assuming it rests exactly
     * [marginBottom] above the window bottom: this container is a `DragLayer` child, and the
     * `DragLayer` is itself inset by the gesture-nav bar, so a resting-offset assumption left that
     * nav inset sitting in the gap (~24dp too much space). Both the bar's bottom (via
     * [getLocationInWindow]) and the IME inset are measured against the same full-screen window, so
     * the result is correct regardless of nav-bar size. Self-correcting per frame, so the
     * insets-animation callback can call it each step for a smooth ride.
     */
    private fun applyImeTranslation(imeBottom: Int) {
        if (imeBottom <= 0) {
            translationY = 0f
            return
        }
        val root = rootView ?: return
        getLocationInWindow(tmpLoc)
        // Laid-out bottom in window coords, with the current translation removed (getLocationInWindow
        // includes it) — constant across frames.
        val restBottom = (tmpLoc[1] + height) - translationY
        val keyboardTop = root.height - imeBottom
        // Only ever ride UP to sit imeGap above the keyboard; never dip below the resting spot. Early
        // in the keyboard's slide the keyboard top is still below the resting bar, so min() keeps the
        // bar put until the keyboard rises past it, then it tracks the keyboard the rest of the way.
        val desiredBottom = minOf(restBottom, (keyboardTop - imeGap).toFloat())
        translationY = desiredBottom - restBottom
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        input = findViewById(R.id.ares_search_input)
        icon = findViewById(R.id.ares_search_icon)
        pill = findViewById(R.id.ares_search_pill)

        // The collapsed search glyph's tint, set in XML (?android:textColorSecondary). Captured so
        // collapse() can restore it after expand() retints the icon for the tonal close button.
        defaultIconTint = icon.imageTintList
        defaultIconPadding = icon.paddingLeft
        // Drive the pill colour from code so it can animate across the morph; start collapsed.
        pill.backgroundTintList = android.content.res.ColorStateList.valueOf(collapsedPillColor)

        installImeFollower()

        pill.setOnClickListener { if (!expanded) expand() }
        // Collapsed, a click on the icon opens the affordance.
        icon.setOnClickListener { if (!expanded) expand() }
        // Expanded, the icon is the dismiss button. Collapse on ACTION_DOWN and consume the gesture,
        // so the tap is not eaten by the IME resolving its own dismissal first — that lag was the
        // old two-tap-to-close bug. When collapsed the listener passes the event through, so the
        // click-to-expand above still fires normally.
        icon.setOnTouchListener { _, ev ->
            if (expanded && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                collapse()
                true
            } else {
                false
            }
        }
        // Back while the keyboard is up (routed here by ExtendedEditText.onKeyPreIme): if the query
        // is empty, one back collapses the whole affordance — keyboard AND bar — instead of only
        // dropping the keyboard and stranding an empty open search bar (owner). With text present it
        // returns false and falls through to the default: drop the keyboard, keep the query and its
        // results.
        input.setOnBackKeyListener {
            if (expanded && input.text.isNullOrEmpty()) {
                collapse()
                true
            } else {
                false
            }
        }
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
        // the keyboard opens. This is the resting placement; the frame-by-frame slide as the
        // keyboard opens/closes is driven by the insets-animation callback (see [installImeFollower]),
        // so while that animation runs we leave translationY to it and only settle here otherwise.
        lastImeBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            0
        }
        if (!imeAnimating) applyImeTranslation(lastImeBottom)
        return super.onApplyWindowInsets(insets)
    }

    /**
     * Makes the bar ride the keyboard frame-by-frame instead of snapping to its final spot in one
     * layout pass. Without this the IME inset arrives in a single [onApplyWindowInsets] and the bar
     * jumps to its above-keyboard position while the keyboard is still sliding up — the "search bar
     * re-renders above the keyboard as it just appears" glitch the owner described.
     */
    private fun installImeFollower() {
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(
            this,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(
                DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                private val imeType = androidx.core.view.WindowInsetsCompat.Type.ime()

                override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    if (animation.typeMask and imeType != 0) imeAnimating = true
                }

                override fun onProgress(
                    insets: androidx.core.view.WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>,
                ): androidx.core.view.WindowInsetsCompat {
                    applyImeTranslation(insets.getInsets(imeType).bottom)
                    return insets
                }

                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    if (animation.typeMask and imeType != 0) {
                        imeAnimating = false
                        // Settle exactly on the final inset that onApplyWindowInsets recorded.
                        applyImeTranslation(lastImeBottom)
                        // If the user dismissed the keyboard (it is now down) while search is open
                        // and the query is EMPTY, close the whole affordance too — an empty open bar
                        // has no results worth keeping, so one back gesture should leave search
                        // entirely (owner). With text present the bar stays so the results remain.
                        // Keyed off the keyboard hiding rather than a back-key listener because
                        // predictive back (gesture nav) bypasses onKeyPreIme; this fires however the
                        // keyboard was dismissed. Guarded by [collapsing] so collapse()'s own
                        // hideKeyboard() does not re-enter, and by [expanded] so a completed collapse
                        // is a no-op.
                        if (lastImeBottom == 0 && expanded && !collapsing && input.text.isNullOrEmpty()) {
                            collapse()
                        }
                    }
                }
            },
        )
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

        // Highlight the top result as the default selection (owner). Drawn behind the first result
        // row of the search recycler; toggled from the search callback below.
        highlightDecoration?.let { containerView.mSearchRecyclerView.removeItemDecoration(it) }
        val highlight = AresSearchHighlightDecoration(context)
        highlightDecoration = highlight
        containerView.mSearchRecyclerView.addItemDecoration(highlight)

        // §17 hybrid search: our plain app rows on top, Lawnchair's richer categories below (only
        // while a query is active). Enable the permission-free, non-recreate category prefs so
        // calculator/settings/shortcuts run; web suggestions are on by default. Contacts/files need
        // runtime permissions (they self-gate to empty until granted).
        app.lawnchair.preferences.PreferenceManager.getInstance(context).apply {
            searchResultCalculator.set(true)
            searchResultSettings.set(true)
            searchResultShortcuts.set(true)
            // Contacts + files (owner). These carry a `recreate` flag, so set them only when not
            // already on, to avoid a relaunch loop. Each self-gates to empty until its runtime
            // permission is granted — requested from [expand] the first time search is opened.
            if (!searchResultPeople.get()) searchResultPeople.set(true)
            if (!searchResultFilesToggle.get()) searchResultFilesToggle.set(true)
            if (!searchResultVisualMedia.get()) searchResultVisualMedia.set(true)
            if (!searchResultAudio.get()) searchResultAudio.set(true)
        }
        searchBarController.initialize(
            AresRichSearchAlgorithm(context, containerView.appsStore),
            input,
            ActivityContext.lookupContext(containerView.context),
            object : SearchCallback<AdapterItem> {
                override fun onSearchResult(query: String, items: ArrayList<AdapterItem>?) {
                    if (items == null) return
                    // The highlighted default the keyboard's action launches is the first LAUNCHABLE
                    // row — skipping section headers. With apps on top that is position 0; for a
                    // query yielding only rich results (calculator, settings, contact, web) it is the
                    // first real row beneath its section header.
                    val pos = items.indexOfFirst { it.isLaunchable() }
                    topResultPosition = pos
                    topResultItem = pos.takeIf { it >= 0 }?.let { items[it] }
                    containerView.setSearchResults(items)
                    // Our green pill highlights only PLAIN APP rows, which have no background of
                    // their own. Rich results (web, contacts, settings…) already get a focus
                    // highlight from the stock SearchItemBackground/SearchItemDecorator when they are
                    // the quick-launch row — recoloured to the same green — so drawing our pill over
                    // them too would double the highlight (owner). Enter-to-launch still targets the
                    // top result of any type; this governs only our pill.
                    highlight.activePosition =
                        pos.takeIf { it >= 0 && topResultItem?.isPlainAppRow() == true } ?: -1
                    containerView.mSearchRecyclerView.invalidateItemDecorations()
                }

                override fun clearSearchResult() {
                    topResultPosition = -1
                    topResultItem = null
                    highlight.activePosition = -1
                    containerView.mSearchRecyclerView.invalidateItemDecorations()
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

        // The keyboard's search action launches the highlighted top result. Replaces the stock
        // controller's editor-action handler, whose launchHighlightedItem() is dead here — it drives
        // Lawnchair's rich-results quick-launch, which our plain app-name filter never produces.
        input.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                (
                    actionId == android.view.inputmethod.EditorInfo.IME_NULL &&
                        event?.action == KeyEvent.ACTION_DOWN
                    )
            if (isSearchAction) launchTopResult() else false
        }
    }

    /**
     * Requests the runtime permissions the contacts/files search providers need — once, the first
     * time the user opens search. Already-granted or permanently-denied permissions don't re-prompt.
     * The providers self-gate to empty without these, so search still works; granting just lights up
     * those two categories.
     */
    private fun maybeRequestSearchPermissions() {
        if (requestedSearchPerms) return
        requestedSearchPerms = true
        val launcher = Launcher.getLauncher(context)
        val wanted = mutableListOf(android.Manifest.permission.READ_CONTACTS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            wanted += android.Manifest.permission.READ_MEDIA_IMAGES
            wanted += android.Manifest.permission.READ_MEDIA_VIDEO
            wanted += android.Manifest.permission.READ_MEDIA_AUDIO
        }
        val needed = wanted.filter {
            launcher.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            launcher.requestPermissions(needed.toTypedArray(), REQ_CODE_SEARCH_PERMISSIONS)
        }
    }

    /**
     * Launches the highlighted top result, if any; the source view drives the launch animation.
     * App rows go through the launcher's safe-launch path (as before); every other result type —
     * settings, contacts, files, calculator, web suggestions — fires its row's own click action, so
     * the keyboard's enter/search key matches a tap on the highlighted row (owner).
     */
    private fun launchTopResult(): Boolean {
        val pos = topResultPosition
        if (pos < 0) return false
        val source = appsView?.mSearchRecyclerView?.findViewHolderForAdapterPosition(pos)?.itemView
            ?: return false
        val info = topResultItem?.itemInfo
        if (info is com.android.launcher3.model.data.AppInfo) {
            val intent = info.getIntent() ?: return false
            return Launcher.getLauncher(context).startActivitySafely(source, intent, info) != null
        }
        return source.performClick()
    }

    /**
     * Whether a row can be launched by enter — i.e. it is a real result, not a section header,
     * empty-state card, or divider. Our plain app rows are not [SearchAdapterItem]s, so they are
     * always launchable.
     */
    private fun AdapterItem.isLaunchable(): Boolean {
        val target = (this as? app.lawnchair.search.adapter.SearchAdapterItem)?.searchTarget
            ?: return true
        return target.resultType != app.lawnchair.search.adapter.SearchTargetCompat.RESULT_TYPE_SECTION_HEADER &&
            target.resultType != app.lawnchair.search.adapter.SearchTargetCompat.RESULT_TYPE_EMPTY_RESULT &&
            target.layoutType != com.android.app.search.LayoutType.EMPTY_DIVIDER
    }

    /**
     * A plain app row — a stock `BubbleTextView` bound by the app adapter — as opposed to a rich
     * [app.lawnchair.search.adapter.SearchAdapterItem] (web, contact, settings, calculator…), which
     * renders its own row background. Governs whether the highlight draws a filled pill (app rows)
     * or an outline ring (rich rows), so the pill never doubles a row's own surface.
     */
    private fun AdapterItem.isPlainAppRow(): Boolean =
        this !is app.lawnchair.search.adapter.SearchAdapterItem

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
        // First time search is opened, ask for the contacts/media permissions the enabled providers
        // need; they stay empty until granted.
        maybeRequestSearchPermissions()
        // Flips `expanded` immediately, so a re-entrant call during the animation is a no-op.
        input.isVisible = true
        // Fade the input in over the width morph rather than hard-appearing it (see animateWidthTo).
        input.alpha = 0f
        // Trailing glyph becomes an explicit close (✕) so "close search" reads as a distinct button
        // on the right (owner). Styled as a Material You filled-tonal icon button: a tonal circle
        // behind the ✕, with the ✕ in the on-container tone. Collapsed it is the bare search
        // magnifier again; see [collapse]. Cancel any in-flight collapse crossfade and restore alpha
        // so a fast re-open never leaves the icon half-faded.
        // Twist-crossfade the resting magnifier into the ✕ close button, mirroring the collapse
        // (see [crossfadeIconToSearch]) so opening and closing read as one continuous motion rather
        // than a hard cut.
        crossfadeIconToClose()
        // Raise the keyboard the instant search is tapped rather than after the morph settles
        // (owner). This focuses/serves the IME while the field is still mid-morph, which is exactly
        // the timing the note below calls flaky — so it is paired with a re-assert at the morph's
        // END ([focusAndRaiseKeyboard] in the completion). The early call gets the keyboard moving
        // immediately; the late call repairs the rare mid-morph binding that serves the IME to the
        // DecorView or lands a stale cursor. Re-asserting is idempotent, so raising twice is safe.
        focusAndRaiseKeyboard()
        // Collapsed, the field measures ZERO wide (its start margin plus its trailing-icon end inset
        // exceed the collapsed pill), and the morph grows it to full width over ~180ms. Focusing on
        // the settled, full-width field is what a normal tap does, so serving the IME again here
        // deterministically leaves it bound with a correct cursor even if the early call above did
        // not fully take.
        // Settle the pill from the bright fob colour to the calmer expanded surface over the morph.
        animatePillColorTo(expandedPillColor, MORPH_DURATION_MS)
        animateWidthTo(expandedWidth(), fadeInInput = true) { focusAndRaiseKeyboard() }
    }

    /** Crossfades the pill's fill colour across the morph via its backgroundTint. */
    private fun animatePillColorTo(target: Int, durationMs: Long) {
        pillColorAnimator?.cancel()
        val start = pill.backgroundTintList?.defaultColor ?: collapsedPillColor
        pillColorAnimator = ValueAnimator.ofObject(android.animation.ArgbEvaluator(), start, target).apply {
            duration = durationMs
            interpolator = morphInterpolator
            addUpdateListener {
                pill.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(it.animatedValue as Int)
            }
            start()
        }
    }

    /**
     * Serves the IME to the (now settled, full-width) input: request focus, place the cursor at the
     * end, and raise the keyboard. Called from the expand morph's completion so the field is laid
     * out at full width — the same preconditions a normal tap satisfies, which is why focus binds a
     * clean input connection with a correct cursor and backspace works first try. The keyboard is
     * raised both through [ExtendedEditText.showKeyboard] (SHOW_IMPLICIT) and the insets controller's
     * `show(ime())` for reliability; the insets follower rides the bar up with it.
     */
    private fun focusAndRaiseKeyboard() {
        if (!input.isVisible) return // collapsed again before the morph finished
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        input.showKeyboard()
        androidx.core.view.ViewCompat.getWindowInsetsController(input)
            ?.show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    private fun collapse() {
        if (!expanded) return
        collapsing = true
        // Keyboard drops first; the insets follower rides the bar down with it (applyImeTranslation).
        input.hideKeyboard()
        input.clearFocus()
        input.setText("")
        searchBarController.reset()
        crossfadeIconToSearch()
        // Return the pill to the bright fob colour as it shrinks back to a button.
        animatePillColorTo(collapsedPillColor, MORPH_DURATION_COLLAPSE_MS)
        // Fade the input out and hide it only once the pill has finished shrinking, so the field
        // collapses smoothly instead of blanking the instant the close button is tapped. `expanded`
        // stays true (it reads input.isVisible) until then, keeping re-entrant taps a harmless no-op.
        animateWidthTo(
            collapsedSize,
            fadeInInput = false,
            fadeOutInput = true,
            durationMs = MORPH_DURATION_COLLAPSE_MS,
        ) {
            input.isVisible = false
            input.alpha = 1f
            collapsing = false
        }
    }

    /**
     * Crossfades the trailing glyph from the tonal close button back to the resting search magnifier,
     * rather than a hard swap the instant the button is tapped. Quick, so it reads as part of the
     * collapse rather than a separate beat.
     */
    private fun crossfadeIconToSearch() {
        icon.animate().cancel()
        icon.animate()
            .alpha(0f)
            .rotationBy(90f) // the ✕ twists a quarter-turn as it leaves — a bit of Material You whimsy
            .setDuration(ICON_CROSSFADE_MS)
            .withEndAction {
                icon.setImageResource(R.drawable.ares_ic_search)
                icon.background = null
                // Removing the inset background does not restore padding; re-assert the resting
                // 16dp so the collapsed magnifier returns to 24dp instead of staying oversized.
                icon.setPadding(defaultIconPadding, defaultIconPadding, defaultIconPadding, defaultIconPadding)
                icon.imageTintList = defaultIconTint
                // The magnifier enters pre-twisted and settles upright, so the swap reads as one
                // continuous turn rather than a hard cut. Always ends at 0, so it never accumulates.
                icon.rotation = -90f
                icon.animate().alpha(1f).rotation(0f).setDuration(ICON_CROSSFADE_MS).start()
            }
            .start()
    }

    /**
     * Crossfades the trailing glyph from the resting magnifier into the tonal ✕ close button — the
     * mirror of [crossfadeIconToSearch], with the same quarter-turn twist — so opening reads as one
     * continuous motion rather than the old hard cut, matching the collapse the owner liked.
     */
    private fun crossfadeIconToClose() {
        icon.animate().cancel()
        icon.animate()
            .alpha(0f)
            .rotationBy(90f) // the magnifier twists out, as the ✕ does on collapse
            .setDuration(ICON_CROSSFADE_MS)
            .withEndAction {
                icon.setImageResource(R.drawable.ares_ic_search_close)
                icon.setBackgroundResource(R.drawable.ares_search_close_bg)
                // The inset background reports 8dp padding; re-assert the resting 16dp so the ✕ stays
                // 24dp centred in the 40dp tonal circle rather than swelling to fill it.
                icon.setPadding(defaultIconPadding, defaultIconPadding, defaultIconPadding, defaultIconPadding)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.ares_my_icon_button_icon),
                )
                // The ✕ enters pre-twisted and settles upright — one continuous turn. Always ends at
                // 0, so rotation never accumulates across repeated open/close.
                icon.rotation = -90f
                icon.animate().alpha(1f).rotation(0f).setDuration(ICON_CROSSFADE_MS).start()
            }
            .start()
    }

    /** The pill fills the container, which is already inset by the resting margins. */
    private fun expandedWidth(): Int = width.coerceAtLeast(collapsedSize)

    private fun animateWidthTo(
        target: Int,
        fadeInInput: Boolean,
        fadeOutInput: Boolean = false,
        durationMs: Long = MORPH_DURATION_MS,
        onEnd: () -> Unit,
    ) {
        widthAnimator?.cancel()
        val start = pill.width
        if (start == target) {
            if (fadeInInput) input.alpha = 1f
            if (fadeOutInput) input.alpha = 0f
            onEnd()
            return
        }
        widthAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = durationMs
            interpolator = morphInterpolator
            addUpdateListener { animator ->
                val lp = pill.layoutParams
                lp.width = animator.animatedValue as Int
                pill.layoutParams = lp
                // Bring the input up with the morph on expand so its text/hint doesn't hard-appear at
                // full width; on collapse fade it back out as the pill shrinks rather than blanking it.
                if (fadeInInput) input.alpha = animator.animatedFraction
                if (fadeOutInput) input.alpha = 1f - animator.animatedFraction
            }
            // Explicit AnimatorListenerAdapter (not the KTX addListener(onCancel=,onEnd=), whose
            // overload resolution falls back to the Java method once onCancel is named) so we can
            // tell a natural end from a cancel.
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    widthAnimator = null
                    // Pin the final width explicitly rather than trusting the last animation frame.
                    // If the animator is cancelled, or the device has its animator duration scale
                    // turned down, the closing frames may never be delivered and the pill would be
                    // left stranded mid-morph.
                    val lp = pill.layoutParams
                    lp.width = target
                    pill.layoutParams = lp
                    if (fadeInInput) input.alpha = 1f
                    // Run the completion ONLY on a natural end. A cancel also fires onAnimationEnd,
                    // and collapse() cancels a still-running expand as its first step — whose
                    // completion is focusAndRaiseKeyboard(). Without this guard, tapping the close
                    // fob during the ~180ms open morph re-focuses the field and re-raises the IME
                    // right after collapse asked to drop it (a keyboard flash). The superseding
                    // animation owns the field's next state, so the cancelled one must not complete.
                    if (!cancelled) onEnd()
                }
            })
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
        /** Request code for the contacts/media search permissions. */
        const val REQ_CODE_SEARCH_PERMISSIONS = 4517

        /** Expand morph (snappy; owner liked the open, then asked for it a touch quicker). */
        const val MORPH_DURATION_MS = 180L

        /** Collapse morph — deliberately slower than the open so the close reads as a gentle settle. */
        const val MORPH_DURATION_COLLAPSE_MS = 360L

        /** Each half of the trailing icon's close->search crossfade on collapse. Paced to the close. */
        const val ICON_CROSSFADE_MS = 160L

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
