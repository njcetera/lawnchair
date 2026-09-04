/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair

import android.animation.AnimatorSet
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.PowerManager
import android.util.Pair
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import android.window.SplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairApp.Companion.showQuickstepWarningIfNecessary
import app.lawnchair.areslauncher.AresDragWatch
import app.lawnchair.areslauncher.AresFolderExitHandoff
import app.lawnchair.areslauncher.AresFolderPreview
import app.lawnchair.areslauncher.AresIconTransition
import app.lawnchair.areslauncher.AresThemeIconRefresh
import app.lawnchair.compat.LawnchairQuickstepCompat
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.wallpaper.service.WallpaperService
import app.lawnchair.gestures.GestureController
import app.lawnchair.gestures.VerticalSwipeTouchController
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.ui.LawnchairShortcutActivity
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.root.RootHelperManager
import app.lawnchair.root.RootNotAvailableException
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.ui.popup.LauncherOptionsPopup
import app.lawnchair.ui.popup.LawnchairShortcut
import app.lawnchair.util.getThemedIconPacksInstalled
import app.lawnchair.util.unsafeLazy
import app.lawnchair.views.LawnchairFloatingSurfaceView
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BaseActivity
import com.android.launcher3.BubbleTextView
import com.android.launcher3.GestureNavContract
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_WIDGETS_PREDICTION
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.PredictedContainerInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.statemanager.StateManager.StateHandler
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.uioverrides.states.AllAppsState
import com.android.launcher3.uioverrides.states.BackgroundAppState
import com.android.launcher3.uioverrides.states.OverviewState
import com.android.launcher3.util.ActivityOptionsWrapper
import com.android.launcher3.util.Executors
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.SystemUiController.UI_STATE_BASE_WINDOW
import com.android.launcher3.util.Themes
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.util.TouchController
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.OptionsPopupView
import com.android.launcher3.views.OptionsPopupView.OptionItem
import com.android.launcher3.widget.LauncherWidgetHolder
import com.android.launcher3.widget.RoundedCornerEnforcement
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.shared.system.QuickStepContract
import com.kieronquinn.app.smartspacer.sdk.client.SmartspacerClient
import com.patrykmichalik.opto.core.onEach
import dev.kdrag0n.monet.theme.ColorScheme
import java.util.stream.Stream
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class LawnchairLauncher : QuickstepLauncher() {
    private val defaultOverlay by unsafeLazy { OverlayCallbackImpl(this) }
    private val prefs by unsafeLazy { PreferenceManager.getInstance(this) }
    private val preferenceManager2 by unsafeLazy { PreferenceManager2.getInstance(this) }
    private val insetsController: WindowInsetsControllerCompat by lazy {
        val window = launcher.window
            ?: throw Exception("WindowInsetsControllerCompat not available.")
        WindowInsetsControllerCompat(window, rootView)
    }
    private val themeProvider by unsafeLazy { ThemeProvider.INSTANCE.get(this) }
    private val noStatusBarStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState is OverviewState) {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {
            if (finalState !is OverviewState) {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    private val rememberPositionStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState is AllAppsState) {
                mAppsView.activeRecyclerView.restoreScrollPosition()
            }
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {}
    }
    private val statusBarClockListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            when (toState) {
                is BackgroundAppState,
                is OverviewState,
                is AllAppsState,
                -> {
                    LawnchairApp.instance.restoreClockInStatusBar()
                }

                else -> {
                    workspace.updateStatusbarClock()
                }
            }
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {}
    }
    private val clearSearchStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionComplete(finalState: LauncherState) {
            if (finalState == LauncherState.NORMAL && mAppsView != null && mAppsView.isSearching) {
                mAppsView?.post {
                    mAppsView.reset(false, true)
                }
            }
        }
    }

    // AresLauncher §9 wallpaper dim: fades the app-list readability dim in only once the app list is
    // fully shown (transition to ALL_APPS complete) and out the moment it starts to leave. Owner
    // wanted it gated to the settled page, not tied to swipe progress.
    private var appListDimAnimator: android.animation.ValueAnimator? = null
    private val appListDimStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState !is AllAppsState) animateAppListDim(0f)
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {
            animateAppListDim(if (finalState is AllAppsState) 1f else 0f)
        }
    }

    private fun animateAppListDim(target: Float) {
        val root = rootView ?: return
        appListDimAnimator?.cancel()
        val start = root.aresWallpaperDimProgress
        if (start == target) {
            root.aresWallpaperDimProgress = target
            return
        }
        appListDimAnimator = android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = if (target > start) APP_LIST_DIM_FADE_IN_MS else APP_LIST_DIM_FADE_OUT_MS
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { root.aresWallpaperDimProgress = it.animatedValue as Float }
            start()
        }
    }

    private lateinit var colorScheme: ColorScheme
    private var hasBackGesture = false

    val gestureController by unsafeLazy { GestureController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        layoutInflater.factory2 = LawnchairLayoutFactory(this)
        super.onCreate(savedInstanceState)

        prefs.launcherTheme.subscribeChanges(this, ::updateTheme)
        prefs.feedProvider.subscribeChanges(this, defaultOverlay::reconnect)
        preferenceManager2.enableFeed.get().distinctUntilChanged().onEach { enable ->
            defaultOverlay.setEnableFeed(enable)
        }.launchIn(scope = lifecycleScope)
        launcher.stateManager.addStateListener(clearSearchStateListener)
        launcher.stateManager.addStateListener(appListDimStateListener)

        if (prefs.autoLaunchRoot.get()) {
            lifecycleScope.launch {
                try {
                    RootHelperManager.INSTANCE.get(this@LawnchairLauncher)
                } catch (_: RootNotAvailableException) {
                }
            }
        }

        preferenceManager2.showStatusBar.get().distinctUntilChanged().onEach {
            with(insetsController) {
                if (it) {
                    show(WindowInsetsCompat.Type.statusBars())
                } else {
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            }
            with(launcher.stateManager) {
                if (it) {
                    removeStateListener(noStatusBarStateListener)
                } else {
                    addStateListener(noStatusBarStateListener)
                }
            }
        }.launchIn(scope = lifecycleScope)

        preferenceManager2.statusBarClock.get().onEach {
            with(launcher.stateManager) {
                if (it) {
                    addStateListener(statusBarClockListener)
                } else {
                    removeStateListener(statusBarClockListener)
                    // Make sure status bar clock is restored when the preference is toggled off
                    LawnchairApp.instance.restoreClockInStatusBar()
                }
            }
        }
        preferenceManager2.rememberPosition.get().onEach {
            with(launcher.stateManager) {
                if (it) {
                    addStateListener(rememberPositionStateListener)
                } else {
                    removeStateListener(rememberPositionStateListener)
                }
            }
        }.launchIn(scope = lifecycleScope)

        prefs.overrideWindowCornerRadius.subscribeValues(this) {
            QuickStepContract.sHasCustomCornerRadius = it
        }
        prefs.windowCornerRadius.subscribeValues(this) {
            QuickStepContract.sCustomCornerRadius = it.toFloat()
        }
        preferenceManager2.roundedWidgets.onEach(launchIn = lifecycleScope) {
            RoundedCornerEnforcement.sRoundedCornerEnabled = it
        }
        val isWorkspaceDarkText = Themes.getAttrBoolean(this, R.attr.isWorkspaceDarkText)
        preferenceManager2.darkStatusBar.onEach(launchIn = lifecycleScope) { darkStatusBar ->
            systemUiController?.updateUiState(UI_STATE_BASE_WINDOW, isWorkspaceDarkText || darkStatusBar)
        }
        preferenceManager2.backPressGestureHandler.onEach(launchIn = lifecycleScope) { handler ->
            hasBackGesture = handler !is GestureHandlerConfig.NoOp
        }

        LauncherOptionsPopup.restoreMissingPopupOptions(launcher)
        LauncherOptionsPopup.migrateLegacyPreferences(launcher)

        // Handle update from version 12 Alpha 4 to version 12 Alpha 5.
        if (
            prefs.themedIcons.get() &&
            packageManager.getThemedIconPacksInstalled(this).isEmpty()
        ) {
            prefs.themedIcons.set(newValue = false)
        }

        colorScheme = themeProvider.colorScheme

        showQuickstepWarningIfNecessary()

        reloadIconsIfNeeded()

        AppDatabase.INSTANCE.get(this).checkpointSync()
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent != null && intent.action == LawnchairShortcutActivity.START_ACTION) {
            val handlerString = intent.getStringExtra(LawnchairShortcutActivity.EXTRA_HANDLER)
            val config = handlerString?.let { GestureHandlerConfig.fromString(it) }
            if (config != null && config.isExternallyInvokable()) {
                gestureController.handle(config)
            }
        }

        // AresLauncher §20A: home means "put everything back to a known state", so it leaves the
        // home grid's edit mode -- joining Back and a tap on empty space as the third exit.
        //
        // Hooked on the intent rather than on a gesture callback so every route that actually
        // delivers one behaves identically. Verified on the emulator: the HOME key and the pill
        // gesture performed *from another app* both arrive here and both now exit edit mode.
        //
        // NOT covered, and measured rather than assumed: the pill gesture while the launcher is
        // already the foreground app. Logcat shows the system's swipe-up gesture monitor "stealing
        // input gesture ... from app.lawnchair.debug" and no home intent is delivered at all, so
        // there is nothing here to hook. That case needs a separate decision -- see the report.
        //
        // Done BEFORE super, which starts a rebind for ACTION_MAIN: exiting first means the rows
        // are rebound already out of edit mode instead of carrying badges into the new binding.
        if (intent?.action == Intent.ACTION_MAIN) {
            workspace?.aresHomeList?.exitEditMode()
        }

        super.onNewIntent(intent)
    }

    override fun collectStateHandlers(out: MutableList<StateHandler<LauncherState>>) {
        super.collectStateHandlers(out)
        out.add(SearchBarStateHandler(this))
    }

    override fun getAllAppsItemLongClickListener(): View.OnLongClickListener {
        return View.OnLongClickListener { view ->
            if (view is FolderIcon && view.mInfo.id != ItemInfo.NO_ID) {
                LawnchairShortcut.showAppDrawerFolderPopup(this, view)
            } else {
                super.getAllAppsItemLongClickListener().onLongClick(view)
            }
        }
    }

    override fun getSupportedShortcuts(container: Int): Stream<SystemShortcut.Factory<*>> = Stream.concat(
        super.getSupportedShortcuts(container),
        Stream.concat(
            Stream.of(
                app.lawnchair.areslauncher.AresAddToHome.SHORTCUT,
                LawnchairShortcut.UNINSTALL,
                LawnchairShortcut.CUSTOMIZE,
                LawnchairShortcut.OPEN_IN_STORE,
            ),
            if (LawnchairApp.isRecentsEnabled) Stream.of(LawnchairShortcut.PAUSE_APPS) else Stream.empty(),
        ),
    )

    fun updateTheme() {
        if (themeProvider.colorScheme != colorScheme) {
            recreate()
        } else {
            mWallpaperThemeManager.updateTheme()
        }
    }

    /**
     * Keeps the edge back GESTURE alive while the home grid is editing.
     *
     * Stock excludes the back gesture whenever the launcher sits at NORMAL with nothing floating,
     * on the reasoning that the home screen has nowhere to go back to — `updateDisallowBack` hands
     * `SYSTEM_GESTURE_EXCLUSION_RECT` to the framework and the swipe stops being a system gesture
     * at all. Edit mode is still NORMAL, so the exclusion applied there too and the mode could only
     * be left with the BACK key, never the gesture. The gesture was not being ignored; it was never
     * delivered.
     *
     * [onStateBack] already does the right thing once a back actually arrives, so this is the whole
     * fix: stop suppressing it while there is something to dismiss.
     */
    override fun aresWantsBackGesture(): Boolean =
        workspace?.aresHomeList?.isEditMode() == true

    override fun onStateBack() {
        // AresLauncher §4: back leaves the home grid's edit mode before anything else. This is the
        // #5 fallback handler in Launcher.getOnBackAnimationCallback(), so an open popup or an
        // in-flight drag has already had its turn -- by the time we get here, edit mode really is
        // the thing the user means to dismiss.
        if (workspace?.aresHomeList?.exitEditMode() == true) return

        val searchInput = mAppsView?.searchUiManager?.editText
        val isSearching = mAppsView?.isSearching == true || searchInput?.hasFocus() == true
        if (isSearching) {
            mAppsView?.searchUiManager?.resetSearch()
            allAppsController.animateAllAppsToNoScale()
        } else {
            super.onStateBack()
        }
    }

    override fun createTouchControllers(): Array<TouchController> {
        val verticalSwipeController = VerticalSwipeTouchController(this, gestureController)
        // AresLauncher §9: horizontal pane navigation (home <-> app list). Ordered ahead of the
        // vertical controllers so pane navigation wins any ambiguity; in practice they cannot
        // compete, since each only claims a drag once its own axis dominates.
        val paneSwipeController = app.lawnchair.areslauncher.AresPaneSwipeController(this)

        // The home grid scrolls vertically, but TouchControllers are offered every gesture before
        // any child view sees it -- so without this the grid would receive no vertical drags at all
        // (measured: ACTION_DOWN arrived, zero scroll calls followed). Each stock controller is
        // wrapped so it stands aside for gestures starting on the grid; edge gestures, which begin
        // outside it, are untouched. Our own pane controller is not wrapped: it claims only
        // horizontal drags, which the grid does not want. See AresHomeScrollGuard.
        // The unfolded app-list pane scrolls too, and sits outside the grid's bounds -- without it
        // here, a vertical drag on the pane was claimed upstream and opened ALL_APPS, sliding the
        // folded container's sheet in over the persistent pane.
        val gridProvider = { workspace?.aresHomeList }
        val paneProvider = { workspace?.aresAppListPane }
        // The DragController is deliberately NOT wrapped, and that exclusion is load-bearing.
        //
        // It is the one stock controller that never *competes* for a gesture: its
        // onControllerInterceptTouchEvent returns `mDragDriver != null && ...`, so it claims only
        // while a drag it started is already in flight. Declining that is never right -- it muzzles
        // the drag rather than standing aside for the grid.
        //
        // Wrapped, it broke every DragController drag that began inside the home grid's bounds
        // while the launcher was in NORMAL: the guard latches its answer at ACTION_DOWN, and an
        // open folder is drawn *over* the grid, so a drag started inside a folder was declined for
        // its whole life. No MOVE reached handleMoveEvent, so no drop target was ever found and
        // drop() never ran. It also cost DragController its ACTION_DOWN, which is where
        // LauncherDragController reads mMotionDown for the drag view's registration point.
        //
        // Nothing is given up. During a home-grid reorder (ItemTouchHelper) no DragController drag
        // exists, so the unwrapped controller returns false exactly as the wrapped one did.
        val stock = super.createTouchControllers().map { controller ->
            if (controller is DragController<*>) {
                controller
            } else {
                app.lawnchair.areslauncher.AresHomeScrollGuard(
                    this,
                    controller,
                    gridProvider,
                    paneProvider,
                )
            } as TouchController
        }.toTypedArray()

        // §11a: Lawnchair's OWN vertical swipe controller has to be guarded too, and its absence
        // from the guard was the notification-shade hijack.
        //
        // MEASURED, on the emulator, unfolded, with the probe in
        // BaseDragLayer.findControllerToHandleTouch: a fresh downward drag over the app-list pane
        // was offered to all nine controllers and NONE of them claimed the ACTION_DOWN -- the guard
        // declined StatusBarTouchController and five other stock controllers with overPane=true --
        // and the notification shade opened anyway. The same drag inside Settings did not open it,
        // so it was never a system-wide gesture, and logcat showed no input monitor stealing it.
        //
        // VerticalSwipeTouchController is the one vertical controller in that list that was NOT
        // wrapped, and the DOWN-only probe could not see it claim, because its
        // onControllerInterceptTouchEvent returns `detector.isDraggingOrSettling` -- false at DOWN,
        // true only once the drag passes slop on a later MOVE. Its route to the shade is:
        //
        //   getSwipeDirection() arms DIRECTION_DOWN when `overrideSwipeDown ||
        //   overrideTwoFingerSwipeDown`. overrideSwipeDown is false at default, but
        //   twoFingerSwipeDownGestureHandler defaults to OpenQuickSettings, which is not NoOp, so
        //   overrideTwoFingerSwipeDown is TRUE and the downward direction is armed. onDrag() then
        //   branches on pointerCount, and the ONE-finger branch calls gestureController.onSwipeDown()
        //   -- whose handler defaults to GestureHandlerConfig.OpenNotifications.
        //
        // So a one-finger downward drag opens the shade because a TWO-finger preference armed the
        // direction. §11a's table eliminated this controller on the grounds that
        // swipeDownGestureHandler was still at its default; that is true and is not sufficient,
        // because getSwipeDirection() is an OR with the two-finger preference. That elimination is
        // now withdrawn.
        //
        // It explains every observation on the record: the asymmetry the user described (a stream
        // that STARTS upward is classified up by BothAxesSwipeDetector and never reaches the down
        // branch, while a fresh downward stream does); that it happens over the home grid as well
        // as the pane (this controller is scoped to neither); and that it is worst unfolded, since
        // canInterceptTouch() requires LauncherState.NORMAL -- which unfolded is where the launcher
        // stays, with the pane persistent, while folded the app list puts it in ALL_APPS.
        //
        // Wrapping restores the guard's stated contract rather than changing the gesture: the guard
        // declines only in NORMAL and only for gestures starting inside the grid or the pane, so
        // Lawnchair's swipe gestures are untouched everywhere else.
        val guardedVerticalSwipe = app.lawnchair.areslauncher.AresHomeScrollGuard(
            this,
            verticalSwipeController,
            gridProvider,
            paneProvider,
        )

        return arrayOf<TouchController>(paneSwipeController, guardedVerticalSwipe) + stock
    }

    override fun handleHomeTap() {
        gestureController.onHomePressed()
    }

    fun bindItems(items: List<ItemInfo>, forceAnimateIcons: Boolean) {
        // pE-TODO(QPR1): Note: null is modelWriter + bindItems override something
        val inflatedItems = items.map { i ->
            Pair.create(
                i,
                itemInflater?.inflateItem(
                    i,
                    null,
                ),
            )
        }.toList()
        bindInflatedItems(inflatedItems, if (forceAnimateIcons) AnimatorSet() else null)
    }

    override fun finishBindingItems(pagesBoundFirst: com.android.launcher3.util.IntSet?) {
        super.finishBindingItems(pagesBoundFirst)

        // Ledger row 77. Deliberately HERE and not in onCreate: `enqueueModelUpdateTask` drops the
        // task on the floor when `isModelLoaded()` is false, with no log, and on a theme-switch
        // recreate onCreate runs before the loader finishes. Measured 2026-09-04 -- the whole first
        // Pixel A/B compared the control against a fix that never executed.
        // Read-only drag recorder (ledger row 84). Registered here rather than in onCreate so it
        // binds to the DragController of the activity that will actually take the gestures, and
        // re-registers idempotently after the recreate a theme switch or a fold causes.
        AresDragWatch.register(this)
        AresThemeIconRefresh.refreshIfIconStateChanged(this)
        // Apply the soft rebind FIRST: it is what makes the grid final. A rebind whose row set is
        // unchanged (a fold/unfold, which always triggers one) costs nothing and the grid never
        // flickers; one that genuinely changed falls back to the full rebuild, which tears every tile
        // down and re-adds it. See AresHomeAdapter.finishSoftRebind.
        //
        // ORDER MATTERS, and getting it wrong is what broke the edit-mode icon transition
        // (owner 2026-09-01, "the tile animation moves off the tile midway"). AresIconTransition's
        // contract is "bind-complete = the reloaded icons are on the grid", and it resolves its
        // per-tile covers on that signal. With the soft rebind the grid is NOT final until the buffer
        // is applied, so calling playFrozen first told the transition to start resolving and only then
        // rebuilt the tiles underneath it.
        workspace?.aresHomeList?.aresAdapter?.finishSoftRebind()
        // Bind-complete = the reloaded icons are on the grid. If an icon-pack pick froze the old grid
        // (AresEditCarousel), wipe to the finished new one now; a no-op otherwise.
        AresIconTransition.playFrozen(this, workspace?.aresHomeList)
        // §22 dual-pane: setInsets posts the dual-pane sync during an unfold's rebind, so prune bails
        // on isWorkspaceLoading and the widget-bearing (visually empty) screens survive as blank pages
        // to the right unfolded. Re-run the idempotent sync now that binding is done (owner 2026-09-01).
        workspace?.aresResyncDualPaneAfterBind()
    }

    override fun handleGestureContract(intent: Intent) {
        if (!LawnchairApp.isRecentsEnabled && prefs.enableGnc.get()) {
            val gnc = GestureNavContract.fromIntent(intent)
            if (gnc != null) {
                AbstractFloatingView.closeOpenViews(
                    this,
                    false,
                    AbstractFloatingView.TYPE_ICON_SURFACE,
                )
                LawnchairFloatingSurfaceView.show(this, gnc)
            }
        }
    }

    override fun onUiChangedWhileSleeping() {
        if (Utilities.ATLEAST_S) {
            super.onUiChangedWhileSleeping()
        }
    }

    override fun showDefaultOptions(x: Float, y: Float) {
        val showWallpaperCarousel = "+carousel" in preferenceManager2.launcherPopupOrder.firstCached()

        if (showWallpaperCarousel) {
            show<LawnchairLauncher>(
                this,
                getPopupTarget(x, y),
                OptionsPopupView.getOptions(this),
            )
        } else {
            super.showDefaultOptions(x, y)
        }
    }

    private fun <T> show(
        activityContext: ActivityContext?,
        targetRect: RectF,
        items: List<OptionItem>,
        shouldAddArrow: Boolean = false,
        width: Int = 0,
    ): OptionsPopupView<T>? where T : Context?, T : ActivityContext? {
        if (activityContext == null) return null

        val isEmpty = WallpaperService.INSTANCE.get(this).getTopWallpapers().isEmpty()
        val layout = if (isEmpty) R.layout.longpress_options_menu else R.layout.wallpaper_options_popup

        val popup = activityContext.layoutInflater.inflate(layout, activityContext.dragLayer, false) as OptionsPopupView<T>
        popup.setTargetRect(targetRect)
        popup.setShouldAddArrow(shouldAddArrow)

        for (item in items) {
            val deepLayout = if (isEmpty) R.layout.system_shortcut else R.layout.wallpaper_options_popup_item

            val view = popup.inflateAndAdd<DeepShortcutView>(deepLayout, popup)
            if (width > 0) view.layoutParams.width = width
            view.iconView.setBackgroundDrawable(item.icon)
            view.bubbleText.text = item.label
            view.setOnClickListener(popup)
            view.setOnLongClickListener(popup)
            popup.mItemMap[view] = item
        }

        popup.show()
        return popup
    }

    fun createAppWidgetHolder(): LauncherWidgetHolder {
        val holder = LauncherWidgetHolder.newInstance(this)
        holder.setAppWidgetRemovedCallback { appWidgetId ->
            workspace.removeWidget(appWidgetId)
        }
        return holder
    }

    override fun makeDefaultActivityOptions(splashScreenStyle: Int): ActivityOptionsWrapper {
        val callbacks = RunnableList()
        val options = if (Utilities.ATLEAST_Q) {
            LawnchairQuickstepCompat.activityOptionsCompat.makeCustomAnimation(
                this,
                0,
                0,
                Executors.MAIN_EXECUTOR.handler,
                null,
            ) {
                callbacks.executeAllAndDestroy()
            }
        } else {
            ActivityOptions.makeBasic()
        }
        if (Utilities.ATLEAST_T) {
            options.splashScreenStyle = splashScreenStyle
        }

        Utilities.allowBGLaunch(options)
        return ActivityOptionsWrapper(options, callbacks)
    }

    override fun getActivityLaunchOptions(v: View?, item: ItemInfo?): ActivityOptionsWrapper {
        return runCatching {
            super.getActivityLaunchOptions(v, item)
        }.getOrElse {
            getActivityLaunchOptionsDefault(v)
        }
    }

    private fun getActivityLaunchOptionsDefault(v: View?): ActivityOptionsWrapper {
        var left = 0
        var top = 0
        var width = v!!.measuredWidth
        var height = v.measuredHeight
        if (v is BubbleTextView) {
            // Launch from center of icon, not entire view
            val icon: Drawable? = v.icon
            if (icon != null) {
                val bounds = icon.bounds
                left = (width - bounds.width()) / 2
                top = v.paddingTop
                width = bounds.width()
                height = bounds.height()
            }
        }
        val options = Utilities.allowBGLaunch(
            ActivityOptions.makeClipRevealAnimation(
                v,
                left,
                top,
                width,
                height,
            ),
        )
        if (Utilities.ATLEAST_T) {
            options.splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_ICON
        }
        options.launchDisplayId = if (v.display != null) v.display.displayId else Display.DEFAULT_DISPLAY
        val callback = RunnableList()
        return ActivityOptionsWrapper(options, callback)
    }

    override fun onResume() {
        super.onResume()
        restartIfPending()
        refreshPredictionContainersFromModel()

        dragLayer.viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                private var handled = false

                override fun onDraw() {
                    if (handled) {
                        return
                    }
                    handled = true

                    dragLayer.post {
                        dragLayer.viewTreeObserver.removeOnDrawListener(this)
                        // Drop stuck All Apps RenderEffect on icons after returning home.
                        depthController.clearStuckBlurOnResumeIfHome()
                        // SPIKE (owner 2026-08-25): WP-style home reveal. No-op unless
                        // AresHomeReveal.enabled. Only on a real home APPEARANCE from another app
                        // (the activity was stopped and came back) -- not while already on the
                        // launcher (owner: don't animate if already home).
                        if (aresWasStopped) {
                            aresWasStopped = false
                            // Home scroll reset (owner 2026-08-25): if the launcher was gone long
                            // enough to count as "left for an app" (>= ARES_HOME_TOP_RESET_MS), come
                            // back at the TOP of the vertical list rather than wherever it was
                            // scrolled to. A quick there-and-back (tapped an app, swiped straight
                            // home) stays put, so an accidental launch returns you where you were.
                            // Scroll first, then post the reveal so its rise plays on the top rows
                            // that the reset just brought into view (scrollToPosition requestLayouts
                            // async, so the post lets that pass land before the reveal reads tiles).
                            val list = workspace?.aresHomeList
                            val bg = android.os.SystemClock.elapsedRealtime() - aresStoppedAt
                            if (list != null && bg >= ARES_HOME_TOP_RESET_MS) {
                                // Return to the TOP of the list on a real return from an app
                                // (owner 2026-08-25) -- but INVISIBLY. A bare scrollToPosition(0)
                                // snaps the content from wherever it was scrolled up to the top in
                                // the ~3 frames before the posted reveal displaces the tiles, and
                                // that instant mid-list -> top content jump reads as a jarring
                                // "reload/refresh without animation" (owner 2026-08-28). So hide the
                                // list across the scroll + relayout; the reveal then rises the tiles
                                // in from off-screen, so the jump is never drawn -- the user just
                                // sees the wave land on the top rows. alpha is restored in a finally
                                // so a disabled or empty reveal can never leave the grid blank.
                                list.scrollToPosition(0)
                                list.alpha = 0f
                                list.post {
                                    try {
                                        app.lawnchair.areslauncher.AresHomeReveal
                                            .maybePlayOnHomeAppear(this@LawnchairLauncher)
                                    } finally {
                                        // Restore on the NEXT frame, not synchronously: when a WP
                                        // folder was left expanded, play() defers its off-screen
                                        // tile-bunching to its OWN list.post (collapseWpFolderImmediate),
                                        // so a synchronous alpha=1 here would show one frame of the
                                        // settled/collapsed top-of-grid before the reveal starts -- the
                                        // jump this whole branch exists to hide (nightly review
                                        // 2026-08-29, finding 1). This post is queued AFTER the reveal's
                                        // deferred playInner post, so alpha is restored once the tiles
                                        // are already off-screen. On the non-folder path playInner ran
                                        // synchronously, so the tiles are bunched before this fires too.
                                        // Still unconditional, so a disabled/empty/throwing reveal can
                                        // never leave the grid blank.
                                        list.post { list.alpha = 1f }
                                    }
                                }
                            } else {
                                app.lawnchair.areslauncher.AresHomeReveal
                                    .maybePlayOnHomeAppear(this@LawnchairLauncher)
                            }
                        }
                    }
                }
            },
        )
    }

    /**
     * True between an [onStop] and the next [onResume] -- i.e. the launcher was fully backgrounded
     * (an app covered it) and is now coming back. Used to fire the home reveal only on a real return
     * from an app, not on an in-place resume while already on the launcher. See onResume above.
     */
    private var aresWasStopped = false

    /**
     * Wall-clock time ([android.os.SystemClock.elapsedRealtime]) the launcher was last backgrounded,
     * so the next [onResume] can tell a quick there-and-back (came home within
     * [ARES_HOME_TOP_RESET_MS]) from a real absence in an app. See onResume: a quick return keeps
     * the home list where it was; a longer one resets it to the top (owner 2026-08-25).
     *
     * elapsedRealtime, not uptimeMillis: uptime freezes during deep sleep, so a long screen-off with
     * the launcher backgrounded would read as a sub-5s "quick return" and wrongly keep the old
     * scroll position. elapsedRealtime counts wall time through sleep (adversarial review
     * 2026-08-25, Finding 5).
     */
    private var aresStoppedAt = 0L

    override fun onStop() {
        super.onStop()
        aresWasStopped = true
        aresStoppedAt = android.os.SystemClock.elapsedRealtime()
        // Leave edit mode when the SCREEN turns off / device locks (owner 2026-08-31) -- so the
        // launcher doesn't come back into edit mode after unlock. Gated on !isInteractive so this
        // fires for a screen-off/lock but NOT for merely switching to another app or recents (where
        // the screen stays on and leaving edit mode would be surprising). exitEditMode is a no-op
        // when not editing.
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!interactive) {
            workspace?.aresHomeList?.exitEditMode()
        }
    }

    override fun onStateSetEnd(state: LauncherState) {
        super.onStateSetEnd(state)
        refreshPredictionContainersFromModel()
        // AresLauncher edge-to-edge: the base Launcher re-clips the workspace pager to page bounds
        // at the end of EVERY state transition (setClipChildren(!FLAG_MULTI_PAGE)), which re-clips
        // the home list's edge-to-edge overscan back to the status/nav-bar line after visiting
        // all-apps. Re-assert the unclip here so home content keeps flowing behind the bars. The
        // Ares desktop is a single flattened page (Strategy D), so there is no adjacent page to
        // bleed into. AresHomeListView.onMeasure keeps it unclipped on the first frame and on folds.
        workspace.clipChildren = false
        workspace.clipToPadding = false
    }

    override fun onDestroy() {
        // state-seam P5 / ledger S5: drop any folder-drag state pinned to THIS activity before it
        // goes. These are process-global singletons; a fold recreates the Launcher and, mid-drag,
        // their per-drag terminal callbacks (onDragEnd / preview close) may never fire — leaving a
        // dead Launcher and grid behind, so isActive() stays true and the next folder-exit drag
        // relays into a detached grid, or a ghost icon outlives the activity. Both clears are safe
        // to call when nothing is in flight (guarded / no-op).
        AresFolderExitHandoff.onLauncherDestroyed(this)
        AresDragWatch.onLauncherDestroyed(this)
        AresFolderPreview.onLauncherDestroyed(this)
        super.onDestroy()
        // Only actually closes if required, safe to call if not enabled
        SmartspacerClient.close()
    }

    override fun getDefaultOverlay(): LauncherOverlayManager = defaultOverlay

    fun recreateIfNotScheduled() {
        if (sRestartFlags == 0) {
            recreate()
        }
    }

    private fun restartIfPending() {
        when {
            sRestartFlags and FLAG_RESTART != 0 -> lawnchairApp.restart(false)

            sRestartFlags and FLAG_RECREATE != 0 -> {
                sRestartFlags = 0
                recreate()
            }
        }
    }

    private fun refreshPredictionContainersFromModel() {
        LauncherAppState.getInstance(this).model.loadAsync { dataModel ->
            if (dataModel == null || isDestroyed) return@loadAsync

            val predictedContainers = synchronized(dataModel) {
                listOf(
                    dataModel.itemsIdMap[CONTAINER_ALL_APPS_PREDICTION] as? PredictedContainerInfo,
                    dataModel.itemsIdMap[CONTAINER_HOTSEAT_PREDICTION] as? PredictedContainerInfo,
                    dataModel.itemsIdMap[CONTAINER_WIDGETS_PREDICTION] as? PredictedContainerInfo,
                ).filterNotNull()
            }

            Executors.MAIN_EXECUTOR.execute {
                if (isDestroyed) return@execute
                predictedContainers.forEach(::bindPredictedContainerInfo)
            }
        }
    }

    /**
     * Reloads app icons if there is an active icon pack & [PreferenceManager2.alwaysReloadIcons] is enabled.
     */
    private fun reloadIconsIfNeeded() {
        if (
            preferenceManager2.alwaysReloadIcons.firstCached()
        ) {
            LauncherAppState.getInstance(this).model.reloadIfActive()
        }
    }

    companion object {
        private const val FLAG_RECREATE = 1 shl 0
        private const val FLAG_RESTART = 1 shl 1

        // §9 app-list wallpaper dim fade timings. Fade-in shortened to darken slightly faster on
        // arrival (owner 2026-08-31); fade-out kept at the original gentle 2s.
        private const val APP_LIST_DIM_FADE_IN_MS = 1500L
        private const val APP_LIST_DIM_FADE_OUT_MS = 2000L

        // How long the launcher must have been backgrounded for a return to reset the home list to
        // the top (owner 2026-08-25, "after 5 seconds of being in an app... takes them back to the
        // top"). Under this, a there-and-back keeps the prior scroll position. One knob to tune.
        private const val ARES_HOME_TOP_RESET_MS = 5000L

        var sRestartFlags = 0

        val instance get() = LawnchairApp.launcher
    }
}

val Context.launcher: LawnchairLauncher
    get() = BaseActivity.fromContext(this)

val Context.launcherNullable: LawnchairLauncher? get() = try {
    launcher
} catch (_: IllegalArgumentException) {
    null
}
