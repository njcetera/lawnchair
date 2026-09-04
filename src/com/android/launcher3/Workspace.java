/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications copyright 2025, Lawnchair
 */

package com.android.launcher3;

import static com.android.launcher3.AbstractFloatingView.TYPE_WIDGET_RESIZE_FRAME;
import static com.android.launcher3.BubbleTextView.DISPLAY_FOLDER;
import static com.android.launcher3.LauncherAnimUtils.SPRING_LOADED_EXIT_DELAY;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.EDIT_MODE;
import static com.android.launcher3.LauncherState.FLAG_MULTI_PAGE;
import static com.android.launcher3.LauncherState.FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED;
import static com.android.launcher3.LauncherState.FLAG_WORKSPACE_INACCESSIBLE;
import static com.android.launcher3.LauncherState.HINT_STATE;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.SPRING_LOADED;
import static com.android.launcher3.MotionEventsUtils.isTrackpadMultiFingerSwipe;
import static com.android.launcher3.anim.AnimatorListeners.forSuccessCallback;
import static com.android.launcher3.config.FeatureFlags.FOLDABLE_SINGLE_PAGE;
import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_HOME;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SWIPELEFT;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_SWIPERIGHT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.ViewCompat;

import com.android.app.animation.Interpolators;
import com.android.launcher3.accessibility.AccessibleDragListenerAdapter;
import com.android.launcher3.accessibility.WorkspaceAccessibilityHelper;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.apppairs.AppPairIcon;
import com.android.launcher3.celllayout.CellInfo;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.celllayout.CellPosMapper;
import com.android.launcher3.celllayout.CellPosMapper.CellPos;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.debug.TestEventEmitter;
import com.android.launcher3.debug.TestEventEmitter.TestEvent;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.dragndrop.DragView;
import com.android.launcher3.dragndrop.DraggableView;
import com.android.launcher3.dragndrop.SpringLoadedDragController;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.folder.PreviewBackground;
import com.android.launcher3.graphics.DragPreviewProvider;
import com.android.launcher3.icons.BitmapRenderer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.logger.LauncherAtom;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.logging.StatsLogManager.LauncherEvent;
import com.android.launcher3.model.data.AppPairInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pageindicators.PageIndicator;
import com.android.launcher3.statemanager.StateManager;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.touch.WorkspaceTouchListener;
import com.android.launcher3.util.EdgeEffectCompat;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSet;
import com.android.launcher3.util.IntSparseArrayMap;
import com.android.launcher3.util.LauncherBindableItemsContainer;
import com.android.launcher3.util.MSDLPlayerWrapper;
import com.android.launcher3.util.OverlayEdgeEffect;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.Thunk;
import com.android.launcher3.util.WallpaperOffsetInterpolator;
import com.android.launcher3.widget.LauncherAppWidgetHostView;
import com.android.launcher3.widget.NavigableAppWidgetHostView;
import com.android.launcher3.widget.PendingAddShortcutInfo;
import com.android.launcher3.widget.PendingAddWidgetInfo;
import com.android.launcher3.widget.util.WidgetSizes;
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks;
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayTouchProxy;

import com.google.android.msdl.data.model.MSDLToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import app.lawnchair.areslauncher.AresPanelAllAppsContainerView;
import app.lawnchair.areslauncher.AresFolderDrag;
import app.lawnchair.areslauncher.AresFolderDrop;
import app.lawnchair.areslauncher.AresHomeDrop;
import app.lawnchair.areslauncher.AresHomeDropPreview;
import app.lawnchair.areslauncher.AresFolderHeal;
import app.lawnchair.areslauncher.AresHomeListView;
import app.lawnchair.hotseat.HotseatPagedView;
import app.lawnchair.preferences2.PreferenceCacheExtensionsKt;
import static app.lawnchair.util.LawnchairUtilsKt.toBitmap;
import app.lawnchair.LawnchairApp;
import app.lawnchair.LawnchairAppKt;
import app.lawnchair.preferences.PreferenceManager;
import app.lawnchair.preferences2.PreferenceManager2;
import app.lawnchair.smartspace.DoubleShadowTextView;
import app.lawnchair.smartspace.SmartspaceAppWidgetProvider;
import app.lawnchair.smartspace.model.LawnchairSmartspace;
import app.lawnchair.smartspace.model.SmartspaceMode;
import app.lawnchair.theme.drawable.DrawableTokens;
import app.lawnchair.util.LawnchairUtilsKt;

/**
 * The workspace is a wide area with a wallpaper and a finite number of pages.
 * Each page contains a number of icons, folders or widgets the user can
 * interact with. A workspace is meant to be used with a fixed width only.
 *
 * @param <T> Class that extends View and PageIndicator
 */
public class Workspace<T extends View & PageIndicator> extends PagedView<T>
        implements DropTarget, DragSource, View.OnTouchListener, CellLayoutContainer,
        DragController.DragListener, Insettable, StateHandler<LauncherState>,
        WorkspaceLayoutManager, LauncherBindableItemsContainer, LauncherOverlayCallbacks {

    /**
     * The value that {@link #mTransitionProgress} must be greater than for
     * {@link #transitionStateShouldAllowDrop()} to return true.
     */
    private static final float ALLOW_DROP_TRANSITION_PROGRESS = 0.25f;

    /**
     * The value that {@link #mTransitionProgress} must be greater than for
     * {@link #isFinishedSwitchingState()} ()} to return true.
     */
    private static final float FINISHED_SWITCHING_STATE_TRANSITION_PROGRESS = 0.5f;

    private static final float SIGNIFICANT_MOVE_SCREEN_WIDTH_PERCENTAGE = 0.15f;

    private static final boolean ENFORCE_DRAG_EVENT_ORDER = false;

    private static final int ADJACENT_SCREEN_DROP_DURATION = 300;

    public static final int DEFAULT_PAGE = 0;

    private int mAllAppsIconSize;

    private LayoutTransition mLayoutTransition;
    @Thunk
    final WallpaperManager mWallpaperManager;

    protected ShortcutAndWidgetContainer mDragSourceInternal;

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    @Thunk
    public final IntSparseArrayMap<CellLayout> mWorkspaceScreens = new IntSparseArrayMap<>();

    @Thunk
    final IntArray mScreenOrder = new IntArray();

    /**
     * AresLauncher Strategy D: CONTAINER_DESKTOP items are redirected here instead of
     * into a CellLayout grid cell. Lazily created on first use. See
     * design/vertical-home-strategies.md and design/component-verification-1.md.
     */
    private AresHomeListView mAresHomeList;

    /**
     * AresLauncher foldable dual-pane: the persistent app list occupying panel 1 while unfolded.
     *
     * <p>Inflated once and reused for the activity's lifetime -- it is DETACHED while folded, never
     * nulled, so its own AllAppsStore can keep being fed and the next unfold is instant. See
     * {@link #aresEnsureAppListPaneInflated()}, {@link #syncAresAppListPane()} and
     * design/foldable-dual-pane.md.
     */
    private AresPanelAllAppsContainerView mAresAppList;

    /** True while the pane is lifted out by a temporary detach; see removeAllWorkspaceScreens. */
    private boolean mAresAppListTempDetached = false;

    @Thunk
    boolean mDeferRemoveExtraEmptyScreen = false;

    /**
     * CellInfo for the cell that is currently being dragged
     */
    protected CellInfo mDragInfo;

    /**
     * Target drop area calculated during last acceptDrop call.
     */
    @Thunk
    int[] mTargetCell = new int[2];
    private int mDragOverX = -1;
    private int mDragOverY = -1;

    /**
     * The CellLayout that is currently being dragged over
     */
    @Thunk
    CellLayout mDragTargetLayout = null;
    /**
     * The CellLayout that we will show as highlighted
     */
    private CellLayout mDragOverlappingLayout = null;

    /**
     * The CellLayout which will be dropped to
     */
    private CellLayout mDropToLayout = null;

    @Thunk
    final Launcher mLauncher;
    @Thunk
    DragController mDragController;

    protected final int[] mTempXY = new int[2];
    private final float[] mTempFXY = new float[2];
    private final Rect mTempRect = new Rect();
    @Thunk
    float[] mDragViewVisualCenter = new float[2];

    private SpringLoadedDragController mSpringLoadedDragController;

    private boolean mIsSwitchingState = false;

    boolean mChildrenLayersEnabled = true;

    private boolean mStripScreensOnPageStopMoving = false;

    private boolean mDeferStripEmptyScreensForScreenRemap = false;
    public boolean mHasOnLayoutBeenCalled = false;

    private boolean mWorkspaceFadeInAdjacentScreens;

    final WallpaperOffsetInterpolator mWallpaperOffset;
    private boolean mUnlockWallpaperFromDefaultPageOnLayout;

    public static final int REORDER_TIMEOUT = 650;
    protected final Alarm mReorderAlarm = new Alarm();
    private PreviewBackground mFolderCreateBg;
    /** The underlying view that we are dragging something over. */
    private View mDragOverView = null;
    private FolderIcon mDragOverFolderIcon = null;
    private boolean mCreateUserFolderOnDrop = false;
    private boolean mAddToExistingFolderOnDrop = false;
    private boolean mDisallowPagedViewInterceptForIconSwipe = false;

    // Variables relating to touch disambiguation (scrolling workspace vs. scrolling a widget)
    private float mXDown;
    private float mYDown;
    private View mFirstPagePinnedItem;
    private boolean mIsEventOverFirstPagePinnedItem;

    final static float START_DAMPING_TOUCH_SLOP_ANGLE = (float) Math.PI / 6;
    final static float MAX_SWIPE_ANGLE = (float) Math.PI / 3;
    final static float TOUCH_SLOP_DAMPING_FACTOR = 4;

    // Relating to the animation of items being dropped externally
    public static final int ANIMATE_INTO_POSITION_AND_DISAPPEAR = 0;
    public static final int ANIMATE_INTO_POSITION_AND_REMAIN = 1;
    public static final int ANIMATE_INTO_POSITION_AND_RESIZE = 2;
    public static final int COMPLETE_TWO_STAGE_WIDGET_DROP_ANIMATION = 3;
    public static final int CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION = 4;

    // Related to dragging, folder creation and reordering
    private static final int DRAG_MODE_NONE = 0;
    private static final int DRAG_MODE_CREATE_FOLDER = 1;
    private static final int DRAG_MODE_ADD_TO_FOLDER = 2;
    private static final int DRAG_MODE_REORDER = 3;
    protected int mDragMode = DRAG_MODE_NONE;
    @Thunk
    int mLastReorderX = -1;
    @Thunk
    int mLastReorderY = -1;

    private SparseArray<Parcelable> mSavedStates;
    private final IntArray mRestoredPages = new IntArray();

    private float mCurrentScale;
    private float mTransitionProgress;

    // State related to Launcher Overlay
    private OverlayEdgeEffect mOverlayEdgeEffect;
    private boolean mOverlayShown = false;
    private float mOverlayProgress; // 1 -> overlay completely visible, 0 -> home visible
    private final List<LauncherOverlayCallbacks> mOverlayCallbacks = new ArrayList<>();

    private boolean mForceDrawAdjacentPages = false;

    // Handles workspace state transitions
    private final WorkspaceStateTransitionAnimation mStateTransitionAnimation;

    private final StatsLogManager mStatsLogManager;

    private final MSDLPlayerWrapper mMSDLPlayerWrapper;

    private final StateManager.StateListener<LauncherState> mAccessibilityDropListener =
            new StateListener<>() {
                @Override
                public void onStateTransitionComplete(LauncherState finalState) {
                    if (finalState == NORMAL) {
                        performAccessibilityActionOnViewTree(Workspace.this);
                    }
                }
            };

    @Nullable
    private DragController.DragListener mAccessibilityDragListener;

    PreferenceManager2 mPreferenceManager2;
    PreferenceManager mPreferenceManger;

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context The application's context.
     * @param attrs   The attributes set containing the Workspace's customization values.
     */
    public Workspace(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Used to inflate the Workspace from XML.
     *
     * @param context  The application's context.
     * @param attrs    The attributes set containing the Workspace's customization values.
     * @param defStyle Unused.
     */
    public Workspace(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mLauncher = Launcher.getLauncher(context);
        mStateTransitionAnimation = new WorkspaceStateTransitionAnimation(mLauncher, this);
        mWallpaperManager = WallpaperManager.getInstance(context);
        mAllAppsIconSize = mLauncher.getDeviceProfile().getAllAppsProfile().getIconSizePx();
        mPreferenceManager2 = PreferenceManager2.getInstance(context);
        mPreferenceManger = PreferenceManager.getInstance(context);
        mWallpaperOffset = new WallpaperOffsetInterpolator(this);

        setHapticFeedbackEnabled(false);
        initWorkspace();

        // Disable multitouch across the workspace/all apps/customize tray
        setMotionEventSplittingEnabled(true);
        setOnTouchListener(new WorkspaceTouchListener(mLauncher, this));
        mStatsLogManager = StatsLogManager.newInstance(context);
        mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context);
    }

    @Override
    public void setInsets(Rect insets) {
        DeviceProfile grid = mLauncher.getDeviceProfile();

        mWorkspaceFadeInAdjacentScreens = grid.shouldFadeAdjacentWorkspaceScreens();

        Rect padding = grid.workspacePadding;
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
        mInsets.set(insets);

        if (mWorkspaceFadeInAdjacentScreens) {
            // In landscape mode the page spacing is set to the default.
            setPageSpacing(grid.edgeMarginPx);
        } else {
            // In portrait, we want the pages spaced such that there is no
            // overhang of the previous / next page into the current page viewport.
            // We assume symmetrical padding in portrait mode.
            int maxInsets = Math.max(insets.left, insets.right);
            int maxPadding = Math.max(grid.edgeMarginPx, padding.left + 1);
            setPageSpacing(Math.max(maxInsets, maxPadding));
        }

        updateCellLayoutMeasures();
        updateWorkspaceWidgetsSizes();
        setPageIndicatorInset();
        // AresLauncher: a live fold/unfold reaches us here, so this is where the dual-pane app list
        // attaches or detaches -- but it must NOT run inline. We are inside LauncherRootView's
        // recursive inset dispatch, and attaching/detaching the pane fires its
        // onAttachedToWindow/onDetachedFromWindow, which add and remove the floating search pill
        // from the shared DragLayer. Mutating DragLayer's children while DragLayer is mid-walk
        // over those same children crashed the launcher on every fold (device-confirmed:
        // "NULL CHILD at i=11, startCount=12, nowCount=11"). Deferring past the dispatch keeps the
        // view tree stable for the duration of the walk.
        postSyncAresDualPane();
    }

    /** See the deferral note in {@link #setInsets(Rect)}. */
    private final Runnable mSyncAresDualPane = this::syncAresDualPane;

    /**
     * AresLauncher §22: re-establishes the dual-pane invariant off the current call stack.
     *
     * <p>Always deferred, never called inline. Re-anchoring re-parents the pane, which fires its
     * attach/detach hooks and those mutate the shared DragLayer's children; doing that from inside
     * another walk over the view tree is what crashed every fold before (see the note in
     * {@link #setInsets(Rect)}). {@link #syncAresDualPane()} is idempotent, so collapsing repeat
     * posts is both safe and cheaper than re-parenting several times per bind.
     */
    private void postSyncAresDualPane() {
        removeCallbacks(mSyncAresDualPane);
        post(mSyncAresDualPane);
    }

    /**
     * AresLauncher (owner 2026-09-01): re-run the dual-pane sync after a BIND completes.
     *
     * <p>An unfold triggers a rebind, and {@link #setInsets(Rect)} posts {@link #syncAresDualPane()}
     * while that load is still in flight -- so {@link #pruneAresStrayPages()} hits its
     * {@code isWorkspaceLoading()} guard and bails, and nothing re-prunes once the load finishes.
     * The widget-bearing screens (whose content Strategy D flattens into the home list, leaving the
     * page itself empty) therefore survive as blank pages to the right unfolded. Re-posting the
     * idempotent sync here, from {@code finishBindingItems}, runs prune on the settled grid.
     */
    public void aresResyncDualPaneAfterBind() {
        postSyncAresDualPane();
    }

    /**
     * AresLauncher §22: the complete unfolded dual-pane invariant, re-asserted from scratch.
     *
     * <p>Three things must hold together, and asserting only some of them is what left the user
     * looking at four panels with the app list three screens away:
     *
     * <ol>
     *   <li>the home list is on workspace child 0,
     *   <li>the app-list pane is on workspace child 1 -- the right-hand panel of the <em>first</em>
     *       page-pair, which is the one actually on screen,
     *   <li>no pages exist beyond that pair.
     * </ol>
     *
     * <p>Order matters: the panes have to be re-anchored <em>before</em> the prune, because a stray
     * page holding the pane does not look empty and would survive the sweep.
     */
    private void syncAresDualPane() {
        reanchorAresHomeList();
        syncAresAppListPane();
        pruneAresStrayPages();
        leaveAllAppsWhileThePaneIsPersistent();
        updateAresHotseatVisibility();
    }

    /**
     * Hide the hotseat (dock) when unfolded (owner 2026-08-25). The dual-pane layout keeps the app
     * list one swipe away, so the dock is redundant on the large screen; the folded posture keeps
     * it. Runs from {@link #syncAresDualPane()}, which {@link #setInsets(Rect)} posts on every fold
     * and unfold, so it re-applies on each posture change. State transitions animate the hotseat's
     * alpha, not its visibility, so GONE/VISIBLE here does not fight them.
     */
    private void updateAresHotseatVisibility() {
        Hotseat hotseat = mLauncher.getHotseat();
        if (hotseat != null) {
            hotseat.setVisibility(isTwoPanelEnabled() ? GONE : VISIBLE);
        }
    }

    /**
     * Drops out of {@link LauncherState#ALL_APPS} once the app list is a persistent panel.
     *
     * <p>The two app-list models collide on a live unfold. Folded, the app list <em>is</em> the
     * ALL_APPS state -- an overlay the §9 pane swipe drives. Unfolded, it is an ordinary child of
     * workspace panel 1 that is always visible. Unfold while the state is ALL_APPS and you get
     * both at once: the user reported "a second app list displayed over the home screen on the
     * unfolded display... I need to do a back gesture to remove the extra app list". BACK exits the
     * state, which is why the duplicate does not return until the next fold/unfold.
     *
     * <p>Unfolded the state carries no information -- the pane is visible either way -- so it can
     * only ever duplicate the pane, and leaving it is unconditionally right. Not animated: this is
     * correcting a state that should never have survived the posture change, not performing a
     * transition the user asked for.
     *
     * <p>Gated on the pane actually being attached, not merely on two-panel mode, so a bind that
     * has not reached panel 1 yet cannot leave the user looking at neither surface. It runs from
     * {@link #syncAresDualPane()} for the same reason everything else there does: {@link
     * #setInsets(Rect)} posts that sweep on every fold and unfold, and it must not run inline
     * inside the inset dispatch.
     */
    private void leaveAllAppsWhileThePaneIsPersistent() {
        if (!isTwoPanelEnabled() || mAresAppList == null || mAresAppList.getParent() == null) {
            return;
        }
        if (!mLauncher.isInState(ALL_APPS)) {
            return;
        }
        mLauncher.getStateManager().goToState(NORMAL, false /* animated */);
    }

    /**
     * Puts the home list back on workspace child 0 if something moved the pages under it.
     *
     * <p>{@link #getOrCreateAresHomeList()} already pins it there, but only runs while items are
     * being bound. {@link #applyScreenOrderToChildViews()} re-sorts the child views afterwards, so
     * without this the home list follows its old page to wherever the persisted screen order puts
     * it. Deliberately does not create the list -- a workspace with no desktop items should stay
     * that way.
     */
    private void reanchorAresHomeList() {
        if (mAresHomeList != null) {
            getOrCreateAresHomeList();
        }
    }

    /**
     * Drops workspace pages beyond the first panel pair while the dual pane is up.
     *
     * <p>Strategy D flattens every CONTAINER_DESKTOP item into the single home list on child 0, so
     * a logical screen carries no content of its own -- the model's screen ids exist only to keep
     * the loader's cellX/cellY validation happy, and {@link
     * app.lawnchair.areslauncher.AresWidgetAdd#findFreeCell} mints a fresh one whenever the current
     * ones are full. Every screen past the first therefore renders as a blank page that the user
     * can swipe to, and in two-panel mode each one is <em>doubled</em> into a pair.
     *
     * <p>Stock's own sweep cannot do this. {@link #stripEmptyScreens()} deliberately preserves any
     * empty page whose id is in the persisted screen order, and {@link
     * #convertFinalScreenToEmptyScreenIfNecessary()} gives up entirely as soon as the last page is
     * non-empty -- which the misplaced pane made true, so the strays it created also protected
     * themselves. Both behaviours are correct for stock, where an empty page is a place the user
     * put nothing yet; here there is no such thing.
     *
     * <p>Scoped to two-panel mode on purpose: folded page bookkeeping is reported working and is
     * left exactly as it was.
     */
    private void pruneAresStrayPages() {
        if (mAresHomeList == null || !isTwoPanelEnabled() || mLauncher.isWorkspaceLoading()) {
            return;
        }
        int keep = getPanelCount();
        if (getChildCount() <= keep) {
            return;
        }
        boolean removedAny = false;
        // Back to front: removing a child shifts every index above it.
        for (int i = getChildCount() - 1; i >= keep; i--) {
            CellLayout page = (CellLayout) getChildAt(i);
            // Never strip a page that is holding something. After the re-anchoring above this can
            // only be a genuine grid item, but a widget mid-drop would qualify too and must be left
            // alone.
            if (page.getShortcutsAndWidgets().getChildCount() != 0) {
                continue;
            }
            int screenId = getCellLayoutId(page);
            if (screenId == -1 || screenId == FIRST_SCREEN_ID) {
                continue;
            }
            mWorkspaceScreens.remove(screenId);
            mScreenOrder.removeValue(screenId);
            removeView(page);
            removedAny = true;
        }
        if (!removedAny) {
            return;
        }
        if (getNextPage() >= getChildCount()) {
            setCurrentPage(0);
        }
        updateAccessibilityViewPageDescription();
        showPageIndicatorAtCurrentScroll();
        // Persist the shortened order, otherwise the stale one is merged back in by
        // WorkspaceData.collectWorkspaceScreens and the strays return on the next bind.
        persistCurrentScreenOrderSync();
    }

    private void setPageIndicatorInset() {
        DeviceProfile grid = mLauncher.getDeviceProfile();

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPageIndicator.getLayoutParams();

        // Set insets for page indicator
        Rect padding = grid.workspacePadding;
        if (grid.isVerticalBarLayout()) {
            lp.leftMargin = padding.left + grid.workspaceCellPaddingXPx;
            lp.rightMargin = padding.right + grid.workspaceCellPaddingXPx;
            lp.bottomMargin = padding.bottom;
        } else {
            lp.leftMargin = lp.rightMargin = 0;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            lp.bottomMargin = grid.hotseatBarSizePx - grid.workspaceCellPaddingXPx;
        }
        mPageIndicator.setLayoutParams(lp);

        // AresLauncher §10 (no dock): showPageIndicatorAtCurrentScroll() already gates the
        // indicator on this same preference, but it only fires while scrolling -- and a
        // single-page Strategy D workspace never scrolls, so the indicator would stay visible
        // forever. Apply the same gate here, on a path that always runs.
        var isHotseatEnabled = PreferenceCacheExtensionsKt.firstCached(
                mPreferenceManager2.isHotseatEnabled());
        mPageIndicator.setVisibility(isHotseatEnabled ? VISIBLE : INVISIBLE);
    }

    private void updateCellLayoutMeasures() {
        Rect padding = mLauncher.getDeviceProfile().cellLayoutPaddingPx;
        mWorkspaceScreens.forEach(cellLayout -> {
            cellLayout.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            cellLayout.setSpaceBetweenCellLayoutsPx(getPageSpacing() / 4);
        });
    }

    private void updateWorkspaceWidgetsSizes() {
        int numberOfScreens = mScreenOrder.size();
        for (int i = 0; i < numberOfScreens; i++) {
            ShortcutAndWidgetContainer shortcutAndWidgetContainer = mWorkspaceScreens.get(mScreenOrder.get(i))
                    .getShortcutsAndWidgets();
            int shortcutsAndWidgetCount = shortcutAndWidgetContainer.getChildCount();
            for (int j = 0; j < shortcutsAndWidgetCount; j++) {
                View view = shortcutAndWidgetContainer.getChildAt(j);
                if (view instanceof LauncherAppWidgetHostView
                        && view.getTag() instanceof LauncherAppWidgetInfo) {
                    LauncherAppWidgetInfo launcherAppWidgetInfo =
                            (LauncherAppWidgetInfo) view.getTag();
                    WidgetSizes.updateWidgetSizeRanges((LauncherAppWidgetHostView) view,
                            mLauncher, launcherAppWidgetInfo.spanX, launcherAppWidgetInfo.spanY);
                }
            }
        }
    }

    /**
     * Estimates the size of an item using spans: hSpan, vSpan.
     *
     * @return MAX_VALUE for each dimension if unsuccessful.
     */
    public int[] estimateItemSize(ItemInfo itemInfo) {
        int[] size = new int[2];
        if (getChildCount() > 0) {
            // Use the first page to estimate the child position
            CellLayout cl = (CellLayout) getChildAt(0);
            boolean isWidget = itemInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET;

            Rect r = estimateItemPosition(cl, 0, 0, itemInfo.spanX, itemInfo.spanY);

            float scale = 1;
            if (isWidget) {
                DeviceProfile profile = mLauncher.getDeviceProfile();
                final PointF appWidgetScale = profile.getAppWidgetScale(null);
                scale = Utilities.shrinkRect(r, appWidgetScale.x, appWidgetScale.y);
            }
            size[0] = r.width();
            size[1] = r.height();

            if (isWidget) {
                size[0] /= scale;
                size[1] /= scale;
            }
            return size;
        } else {
            size[0] = Integer.MAX_VALUE;
            size[1] = Integer.MAX_VALUE;
            return size;
        }
    }

    public float getWallpaperOffsetForCenterPage() {
        return getWallpaperOffsetForPage(getPageNearestToCenterOfScreen());
    }

    private float getWallpaperOffsetForPage(int page) {
        int pageScroll = getScrollForPage(page);
        return mWallpaperOffset.wallpaperOffsetForScroll(pageScroll);
    }

    /**
     * Returns the number of pages used for the wallpaper parallax.
     */
    public int getNumPagesForWallpaperParallax() {
        return mWallpaperOffset.getNumPagesForWallpaperParallax();
    }

    public Rect estimateItemPosition(CellLayout cl, int hCell, int vCell, int hSpan, int vSpan) {
        Rect r = new Rect();
        if (cl == null)
            return r;
        cl.cellToRect(hCell, vCell, hSpan, vSpan, r);
        return r;
    }

    @Override
    public void onDragStart(DragObject dragObject, DragOptions options) {
        if (ENFORCE_DRAG_EVENT_ORDER) {
            enforceDragParity("onDragStart", 0, 0);
        }

        if (mDragInfo != null && mDragInfo.cell != null) {
            CellLayout layout = (CellLayout) (mDragInfo.cell instanceof LauncherAppWidgetHostView
                    // LC: https://github.com/LawnchairLauncher/lawnchair/issues/3143
                    && dragObject.dragView.getContentViewParent() != null
                    ? dragObject.dragView.getContentViewParent().getParent()
                    : mDragInfo.cell.getParent().getParent());
            layout.markCellsAsUnoccupiedForView(mDragInfo.cell);
        }

        updateChildrenLayersEnabled();

        // Do not add a new page if it is a accessible drag which was not started by the workspace.
        // We do not support accessibility drag from other sources and instead provide a direct
        // action for move/add to homescreen.
        // When a accessible drag is started by the folder, we only allow rearranging withing the
        // folder.
        // AresLauncher: a drag that came out of one of our open folders must not bring stock's
        // workspace-level drag presentation with it -- no SPRING_LOADED zoom-out, no drop-target
        // bar, and no extra empty page. See AresFolderDrag#isFolderDrag for the measurement and
        // the reasoning; the same predicate gates DropTargetBar.
        boolean aresFolderDrag = AresFolderDrag.isFolderDrag(mLauncher, dragObject.dragSource);

        boolean addNewPage = !(options.isAccessibleDrag && dragObject.dragSource != this)
                && !aresFolderDrag;
        if (addNewPage) {
            mDeferRemoveExtraEmptyScreen = false;
            addExtraEmptyScreenOnDrag(dragObject);

            if (dragObject.dragInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET
                    && dragObject.dragSource != this) {
                // When dragging a widget from different source, move to a page which has
                // enough space to place this widget (after rearranging/resizing). We special case
                // widgets as they cannot be placed inside a folder.
                // Start at the current page and search right (on LTR) until finding a page with
                // enough space. Since an empty screen is the furthest right, a page must be found.
                int currentPage = getDestinationPage();
                for (int pageIndex = currentPage; pageIndex < getPageCount(); pageIndex++) {
                    CellLayout page = (CellLayout) getPageAt(pageIndex);
                    if (page.hasReorderSolution(dragObject.dragInfo)) {
                        setCurrentPage(pageIndex);
                        break;
                    }
                }
            }
        }

        if (mAccessibilityDragListener != null) {
            mAccessibilityDragListener.onDragStart(dragObject, options);
        }
        if (!mLauncher.isInState(EDIT_MODE) && !aresFolderDrag) {
            mLauncher.getStateManager().goToState(SPRING_LOADED);
        }
        mStatsLogManager.logger().withItemInfo(dragObject.dragInfo)
                .withInstanceId(dragObject.logInstanceId)
                .log(LauncherEvent.LAUNCHER_ITEM_DRAG_STARTED);
    }

    private boolean isTwoPanelEnabled() {
        return !FOLDABLE_SINGLE_PAGE.get() && mLauncher.mDeviceProfile.getDeviceProperties().isTwoPanels();
    }

    public void deferRemoveExtraEmptyScreen() {
        mDeferRemoveExtraEmptyScreen = true;
    }

    @Override
    public void onDragEnd() {
        if (ENFORCE_DRAG_EVENT_ORDER) {
            enforceDragParity("onDragEnd", 0, 0);
        }

        updateChildrenLayersEnabled();
        StateManager<LauncherState, Launcher> stateManager = mLauncher.getStateManager();
        stateManager.addStateListener(new StateManager.StateListener<LauncherState>() {
            @Override
            public void onStateTransitionComplete(LauncherState finalState) {
                if (finalState == NORMAL) {
                    if (!mDeferRemoveExtraEmptyScreen) {
                        removeExtraEmptyScreen(true /* stripEmptyScreens */);
                    }
                    stateManager.removeStateListener(this);
                }
            }
        });

        if (mAccessibilityDragListener != null) {
            mAccessibilityDragListener.onDragEnd();
        }
        // AresLauncher: the dwell that arms a folder as a drop target (AresFolderDrop) cannot be
        // torn down in onDragExit -- DragController.drop calls that BEFORE onDrop, so the arm has
        // to survive it. This is the one hook that always runs and always runs last.
        //
        // UNLESS the drag was handed off to the in-grid pipeline (rows 31/32): its commit runs
        // from ItemTouchHelper's clearView ~250ms of settle AFTER this hook, and cancelling here
        // was measured wiping the armed target in that gap -- dwell armed, reflow froze, nothing
        // committed. Every in-grid ending clears the dwell itself.
        if (!app.lawnchair.areslauncher.AresFolderExitHandoff.ownsDwellTeardown()) {
            AresFolderDrop.cancel();
        }
        mDragInfo = null;
        mDragSourceInternal = null;
    }

    /**
     * Initializes various states for this workspace.
     */
    protected void initWorkspace() {
        mCurrentPage = getDefaultPage();
        setClipToPadding(false);

        setupLayoutTransition();

        // Set the wallpaper dimensions when Launcher starts up
        setWallpaperDimension();
    }

    public void updateStatusbarClock() {
        if (mCurrentPage == 0 && PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.getStatusBarClock())) {
            LawnchairAppKt.getLawnchairApp(mLauncher).hideClockInStatusBar();
        } else {
            LawnchairAppKt.getLawnchairApp(mLauncher).restoreClockInStatusBar();
        }
    }

    private void setupLayoutTransition() {
        // We want to show layout transitions when pages are deleted, to close the gap.
        mLayoutTransition = new LayoutTransition();

        mLayoutTransition.enableTransitionType(LayoutTransition.DISAPPEARING);
        mLayoutTransition.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        // Change the interpolators such that the fade animation plays before the move animation.
        // This prevents empty adjacent pages to overlay during animation
        mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING,
                Interpolators.clampToProgress(Interpolators.ACCELERATE_DECELERATE, 0, 0.5f));
        mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING,
                Interpolators.clampToProgress(Interpolators.ACCELERATE_DECELERATE, 0.5f, 1));

        mLayoutTransition.disableTransitionType(LayoutTransition.APPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        setLayoutTransition(mLayoutTransition);
    }

    void enableLayoutTransitions() {
        setLayoutTransition(mLayoutTransition);
    }

    void disableLayoutTransitions() {
        setLayoutTransition(null);
    }

    @Override
    public void onViewAdded(View child) {
        if (!(child instanceof CellLayout)) {
            throw new IllegalArgumentException("A Workspace can only have CellLayout children.");
        }
        CellLayout cl = ((CellLayout) child);
        cl.setOnInterceptTouchListener(this);
        cl.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // AresLauncher: a workspace page is never a real occupancy grid here — Strategy D flattens
        // every desktop item into AresHomeListView, which is itself the page's only child. Stock's
        // drop outline is computed by findNearestArea against that empty grid, so it renders as a
        // rectangle unrelated to where the item lands. Suppressed at the single funnel every
        // workspace page passes through, which covers page 0, the dual-pane panel and any stray.
        cl.setVisualizeDropLocation(false);
        super.onViewAdded(child);
    }

    /**
     * Initializes and binds the first page
     */
    public void bindAndInitFirstWorkspaceScreen() {
        // Add the first page
        CellLayout firstPage = insertNewWorkspaceScreen(Workspace.FIRST_SCREEN_ID, getChildCount());
        if (!PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.getEnableSmartspace())) {
            mFirstPagePinnedItem = null;
            return;
        }
        if (mFirstPagePinnedItem == null) {
            SmartspaceMode smartspaceMode = PreferenceCacheExtensionsKt
                .firstCached(mPreferenceManager2.getSmartspaceMode());
            if (!smartspaceMode.isAvailable(this.mLauncher)) {
                // The current smartspace mode is not available,
                // setting the smartspace mode to one that is always available
                smartspaceMode = LawnchairSmartspace.INSTANCE;
                com.patrykmichalik.opto.core.PreferenceExtensionsKt.setBlocking(mPreferenceManager2.getSmartspaceMode(), smartspaceMode);
            }
            // In transposed layout, we add the first page pinned widget in the Grid.
            // As workspace does not touch the edges, we do not need a full
            // width first page pinned item.
            mFirstPagePinnedItem = LayoutInflater.from(getContext())
                    .inflate(smartspaceMode.getLayoutResourceId(), firstPage, false);
        }

        int cellHSpan = mLauncher.getDeviceProfile().inv.numColumns;
        CellLayoutLayoutParams lp = new CellLayoutLayoutParams(0, 0, cellHSpan, 1);
        lp.canReorder = false;
        if (!firstPage.addViewToCellLayout(
                mFirstPagePinnedItem, 0, R.id.search_container_workspace, lp, true)) {
            Log.e(TAG, "Failed to add to item at (0, 0) to CellLayout");
        }
    }

    public void removeAllWorkspaceScreens() {
        // Disable all layout transitions before removing all pages to ensure that we don't get the
        // transition animations competing with us changing the scroll when we add pages
        disableLayoutTransitions();

        // Recycle the first page pinned item
        if (mFirstPagePinnedItem != null) {
            ((ViewGroup) mFirstPagePinnedItem.getParent()).removeView(mFirstPagePinnedItem);
        }

        // AresLauncher (owner 2026-09-01): lift the home list out BEFORE the pages are destroyed.
        //
        // removeAllViews() recurses dispatchDetachedFromWindow() through every page's subtree, and
        // Strategy D parents the home list inside page 0 -- so every rebind (a fold always triggers
        // one) tore the list off the window and re-homed it. Measured on the Pixel Fold:
        //     AresAttach: DETACHED kids=16 w=1018 / ATTACHED kids=16 w=1018   (10ms apart)
        // The cost is not AppWidgetHostView re-inflating (it has no attach hooks at all) but the
        // generic detach teardown: resetDisplayList() over the whole subtree, plus
        // RecyclerView.onDetachedFromWindow ending animations, nulling the LayoutManager's host and
        // releasing cached views, then a forced full layout on re-attach.
        //
        // A temporary detach avoids all of it while still letting the pages be rebuilt normally --
        // which matters, because the page rebuild is what BUILDS the unfolded dual-pane split. Two
        // earlier attempts RETAINED page 0 instead and broke the split both times (home rendered
        // full-width with the app list overlapping it); stepping out of the rebuild's way is the fix,
        // not fighting it. Re-attached below in this same synchronous block, per the platform contract.
        // The app-list pane takes the same beating: it lives in page 1, which this teardown also
        // destroys, so it was detached and re-attached on every rebind too (measured: PANE DETACHED
        // w=1018 / PANE ATTACHED w=1018, twice per fold) -- the owner's remaining "app list does a
        // short flicker". Lift it out the same way. It cannot be re-attached in this method (page 1 is
        // recreated later in the bind), so syncAresAppListPane() completes the pairing once the panel
        // exists.
        // NOT gated on isTwoPanelEnabled(): during an unfold the DeviceProfile can still report the
        // folded posture while the rebind is already running (measured race -- see
        // AresHomeListView.onMeasure), so gating on it made this silently skip and the pane took a real
        // detach anyway (measured: PANE DETACHED/ATTACHED 1.9s apart).
        if (mAresAppList != null
                && mAresAppList.getParent() instanceof ShortcutAndWidgetContainer) {
            mAresAppListTempDetached = true;
            ((ShortcutAndWidgetContainer) mAresAppList.getParent())
                    .aresDetachChildTemporarily(mAresAppList);
        }

        ShortcutAndWidgetContainer aresListParent = null;
        ViewGroup.LayoutParams aresListLp = null;
        if (mAresHomeList != null
                && mAresHomeList.getParent() instanceof ShortcutAndWidgetContainer) {
            aresListParent = (ShortcutAndWidgetContainer) mAresHomeList.getParent();
            aresListLp = mAresHomeList.getLayoutParams();
            // onDetachedFromWindow will not run now, so do its one piece of real work explicitly.
            mAresHomeList.cancelEmptySpaceLongPressForReparent();
            aresListParent.aresDetachChildTemporarily(mAresHomeList);
        }

        // Remove the pages and clear the screen models
        removeAllViews();
        mScreenOrder.clear();
        mWorkspaceScreens.clear();

        // AresLauncher Strategy D: this only clears CellLayout pages, which no longer hold
        // CONTAINER_DESKTOP items (see addInScreen above). Without this, every full rebind
        // (e.g. triggered by onNewIntent, see ModelCallbacks.startBinding()) re-adds the same
        // items to mAresHomeList's adapter without ever clearing it first, producing duplicate
        // rows.
        if (mAresHomeList != null) {
            mAresHomeList.getAresAdapter().clear();
        }

        // Ensure there is always at least one page during bind lifecycle.
        bindAndInitFirstWorkspaceScreen();

        // Re-attach the lifted home list into the freshly created page, in this SAME synchronous block
        // (see the detach above). It must not be left to getOrCreateAresHomeList(), which runs lazily
        // from addInScreen during item binding -- a later frame, which would leave the list parentless
        // and undrawn for a frame: a guaranteed blank flash. getOrCreateAresHomeList's "re-home if the
        // parent differs" check then becomes a no-op safety net.
        if (aresListParent != null && mAresHomeList.getParent() == null) {
            CellLayout firstPage = getChildCount() > 0 ? (CellLayout) getChildAt(0) : null;
            ShortcutAndWidgetContainer target =
                    firstPage != null ? firstPage.getShortcutsAndWidgets() : null;
            if (target != null) {
                target.aresAttachChildTemporarily(mAresHomeList, aresListLp);
            } else {
                // No page to land on: fall back to the ordinary path so the list can never be
                // stranded parentless. This costs the old detach/attach, which is still better than
                // a missing home grid.
                Log.w(TAG, "Ares: no first page to re-attach the home list; using normal re-home");
                getOrCreateAresHomeList();
            }
        }

        // Re-enable the layout transitions
        enableLayoutTransitions();
    }

    public void insertNewWorkspaceScreenBeforeEmptyScreen(int screenId) {
        // Find the index to insert this view into.  If the empty screen exists, then
        // insert it before that.
        int insertIndex = mScreenOrder.indexOf(EXTRA_EMPTY_SCREEN_ID);
        if (insertIndex < 0) {
            insertIndex = mScreenOrder.size();
        }
        insertNewWorkspaceScreen(screenId, insertIndex);
    }

    public void insertNewWorkspaceScreen(int screenId) {
        insertNewWorkspaceScreen(screenId, getChildCount());
    }

    public CellLayout insertNewWorkspaceScreen(int screenId, int insertIndex) {
        if (mWorkspaceScreens.containsKey(screenId)) {
            Log.w(TAG, "Screen id " + screenId + " already exists, skipping insertion");
            return mWorkspaceScreens.get(screenId);
        }

        // Inflate the cell layout, but do not add it automatically so that we can get the newly
        // created CellLayout.
        DeviceProfile dp = mLauncher.getDeviceProfile();
        CellLayout newScreen;
        if (FOLDABLE_SINGLE_PAGE.get() && dp.getDeviceProperties().isTwoPanels()) {
            newScreen = (CellLayout) LayoutInflater.from(getContext()).inflate(
                    R.layout.workspace_screen_foldable, this, false /* attachToRoot */);
        } else {
            newScreen = (CellLayout) LayoutInflater.from(getContext()).inflate(
                    R.layout.workspace_screen, this, false /* attachToRoot */);
        }
        newScreen.setCellLayoutContainer(this);

        mWorkspaceScreens.put(screenId, newScreen);
        mScreenOrder.add(insertIndex, screenId);
        addView(newScreen, insertIndex);
        mStateTransitionAnimation.applyChildState(
                mLauncher.getStateManager().getState(), newScreen, insertIndex);

        updatePageScrollValues();
        updateCellLayoutMeasures();
        // AresLauncher: panel 1 only exists once a second page has been inserted, so this is the
        // earliest point the dual-pane app list can be attached during a bind. Anchor inline so the
        // pane is never missing for a frame, and post the full sweep so the stray pages this bind is
        // still busy creating get cleaned up once it settles (pruning inline would fight the bind,
        // which inserts screens one at a time).
        syncAresAppListPane();
        postSyncAresDualPane();
        return newScreen;
    }

    private void addExtraEmptyScreenOnDrag(DragObject dragObject) {
        boolean lastChildOnScreen = false;
        boolean childOnFinalScreen = false;

        if (mDragSourceInternal != null) {
            int dragSourceChildCount = mDragSourceInternal.getChildCount();

            // If the icon was dragged from Hotseat, there is no page pair
            if (isTwoPanelEnabled() && !mLauncher.isHotseatLayout(
                    (View) mDragSourceInternal.getParent())) {
                int pagePairScreenId = getScreenPair(getCellPosMapper().mapModelToPresenter(
                        dragObject.dragInfo).screenId);
                CellLayout pagePair = mWorkspaceScreens.get(pagePairScreenId);
                dragSourceChildCount += pagePair.getShortcutsAndWidgets().getChildCount();
            }

            // When the drag view content is a LauncherAppWidgetHostView, we should increment the
            // drag source child count by 1 because the widget in drag has been detached from its
            // original parent, ShortcutAndWidgetContainer, and reattached to the DragView.
            if (dragObject.dragView.getContentView() instanceof LauncherAppWidgetHostView) {
                dragSourceChildCount++;
            }

            if (dragSourceChildCount == 1) {
                lastChildOnScreen = true;
            }
            CellLayout cl = (CellLayout) mDragSourceInternal.getParent();
            if (!FOLDABLE_SINGLE_PAGE.get() && getLeftmostVisiblePageForIndex(indexOfChild(cl))
                    == getLeftmostVisiblePageForIndex(getPageCount() - 1)) {
                childOnFinalScreen = true;
            }
        }

        // If this is the last item on the final screen
        if (lastChildOnScreen && childOnFinalScreen) {
            return;
        }

        forEachExtraEmptyPageId(extraEmptyPageId -> {
            if (!mWorkspaceScreens.containsKey(extraEmptyPageId)) {
                insertNewWorkspaceScreen(extraEmptyPageId);
            }
        });
    }


    /**
     * Returns if the given screenId is already in the Workspace
     */
    public boolean containsScreenId(int screenId) {
        return this.mWorkspaceScreens.containsKey(screenId);
    }

    /**
     * Inserts extra empty pages to the end of the existing workspaces.
     * Usually we add one extra empty screen, but when two panel home is enabled we add
     * two extra screens.
     **/
    public void addExtraEmptyScreens() {
        forEachExtraEmptyPageId(extraEmptyPageId -> {
            if (!mWorkspaceScreens.containsKey(extraEmptyPageId)) {
                insertNewWorkspaceScreen(extraEmptyPageId);
            }
        });
    }

    /**
     * Calls the consumer with all the necessary extra empty page IDs.
     * On a normal one panel Workspace that means only EXTRA_EMPTY_SCREEN_ID,
     * but in a two panel scenario this also includes EXTRA_EMPTY_SCREEN_SECOND_ID.
     */
    private void forEachExtraEmptyPageId(Consumer<Integer> callback) {
        callback.accept(EXTRA_EMPTY_SCREEN_ID);
        if (isTwoPanelEnabled()) {
            callback.accept(EXTRA_EMPTY_SCREEN_SECOND_ID);
        }
    }

    /**
     * If two panel home is enabled we convert the last two screens that are visible at the same
     * time. In other cases we only convert the last page.
     */
    private void convertFinalScreenToEmptyScreenIfNecessary() {
        if (mLauncher.isWorkspaceLoading()) {
            // Invalid and dangerous operation if workspace is loading
            return;
        }

        int panelCount = getPanelCount();
        if (hasExtraEmptyScreens() || mScreenOrder.size() < panelCount) {
            return;
        }

        SparseArray<CellLayout> finalScreens = new SparseArray<>();

        int pageCount = mScreenOrder.size();
        // First we add the last page(s) to the finalScreens collection. The number of final pages
        // depends on the panel count.
        for (int pageIndex = pageCount - panelCount; pageIndex < pageCount; pageIndex++) {
            int screenId = mScreenOrder.get(pageIndex);
            CellLayout screen = mWorkspaceScreens.get(screenId);
            if (screen == null || screen.getShortcutsAndWidgets().getChildCount() != 0
                    || screen.isDropPending()) {
                // Final screen doesn't exist or it isn't empty or there's a pending drop
                return;
            }
            finalScreens.append(screenId, screen);
        }

        // Then we remove the final screens from the collections (but not from the view hierarchy)
        // and we store them as extra empty screens.
        for (int i = 0; i < finalScreens.size(); i++) {
            int screenId = finalScreens.keyAt(i);

            CellLayout screen = finalScreens.get(screenId);

            mWorkspaceScreens.remove(screenId);
            mScreenOrder.removeValue(screenId);

            int newScreenId = mWorkspaceScreens.containsKey(EXTRA_EMPTY_SCREEN_ID)
                    ? EXTRA_EMPTY_SCREEN_SECOND_ID : EXTRA_EMPTY_SCREEN_ID;
            mWorkspaceScreens.put(newScreenId, screen);
            mScreenOrder.add(newScreenId);
        }
    }

    public void removeExtraEmptyScreen(boolean stripEmptyScreens) {
        removeExtraEmptyScreenDelayed(0, stripEmptyScreens, null);
    }

    /**
     * The purpose of this method is to remove empty pages from Workspace.
     * Empty page(s) from the end of mWorkspaceScreens will always be removed. The pages with
     * ID = Workspace.EXTRA_EMPTY_SCREEN_IDS will be removed if there are other non-empty pages.
     * If there are no more non-empty pages left, extra empty page(s) will either stay or get added.
     * <p>
     * If stripEmptyScreens is true, all empty pages (not just the ones on the end) will be removed
     * from the Workspace, and if there are no more pages left then extra empty page(s) will be
     * added.
     * <p>
     * The number of extra empty pages is equal to what getPanelCount() returns.
     * <p>
     * After the method returns the possible pages are:
     * stripEmptyScreens = true : [non-empty pages, extra empty page(s) alone]
     * stripEmptyScreens = false : [non-empty pages, empty pages (not in the end),
     * extra empty page(s) alone]
     */
    public void removeExtraEmptyScreenDelayed(
            int delay, boolean stripEmptyScreens, Runnable onComplete) {
        if (mLauncher.isWorkspaceLoading()) {
            // Don't strip empty screens if the workspace is still loading
            return;
        }

        if (delay > 0) {
            postDelayed(
                    () -> removeExtraEmptyScreenDelayed(0, stripEmptyScreens, onComplete), delay);
            return;
        }

        // First we convert the last page to an extra page if the last page is empty
        // and we don't already have an extra page.
        convertFinalScreenToEmptyScreenIfNecessary();
        // Then we remove the extra page(s) if they are not the only pages left in Workspace.
        if (hasExtraEmptyScreens()) {
            forEachExtraEmptyPageId(extraEmptyPageId -> {
                removeView(mWorkspaceScreens.get(extraEmptyPageId));
                mWorkspaceScreens.remove(extraEmptyPageId);
                mScreenOrder.removeValue(extraEmptyPageId);
            });

            // Since we removed some screens, before moving to next page, update the state
            // description with correct page numbers.
            updateAccessibilityViewPageDescription();
            setCurrentPage(getNextPage());

            // Update the page indicator to reflect the removed page.
            showPageIndicatorAtCurrentScroll();
        }

        if (stripEmptyScreens) {
            // This will remove all empty pages from the Workspace. If there are no more pages left,
            // it will add extra page(s) so that users can put items on at least one page.
            stripEmptyScreens();
        }

        persistCurrentScreenOrderSync();

        if (onComplete != null) {
            onComplete.run();
        }
    }

    public boolean hasExtraEmptyScreens() {
        return mWorkspaceScreens.containsKey(EXTRA_EMPTY_SCREEN_ID)
                && getChildCount() > getPanelCount()
                && (!isTwoPanelEnabled()
                || mWorkspaceScreens.containsKey(EXTRA_EMPTY_SCREEN_SECOND_ID));
    }

    /**
     * Commits the extra empty pages then returns the screen ids of those new screens.
     * Usually there's only one extra empty screen, but when two panel home is enabled we commit
     * two extra screens.
     * <p>
     * Returns an empty IntSet in case we cannot commit any new screens.
     */
    public IntSet commitExtraEmptyScreens() {
        if (mLauncher.isWorkspaceLoading()) {
            // Invalid and dangerous operation if workspace is loading
            return new IntSet();
        }

        IntSet extraEmptyPageIds = new IntSet();
        forEachExtraEmptyPageId(extraEmptyPageId ->
                extraEmptyPageIds.add(commitExtraEmptyScreen(extraEmptyPageId)));

        return extraEmptyPageIds;
    }

    private int commitExtraEmptyScreen(int emptyScreenId) {
        CellLayout cl = mWorkspaceScreens.get(emptyScreenId);
        mWorkspaceScreens.remove(emptyScreenId);
        mScreenOrder.removeValue(emptyScreenId);

        int newScreenId = LauncherAppState.getInstance(getContext())
                .getModel().getModelDbController().getNewScreenId();
        // Launcher database isn't aware of empty pages that are already bound, so we need to
        // skip those IDs manually.
        while (mWorkspaceScreens.containsKey(newScreenId)) {
            newScreenId++;
        }

        mWorkspaceScreens.put(newScreenId, cl);
        mScreenOrder.add(newScreenId);
        persistCurrentScreenOrderSync();

        return newScreenId;
    }

    @Override
    public Hotseat getHotseat() {
        return mLauncher.getHotseat();
    }

    /**
     * AresLauncher (ledger row 92): re-attach the {@link ItemInfo} that the widget host dropped.
     *
     * <p>{@code Launcher.bindInflatedItems} hands a widget through
     * {@code LauncherWidgetHolder.attachViewToHostAndGetAttachedView}, which may {@code
     * recycleExistingView} and return a <em>different</em> view instance than the one the inflater
     * tagged. That replacement carries no tag. Stock survives it because its {@code addInScreen}
     * only needs the tag for a view id; Strategy D does not -- {@link #addInScreen} reads the tag to
     * decide WHICH ITEM to hand the home adapter, so a null tag is not a degraded add, it is a
     * silent DROP.
     *
     * <p>Measured on the owner's Pixel 2026-09-04: eight
     * {@code "Attempted to add null item to Ares home list"} lines per launcher process, and exactly
     * eight desktop rows -- every one of them {@code itemType=4} -- present in the database and
     * absent from the grid. Identical in two consecutive processes, so this is deterministic and
     * not a race. The owner saw it as *"most of my widgets are missing"* and then as *"home state is
     * not saving correctly"*: the state was saving perfectly, it was never being read back.
     *
     * <p>The repair is done HERE rather than inside {@link #addInScreen} because this is the only
     * bind-path entry that still HAS the authoritative {@code info}; by the time {@code addInScreen}
     * runs, the tag is the sole remaining source and it is already gone. Setting the tag rather than
     * routing around it also fixes the non-desktop containers, which had the same latent hole.
     */
    @Override
    public void addInScreenFromBind(View child, ItemInfo info) {
        if (child != null && info != null && child.getTag() == null) {
            if (!ARES_BIND_TAG_REPAIR) {
                // Control arm for the one-build A/B. Without this branch a guard that has quietly
                // stopped engaging is indistinguishable in a log from one whose condition never
                // arose -- and this guard's failure mode is invisible on the screen.
                Log.i(TAG, "AresBindTag: DECLINED (debug.ares.bind_tag_repair=0) for " + info);
            } else {
                Log.i(TAG, "AresBindTag: restoring dropped tag for " + info);
                child.setTag(info);
            }
        }
        WorkspaceLayoutManager.super.addInScreenFromBind(child, info);
    }

    /**
     * Control switch for {@link #addInScreenFromBind}'s tag repair. Read once: the arms must not be
     * able to change under a running process, or an A/B measures a mixture of both.
     */
    private static final boolean ARES_BIND_TAG_REPAIR =
            !"0".equals(android.os.SystemProperties.get("debug.ares.bind_tag_repair", "1"));

    /**
     * AresLauncher Strategy D redirect point. CONTAINER_DESKTOP items (real home-screen
     * apps/shortcuts/folders/widgets) are appended to {@link #mAresHomeList} instead of
     * being placed into a CellLayout grid cell -- CellLayout's grid math
     * (findNearestArea/createAreaForResize/mOccupied) is deliberately not used for these
     * items per Strategy D (design/vertical-home-strategies.md). All other containers
     * (hotseat, etc.) keep the original grid-based behavior unchanged.
     */
    @Override
    public void addInScreen(View child, int container, int screenId, int x, int y,
            int spanX, int spanY) {
        // AresLauncher: heal a folder that carries the same app twice (a stale duplicate database
        // row left by the pre-fix eject/add-back path) at the one funnel every bound item passes
        // through, whatever its container -- so this covers a HOTSEAT folder, which never routes
        // through the Ares home adapter. A first attempt that healed only in the adapter silently
        // missed the owner's actual case (a hotseat folder). See AresFolderHeal.
        if (child.getTag() instanceof FolderInfo folderInfo) {
            boolean deduped = AresFolderHeal.dedupe(mLauncher, folderInfo);
            // Heal folder children whose stored icon went stale to a placeholder (owner 2026-08-25).
            // Async on the model worker thread -- the icon cache asserts that thread -- and repaints
            // the folder itself when done, so nothing is needed here.
            AresFolderHeal.refreshChildIcons(mLauncher, folderInfo);
            if (deduped && child instanceof FolderIcon folderIcon) {
                folderIcon.onItemsChanged(false);
            }
        }
        if (container == CONTAINER_DESKTOP) {
            ItemInfo info = (ItemInfo) child.getTag();
            if (info == null) {
                // Name the child. A bare "null item" line cost the owner every widget on their home
                // screen for an unknown number of days (ledger row 89/92): the message was in the
                // log the whole time, eight times per process, and said nothing about WHAT was
                // dropped, so it read as noise rather than as "eight widgets just vanished".
                Log.e(TAG, "Attempted to add null item to Ares home list, child="
                        + child.getClass().getSimpleName() + " screenId=" + screenId
                        + " at (" + x + "," + y + ")");
                return;
            }
            // The already-inflated child is discarded: the adapter is data-backed and re-inflates
            // rows on demand via ItemInflater (the same path that produced this view), which keeps
            // RecyclerView recycling correct. See design/architecture-reassessment.md §4.
            getOrCreateAresHomeList().getAresAdapter().addItem(info);
            return;
        }
        WorkspaceLayoutManager.super.addInScreen(child, container, screenId, x, y, spanX, spanY);
    }

    /**
     * AresLauncher §4: true while a home-list row is being dragged to a new position.
     *
     * <p>Read by {@link app.lawnchair.areslauncher.AresPaneSwipeController} so a sideways wobble
     * during a reorder can't pull the app-list pane in. Kept as a null-safe query rather than
     * exposing the list itself, so nothing outside Workspace can reach in and mutate it.
     */
    public boolean isAresReorderInProgress() {
        return mAresHomeList != null && mAresHomeList.isReorderInProgress();
    }

    /**
     * AresLauncher §4: true while the home grid is in edit mode.
     *
     * <p>Also read by {@link app.lawnchair.areslauncher.AresPaneSwipeController}, and it is not
     * redundant with {@link #isAresReorderInProgress()}. That one only reports a reorder that has
     * <em>already</em> begun, which is circular for a sideways drag: the controller claims
     * horizontal gestures before any child view sees them, so the grid never receives the moves
     * that would have started the reorder in the first place, and dragging an icon left or right
     * did nothing. Edit mode is the state in which a horizontal drag on the grid means "move this
     * item", so the controller stands down for its whole duration.
     */
    public boolean isAresEditMode() {
        return mAresHomeList != null && mAresHomeList.isEditMode();
    }

    /**
     * AresLauncher: the home grid, or null before any desktop item has been bound.
     *
     * <p>Exposed for {@link app.lawnchair.areslauncher.AresHomeScrollGuard}, which needs the grid's
     * on-screen bounds to decide whether a gesture began on it. Read-only by convention: callers
     * ask it about geometry, they do not mutate it.
     */
    @Nullable
    public AresHomeListView getAresHomeList() {
        return mAresHomeList;
    }

    /**
     * AresLauncher: true when this Workspace is the Ares home surface, i.e. it owns desktop items.
     *
     * <p>Used by {@link app.lawnchair.areslauncher.AresWidgetAdd} to scope the Ares add/drop paths,
     * so the Taskbar's widget sheet (hosted by a non-Launcher context, whose caller passes null)
     * keeps stock behaviour.
     *
     * <p><b>This asks about the SURFACE, not about the view.</b> It used to return
     * {@code mAresHomeList != null}, and that was a live crash on an empty home screen. The list is
     * created lazily, only when a {@code CONTAINER_DESKTOP} item first binds — so with zero desktop
     * items <em>every</em> Ares gate in the tree answered false and handed the work back to the
     * grid-native stock path. {@link app.lawnchair.areslauncher.AresHomeDrop#handleExternalDrop}
     * bails on exactly this check, so dragging an app from the app list onto an empty home reached
     * {@code Workspace.onDropExternal} and reproduced the §15 crash:
     *
     * <pre>
     * NullPointerException: 'void ShortcutAndWidgetContainer.measureChild(View)' on a null object
     *     at DragLayer.animateViewIntoPosition(DragLayer.java:255)
     *     at Workspace.onDropExternal(Workspace.java:3429)
     * </pre>
     *
     * An empty home is reachable and sticky: the × badge removes any item, emptying a folder
     * removes the folder, and the state only has to survive one process restart.
     *
     * <p>Under Strategy D {@link #addInScreen} redirects <em>every</em> {@code CONTAINER_DESKTOP}
     * item into the Ares list unconditionally, so a Launcher Workspace is the Ares home surface by
     * construction whether or not anything has bound yet. Callers that need the view itself must
     * use {@link #getOrCreateAresHomeListForDrop()}, not {@link #getAresHomeList()}, which is still
     * null until the first bind.
     */
    public boolean hasAresHomeList() {
        return true;
    }

    /**
     * AresLauncher: the home grid, creating and attaching it if no desktop item has bound yet.
     *
     * <p>The counterpart to {@link #hasAresHomeList()} returning true before the view exists. A drop
     * onto an empty home is the first thing that needs the list, exactly as a bind is, and it needs
     * it built the same way — see {@link #getOrCreateAresHomeList()}.
     */
    public AresHomeListView getOrCreateAresHomeListForDrop() {
        return getOrCreateAresHomeList();
    }

    /**
     * AresLauncher: the desktop items currently in the home list, in visual order.
     *
     * <p>A snapshot copy, not the live backing list — callers only read it (to compute the next
     * {@code rank} and to derive grid occupancy for a new item), and handing out the mutable list
     * would let them corrupt the adapter's state. Empty before the first bind.
     */
    @NonNull
    public List<ItemInfo> getAresHomeItems() {
        return mAresHomeList == null
                ? Collections.emptyList()
                : mAresHomeList.getAresAdapter().snapshot();
    }

    /**
     * Returns the home list, attaching it to the first page's ShortcutAndWidgetContainer if it
     * isn't already parented there.
     *
     * <p>Always the <em>first</em> page, deliberately, and never the item's own {@code screenId}:
     * Strategy D flattens every CONTAINER_DESKTOP item into one continuous list, so the model's
     * screen ids carry no meaning for us. Honouring them instead parks the list on whichever page
     * the model happened to assign, which in practice is an off-screen one.
     *
     * <p>Re-attachment has to be checked on every call, not just at creation: a full rebind runs
     * {@link #removeAllWorkspaceScreens()}, which removes every CellLayout page and takes the list
     * with it, before a fresh page is created. The adapter's data lives in the adapter, so it
     * survives that; only the view needs re-homing.
     */
    private AresHomeListView getOrCreateAresHomeList() {
        if (mAresHomeList == null) {
            mAresHomeList = new AresHomeListView(getContext(), mLauncher);
        }
        CellLayout page = getChildCount() > 0 ? (CellLayout) getChildAt(0) : null;
        if (page != null) {
            ShortcutAndWidgetContainer target = page.getShortcutsAndWidgets();
            if (mAresHomeList.getParent() != target) {
                if (mAresHomeList.getParent() instanceof ViewGroup oldParent) {
                    oldParent.removeView(mAresHomeList);
                }
                // isLockedToGrid=false makes CellLayoutLayoutParams.setup() a no-op, so these
                // hand-set bounds survive measurement instead of being recomputed from cell spans.
                // AresHomeListView keeps them in sync with the container in onMeasure().
                CellLayoutLayoutParams lp = new CellLayoutLayoutParams(0, 0, 1, 1);
                lp.isLockedToGrid = false;
                lp.x = 0;
                lp.y = 0;
                lp.width = target.getMeasuredWidth();
                lp.height = target.getMeasuredHeight();
                target.addView(mAresHomeList, lp);
            }
        }
        return mAresHomeList;
    }

    /**
     * AresLauncher foldable dual-pane: keeps the app-list panel attached to workspace panel 1 while
     * two-panel mode is active, and detached otherwise.
     *
     * <p>Unfolded, {@link #getPanelCount()} is 2 and Launcher3 lays out two side-by-side CellLayout
     * pages as one logical page-pair. Stock pairs two *home* screens; AresLauncher instead fixes
     * panel 0 to the home list and panel 1 to the app list, so both panes are visible at once with
     * no swipe. Folded, panel count drops back to 1, panel 1 ceases to exist, and the established
     * single-pane swipe navigation (§9) applies unchanged.
     *
     * <p>Called from {@link #setInsets(Rect)} (which runs on every device-profile change, so it
     * covers live fold/unfold) and from {@link #insertNewWorkspaceScreen(int, int)} (which covers
     * pages appearing during a bind). Both are needed: a fold can happen with pages already built,
     * and pages can be built after the profile is already two-panel.
     */
    /**
     * The unfolded dual-pane app list, or null while folded. Exposed so
     * {@link ModelCallbacks#bindAllApplications} can feed its independent
     * {@link com.android.launcher3.allapps.AllAppsStore}.
     */
    @Nullable
    public AresPanelAllAppsContainerView getAresAppListPane() {
        return mAresAppList != null && mAresAppList.getParent() != null ? mAresAppList : null;
    }

    /**
     * The app-list pane instance for the model store feed, whether or not it is currently attached.
     *
     * <p>The pane is inflated once and reused across fold cycles ({@link #syncAresAppListPane()} only
     * detaches it while folded, never nulls it), and it holds its own independent
     * {@link com.android.launcher3.allapps.AllAppsStore}. Feeding that store even while the pane is
     * detached (folded) keeps it current, so the next unfold shows the right app list immediately --
     * closing the gap where an app installed while folded, or a bind that lands between the pane's
     * detach and re-attach, left the unfolded list stale or empty (owner 2026-08-25, "only when
     * unfolded"). Distinct from {@link #getAresAppListPane()}, which stays null while detached for
     * callers that need the pane actually on screen.
     */
    @Nullable
    public AresPanelAllAppsContainerView getAresAppListPaneForModelFeed() {
        return mAresAppList;
    }

    /**
     * Inflates the app-list pane if it does not exist yet, without attaching it, and returns it.
     *
     * <p>Called from the model feed so the pane is populated from the FIRST app bind rather than
     * from whenever it is first shown. Measured on a folded cold start (2026-09-01): the pane was
     * inflated lazily by the unfold itself, long after {@code bindAllApplications} had run, so the
     * feed had nothing to feed and the pane landed with {@code storeApps=0} -- still empty 2.5s
     * later, populated only at ~3.1s when the next bind arrived. That is the owner's "on the first
     * unfold it takes a few seconds for the app list to render, then it is instant". Inflating here
     * costs ~23ms once and makes the first unfold as warm as every later one.
     *
     * <p>Inflation is deliberately independent of posture: the pane holds its own AllAppsStore and
     * is fed while detached (see {@link #getAresAppListPaneForModelFeed()}), so building it while
     * folded is exactly what makes the first unfold instant.
     */
    public AresPanelAllAppsContainerView aresEnsureAppListPaneInflated() {
        if (mAresAppList == null) {
            // Inflated, not constructed: onFinishInflate() is where the container binds its
            // adapters and initialises search, and it never runs for a programmatically built view.
            mAresAppList = (AresPanelAllAppsContainerView) LayoutInflater.from(getContext())
                    .inflate(R.layout.ares_panel_all_apps, this, false);
        }
        return mAresAppList;
    }

    /**
     * Per-posture ownership of the floating search affordance.
     *
     * Both the folded container and the unfolded pane are full
     * {@link com.android.launcher3.allapps.ActivityAllAppsContainerView}s with a floating §17 pill,
     * and both park that pill in the same shared {@link com.android.launcher3.dragndrop.DragLayer}.
     * Exactly one surface is usable at a time, so exactly one pill should be visible: the pane's
     * while unfolded, the folded container's otherwise. Without this, unfolding leaves the folded
     * container's pill floating over the dual-pane layout alongside the pane's own.
     */
    private void updateAresSearchOwnership(boolean paneOwnsSearch) {
        if (mLauncher.getAppsView() == null) {
            return;
        }
        View foldedSearch = mLauncher.getAppsView().getSearchView();
        if (foldedSearch != null) {
            foldedSearch.setVisibility(paneOwnsSearch ? GONE : VISIBLE);
        }
    }

    private void syncAresAppListPane() {
        // §9 unfolded wallpaper dim: on for two panels, off folded (where the app-list page drives
        // its own state-gated dim instead). Set here because this is the one place posture is already
        // resolved on every fold, and it runs before the early return below.
        if (mLauncher.getRootView() != null) {
            mLauncher.getRootView().setAresUnfoldedWallpaperDim(isTwoPanelEnabled());
        }
        if (!isTwoPanelEnabled() || getChildCount() < 2) {
            // getChildCount() < 2 is TRANSIENT during a rebind: the pages have been torn down and
            // panel 1 has not been recreated yet. Tearing the pane out on that transient is what made
            // the app list flicker on unfold -- measured PANE DETACHED/ATTACHED 1.9s apart, which is a
            // visibly missing pane, not a repaint. Only detach when two panels are genuinely gone
            // (folded); otherwise leave the pane alone and let the re-anchor below run once panel 1
            // exists.
            if (!isTwoPanelEnabled() && mAresAppList != null) {
                if (mAresAppList.getParent() instanceof ViewGroup oldParent) {
                    oldParent.removeView(mAresAppList);
                }
                // The pane is out of service until the next unfold. If it was lifted out by a
                // TEMPORARY detach (removeAllWorkspaceScreens), onDetachedFromWindow never ran, so it
                // still owns its floating pill in the shared DragLayer -- which stayed stranded on
                // the folded home on top of the folded container's own pill (owner 2026-09-01).
                // Do that teardown explicitly; it is idempotent when the real callback did run.
                mAresAppList.releaseSearchPill();
                // Same reason, second symptom: the base ActivityAllAppsContainerView registers a
                // device-profile change listener and a cross-window blur listener in
                // onAttachedToWindow and releases them in onDetachedFromWindow. Skipping the detach
                // callback leaked one live registration set per fold cycle, each holding the
                // Launcher through the blur lambda (measured emulator-5554 2026-09-01: 3 PANE
                // ATTACHED, 0 PANE DETACHED over three folds). Idempotent, same as above.
                mAresAppList.releaseWindowRegistrations();
                mAresAppListTempDetached = false;
            }
            updateAresSearchOwnership(false);
            return;
        }
        CellLayout panel = (CellLayout) getChildAt(1);
        ShortcutAndWidgetContainer target = panel.getShortcutsAndWidgets();
        aresEnsureAppListPaneInflated();
        if (mAresAppList.getParent() != target) {
            if (mAresAppList.getParent() instanceof ViewGroup oldParent) {
                oldParent.removeView(mAresAppList);
            }
            // isLockedToGrid=false makes CellLayoutLayoutParams.setup() a no-op so these hand-set
            // bounds survive measurement; the pane keeps them in sync in onMeasure().
            CellLayoutLayoutParams lp = new CellLayoutLayoutParams(0, 0, 1, 1);
            lp.isLockedToGrid = false;
            lp.x = 0;
            lp.y = 0;
            lp.width = target.getMeasuredWidth();
            lp.height = target.getMeasuredHeight();
            if (mAresAppListTempDetached && mAresAppList.getParent() == null) {
                // Completes the temporary detach opened by removeAllWorkspaceScreens: re-attach
                // WITHOUT the window lifecycle, so the pane keeps its inflated content and its
                // AllApps state instead of tearing down and rebuilding (the flicker).
                mAresAppListTempDetached = false;
                target.aresAttachChildTemporarily(mAresAppList, lp);
            } else {
                target.addView(mAresAppList, lp);
            }
        }
        updateAresSearchOwnership(true);
    }

    @Override
    public void onAddDropTarget(DropTarget target) {
        mDragController.addDropTarget(target);
    }

    @Override
    public CellLayout getScreenWithId(int screenId) {
        return mWorkspaceScreens.get(screenId);
    }

    @Override
    public int getCellLayoutId(CellLayout layout) {
        int index = mWorkspaceScreens.indexOfValue(layout);
        if (index != -1) {
            return mWorkspaceScreens.keyAt(index);
        }
        Hotseat hotseat = mLauncher.getHotseat();
        if (hotseat != null && hotseat.isHotseatPage(layout)) {
            return layout.getHotseatPageIndex();
        }
        return -1;
    }

    public int getPageIndexForScreenId(int screenId) {
        return indexOfChild(mWorkspaceScreens.get(screenId));
    }

    @Override
    public int getCellLayoutIndex(CellLayout cellLayout) {
        return indexOfChild(mWorkspaceScreens.get(getCellLayoutId(cellLayout)));
    }

    @Override
    public int getPanelCount() {
        return isTwoPanelEnabled() ? 2 : super.getPanelCount();
    }

    public IntSet getCurrentPageScreenIds() {
        return IntSet.wrap(getScreenIdForPageIndex(getCurrentPage()));
    }

    public int getScreenIdForPageIndex(int index) {
        if (0 <= index && index < mScreenOrder.size()) {
            return mScreenOrder.get(index);
        }
        return -1;
    }

    public IntArray getScreenOrder() {
        return mScreenOrder;
    }

    /**
     * Returns the screen ID of a page that is shown together with the given page screen ID when the
     * two panel UI is enabled.
     */
    public int getScreenPair(int screenId) {
        if (screenId == EXTRA_EMPTY_SCREEN_ID) {
            return EXTRA_EMPTY_SCREEN_SECOND_ID;
        } else if (screenId == EXTRA_EMPTY_SCREEN_SECOND_ID) {
            return EXTRA_EMPTY_SCREEN_ID;
        } else if (screenId % 2 == 0) {
            return screenId + 1;
        } else {
            return screenId - 1;
        }
    }

    /**
     * Returns {@link CellLayout} that is shown together with the given {@link CellLayout} when the
     * two panel UI is enabled.
     */
    @Nullable
    public CellLayout getScreenPair(CellLayout cellLayout) {
        if (!isTwoPanelEnabled()) {
            return null;
        }
        int screenId = getCellLayoutId(cellLayout);
        if (screenId == -1) {
            return null;
        }
        return getScreenWithId(getScreenPair(screenId));
    }

    public void stripEmptyScreens() {
        if (mLauncher.isWorkspaceLoading()) {
            // Don't strip empty screens if the workspace is still loading.
            // This is dangerous and can result in data loss.
            return;
        }

        if (mDeferStripEmptyScreensForScreenRemap) {
            return;
        }

        if (mLauncher.isInState(EDIT_MODE)) {
            return;
        }

        if (isPageInTransition()) {
            mStripScreensOnPageStopMoving = true;
            return;
        }

        IntSet persistedScreenIds = getPersistedWorkspaceScreenIds();
        int currentPage = getNextPage();
        IntArray removeScreens = new IntArray();
        int total = mWorkspaceScreens.size();
        for (int i = 0; i < total; i++) {
            int id = mWorkspaceScreens.keyAt(i);
            CellLayout cl = mWorkspaceScreens.valueAt(i);
            // FIRST_SCREEN_ID can never be removed.
            if (shouldPreserveEmptyScreenWhenStripping(
                    id, persistedScreenIds, isExtraEmptyScreen(id))) {
                continue;
            }
            if ((!PreferenceCacheExtensionsKt.firstCached(PreferenceManager2.INSTANCE.get(getContext()).getEnableSmartspace(), PreferenceManager2.INSTANCE.get(getContext())) || id > FIRST_SCREEN_ID)
                    && cl.getShortcutsAndWidgets().getChildCount() == 0) {
                removeScreens.add(id);
            }
        }

        // When two panel home is enabled we only remove an empty page if both visible pages are
        // empty.
        if (isTwoPanelEnabled()) {
            // We go through all the pages that were marked as removable and check their page pair
            Iterator<Integer> removeScreensIterator = removeScreens.iterator();
            while (removeScreensIterator.hasNext()) {
                int pageToRemove = removeScreensIterator.next();
                int pagePair = getScreenPair(pageToRemove);
                if (!removeScreens.contains(pagePair)) {
                    // The page pair isn't empty so we want to remove the current page from the
                    // removable pages' collection
                    removeScreensIterator.remove();
                }
            }
        }

        // We enforce at least one page (two pages on two panel home) to add new items to.
        // In the case that we remove the last such screen(s), we convert the last screen(s)
        // to the empty screen(s)
        int minScreens = getPanelCount();

        int pageShift = 0;
        for (int i = 0; i < removeScreens.size(); i++) {
            int id = removeScreens.get(i);
            CellLayout cl = mWorkspaceScreens.get(id);
            mWorkspaceScreens.remove(id);
            mScreenOrder.removeValue(id);

            if (getChildCount() > minScreens) {
                // If this isn't the last page, just remove it
                if (indexOfChild(cl) < currentPage) {
                    pageShift++;
                }
                removeView(cl);
            } else {
                // The last page(s) should be converted into extra empty page(s)
                int extraScreenId = isTwoPanelEnabled() && id % 2 == 1
                        // This is the right panel in a two panel scenario
                        ? EXTRA_EMPTY_SCREEN_SECOND_ID
                        // This is either the last screen in a one panel scenario, or the left panel
                        // in a two panel scenario when there are only two empty pages left
                        : EXTRA_EMPTY_SCREEN_ID;
                mWorkspaceScreens.put(extraScreenId, cl);
                mScreenOrder.add(extraScreenId);
            }
        }

        if (pageShift >= 0) {
            setCurrentPage(currentPage - pageShift);
        }

        // Now that we have removed some pages, ensure state description is up to date.
        updateAccessibilityViewPageDescription();

        // Reset default home page if it's now out of range after page removal
        int storedDefault = PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.getDefaultHomePage());
        if (storedDefault >= getChildCount()) {
            setDefaultPage(DEFAULT_PAGE);
        }
        persistCurrentScreenOrderSync();
    }

    /**
     * Needed here because launcher has a fullscreen exclusion rect and doesn't pilfer the pointers.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (shouldSkipPagedViewInterceptionForIconSwipe(ev)) {
            return false;
        } // Lawnchair: Icon swipe gesture feature
        if (isTrackpadMultiFingerSwipe(ev)) {
            return false;
        }
        // AresLauncher §4: while the grid is in edit mode the workspace must never page. Strategy D
        // flattens every CONTAINER_DESKTOP item onto a single home list on page 0; any further
        // screen id exists only to satisfy the loader's cellX/cellY validation and renders as a
        // blank CellLayout (see pruneAresStrayPages). In NORMAL that page is unreachable because
        // AresPaneSwipeController claims horizontal drags before any child view sees them -- but in
        // edit mode that controller stands down (a horizontal drag means "move this item"), which
        // uncovered the stray page: a horizontal swipe on empty space paged the PagedView straight
        // to it (owner report 2026-08-25). Declining interception here leaves the gesture with the
        // grid, where a reorder can pick it up and empty space simply does nothing -- and it also
        // keeps edit mode from opening the Discover-feed overlay, which paging would otherwise reach.
        if (isAresEditMode()) {
            return false;
        }
        // Same reason the list pins its own vertical scroll while the inline folder-rename editor is
        // up: the editor is a real EditText at an absolute DragLayer position computed ONCE, so any
        // movement of the surface underneath leaves it floating (owner 2026-09-02). This is the
        // belt-and-braces half -- AresPaneSwipeController normally claims horizontal drags on home
        // before the PagedView sees them, but it stands down in edit mode, which is exactly where
        // this interception would otherwise take over.
        if (mAresHomeList != null && mAresHomeList.isInlineRenameActive()) {
            return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    // Lawnchair: Icon swipe gesture feature
    private boolean shouldSkipPagedViewInterceptionForIconSwipe(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDisallowPagedViewInterceptForIconSwipe = isTouchOnIconWithSwipeGesture(
                        ev.getX(), ev.getY(), false);
                if (mDisallowPagedViewInterceptForIconSwipe) {
                    resetTouchState();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                return mDisallowPagedViewInterceptForIconSwipe;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean shouldSkip = mDisallowPagedViewInterceptForIconSwipe;
                mDisallowPagedViewInterceptForIconSwipe = false;
                if (shouldSkip) {
                    resetTouchState();
                }
                return shouldSkip;

            default:
                return false;
        }
    }

    // Lawnchair: Icon swipe gesture feature
    public boolean isTouchOnIconWithSwipeGesture(float x, float y, boolean vertical) {
        boolean hasConfiguredIconSwipeGesture = false;
        BubbleTextView touchedIcon = findIconAtPosition(x, y);
        if (touchedIcon != null) {
            if (vertical) {
                hasConfiguredIconSwipeGesture = touchedIcon.hasConfiguredVerticalIconSwipeGesture();
            } else {
                hasConfiguredIconSwipeGesture = touchedIcon.hasConfiguredHorizontalIconSwipeGesture();
            }
        }
        return hasConfiguredIconSwipeGesture;
    }

    // Lawnchair: Icon swipe gesture feature
    private BubbleTextView findIconAtPosition(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof CellLayout cellLayout)) {
                continue;
            }
            float localX = x - child.getLeft();
            float localY = y - child.getTop();
            if (!Utilities.pointInView(cellLayout, localX, localY, 0)) {
                continue;
            }
            BubbleTextView foundView = findIconInCellLayout(cellLayout, localX, localY);
            if (foundView != null) {
                return foundView;
            }
        }
        return null;
    }

    // Lawnchair: Icon swipe gesture feature
    private BubbleTextView findIconInCellLayout(CellLayout cellLayout, float x, float y) {
        ShortcutAndWidgetContainer container = cellLayout.getShortcutsAndWidgets();
        float containerX = x - container.getLeft();
        float containerY = y - container.getTop();
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (!(child instanceof BubbleTextView bubbleTextView)
                    || child.getVisibility() != VISIBLE) {
                continue;
            }
            if (Utilities.pointInView(child,
                    containerX - child.getLeft(),
                    containerY - child.getTop(),
                    0)) {
                return bubbleTextView;
            }
        }
        return null;
    }


    /**
     * Needed here because launcher has a fullscreen exclusion rect and doesn't pilfer the pointers.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isTrackpadMultiFingerSwipe(ev)) {
            return false;
        }
        // Declining INTERCEPTION is not enough to stop paging while the inline folder rename is up:
        // when no child consumes the gesture -- an edge swipe, or empty space -- the event still
        // reaches this onTouchEvent and PagedView drags from here. That is how a rename could still
        // be swiped off the edge onto the blank stray CellLayout (owner 2026-09-02). Same guard as
        // onInterceptTouchEvent; both are needed.
        if (mAresHomeList != null && mAresHomeList.isInlineRenameActive()) {
            return false;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onDisallowSwipeToMinusOnePage() {
        mLauncher.getOverlayManager().onDisallowSwipeToMinusOnePage();
    }

    /**
     * Called directly from a CellLayout (not by the framework), after we've been added as a
     * listener via setOnInterceptTouchEventListener(). This allows us to tell the CellLayout
     * that it should intercept touch events, which is not something that is normally supported.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return shouldConsumeTouch(v);
    }

    private boolean shouldConsumeTouch(View v) {
        return !workspaceIconsCanBeDragged()
                || (!workspaceInModalState() && !isVisible(v));
    }

    public boolean isSwitchingState() {
        return mIsSwitchingState;
    }

    /**
     * This differs from isSwitchingState in that we take into account how far the transition
     * has completed.
     */
    public boolean isFinishedSwitchingState() {
        return !mIsSwitchingState
                || (mTransitionProgress > FINISHED_SWITCHING_STATE_TRANSITION_PROGRESS);
    }

    @Override
    public boolean dispatchUnhandledMove(View focused, int direction) {
        if (workspaceInModalState() || !isFinishedSwitchingState()) {
            // when the home screens are shrunken, shouldn't allow side-scrolling
            return false;
        }
        return super.dispatchUnhandledMove(focused, direction);
    }

    @Override
    protected void updateIsBeingDraggedOnTouchDown(MotionEvent ev) {
        super.updateIsBeingDraggedOnTouchDown(ev);

        mXDown = ev.getX();
        mYDown = ev.getY();
        if (mFirstPagePinnedItem != null) {
            final float[] tempFXY = new float[2];
            tempFXY[0] = mXDown;
            tempFXY[1] = mYDown;
            Utilities.mapCoordInSelfToDescendant(mFirstPagePinnedItem, this, tempFXY);
            mIsEventOverFirstPagePinnedItem = mFirstPagePinnedItem.getLeft() <= tempFXY[0]
                    && mFirstPagePinnedItem.getRight() >= tempFXY[0]
                    && mFirstPagePinnedItem.getTop() <= tempFXY[1]
                    && mFirstPagePinnedItem.getBottom() >= tempFXY[1];
        } else {
            mIsEventOverFirstPagePinnedItem = false;
        }
        if (!mIsEventOverFirstPagePinnedItem) {
            mIsEventOverFirstPagePinnedItem = isEventOverQsb(mXDown, mYDown);
        }
    }

    private boolean isEventOverQsb(float x, float y) {
        CellLayout target = (CellLayout) getChildAt(mCurrentPage);
        if (target.getShortcutsAndWidgets() == null)
            return false;
        ShortcutAndWidgetContainer container = target.getShortcutsAndWidgets();
        mTempFXY[0] = x;
        mTempFXY[1] = y;
        Utilities.mapCoordInSelfToDescendant(container, this, mTempFXY);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof LauncherAppWidgetInfo))
                continue;
            LauncherAppWidgetInfo info = (LauncherAppWidgetInfo) tag;
            if (!info.providerName.equals(SmartspaceAppWidgetProvider.componentName))
                continue;

            boolean isOverQsb = child.getLeft() <= mTempFXY[0] && child.getRight() >= mTempFXY[0]
                    && child.getTop() <= mTempFXY[1] && child.getBottom() >= mTempFXY[1];
            if (isOverQsb)
                return true;
        }
        return false;
    }

    @Override
    protected void determineScrollingStart(MotionEvent ev) {
        if (!isFinishedSwitchingState() || mIsEventOverFirstPagePinnedItem) return;

        float deltaX = ev.getX() - mXDown;
        float absDeltaX = Math.abs(deltaX);
        float absDeltaY = Math.abs(ev.getY() - mYDown);

        if (Float.compare(absDeltaX, 0f) == 0) return;

        float slope = absDeltaY / absDeltaX;
        float theta = (float) Math.atan(slope);

        if (absDeltaX > mTouchSlop || absDeltaY > mTouchSlop) {
            cancelCurrentPageLongPress();
        }

        if (theta > MAX_SWIPE_ANGLE) {
            // Above MAX_SWIPE_ANGLE, we don't want to ever start scrolling the workspace
            return;
        } else if (theta > START_DAMPING_TOUCH_SLOP_ANGLE) {
            // Above START_DAMPING_TOUCH_SLOP_ANGLE and below MAX_SWIPE_ANGLE, we want to
            // increase the touch slop to make it harder to begin scrolling the workspace. This
            // results in vertically scrolling widgets to more easily. The higher the angle, the
            // more we increase touch slop.
            theta -= START_DAMPING_TOUCH_SLOP_ANGLE;
            float extraRatio = (float)
                    Math.sqrt((theta / (MAX_SWIPE_ANGLE - START_DAMPING_TOUCH_SLOP_ANGLE)));
            super.determineScrollingStart(ev, 1 + TOUCH_SLOP_DAMPING_FACTOR * extraRatio);
        } else {
            // Below START_DAMPING_TOUCH_SLOP_ANGLE, we don't do anything special
            super.determineScrollingStart(ev);
        }
    }

    protected void onPageBeginTransition() {
        // Widget resize frame doesn't receive events to close when talkback is enabled. For that
        // case, close it here.
        AbstractFloatingView.closeOpenViews(mLauncher, false, TYPE_WIDGET_RESIZE_FRAME);

        super.onPageBeginTransition();
        updateChildrenLayersEnabled();
    }

    protected void onPageEndTransition() {
        super.onPageEndTransition();
        updateChildrenLayersEnabled();

        if (mDragController.isDragging()) {
            if (workspaceInModalState()) {
                // If we are in springloaded mode, then force an event to check if the current touch
                // is under a new page (to scroll to)
                mDragController.forceTouchMove();
            }
        }

        if (mStripScreensOnPageStopMoving) {
            stripEmptyScreens();
            mStripScreensOnPageStopMoving = false;
        }

        // Inform the Launcher activity that the page transition ended so that it can react to the
        // newly visible page if it wants to.
        mLauncher.onPageEndTransition();
    }

    public void setLauncherOverlay(LauncherOverlayTouchProxy overlay) {
        final EdgeEffectCompat newEffect;
        if (overlay == null) {
            newEffect = new EdgeEffectCompat(getContext());
            mOverlayEdgeEffect = null;
        } else {
            newEffect = mOverlayEdgeEffect = new OverlayEdgeEffect(getContext(), overlay);
            overlay.setOverlayCallbacks(this);
        }

        if (mIsRtl) {
            mEdgeGlowRight = newEffect;
        } else {
            mEdgeGlowLeft = newEffect;
        }
        onOverlayScrollChanged(0);
    }

    public boolean hasOverlay() {
        return mOverlayEdgeEffect != null;
    }

    @Override
    protected void snapToDestination() {
        if (mOverlayEdgeEffect != null && !mOverlayEdgeEffect.isFinished()) {
            snapToPageImmediately(0);
        } else {
            super.snapToDestination();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);

        // Update the page indicator progress.
        // Unlike from other states, we show the page indicator when transitioning from HINT_STATE.
        boolean isSwitchingState = mIsSwitchingState
                && mLauncher.getStateManager().getCurrentStableState() != HINT_STATE;
        boolean isTransitioning = isSwitchingState
                || (getLayoutTransition() != null && getLayoutTransition().isRunning());
        if (!isTransitioning) {
            showPageIndicatorAtCurrentScroll();
        }

        updatePageAlphaValues();
        updatePageScrollValues();
        enableHwLayersOnVisiblePages();
    }

    public void showPageIndicatorAtCurrentScroll() {
        if (mPageIndicator != null) {
            mPageIndicator.setScroll(getScrollX(), computeMaxScroll());
            var isHotseatEnabled = PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.isHotseatEnabled());
            mPageIndicator.setVisibility(isHotseatEnabled ? VISIBLE : INVISIBLE);
        }
    }

    @Override
    protected boolean shouldFlingForVelocity(int velocityX) {
        // When the overlay is moving, the fling or settle transition is controlled by the overlay.
        return Float.compare(Math.abs(mOverlayProgress), 0) == 0
                && super.shouldFlingForVelocity(velocityX);
    }

    /**
     * The overlay scroll is being controlled locally, just update our overlay effect
     */
    @Override
    public void onOverlayScrollChanged(float scroll) {
        mOverlayProgress = Utilities.boundToRange(scroll, 0, 1);
        if (Float.compare(mOverlayProgress, 1f) == 0) {
            if (!mOverlayShown) {
                mOverlayShown = true;
                mLauncher.onOverlayVisibilityChanged(true);
            }
        } else if (Float.compare(mOverlayProgress, 0f) == 0) {
            if (mOverlayShown) {
                mOverlayShown = false;
                mLauncher.onOverlayVisibilityChanged(false);
            }
        }
        int count = mOverlayCallbacks.size();
        for (int i = 0; i < count; i++) {
            mOverlayCallbacks.get(i).onOverlayScrollChanged(mOverlayProgress);
        }
    }

    /**
     * Adds a callback for receiving overlay progress
     */
    public void addOverlayCallback(LauncherOverlayCallbacks callback) {
        mOverlayCallbacks.add(callback);
        callback.onOverlayScrollChanged(mOverlayProgress);
    }

    /**
     * Removes a previously added overlay progress callback
     */
    public void removeOverlayCallback(LauncherOverlayCallbacks callback) {
        mOverlayCallbacks.remove(callback);
    }

    @Override
    protected void notifyPageSwitchListener(int prevPage) {
        super.notifyPageSwitchListener(prevPage);
        if (prevPage != mCurrentPage) {
            StatsLogManager.EventEnum event = (prevPage < mCurrentPage)
                    ? LAUNCHER_SWIPERIGHT : LAUNCHER_SWIPELEFT;
            mLauncher.getStatsLogManager().logger()
                    .withSrcState(LAUNCHER_STATE_HOME)
                    .withDstState(LAUNCHER_STATE_HOME)
                    .withContainerInfo(LauncherAtom.ContainerInfo.newBuilder()
                            .setWorkspace(
                                    LauncherAtom.WorkspaceContainer.newBuilder()
                                            .setPageIndex(prevPage)).build())
                    .log(event);
            updateStatusbarClock();
        }
    }

    protected void setWallpaperDimension() {
        Executors.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final Point size = LauncherAppState.getIDP(getContext()).defaultWallpaperSize;
                if (!mWallpaperManager.isWallpaperSupported()) {
                    mWallpaperManager.suggestDesiredDimensions(0, 0);
                } else if (size.x != mWallpaperManager.getDesiredMinimumWidth()
                        || size.y != mWallpaperManager.getDesiredMinimumHeight()) {
                    mWallpaperManager.suggestDesiredDimensions(size.x, size.y);
                }
            }
        });
    }

    public void lockWallpaperToDefaultPage() {
        mWallpaperOffset.setLockToDefaultPage(true);
    }

    public void unlockWallpaperFromDefaultPageOnNextLayout() {
        if (mWallpaperOffset.isLockedToDefaultPage()) {
            mUnlockWallpaperFromDefaultPageOnLayout = true;
            requestLayout();
        }
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        mWallpaperOffset.syncWithScroll();
    }

    @Override
    public void announceForAccessibility(CharSequence text) {
        // Don't announce if apps is on top of us.
        if (!mLauncher.isInState(ALL_APPS)) {
            super.announceForAccessibility(text);
        }
    }

    private void updatePageAlphaValues() {
        // We need to check the isDragging case because updatePageAlphaValues is called between
        // goToState(SPRING_LOADED) and onStartStateTransition.
        if (!workspaceInModalState() && !mIsSwitchingState && !mDragController.isDragging()) {
            int screenCenter = getScrollX() + getMeasuredWidth() / 2;
            for (int i = 0; i < getChildCount(); i++) {
                CellLayout child = (CellLayout) getChildAt(i);
                if (child != null) {
                    float scrollProgress = getScrollProgress(screenCenter, child, i);
                    float alpha = 1 - Math.abs(scrollProgress);
                    if (mWorkspaceFadeInAdjacentScreens) {
                        child.getShortcutsAndWidgets().setAlpha(alpha);
                    } else {
                        // Pages that are off-screen aren't important for accessibility.
                        child.getShortcutsAndWidgets().setImportantForAccessibility(
                                alpha > 0 ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                                        : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    }
                }
            }
        }
    }

    private void updatePageScrollValues() {
        int screenCenter = getScrollX() + getMeasuredWidth() / 2;
        for (int i = 0; i < getChildCount(); i++) {
            CellLayout child = (CellLayout) getChildAt(i);
            if (child != null) {
                float scrollProgress = getScrollProgress(screenCenter, child, i);
                child.setScrollProgress(scrollProgress);
            }
        }
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mWallpaperOffset.setWindowToken(getWindowToken());
        computeScroll();
        mLauncher.getStateManager().addStateListener(mAccessibilityDropListener);
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mWallpaperOffset.setWindowToken(null);
        mLauncher.getStateManager().removeStateListener(mAccessibilityDropListener);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mHasOnLayoutBeenCalled = true; // b/349929393 - is the required call to onLayout not done?
        if (mUnlockWallpaperFromDefaultPageOnLayout) {
            mWallpaperOffset.setLockToDefaultPage(false);
            mUnlockWallpaperFromDefaultPageOnLayout = false;
        }
        if (mFirstLayout && mCurrentPage >= 0 && mCurrentPage < getChildCount()) {
            mWallpaperOffset.syncWithScroll();
            mWallpaperOffset.jumpToFinal();
        }
        super.onLayout(changed, left, top, right, bottom);
        updatePageAlphaValues();
    }

    @Override
    public int getDescendantFocusability() {
        if (workspaceInModalState()) {
            return ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        }
        return super.getDescendantFocusability();
    }

    private boolean workspaceInModalState() {
        return !mLauncher.isInState(NORMAL);
    }

    private boolean workspaceInScrollableState() {
        return mLauncher.isInState(SPRING_LOADED) || mLauncher.isInState(EDIT_MODE)
                || !workspaceInModalState();
    }

    /**
     * Returns whether a drag should be allowed to be started from the current workspace state.
     */
    public boolean workspaceIconsCanBeDragged() {
        return mLauncher.getStateManager().getState().hasFlag(FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED);
    }

    private void updateChildrenLayersEnabled() {
        boolean enableChildrenLayers = mIsSwitchingState || isPageInTransition();

        if (enableChildrenLayers != mChildrenLayersEnabled) {
            mChildrenLayersEnabled = enableChildrenLayers;
            if (mChildrenLayersEnabled) {
                enableHwLayersOnVisiblePages();
            } else {
                for (int i = 0; i < getPageCount(); i++) {
                    final CellLayout cl = (CellLayout) getChildAt(i);
                    cl.enableHardwareLayer(false);
                }
            }
        }
    }

    private void enableHwLayersOnVisiblePages() {
        if (mChildrenLayersEnabled) {
            final int screenCount = getChildCount();

            final int[] visibleScreens = getVisibleChildrenRange();
            int leftScreen = visibleScreens[0];
            int rightScreen = visibleScreens[1];
            if (mForceDrawAdjacentPages) {
                // In overview mode, make sure that the two side pages are visible.
                leftScreen = Utilities.boundToRange(getCurrentPage() - 1, 0, rightScreen);
                rightScreen = Utilities.boundToRange(getCurrentPage() + 1,
                        leftScreen, getPageCount() - 1);
            }

            if (leftScreen == rightScreen) {
                // make sure we're caching at least two pages always
                if (rightScreen < screenCount - 1) {
                    rightScreen++;
                } else if (leftScreen > 0) {
                    leftScreen--;
                }
            }

            for (int i = 0; i < screenCount; i++) {
                final CellLayout layout = (CellLayout) getPageAt(i);
                // enable layers between left and right screen inclusive.
                boolean enableLayer = leftScreen <= i && i <= rightScreen;
                layout.enableHardwareLayer(enableLayer);
            }
        }
    }

    public void onWallpaperTap(MotionEvent ev) {
        final int[] position = mTempXY;
        getLocationOnScreen(position);

        int pointerIndex = ev.getActionIndex();
        position[0] += (int) ev.getX(pointerIndex);
        position[1] += (int) ev.getY(pointerIndex);

        mWallpaperManager.sendWallpaperCommand(getWindowToken(),
                ev.getAction() == MotionEvent.ACTION_UP
                        ? WallpaperManager.COMMAND_TAP : WallpaperManager.COMMAND_SECONDARY_TAP,
                position[0], position[1], 0, null);
    }

    private void onStartStateTransition() {
        mIsSwitchingState = true;
        mTransitionProgress = 0;

        updateChildrenLayersEnabled();
    }

    private void onEndStateTransition() {
        mIsSwitchingState = false;
        mForceDrawAdjacentPages = false;
        mTransitionProgress = 1;

        updateChildrenLayersEnabled();
        updateAccessibilityFlags();
    }

    /**
     * Sets the current workspace {@link LauncherState} and updates the UI without any animations
     */
    @Override
    public void setState(LauncherState toState) {
        onStartStateTransition();
        mLauncher.getStateManager().getState().onLeavingState(mLauncher, toState);
        mStateTransitionAnimation.setState(toState);
        onEndStateTransition();
    }

    /**
     * Sets the current workspace {@link LauncherState}, then animates the UI
     */
    @Override
    public void setStateWithAnimation(
            LauncherState toState, StateAnimationConfig config, PendingAnimation animation) {
        StateTransitionListener listener = new StateTransitionListener();
        mLauncher.getStateManager().getState().onLeavingState(mLauncher, toState);
        mStateTransitionAnimation.setStateWithAnimation(toState, config, animation);

        // Invalidate the pages now, so that we have the visible pages before the
        // animation is started
        if (toState.hasFlag(FLAG_MULTI_PAGE)) {
            mForceDrawAdjacentPages = true;
        }
        invalidate(); // This will call dispatchDraw(), which calls getVisiblePages().

        ValueAnimator stepAnimator = ValueAnimator.ofFloat(0, 1);
        stepAnimator.addUpdateListener(listener);
        stepAnimator.addListener(listener);
        animation.add(stepAnimator);
    }

    public WorkspaceStateTransitionAnimation getStateTransitionAnimation() {
        return mStateTransitionAnimation;
    }

    public void updateAccessibilityFlags() {
        // TODO: Update the accessibility flags appropriately when dragging.
        int accessibilityFlag =
                mLauncher.getStateManager().getState().hasFlag(FLAG_WORKSPACE_INACCESSIBLE)
                        ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        if (!mLauncher.getAccessibilityDelegate().isInAccessibleDrag()) {
            int total = getPageCount();
            for (int i = 0; i < total; i++) {
                updateAccessibilityFlags(accessibilityFlag, (CellLayout) getPageAt(i));
            }
            setImportantForAccessibility(accessibilityFlag);
        }
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo() {
        if (getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            // TAPL tests verify that workspace is not present in Overview and AllApps states.
            // TAPL can work only if UIDevice is set up as setCompressedLayoutHeirarchy(false).
            // Hiding workspace from the tests when it's
            // IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS.
            return AccessibilityNodeInfo.obtain();
        }
        return super.createAccessibilityNodeInfo();
    }

    private void updateAccessibilityFlags(int accessibilityFlag, CellLayout page) {
        page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        page.getShortcutsAndWidgets().setImportantForAccessibility(accessibilityFlag);
        page.setContentDescription(null);
        page.setAccessibilityDelegate(null);
    }

    public void startDrag(CellInfo cellInfo, DragOptions options) {
        View child = cellInfo.cell;

        mDragInfo = cellInfo;
        child.setVisibility(INVISIBLE);

        if (options.isAccessibleDrag) {
            mAccessibilityDragListener =
                    new AccessibleDragListenerAdapter(this, WorkspaceAccessibilityHelper::new) {
                        @Override
                        protected void enableAccessibleDrag(boolean enable,
                                @Nullable DragObject dragObject) {
                            super.enableAccessibleDrag(enable, dragObject);
                            Hotseat hotseat = mLauncher.getHotseat();
                            if (hotseat != null) {
                                for (CellLayout page : hotseat.getPageLayouts()) {
                                    setEnableForLayout(page, enable);
                                }
                            }
                            if (enable && dragObject != null
                                    && dragObject.dragInfo instanceof LauncherAppWidgetInfo) {
                                mLauncher.getHotseat().setImportantForAccessibility(
                                        IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                            }
                        }
                    };
        }

        beginDragShared(child, this, options);
    }

    public void beginDragShared(View child, DragSource source, DragOptions options) {
        Object dragObject = child.getTag();
        if (!(dragObject instanceof ItemInfo)) {
            String msg = "Drag started with a view that has no tag set. This "
                    + "will cause a crash (issue 11627249) down the line. "
                    + "View: " + child + "  tag: " + child.getTag();
            throw new IllegalStateException(msg);
        }
        beginDragShared(child, null, source, (ItemInfo) dragObject,
                new DragPreviewProvider(child), options);
    }

    /**
     * Core functionality for beginning a drag operation for an item that will be dropped within
     * the workspace
     */
    public DragView beginDragShared(View child, DraggableView draggableView, DragSource source,
            ItemInfo dragObject, DragPreviewProvider previewProvider, DragOptions dragOptions) {

        float iconScale = 1f;
        if (child instanceof BubbleTextView) {
            Drawable icon = ((BubbleTextView) child).getIcon();
            if (icon instanceof FastBitmapDrawable) {
                iconScale = ((FastBitmapDrawable) icon).getAnimatedScale();
            }
        }

        // Clear the pressed state if necessary
        child.clearFocus();
        child.setPressed(false);
        if (child instanceof BubbleTextView) {
            BubbleTextView icon = (BubbleTextView) child;
            icon.clearPressedBackground();
        }

        if (draggableView == null && child instanceof DraggableView) {
            draggableView = (DraggableView) child;
        }

        final View contentView = previewProvider.getContentView();
        final float scale;
        // The draggable drawable follows the touch point around on the screen
        final Drawable drawable;
        if (contentView == null) {
            drawable = previewProvider.createDrawable();
            scale = previewProvider.getScaleAndPosition(drawable, mTempXY);
        } else {
            drawable = null;
            scale = previewProvider.getScaleAndPosition(contentView, mTempXY);
        }

        int dragLayerX = mTempXY[0];
        int dragLayerY = mTempXY[1];

        Rect dragRect = new Rect();

        if (draggableView != null) {
            draggableView.getSourceVisualDragBounds(dragRect);
            dragLayerY += dragRect.top;
        }


        if (child.getParent() instanceof ShortcutAndWidgetContainer) {
            mDragSourceInternal = (ShortcutAndWidgetContainer) child.getParent();
        }

        if (child instanceof BubbleTextView) {
            BubbleTextView btv = (BubbleTextView) child;
            // AresLauncher: aresSuppressLongPressPopup is set only by drags this launcher starts
            // deliberately -- today, a touch-and-drag inside an open folder in edit mode. The user
            // has already committed to moving the icon, so a menu is wrong; and the popup's
            // PreDragCondition would put DragController into PRE-drag, where no drop can resolve.
            // See DragOptions#aresSuppressLongPressPopup.
            if (!dragOptions.isAccessibleDrag && !dragOptions.aresSuppressLongPressPopup) {
                dragOptions.preDragCondition = btv.startLongPressAction();
            }
            if (btv.isDisplaySearchResult()) {
                dragOptions.preDragEndScale = (float) mAllAppsIconSize / btv.getIconSize();
            }
        }

        boolean lockHomeScreen = PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.getLockHomeScreen());
        if (lockHomeScreen) {
            child.setVisibility(View.VISIBLE);

            if (dragOptions.preDragCondition != null) {
                if (Flags.msdlFeedback()) {
                    mMSDLPlayerWrapper.playToken(MSDLToken.LONG_PRESS);
                } else {
                    mLauncher.getDragLayer().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            }
            return null;
        }
        if (dragOptions.preDragCondition != null) {
            int xDragOffSet = dragOptions.preDragCondition.getDragOffset().x;
            int yDragOffSet = dragOptions.preDragCondition.getDragOffset().y;
            if (xDragOffSet != 0 || yDragOffSet != 0) {
                dragLayerX += xDragOffSet;
                dragLayerY += yDragOffSet;
            }
        }

        final DragView dv;
        if (contentView != null) {
            dv = mDragController.startDrag(
                    contentView,
                    draggableView,
                    dragLayerX,
                    dragLayerY,
                    source,
                    dragObject,
                    dragRect,
                    scale * iconScale,
                    scale,
                    dragOptions);
        } else {
            dv = mDragController.startDrag(
                    drawable,
                    draggableView,
                    dragLayerX,
                    dragLayerY,
                    source,
                    dragObject,
                    dragRect,
                    scale * iconScale,
                    scale,
                    dragOptions);
        }
        return dv;
    }

    private boolean transitionStateShouldAllowDrop() {
        return (!isSwitchingState() || mTransitionProgress > ALLOW_DROP_TRANSITION_PROGRESS) &&
                workspaceIconsCanBeDragged();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acceptDrop(DragObject d) {
        // If it's an external drop (e.g. from All Apps), check if it should be accepted
        CellLayout dropTargetLayout = mDropToLayout;
        if (d.dragSource != this) {
            // Don't accept the drop if we're not over a valid drop target at time of drop
            if (dropTargetLayout == null) {
                return false;
            }
            // AresLauncher: a drag that started inside an open folder used to be REFUSED here
            // (529276c113), because a long-press alone armed one and a release without any movement
            // still resolved a drop against this workspace -- silently relocating the app. The
            // interim refusal is now removed, and the defect it closed is closed at the source
            // instead: Folder.onLongClick no longer starts a drag at all, and the only touch path
            // that does (AresFolderDrag.DragStarter) requires the finger to pass the touch slop
            // first. A press with no movement can no longer produce a drop, so there is nothing
            // left here to refuse -- and a release inside the folder's own bounds never reaches
            // this method, because the open Folder is itself the drop target for that point.
            // AresHomeDrop.handleExternalDrop now completes the move; see addDraggedItem.
            if (!transitionStateShouldAllowDrop()) return false;

            mDragViewVisualCenter = d.getVisualCenter(mDragViewVisualCenter);

            // We want the point to be mapped to the dragTarget.
            mapPointFromDropLayout(dropTargetLayout, mDragViewVisualCenter);

            int spanX;
            int spanY;
            if (mDragInfo != null) {
                final CellInfo dragCellInfo = mDragInfo;
                spanX = dragCellInfo.spanX;
                spanY = dragCellInfo.spanY;
            } else {
                spanX = d.dragInfo.spanX;
                spanY = d.dragInfo.spanY;
            }

            int minSpanX = spanX;
            int minSpanY = spanY;
            if (d.dragInfo instanceof PendingAddWidgetInfo) {
                minSpanX = ((PendingAddWidgetInfo) d.dragInfo).minSpanX;
                minSpanY = ((PendingAddWidgetInfo) d.dragInfo).minSpanY;
            }

            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, dropTargetLayout,
                    mTargetCell);
            float distance = dropTargetLayout.getDistanceFromWorkspaceCellVisualCenter(
                    mDragViewVisualCenter[0], mDragViewVisualCenter[1], mTargetCell);
            if (mCreateUserFolderOnDrop && willCreateUserFolder(d.dragInfo,
                    dropTargetLayout, mTargetCell, distance, true)) {
                return true;
            }

            if (mAddToExistingFolderOnDrop && willAddToExistingUserFolder(d.dragInfo,
                    dropTargetLayout, mTargetCell, distance)) {
                return true;
            }

            int[] resultSpan = new int[2];
            mTargetCell = dropTargetLayout.performReorder((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, spanX, spanY,
                    null, mTargetCell, resultSpan, CellLayout.MODE_ACCEPT_DROP);
            boolean foundCell = mTargetCell[0] >= 0 && mTargetCell[1] >= 0;

            // Don't accept the drop if there's no room for the item
            if (!foundCell) {
                onNoCellFound(dropTargetLayout, d.dragInfo, d.logInstanceId);
                return false;
            }
        }

        int screenId = getCellLayoutId(dropTargetLayout);
        if (Workspace.EXTRA_EMPTY_SCREEN_IDS.contains(screenId)) {
            commitExtraEmptyScreens();
        }

        return true;
    }

    boolean willCreateUserFolder(ItemInfo info, CellLayout target, int[] targetCell,
                                 float distance, boolean considerTimeout) {
        if (distance > target.getFolderCreationRadius(targetCell)) return false;
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);
        return willCreateUserFolder(info, dropOverView, considerTimeout);
    }

    boolean willCreateUserFolder(ItemInfo info, View dropOverView, boolean considerTimeout) {
        if (dropOverView != null) {
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) dropOverView.getLayoutParams();
            if (lp.useTmpCoords && (lp.getTmpCellX() != lp.getCellX()
                    || lp.getTmpCellY() != lp.getCellY())) {
                return false;
            }
        }

        boolean hasntMoved = false;
        if (mDragInfo != null) {
            hasntMoved = dropOverView == mDragInfo.cell;
        }

        if (dropOverView == null || hasntMoved || (considerTimeout && !mCreateUserFolderOnDrop)) {
            return false;
        }

        boolean aboveShortcut = Folder.willAccept(dropOverView.getTag())
                && ((ItemInfo) dropOverView.getTag()).container != CONTAINER_HOTSEAT_PREDICTION;
        boolean willBecomeShortcut = FolderInfo.willAcceptItemType(info.itemType);

        return (aboveShortcut && willBecomeShortcut);
    }

    boolean willAddToExistingUserFolder(ItemInfo dragInfo, CellLayout target, int[] targetCell,
                                        float distance) {
        if (distance > target.getFolderCreationRadius(targetCell)) return false;
        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);
        return willAddToExistingUserFolder(dragInfo, dropOverView);

    }

    boolean willAddToExistingUserFolder(ItemInfo dragInfo, View dropOverView) {
        if (dropOverView != null) {
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) dropOverView.getLayoutParams();
            if (lp.useTmpCoords && (lp.getTmpCellX() != lp.getCellX()
                    || lp.getTmpCellY() != lp.getCellY())) {
                return false;
            }
        }

        if (dropOverView instanceof FolderIcon) {
            FolderIcon fi = (FolderIcon) dropOverView;
            if (fi.acceptDrop(dragInfo)) {
                return true;
            }
        }
        return false;
    }

    boolean createUserFolderIfNecessary(View newView, int container, CellLayout target,
            int[] targetCell, float distance, boolean external, DragObject d) {
        if (distance > target.getFolderCreationRadius(targetCell)) return false;
        View v = target.getChildAt(targetCell[0], targetCell[1]);

        boolean hasntMoved = false;
        if (mDragInfo != null) {
            CellLayout cellParent = getParentCellLayoutForView(mDragInfo.cell);
            hasntMoved = (mDragInfo.cellX == targetCell[0] &&
                    mDragInfo.cellY == targetCell[1]) && (cellParent == target);
        }

        if (v == null || hasntMoved || !mCreateUserFolderOnDrop) return false;
        mCreateUserFolderOnDrop = false;
        final int screenId = getCellLayoutId(target);

        boolean aboveShortcut = Folder.willAccept(v.getTag());
        boolean willBecomeShortcut = Folder.willAccept(newView.getTag());

        if (aboveShortcut && willBecomeShortcut) {
            ItemInfo sourceInfo = (ItemInfo) newView.getTag();
            ItemInfo destInfo = (ItemInfo) v.getTag();
            // if the drag started here, we need to remove it from the workspace
            if (!external) {
                getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
            }

            Rect folderLocation = new Rect();
            float scale = mLauncher.getDragLayer().getDescendantRectRelativeToSelf(v, folderLocation);
            target.removeView(v);
            mStatsLogManager.logger().withItemInfo(destInfo).withInstanceId(d.logInstanceId)
                    .log(LauncherEvent.LAUNCHER_ITEM_DROP_FOLDER_CREATED);
            FolderIcon fi = mLauncher.addFolder(target, container, screenId, targetCell[0],
                    targetCell[1]);
            destInfo.cellX = -1;
            destInfo.cellY = -1;
            sourceInfo.cellX = -1;
            sourceInfo.cellY = -1;

            // If the dragView is null, we can't animate
            boolean animate = d != null;
            if (animate) {
                // In order to keep everything continuous, we hand off the currently rendered
                // folder background to the newly created icon. This preserves animation state.
                fi.setFolderBackground(mFolderCreateBg);
                mFolderCreateBg = new PreviewBackground(getContext());
                fi.performCreateAnimation(destInfo, v, sourceInfo, d, folderLocation, scale);
            } else {
                fi.prepareCreateAnimation(v);
                fi.getFolder().addFolderContent(destInfo);
                fi.getFolder().addFolderContent(sourceInfo);
            }
            return true;
        }
        return false;
    }

    boolean addToExistingFolderIfNecessary(View newView, CellLayout target, int[] targetCell,
            float distance, DragObject d, boolean external) {
        if (distance > target.getFolderCreationRadius(targetCell)) return false;

        View dropOverView = target.getChildAt(targetCell[0], targetCell[1]);
        if (!mAddToExistingFolderOnDrop) return false;
        mAddToExistingFolderOnDrop = false;

        if (dropOverView instanceof FolderIcon) {
            FolderIcon fi = (FolderIcon) dropOverView;
            if (fi.acceptDrop(d.dragInfo)) {
                mStatsLogManager.logger().withItemInfo(fi.mInfo).withInstanceId(d.logInstanceId)
                        .log(LauncherEvent.LAUNCHER_ITEM_DROP_COMPLETED_ON_FOLDER_ICON);
                fi.onDrop(d, false /* itemReturnedOnFailedDrop */);
                // if the drag started here, we need to remove it from the workspace
                if (!external) {
                    getParentCellLayoutForView(mDragInfo.cell).removeView(mDragInfo.cell);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void prepareAccessibilityDrop() {}

    @Override
    public void onDrop(final DragObject d, DragOptions options) {
        mDragViewVisualCenter = d.getVisualCenter(mDragViewVisualCenter);
        CellLayout dropTargetLayout = mDropToLayout;

        // We want the point to be mapped to the dragTarget.
        if (dropTargetLayout != null) {
            mapPointFromDropLayout(dropTargetLayout, mDragViewVisualCenter);
        }

        boolean droppedOnOriginalCell = false;

        boolean snappedToNewPage = false;
        boolean resizeOnDrop = false;
        Runnable onCompleteRunnable = null;
        boolean forceWidgetResize = PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.getForceWidgetResize());
        if (d.dragSource != this || mDragInfo == null) {
            // AresLauncher §15: onDropExternal is grid-native and cannot serve the home list -- it
            // animates the drag view onto a child it just added to a CellLayout, and Strategy D
            // never adds one, so the animation NPEs on the missing parent (an app dragged out of
            // the app list crashed the launcher). AresHomeDrop takes the desktop case; the hotseat
            // is still a real CellLayout and falls through untouched.
            if (!AresHomeDrop.handleExternalDrop(
                    mLauncher, mLauncher.isHotseatLayout(dropTargetLayout), d)) {
                final int[] touchXY = new int[]{(int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1]};
                onDropExternal(touchXY, dropTargetLayout, d);
            }
        } else {
            final View cell = mDragInfo.cell;
            boolean droppedOnOriginalCellDuringTransition = false;

            if (dropTargetLayout != null && !d.cancelled) {
                // Move internally
                boolean hasMovedLayouts = (getParentCellLayoutForView(cell) != dropTargetLayout);
                boolean hasMovedIntoHotseat = mLauncher.isHotseatLayout(dropTargetLayout);
                int container = hasMovedIntoHotseat ? CONTAINER_HOTSEAT : CONTAINER_DESKTOP;
                int screenId = (mTargetCell[0] < 0) ?
                        mDragInfo.screenId : getCellLayoutId(dropTargetLayout);
                int spanX = mDragInfo != null ? mDragInfo.spanX : 1;
                int spanY = mDragInfo != null ? mDragInfo.spanY : 1;
                // First we find the cell nearest to point at which the item is
                // dropped, without any consideration to whether there is an item there.

                mTargetCell = findNearestArea((int) mDragViewVisualCenter[0], (int)
                        mDragViewVisualCenter[1], spanX, spanY, dropTargetLayout, mTargetCell);
                float distance = dropTargetLayout.getDistanceFromWorkspaceCellVisualCenter(
                        mDragViewVisualCenter[0], mDragViewVisualCenter[1], mTargetCell);

                // If the item being dropped is a shortcut and the nearest drop
                // cell also contains a shortcut, then create a folder with the two shortcuts.
                if (createUserFolderIfNecessary(cell, container, dropTargetLayout, mTargetCell,
                        distance, false, d)
                        || addToExistingFolderIfNecessary(cell, dropTargetLayout, mTargetCell,
                        distance, d, false)) {
                    if (!mLauncher.isInState(EDIT_MODE)) {
                        mLauncher.getStateManager().goToState(NORMAL, SPRING_LOADED_EXIT_DELAY);
                    }
                    return;
                }

                // Aside from the special case where we're dropping a shortcut onto a shortcut,
                // we need to find the nearest cell location that is vacant
                ItemInfo item = d.dragInfo;
                int minSpanX = item.spanX;
                int minSpanY = item.spanY;
                if (item.minSpanX > 0 && item.minSpanY > 0) {
                    minSpanX = item.minSpanX;
                    minSpanY = item.minSpanY;
                }

                CellPos originalPresenterPos = getCellPosMapper().mapModelToPresenter(item);
                droppedOnOriginalCell = originalPresenterPos.screenId == screenId
                        && item.container == container
                        && originalPresenterPos.cellX == mTargetCell[0]
                        && originalPresenterPos.cellY == mTargetCell[1];
                droppedOnOriginalCellDuringTransition = droppedOnOriginalCell && mIsSwitchingState;

                // When quickly moving an item, a user may accidentally rearrange their
                // workspace. So instead we move the icon back safely to its original position.
                boolean returnToOriginalCellToPreventShuffling = !isFinishedSwitchingState()
                        && !droppedOnOriginalCellDuringTransition && !dropTargetLayout
                        .isRegionVacant(mTargetCell[0], mTargetCell[1], spanX, spanY);
                int[] resultSpan = new int[2];
                if (returnToOriginalCellToPreventShuffling) {
                    mTargetCell[0] = mTargetCell[1] = -1;
                } else {
                    mTargetCell = dropTargetLayout.performReorder((int) mDragViewVisualCenter[0],
                            (int) mDragViewVisualCenter[1], minSpanX, minSpanY, spanX, spanY,
                            cell, mTargetCell, resultSpan, CellLayout.MODE_ON_DROP);
                }

                boolean foundCell = mTargetCell[0] >= 0 && mTargetCell[1] >= 0;

                // if the widget resizes on drop
                if (foundCell && (cell instanceof AppWidgetHostView) &&
                        (resultSpan[0] != item.spanX || resultSpan[1] != item.spanY)) {
                    resizeOnDrop = true;
                    item.spanX = resultSpan[0];
                    item.spanY = resultSpan[1];
                    AppWidgetHostView awhv = (AppWidgetHostView) cell;
                    WidgetSizes.updateWidgetSizeRanges(awhv, mLauncher, resultSpan[0],
                            resultSpan[1]);
                }

                if (foundCell) {
                    int targetScreenIndex = getPageIndexForScreenId(screenId);
                    int snapScreen = getLeftmostVisiblePageForIndex(targetScreenIndex);
                    // On large screen devices two pages can be shown at the same time, and snap
                    // isn't needed if the source and target screens appear at the same time
                    if (snapScreen != mCurrentPage && !hasMovedIntoHotseat) {
                        snapToPage(snapScreen);
                        snappedToNewPage = true;
                    }
                    final ItemInfo info = (ItemInfo) cell.getTag();
                    if (hasMovedLayouts) {
                        // Reparent the view
                        CellLayout parentCell = getParentCellLayoutForView(cell);
                        if (parentCell != null) {
                            parentCell.removeView(cell);
                        } else if (mDragInfo.cell instanceof LauncherAppWidgetHostView) {
                            d.dragView.detachContentView(/* reattachToPreviousParent= */ false);
                        } else if (FeatureFlags.IS_STUDIO_BUILD) {
                            throw new NullPointerException("mDragInfo.cell has null parent");
                        }
                        addInScreen(cell, container, screenId, mTargetCell[0], mTargetCell[1],
                                info.spanX, info.spanY);
                    }

                    // update the item's position after drop
                    CellLayoutLayoutParams lp = (CellLayoutLayoutParams) cell.getLayoutParams();
                    lp.setTmpCellX(mTargetCell[0]);
                    lp.setCellX(mTargetCell[0]);
                    lp.setTmpCellY(mTargetCell[1]);
                    lp.setCellY(mTargetCell[1]);
                    lp.cellHSpan = item.spanX;
                    lp.cellVSpan = item.spanY;
                    lp.isLockedToGrid = true;

                    if (container != CONTAINER_HOTSEAT
                            && cell instanceof LauncherAppWidgetHostView) {

                        // We post this call so that the widget has a chance to be placed
                        // in its final location
                        onCompleteRunnable = getWidgetResizeFrameRunnable(options,
                                (LauncherAppWidgetHostView) cell, dropTargetLayout, forceWidgetResize);
                    }
                    mLauncher.getModelWriter().modifyItemInDatabase(info, container, screenId,
                            lp.getCellX(), lp.getCellY(), item.spanX, item.spanY);
                } else {
                    if (!returnToOriginalCellToPreventShuffling) {
                        onNoCellFound(dropTargetLayout, d.dragInfo, d.logInstanceId);
                    }
                    if (mDragInfo.cell instanceof LauncherAppWidgetHostView) {
                        d.dragView.detachContentView(/* reattachToPreviousParent= */ true);
                    }

                    // If we can't find a drop location, we return the item to its original position
                    CellLayoutLayoutParams lp = (CellLayoutLayoutParams) cell.getLayoutParams();
                    mTargetCell[0] = lp.getCellX();
                    mTargetCell[1] = lp.getCellY();
                    CellLayout layout = (CellLayout) cell.getParent().getParent();
                    layout.markCellsAsOccupiedForView(cell);
                }
            } else {
                // When drag is cancelled, reattach content view back to its original parent.
                if (cell instanceof LauncherAppWidgetHostView) {
                    d.dragView.detachContentView(/* reattachToPreviousParent= */ true);

                    final CellLayout cellLayout = getParentCellLayoutForView(cell);
                    boolean pageIsVisible = isVisible(cellLayout);

                    if (pageIsVisible) {
                        onCompleteRunnable = getWidgetResizeFrameRunnable(options,
                                (LauncherAppWidgetHostView) cell, cellLayout, forceWidgetResize);
                    }
                }
            }

            final CellLayout parent = (CellLayout) cell.getParent().getParent();
            if (d.dragView.hasDrawn()) {
                if (droppedOnOriginalCellDuringTransition) {
                    // Animate the item to its original position, while simultaneously exiting
                    // spring-loaded mode so the page meets the icon where it was picked up.
                    final RunnableList callbackList = new RunnableList();
                    final Runnable onCompleteCallback = onCompleteRunnable;
                    LauncherState currentState = mLauncher.getStateManager().getState();
                    mLauncher.getDragController().animateDragViewToOriginalPosition(
                            /* onComplete= */ callbackList::executeAllAndDestroy, cell,
                            currentState.getTransitionDuration(mLauncher, true /* isToState */));
                    if (!mLauncher.isInState(EDIT_MODE)) {
                        mLauncher.getStateManager().goToState(NORMAL, /* delay= */ 0,
                                onCompleteCallback == null
                                        ? null
                                        : forSuccessCallback(
                                                () -> callbackList.add(onCompleteCallback)));
                    } else if (onCompleteCallback != null) {
                        forSuccessCallback(() -> callbackList.add(onCompleteCallback));
                    }
                    mLauncher.getDropTargetBar().onDragEnd();
                    parent.onDropChild(cell);
                    return;
                }
                final ItemInfo info = (ItemInfo) cell.getTag();
                boolean isWidget = info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET
                        || info.itemType == LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET;
                if (isWidget && dropTargetLayout != null) {
                    // animate widget to a valid place
                    int animationType = resizeOnDrop ? ANIMATE_INTO_POSITION_AND_RESIZE :
                            ANIMATE_INTO_POSITION_AND_DISAPPEAR;
                    animateWidgetDrop(info, parent, d.dragView, null, animationType, cell, false);
                } else {
                    int duration = snappedToNewPage ? ADJACENT_SCREEN_DROP_DURATION : -1;
                    mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, cell, duration,
                            this);
                }
            } else {
                d.deferDragViewCleanupPostAnimation = false;
                cell.setVisibility(VISIBLE);
            }
            parent.onDropChild(cell);

            if (!mLauncher.isInState(EDIT_MODE)) {
                mLauncher.getStateManager().goToState(NORMAL, SPRING_LOADED_EXIT_DELAY,
                        onCompleteRunnable == null ? null : forSuccessCallback(onCompleteRunnable));
            } else if (onCompleteRunnable != null) {
                forSuccessCallback(onCompleteRunnable);
            }
            mStatsLogManager.logger().withItemInfo(d.dragInfo).withInstanceId(d.logInstanceId)
                    .log(LauncherEvent.LAUNCHER_ITEM_DROP_COMPLETED);

            if (mAccessibilityDragListener != null) {
                // This code needs to be called after StateManager.cancelAnimation. Before changing
                // the order of operations in this method related to the StateListener below, please
                // test that accessibility moves retain focus after accessibility dropping an item.
                // Accessibility focus must be requested after launcher is back to a normal state
                cell.setTag(R.id.perform_a11y_action_on_launcher_state_normal_tag,
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            }
        }

        if (d.stateAnnouncer != null && !droppedOnOriginalCell) {
            d.stateAnnouncer.completeAction(R.string.item_moved);
        }
        TestEventEmitter.sendEvent(TestEvent.WORKSPACE_ON_DROP);
    }

    @Nullable
    private Runnable getWidgetResizeFrameRunnable(DragOptions options,
            LauncherAppWidgetHostView hostView, CellLayout cellLayout, boolean force) {
        AppWidgetProviderInfo pInfo = hostView.getAppWidgetInfo();
        boolean shouldResize = (pInfo.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) || force;
        if (pInfo != null && shouldResize && !options.isAccessibleDrag) {
            return () -> {
                if (!isPageInTransition()) {
                    AppWidgetResizeFrame.showForWidget(hostView, cellLayout);
                }
            };
        }
        return null;
    }

    public void onNoCellFound(
            View dropTargetLayout, ItemInfo itemInfo, @Nullable InstanceId logInstanceId) {
        int strId = mLauncher.isHotseatLayout(dropTargetLayout)
                ? R.string.hotseat_out_of_space : R.string.out_of_space;
        Toast.makeText(mLauncher, mLauncher.getString(strId), Toast.LENGTH_SHORT).show();
        StatsLogManager.StatsLogger logger = mStatsLogManager.logger().withItemInfo(itemInfo);
        if (logInstanceId != null) {
            logger = logger.withInstanceId(logInstanceId);
        }
        logger.log(LauncherEvent.LAUNCHER_ITEM_DROP_FAILED_INSUFFICIENT_SPACE);
    }

    /**
     * Computes and returns the area relative to dragLayer which is used to display a page.
     * In case we have multiple pages displayed at the same time, we return the union of the areas.
     */
    public Rect getPageAreaRelativeToDragLayer() {
        Rect area = new Rect();
        int nextPage = getNextPage();
        int panelCount = getPanelCount();
        for (int page = nextPage; page < nextPage + panelCount; page++) {
            CellLayout child = (CellLayout) getChildAt(page);
            if (child == null) {
                break;
            }

            ShortcutAndWidgetContainer boundingLayout = child.getShortcutsAndWidgets();
            Rect tmpRect = new Rect();
            mLauncher.getDragLayer().getDescendantRectRelativeToSelf(boundingLayout, tmpRect);
            area.union(tmpRect);
        }

        return area;
    }

    @Override
    public void onDragEnter(DragObject d) {
        if (ENFORCE_DRAG_EVENT_ORDER) {
            enforceDragParity("onDragEnter", 1, 1);
        }

        mCreateUserFolderOnDrop = false;
        mAddToExistingFolderOnDrop = false;

        mDropToLayout = null;
        mDragViewVisualCenter = d.getVisualCenter(mDragViewVisualCenter);
        setDropLayoutForDragObject(d, mDragViewVisualCenter[0], mDragViewVisualCenter[1]);
    }

    @Override
    public void onDragExit(DragObject d) {
        if (ENFORCE_DRAG_EVENT_ORDER) {
            enforceDragParity("onDragExit", -1, 0);
        }

        // Here we store the final page that will be dropped to, if the workspace in fact
        // receives the drop
        mDropToLayout = mDragTargetLayout;
        if (mDragMode == DRAG_MODE_CREATE_FOLDER) {
            mCreateUserFolderOnDrop = true;
        } else if (mDragMode == DRAG_MODE_ADD_TO_FOLDER) {
            mAddToExistingFolderOnDrop = true;
        }

        // Reset the previous drag target
        setCurrentDropLayout(null);
        setCurrentDragOverlappingLayout(null);

        mSpringLoadedDragController.cancel();
    }

    private void enforceDragParity(String event, int update, int expectedValue) {
        enforceDragParity(this, event, update, expectedValue);
        for (int i = 0; i < getChildCount(); i++) {
            enforceDragParity(getChildAt(i), event, update, expectedValue);
        }
    }

    private void enforceDragParity(View v, String event, int update, int expectedValue) {
        Object tag = v.getTag(R.id.drag_event_parity);
        int value = tag == null ? 0 : (Integer) tag;
        value += update;
        v.setTag(R.id.drag_event_parity, value);

        if (value != expectedValue) {
            Log.e(TAG, event + ": Drag contract violated: " + value);
        }
    }

    void setCurrentDropLayout(CellLayout layout) {
        if (mDragTargetLayout != null) {
            mDragTargetLayout.revertTempState();
            mDragTargetLayout.onDragExit();
        }
        mDragTargetLayout = layout;
        if (mDragTargetLayout != null) {
            mDragTargetLayout.onDragEnter();
        }
        cleanupReorder(true);
        cleanupFolderCreation();
        setCurrentDropOverCell(-1, -1);
    }

    void setCurrentDragOverlappingLayout(CellLayout layout) {
        if (mDragOverlappingLayout != null) {
            mDragOverlappingLayout.setIsDragOverlapping(false);
        }
        mDragOverlappingLayout = layout;
        if (mDragOverlappingLayout != null) {
            mDragOverlappingLayout.setIsDragOverlapping(true);
        }
    }

    void setCurrentDropOverCell(int x, int y) {
        if (x != mDragOverX || y != mDragOverY) {
            mDragOverX = x;
            mDragOverY = y;
            setDragMode(DRAG_MODE_NONE);
        }
    }

    void setDragMode(int dragMode) {
        if (dragMode != mDragMode) {
            if (dragMode == DRAG_MODE_NONE) {
                cleanupAddToFolder();
                // We don't want to cancel the re-order alarm every time the target cell changes
                // as this feels to slow / unresponsive.
                cleanupReorder(false);
                cleanupFolderCreation();
            } else if (dragMode == DRAG_MODE_ADD_TO_FOLDER) {
                cleanupReorder(true);
                cleanupFolderCreation();
            } else if (dragMode == DRAG_MODE_CREATE_FOLDER) {
                cleanupAddToFolder();
                cleanupReorder(true);
            } else if (dragMode == DRAG_MODE_REORDER) {
                cleanupAddToFolder();
                cleanupFolderCreation();
            }
            mDragMode = dragMode;
        }
    }

    protected void cleanupFolderCreation() {
        if (mFolderCreateBg != null) {
            mFolderCreateBg.animateToRest();
        }

        if (mDragOverView instanceof AppPairIcon api) {
            api.getIconDrawableArea().onTemporaryContainerChange(null);
            mDragOverView = null;
        }
    }

    private void cleanupAddToFolder() {
        if (mDragOverFolderIcon != null) {
            mDragOverFolderIcon.onDragExit();
            mDragOverFolderIcon = null;
        }
    }

    protected void cleanupReorder(boolean cancelAlarm) {
        // Any pending reorders are canceled
        if (cancelAlarm) {
            mReorderAlarm.cancelAlarm();
        }
        mLastReorderX = -1;
        mLastReorderY = -1;
    }

    /*
     *
     * Convert the 2D coordinate xy from the parent View's coordinate space to this CellLayout's
     * coordinate space. The argument xy is modified with the return result.
     */
    private void mapPointFromSelfToChild(View v, float[] xy) {
        xy[0] = xy[0] - v.getLeft();
        xy[1] = xy[1] - v.getTop();
    }

    /**
     * Updates the point in {@param xy} to point to the co-ordinate space of {@param layout}
     *
     * @param layout either hotseat of a page in workspace
     * @param xy     the point location in workspace co-ordinate space
     */
    private void mapPointFromDropLayout(CellLayout layout, float[] xy) {
        if (mLauncher.isHotseatLayout(layout)) {
            mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(this, xy, true);
            mLauncher.getDragLayer().mapCoordInSelfToDescendant(layout, xy);
        } else {
            mapPointFromSelfToChild(layout, xy);
        }
    }

    private boolean isDragWidget(DragObject d) {
        return (d.dragInfo instanceof LauncherAppWidgetInfo ||
                d.dragInfo instanceof PendingAddWidgetInfo);
    }

    public void onDragOver(DragObject d) {
        // Skip drag over events while we are dragging over side pages
        if (!transitionStateShouldAllowDrop()) return;

        ItemInfo item = d.dragInfo;
        if (item == null) {
            if (FeatureFlags.IS_STUDIO_BUILD) {
                throw new NullPointerException("DragObject has null info");
            }
            return;
        }

        // Ensure that we have proper spans for the item that we are dropping
        if (item.spanX < 0 || item.spanY < 0) throw new RuntimeException("Improper spans found");
        mDragViewVisualCenter = d.getVisualCenter(mDragViewVisualCenter);

        // AresLauncher: dwell-to-drop-in (§17). Observation only -- it starts no state machine of
        // stock's and changes nothing below it. This is the only per-move hook a DragController drag
        // offers, and the dwell needs the drag's position even (especially) when it is not moving.
        // It deliberately does NOT use mDragViewVisualCenter, which on this launcher lands 228px
        // above the finger; see AresFolderDrop#onExternalDragOver for the measurement.
        AresFolderDrop.onExternalDragOver(mLauncher, d);
        // AresLauncher §C4: and the grid opens a space for it while it is still held, the same way
        // an in-grid drag does. Also observation-only from stock's point of view -- it adds an
        // entry to our own adapter and changes nothing below this line.
        AresHomeDropPreview.onExternalDragOver(mLauncher, d);

        final View child = (mDragInfo == null) ? null : mDragInfo.cell;
        if (setDropLayoutForDragObject(d, mDragViewVisualCenter[0], mDragViewVisualCenter[1])) {
            if (mDragTargetLayout == null || mLauncher.isHotseatLayout(mDragTargetLayout)) {
                mSpringLoadedDragController.cancel();
            } else {
                mSpringLoadedDragController.setAlarm(mDragTargetLayout);
            }
        }

        // Handle the drag over
        if (mDragTargetLayout != null) {
            // We want the point to be mapped to the dragTarget.
            mapPointFromDropLayout(mDragTargetLayout, mDragViewVisualCenter);

            int minSpanX = item.spanX;
            int minSpanY = item.spanY;
            if (item.minSpanX > 0 && item.minSpanY > 0) {
                minSpanX = item.minSpanX;
                minSpanY = item.minSpanY;
            }

            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], item.spanX, item.spanY,
                    mDragTargetLayout, mTargetCell);
            int reorderX = mTargetCell[0];
            int reorderY = mTargetCell[1];

            setCurrentDropOverCell(mTargetCell[0], mTargetCell[1]);

            float targetCellDistance = mDragTargetLayout.getDistanceFromWorkspaceCellVisualCenter(
                    mDragViewVisualCenter[0], mDragViewVisualCenter[1], mTargetCell);

            manageFolderFeedback(targetCellDistance, d);

            boolean nearestDropOccupied = mDragTargetLayout.isNearestDropLocationOccupied((int)
                            mDragViewVisualCenter[0], (int) mDragViewVisualCenter[1], item.spanX,
                    item.spanY, child, mTargetCell);

            manageReorderOnDragOver(d, targetCellDistance, nearestDropOccupied, minSpanX, minSpanY,
                    reorderX, reorderY);

            if (mDragMode == DRAG_MODE_CREATE_FOLDER || mDragMode == DRAG_MODE_ADD_TO_FOLDER ||
                    !nearestDropOccupied) {
                if (mDragTargetLayout != null) {
                    mDragTargetLayout.revertTempState();
                }
            }
        }
    }

    protected void manageReorderOnDragOver(DragObject d, float targetCellDistance,
            boolean nearestDropOccupied, int minSpanX, int minSpanY, int reorderX, int reorderY) {

        ItemInfo item = d.dragInfo;
        final View child = (mDragInfo == null) ? null : mDragInfo.cell;
        if (!nearestDropOccupied) {
            int[] span = new int[2];
            mDragTargetLayout.performReorder((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, item.spanX, item.spanY,
                    child, mTargetCell, span, CellLayout.MODE_SHOW_REORDER_HINT);
            mDragTargetLayout.visualizeDropLocation(mTargetCell[0], mTargetCell[1], span[0],
                    span[1], d);
            nearestDropOccupied = mDragTargetLayout.isNearestDropLocationOccupied((int)
                            mDragViewVisualCenter[0], (int) mDragViewVisualCenter[1], item.spanX,
                    item.spanY, child, mTargetCell);
        } else if ((mDragMode == DRAG_MODE_NONE || mDragMode == DRAG_MODE_REORDER)
                && (mLastReorderX != reorderX || mLastReorderY != reorderY)
                && targetCellDistance < mDragTargetLayout.getReorderRadius(mTargetCell, item.spanX,
                item.spanY)) {
            mReorderAlarm.cancelAlarm();
            mLastReorderX = reorderX;
            mLastReorderY = reorderY;
            mDragTargetLayout.performReorder((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, item.spanX, item.spanY,
                    child, mTargetCell, new int[2], CellLayout.MODE_SHOW_REORDER_HINT);
            // Otherwise, if we aren't adding to or creating a folder and there's no pending
            // reorder, then we schedule a reorder
            ReorderAlarmListener listener = new ReorderAlarmListener(mDragViewVisualCenter,
                    minSpanX, minSpanY, item.spanX, item.spanY, d, child);
            mReorderAlarm.setOnAlarmListener(listener);
            mReorderAlarm.setAlarm(REORDER_TIMEOUT);
        }
    }

    /**
     * Updates {@link #mDragTargetLayout} and {@link #mDragOverlappingLayout}
     * based on the DragObject's position.
     *
     * The layout will be:
     * - The Hotseat if the drag object is over it
     * - A side page if we are in spring-loaded mode and the drag object is over it
     * - The current page otherwise
     *
     * @return whether the layout is different from the current {@link #mDragTargetLayout}.
     */
    private boolean setDropLayoutForDragObject(DragObject d, float centerX, float centerY) {
        CellLayout layout = null;
        if (shouldUseHotseatAsDropLayout(d)) {
            layout = resolveHotseatDropLayout(d);
        } else if (!isDragObjectOverSmartSpace(d)) {
            // If the object is over qsb/smartspace, we don't want to highlight anything.

            // Check neighbour pages
            layout = checkDragObjectIsOverNeighbourPages(d, centerX);

            if (layout == null) {
                // Check visible pages
                IntSet visiblePageIndices = getVisiblePageIndices();
                for (int visiblePageIndex : visiblePageIndices) {
                    layout = verifyInsidePage(visiblePageIndex, d.x, d.y);
                    if (layout != null) break;
                }
            }
        }

        // Update the current drop layout if the target changed
        if (layout != mDragTargetLayout) {
            setCurrentDropLayout(layout);
            setCurrentDragOverlappingLayout(layout);
            return true;
        }
        return false;
    }

    @Nullable
    private CellLayout resolveHotseatDropLayout(DragObject dragObject) {
        Hotseat hotseat = mLauncher.getHotseat();
        if (hotseat == null) {
            return null;
        }
        // Prefer the visible dock page so drops land where the user is looking.
        CellLayout current = hotseat.getCurrentPageLayout();
        HotseatPagedView pager = hotseat.getPagedView();
        if (pager != null && pager.isPageInTransition()) {
            float[] xy = new float[] { dragObject.x, dragObject.y };
            mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(this, xy, true);
            mLauncher.getDragLayer().mapCoordInSelfToDescendant(pager, xy);
            CellLayout underFinger = pager.findPageAtLocalX(xy[0]);
            if (underFinger != null) {
                return underFinger;
            }
        }
        return current;
    }

    private boolean shouldUseHotseatAsDropLayout(DragObject dragObject) {
        if (mLauncher.getHotseat() == null
                || mLauncher.getHotseat().getShortcutsAndWidgets() == null
                || isDragWidget(dragObject)) {
            return false;
        }
        View hotseatIcons = mLauncher.getHotseat().getPagedView();
        getViewBoundsRelativeToWorkspace(hotseatIcons, mTempRect);
        return mTempRect.contains(dragObject.x, dragObject.y);
    }

    private boolean isDragObjectOverSmartSpace(DragObject dragObject) {
        if (mFirstPagePinnedItem == null) {
            return false;
        }
        getViewBoundsRelativeToWorkspace(mFirstPagePinnedItem, mTempRect);
        return mTempRect.contains(dragObject.x, dragObject.y);
    }

    private CellLayout checkDragObjectIsOverNeighbourPages(DragObject d, float centerX) {
        if (isPageInTransition()) {
            return null;
        }

        // Check the workspace pages whether the object is over any of them

        // Note, centerX represents the center of the object that is being dragged, visually.
        // d.x represents the location of the finger within the dragged item.
        float touchX;
        float touchY = d.y;

        // Go through the pages and check if the dragged item is inside one of them. This block
        // is responsible for determining whether we need to snap to a different screen.
        int nextPage = getNextPage();
        IntSet pageIndexesToVerify = IntSet.wrap(nextPage - 1,
                nextPage + (isTwoPanelEnabled() ? 2 : 1));

        for (int pageIndex : pageIndexesToVerify) {
            // When deciding whether to perform a page switch, we need to consider the most
            // extreme X coordinate between the finger location and the center of the object
            // being dragged. This is either the max or the min of the two depending on whether
            // dragging to the left / right, respectively.
            touchX = (((pageIndex < nextPage) && !mIsRtl) || (pageIndex > nextPage && mIsRtl))
                    ? Math.min(d.x, centerX) : Math.max(d.x, centerX);
            CellLayout layout = verifyInsidePage(pageIndex, touchX, touchY);
            if (layout != null) {
                return layout;
            }
        }
        return null;
    }

    /**
     * Gets the given view's bounds relative to Workspace
     */
    private void getViewBoundsRelativeToWorkspace(View view, Rect outRect) {
        mLauncher.getDragLayer()
                .getDescendantRectRelativeToSelf(view, mTempRect);
        // map draglayer relative bounds to workspace
        mLauncher.getDragLayer().mapRectInSelfToDescendant(this, mTempRect);
        outRect.set(mTempRect);
    }

    /**
     * Returns the child CellLayout if the point is inside the page coordinates, null otherwise.
     */
    private CellLayout verifyInsidePage(int pageNo, float x, float y) {
        if (pageNo >= 0 && pageNo < getPageCount()) {
            CellLayout cl = (CellLayout) getChildAt(pageNo);
            if (x >= cl.getLeft() && x <= cl.getRight()
                    && y >= cl.getTop() && y <= cl.getBottom()) {
                // This point is inside the cell layout
                return cl;
            }
        }
        return null;
    }

    private void manageFolderFeedback(float distance, DragObject dragObject) {
        if (distance > mDragTargetLayout.getFolderCreationRadius(mTargetCell)) {
            if ((mDragMode == DRAG_MODE_ADD_TO_FOLDER
                    || mDragMode == DRAG_MODE_CREATE_FOLDER)) {
                setDragMode(DRAG_MODE_NONE);
            }
            return;
        }

        mDragOverView = mDragTargetLayout.getChildAt(mTargetCell[0], mTargetCell[1]);
        ItemInfo info = dragObject.dragInfo;
        boolean userFolderPending = willCreateUserFolder(info, mDragOverView, false);
        if (mDragMode == DRAG_MODE_NONE && userFolderPending) {
            if (Flags.msdlFeedback()) {
                mMSDLPlayerWrapper.playToken(MSDLToken.DRAG_INDICATOR_DISCRETE);
            }
            mFolderCreateBg = new PreviewBackground(getContext());
            mFolderCreateBg.setup(mLauncher, mLauncher, null,
                    mDragOverView.getMeasuredWidth(), mDragOverView.getPaddingTop());

            // The full preview background should appear behind the icon
            mFolderCreateBg.isClipping = false;

            if (mDragOverView instanceof AppPairIcon api) {
                api.getIconDrawableArea().onTemporaryContainerChange(DISPLAY_FOLDER);
            }

            mFolderCreateBg.animateToAccept(mDragTargetLayout, mTargetCell[0], mTargetCell[1]);
            mDragTargetLayout.clearDragOutlines();
            setDragMode(DRAG_MODE_CREATE_FOLDER);

            if (dragObject.stateAnnouncer != null) {
                dragObject.stateAnnouncer.announce(WorkspaceAccessibilityHelper
                        .getDescriptionForDropOver(mDragOverView, getContext()));
            }
            return;
        }

        boolean willAddToFolder = willAddToExistingUserFolder(info, mDragOverView);
        if (willAddToFolder && mDragMode == DRAG_MODE_NONE) {
            mDragOverFolderIcon = ((FolderIcon) mDragOverView);
            mDragOverFolderIcon.onDragEnter(info);
            if (mDragTargetLayout != null) {
                mDragTargetLayout.clearDragOutlines();
            }
            setDragMode(DRAG_MODE_ADD_TO_FOLDER);

            if (dragObject.stateAnnouncer != null) {
                dragObject.stateAnnouncer.announce(WorkspaceAccessibilityHelper
                        .getDescriptionForDropOver(mDragOverView, getContext()));
            }
            return;
        }

        if (mDragMode == DRAG_MODE_ADD_TO_FOLDER && !willAddToFolder) {
            setDragMode(DRAG_MODE_NONE);
        }
        if (mDragMode == DRAG_MODE_CREATE_FOLDER && !userFolderPending) {
            setDragMode(DRAG_MODE_NONE);
        }
    }

    class ReorderAlarmListener implements OnAlarmListener {
        final float[] dragViewCenter;
        final int minSpanX, minSpanY, spanX, spanY;
        final DragObject dragObject;
        final View child;

        public ReorderAlarmListener(float[] dragViewCenter, int minSpanX, int minSpanY, int spanX,
                                    int spanY, DragObject dragObject, View child) {
            this.dragViewCenter = dragViewCenter;
            this.minSpanX = minSpanX;
            this.minSpanY = minSpanY;
            this.spanX = spanX;
            this.spanY = spanY;
            this.child = child;
            this.dragObject = dragObject;
        }

        public void onAlarm(Alarm alarm) {
            int[] resultSpan = new int[2];
            mTargetCell = findNearestArea((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, mDragTargetLayout,
                    mTargetCell);

            mTargetCell = mDragTargetLayout.performReorder((int) mDragViewVisualCenter[0],
                    (int) mDragViewVisualCenter[1], minSpanX, minSpanY, spanX, spanY,
                    child, mTargetCell, resultSpan, CellLayout.MODE_DRAG_OVER);

            if (mTargetCell[0] < 0 || mTargetCell[1] < 0) {
                mDragTargetLayout.revertTempState();
            } else {
                setDragMode(DRAG_MODE_REORDER);
            }

            mDragTargetLayout.visualizeDropLocation(mTargetCell[0], mTargetCell[1],
                    resultSpan[0], resultSpan[1], dragObject);
        }
    }

    @Override
    public void getHitRectRelativeToDragLayer(Rect outRect) {
        // We want the workspace to have the whole area of the display (it will find the correct
        // cell layout to drop to in the existing drag/drop logic.
        mLauncher.getDragLayer().getDescendantRectRelativeToSelf(this, outRect);
    }

    /**
     * Drop an item that didn't originate on one of the workspace screens.
     * It may have come from Launcher (e.g. from all apps or customize), or it may have
     * come from another app altogether.
     * <p>
     * NOTE: This can also be called when we are outside of a drag event, when we want
     * to add an item to one of the workspace screens.
     */
    private void onDropExternal(final int[] touchXY, final CellLayout cellLayout, DragObject d) {
        final int container = mLauncher.isHotseatLayout(cellLayout)
                ? CONTAINER_HOTSEAT
                : CONTAINER_DESKTOP;
        if (d.dragInfo instanceof PendingAddShortcutInfo) {
            WorkspaceItemInfo si = ((PendingAddShortcutInfo) d.dragInfo)
                    .getActivityInfo(mLauncher).createWorkspaceItemInfo();
            if (si != null) {
                d.dragInfo = si;
                si.container = container;
            }
        }

        ItemInfo info = d.dragInfo;
        int spanX = info.spanX;
        int spanY = info.spanY;
        if (mDragInfo != null) {
            spanX = mDragInfo.spanX;
            spanY = mDragInfo.spanY;
        }
        final int screenId = getCellLayoutId(cellLayout);
        if (!mLauncher.isHotseatLayout(cellLayout)
                && screenId != getScreenIdForPageIndex(mCurrentPage)
                && !mLauncher.isInState(SPRING_LOADED)
                && !mLauncher.isInState(EDIT_MODE)) {
            snapToPage(getPageIndexForScreenId(screenId));
        }

        if (info instanceof PendingAddItemInfo) {
            final PendingAddItemInfo pendingInfo = (PendingAddItemInfo) info;

            boolean findNearestVacantCell = true;
            if (pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
                mTargetCell = findNearestArea(touchXY[0], touchXY[1], spanX, spanY,
                        cellLayout, mTargetCell);
                float distance = cellLayout.getDistanceFromWorkspaceCellVisualCenter(
                        mDragViewVisualCenter[0], mDragViewVisualCenter[1], mTargetCell);
                if (willCreateUserFolder(d.dragInfo, cellLayout, mTargetCell, distance, true)
                        || willAddToExistingUserFolder(
                        d.dragInfo, cellLayout, mTargetCell, distance)) {
                    findNearestVacantCell = false;
                }
            }

            final ItemInfo item = d.dragInfo;
            boolean updateWidgetSize = false;
            if (findNearestVacantCell) {
                int minSpanX = item.spanX;
                int minSpanY = item.spanY;
                if (item.minSpanX > 0 && item.minSpanY > 0) {
                    minSpanX = item.minSpanX;
                    minSpanY = item.minSpanY;
                }
                int[] resultSpan = new int[2];
                mTargetCell = cellLayout.performReorder((int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1], minSpanX, minSpanY, info.spanX, info.spanY,
                        null, mTargetCell, resultSpan, CellLayout.MODE_ON_DROP_EXTERNAL);

                if (resultSpan[0] != item.spanX || resultSpan[1] != item.spanY) {
                    updateWidgetSize = true;
                }
                item.spanX = resultSpan[0];
                item.spanY = resultSpan[1];
            }

            Runnable onAnimationCompleteRunnable = new Runnable() {
                @Override
                public void run() {
                    // Normally removeExtraEmptyScreen is called in Workspace#onDrop, but when
                    // adding an item that may not be dropped right away (due to a config activity)
                    // we defer the removal until the activity returns.
                    deferRemoveExtraEmptyScreen();

                    // When dragging and dropping from customization tray, we deal with creating
                    // widgets/shortcuts/folders in a slightly different way
                    mLauncher.addPendingItem(pendingInfo, container, screenId, mTargetCell,
                            item.spanX, item.spanY);
                    mStatsLogManager.logger().withItemInfo(d.dragInfo)
                            .withInstanceId(d.logInstanceId)
                            .log(LauncherEvent.LAUNCHER_ITEM_DROP_COMPLETED);
                }
            };
            boolean isWidget = pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET
                    || pendingInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET;

            AppWidgetHostView finalView = isWidget ?
                    ((PendingAddWidgetInfo) pendingInfo).boundWidget : null;

            if (finalView != null && updateWidgetSize) {
                WidgetSizes.updateWidgetSizeRanges(finalView, mLauncher, item.spanX, item.spanY);
            }

            int animationStyle = ANIMATE_INTO_POSITION_AND_DISAPPEAR;
            if (isWidget && ((PendingAddWidgetInfo) pendingInfo).info != null &&
                    ((PendingAddWidgetInfo) pendingInfo).getHandler().needsConfigure()) {
                animationStyle = ANIMATE_INTO_POSITION_AND_REMAIN;
            }
            animateWidgetDrop(info, cellLayout, d.dragView, onAnimationCompleteRunnable,
                    animationStyle, finalView, true);
        } else {
            // This is for other drag/drop cases, like dragging from All Apps
            mLauncher.getStateManager().goToState(NORMAL, SPRING_LOADED_EXIT_DELAY);
            // TODO(b/414409465) We could just create a new info making a copy with all the new
            //  needed values instead of choosing on each case what to modify.
            View view = mLauncher.getItemInflater().inflateItem(info, cellLayout, container);
            d.dragInfo = info = (ItemInfo) view.getTag();

            // First we find the cell nearest to point at which the item is
            // dropped, without any consideration to whether there is an item there.
            if (touchXY != null) {
                mTargetCell = findNearestArea(touchXY[0], touchXY[1], spanX, spanY,
                        cellLayout, mTargetCell);
                float distance = cellLayout.getDistanceFromWorkspaceCellVisualCenter(
                        mDragViewVisualCenter[0], mDragViewVisualCenter[1], mTargetCell);
                if (createUserFolderIfNecessary(view, container, cellLayout, mTargetCell, distance,
                        true, d)) {
                    return;
                }
                if (addToExistingFolderIfNecessary(view, cellLayout, mTargetCell, distance, d,
                        true)) {
                    return;
                }
            }

            if (touchXY != null) {
                // when dragging and dropping, just find the closest free spot
                mTargetCell = cellLayout.performReorder((int) mDragViewVisualCenter[0],
                        (int) mDragViewVisualCenter[1], 1, 1, 1, 1,
                        null, mTargetCell, null, CellLayout.MODE_ON_DROP_EXTERNAL);
            } else {
                cellLayout.findCellForSpan(mTargetCell, 1, 1);
            }
            // Add the item to DB before adding to screen ensures that the container and other
            // values of the info is properly updated.
            mLauncher.getModelWriter().addOrMoveItemInDatabase(info, container, screenId,
                    mTargetCell[0], mTargetCell[1]);

            addInScreen(view, container, screenId, mTargetCell[0], mTargetCell[1],
                    info.spanX, info.spanY);
            cellLayout.onDropChild(view);
            cellLayout.getShortcutsAndWidgets().measureChild(view);

            if (d.dragView != null) {
                // We wrap the animation call in the temporary set and reset of the current
                // cellLayout to its final transform -- this means we animate the drag view to
                // the correct final location.
                setFinalTransitionTransform();
                mLauncher.getDragLayer().animateViewIntoPosition(d.dragView, view, this);
                resetTransitionTransform();
            }
            mStatsLogManager.logger().withItemInfo(d.dragInfo).withInstanceId(d.logInstanceId)
                    .log(LauncherEvent.LAUNCHER_ITEM_DROP_COMPLETED);
        }

    }

    private Drawable createWidgetDrawable(ItemInfo widgetInfo, View layout) {
        int[] unScaledSize = estimateItemSize(widgetInfo);
        int visibility = layout.getVisibility();
        layout.setVisibility(VISIBLE);

        int width = MeasureSpec.makeMeasureSpec(unScaledSize[0], MeasureSpec.EXACTLY);
        int height = MeasureSpec.makeMeasureSpec(unScaledSize[1], MeasureSpec.EXACTLY);
        layout.measure(width, height);
        layout.layout(0, 0, unScaledSize[0], unScaledSize[1]);
        Bitmap b = BitmapRenderer.createHardwareBitmap(
                unScaledSize[0], unScaledSize[1], layout::draw);
        layout.setVisibility(visibility);
        return new FastBitmapDrawable(b);
    }

    private void getFinalPositionForDropAnimation(int[] loc, float[] scaleXY,
            DragView dragView, CellLayout layout, ItemInfo info, int[] targetCell, boolean scale,
            final View finalView) {
        // Now we animate the dragView, (ie. the widget or shortcut preview) into its final
        // location and size on the home screen.
        int spanX = info.spanX;
        int spanY = info.spanY;

        Rect r = estimateItemPosition(layout, targetCell[0], targetCell[1], spanX, spanY);
        if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET) {
            DeviceProfile profile = mLauncher.getDeviceProfile();
            if (finalView instanceof NavigableAppWidgetHostView) {
                Rect widgetPadding = profile.widgetPadding;
                r.left -= widgetPadding.left;
                r.right += widgetPadding.right;
                r.top -= widgetPadding.top;
                r.bottom += widgetPadding.bottom;
            }
            PointF appWidgetScale = profile.getAppWidgetScale(null);
            Utilities.shrinkRect(r, appWidgetScale.x, appWidgetScale.y);
        }

        mTempFXY[0] = r.left;
        mTempFXY[1] = r.top;
        setFinalTransitionTransform();
        float cellLayoutScale =
                mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(layout, mTempFXY, true);
        resetTransitionTransform();
        Utilities.roundArray(mTempFXY, loc);

        if (scale) {
            float dragViewScaleX = (1.0f * r.width()) / dragView.getMeasuredWidth();
            float dragViewScaleY = (1.0f * r.height()) / dragView.getMeasuredHeight();

            // The animation will scale the dragView about its center, so we need to center about
            // the final location.
            loc[0] -= (dragView.getMeasuredWidth() - cellLayoutScale * r.width()) / 2
                    - Math.ceil(layout.getUnusedHorizontalSpace() / 2f);
            loc[1] -= (dragView.getMeasuredHeight() - cellLayoutScale * r.height()) / 2;
            scaleXY[0] = dragViewScaleX * cellLayoutScale;
            scaleXY[1] = dragViewScaleY * cellLayoutScale;
        } else {
            // Since we are not cross-fading the dragView, align the drag view to the
            // final cell position.
            float dragScale = dragView.getInitialScale() * cellLayoutScale;
            loc[0] += (dragScale - 1) * dragView.getWidth() / 2;
            loc[1] += (dragScale - 1) * dragView.getHeight() / 2;
            scaleXY[0] = scaleXY[1] = dragScale;

            // If a dragRegion was provided, offset the final position accordingly.
            Rect dragRegion = dragView.getDragRegion();
            if (dragRegion != null) {
                loc[0] += cellLayoutScale * dragRegion.left;
                loc[1] += cellLayoutScale * dragRegion.top;
            }
        }
    }

    public void animateWidgetDrop(ItemInfo info, CellLayout cellLayout, final DragView dragView,
            final Runnable onCompleteRunnable, int animationType, @Nullable final View finalView,
            boolean external) {
        int[] finalPos = new int[2];
        float scaleXY[] = new float[2];
        boolean scalePreview = !(info instanceof PendingAddShortcutInfo);
        getFinalPositionForDropAnimation(finalPos, scaleXY, dragView, cellLayout, info, mTargetCell,
                scalePreview, finalView);

        Resources res = mLauncher.getResources();
        final int duration = res.getInteger(R.integer.config_dropAnimMaxDuration) - 200;

        boolean isWidget = info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET ||
                info.itemType == LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET;
        if ((animationType == ANIMATE_INTO_POSITION_AND_RESIZE || external)
                && finalView != null
                && dragView.getContentView() != finalView) {
            Drawable crossFadeDrawable = createWidgetDrawable(info, finalView);
            dragView.crossFadeContent(crossFadeDrawable, (int) (duration * 0.8f));
        } else if (isWidget && external) {
            scaleXY[0] = scaleXY[1] = Math.min(scaleXY[0], scaleXY[1]);
        }

        DragLayer dragLayer = mLauncher.getDragLayer();
        if (animationType == CANCEL_TWO_STAGE_WIDGET_DROP_ANIMATION) {
            mLauncher.getDragLayer().animateViewIntoPosition(dragView, finalPos, 0f, 0.1f, 0.1f,
                    DragLayer.ANIMATION_END_DISAPPEAR, onCompleteRunnable, duration);
        } else {
            int endStyle;
            if (animationType == ANIMATE_INTO_POSITION_AND_REMAIN) {
                endStyle = DragLayer.ANIMATION_END_REMAIN_VISIBLE;
            } else {
                endStyle = DragLayer.ANIMATION_END_DISAPPEAR;
            }

            Runnable onComplete = new Runnable() {
                @Override
                public void run() {
                    if (finalView != null) {
                        finalView.setVisibility(VISIBLE);
                    }
                    if (onCompleteRunnable != null) {
                        onCompleteRunnable.run();
                    }
                }
            };
            dragLayer.animateViewIntoPosition(dragView, finalPos[0],
                    finalPos[1], 1, scaleXY[0], scaleXY[1], onComplete, endStyle,
                    duration, this);
        }
    }

    public void setFinalTransitionTransform() {
        if (isSwitchingState()) {
            mCurrentScale = getScaleX();
            setScaleX(mStateTransitionAnimation.getFinalScale());
            setScaleY(mStateTransitionAnimation.getFinalScale());
        }
    }

    public void resetTransitionTransform() {
        if (isSwitchingState()) {
            setScaleX(mCurrentScale);
            setScaleY(mCurrentScale);
        }
    }

    /**
     * Return the current CellInfo describing our current drag; this method exists
     * so that Launcher can sync this object with the correct info when the activity is created/
     * destroyed
     */
    public CellInfo getDragInfo() {
        return mDragInfo;
    }

    /**
     * Calculate the nearest cell where the given object would be dropped.
     * <p>
     * pixelX and pixelY should be in the coordinate system of layout
     */
    @Thunk
    int[] findNearestArea(int pixelX, int pixelY,
                          int spanX, int spanY, CellLayout layout, int[] recycle) {
        return layout.findNearestAreaIgnoreOccupied(
                pixelX, pixelY, spanX, spanY, recycle);
    }

    void setup(DragController dragController) {
        mSpringLoadedDragController = new SpringLoadedDragController(mLauncher);
        mDragController = dragController;

        // hardware layers on children are enabled on startup, but should be disabled until
        // needed
        updateChildrenLayersEnabled();
    }

    private boolean isExtraEmptyScreen(int screenId) {
        return screenId == EXTRA_EMPTY_SCREEN_ID || screenId == EXTRA_EMPTY_SCREEN_SECOND_ID;
    }

    private boolean isPageGroupMovable(int pageGroupStart) {
        int panelCount = getPanelCount();
        if (pageGroupStart < 0 || pageGroupStart + panelCount > mScreenOrder.size()) {
            return false;
        }
        for (int i = 0; i < panelCount; i++) {
            if (isExtraEmptyScreen(mScreenOrder.get(pageGroupStart + i))) {
                return false;
            }
        }
        return true;
    }

    private boolean canMovePageGroup(int pageGroupStart, int direction) {
        int panelCount = getPanelCount();
        int targetStart = pageGroupStart + direction * panelCount;
        return isPageGroupMovable(pageGroupStart) && isPageGroupMovable(targetStart);
    }

    private boolean movePageGroup(int pageIndex, int direction) {
        int panelCount = getPanelCount();
        int fromStart = getLeftmostVisiblePageForIndex(pageIndex);
        int targetStart = fromStart + direction * panelCount;
        if (!canMovePageGroup(fromStart, direction)) {
            return false;
        }
        int defaultPage = getDefaultPage();
        int defaultScreenId = getScreenIdForPageIndex(defaultPage);
        IntArray screenOrderSnapshot = new IntArray();
        for (int i = 0; i < mScreenOrder.size(); i++) {
            screenOrderSnapshot.add(mScreenOrder.get(i));
        }
        SparseIntArray screenSwapMap =
                createPageGroupSwapMap(screenOrderSnapshot, fromStart, targetStart, panelCount);
        if (screenSwapMap.size() == 0) {
            return false;
        }
        for (int i = 0; i < panelCount; i++) {
            int fromIndex = fromStart + i;
            int targetIndex = targetStart + i;
            int fromScreenId = mScreenOrder.get(fromIndex);
            int targetScreenId = mScreenOrder.get(targetIndex);
            mScreenOrder.set(fromIndex, targetScreenId);
            mScreenOrder.set(targetIndex, fromScreenId);
        }
        applyScreenOrderToChildViews();
        if (screenSwapMap.size() > 0) {
            mLauncher.getModelWriter().persistWorkspaceScreenOrderSync(getPersistableScreenOrder());
            mDeferStripEmptyScreensForScreenRemap = true;
            mLauncher.getModelWriter().moveWorkspaceScreensInDatabase(
                    screenSwapMap, this::onWorkspaceScreenRemapFinished);
        }
        int remappedDefaultPage = mScreenOrder.indexOf(defaultScreenId);
        if (remappedDefaultPage >= 0 && remappedDefaultPage != defaultPage) {
            setDefaultPage(remappedDefaultPage);
        }
        updateAccessibilityViewPageDescription();
        int pageOffset = pageIndex - fromStart;
        int destinationPage = targetStart + pageOffset;
        setCurrentPage(destinationPage, destinationPage);
        showPageIndicatorAtCurrentScroll();
        return true;
    }

    public IntArray getReorderablePageGroupStarts() {
        IntArray starts = new IntArray();
        int panelCount = getPanelCount();
        IntArray candidateStarts = getPageGroupStarts(mScreenOrder, panelCount);
        for (int i = 0; i < candidateStarts.size(); i++) {
            int start = candidateStarts.get(i);
            if (isPageGroupMovable(start)) {
                starts.add(start);
            }
        }
        return starts;
    }

    @VisibleForTesting
    static IntArray getPageGroupStarts(IntArray screenOrder, int panelCount) {
        IntArray starts = new IntArray();
        for (int i = 0; i < screenOrder.size(); i += panelCount) {
            starts.add(i);
        }
        return starts;
    }

    public boolean moveReorderablePageGroup(int fromGroupIndex, int toGroupIndex) {
        if (fromGroupIndex == toGroupIndex) {
            return true;
        }
        IntArray starts = getReorderablePageGroupStarts();
        if (fromGroupIndex < 0 || fromGroupIndex >= starts.size()
                || toGroupIndex < 0 || toGroupIndex >= starts.size()) {
            return false;
        }
        int fromPage = starts.get(fromGroupIndex);
        int currentGroup = fromGroupIndex;
        int direction = toGroupIndex > fromGroupIndex ? 1 : -1;
        while (currentGroup != toGroupIndex) {
            if (!movePageGroup(fromPage, direction)) {
                return false;
            }
            currentGroup += direction;
            fromPage += direction * getPanelCount();
        }
        return true;
    }

    public boolean setDefaultPageForReorderableGroup(int groupIndex) {
        IntArray starts = getReorderablePageGroupStarts();
        if (groupIndex < 0 || groupIndex >= starts.size()) {
            return false;
        }
        int pageIndex = starts.get(groupIndex);
        setDefaultPage(pageIndex);
        Toast.makeText(mLauncher, R.string.default_home_page_set, Toast.LENGTH_SHORT).show();
        mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS);
        return true;
    }

    public int getDefaultPageGroupIndex() {
        IntArray starts = getReorderablePageGroupStarts();
        int defaultPage = getDefaultPage();
        int leftmostPage = getLeftmostVisiblePageForIndex(defaultPage);
        return starts.indexOf(leftmostPage);
    }

    private IntArray getPersistableScreenOrder() {
        IntArray persistableOrder = new IntArray();
        for (int i = 0; i < mScreenOrder.size(); i++) {
            int screenId = mScreenOrder.get(i);
            if (!isExtraEmptyScreen(screenId)) {
                persistableOrder.add(screenId);
            }
        }
        return persistableOrder;
    }

    private IntSet getPersistedWorkspaceScreenIds() {
        return getPersistedWorkspaceScreenIds(
                LauncherPrefs.get(getContext()).get(LauncherPrefs.WORKSPACE_SCREEN_ORDER));
    }

    @VisibleForTesting
    static IntSet getPersistedWorkspaceScreenIds(String persistedOrder) {
        IntSet ids = new IntSet();
        if (persistedOrder == null || persistedOrder.isBlank()) {
            return ids;
        }
        for (String part : persistedOrder.split(",")) {
            try {
                int id = Integer.parseInt(part.trim());
                if (id >= 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // Skip invalid entries.
            }
        }
        return ids;
    }

    @VisibleForTesting
    static boolean shouldPreserveEmptyScreenWhenStripping(
            int screenId, IntSet persistedScreenIds, boolean isExtraEmptyScreen) {
        return persistedScreenIds.contains(screenId) && !isExtraEmptyScreen;
    }

    private void onWorkspaceScreenRemapFinished() {
        mDeferStripEmptyScreensForScreenRemap = false;
    }

    private void persistCurrentScreenOrderSync() {
        if (mLauncher.isWorkspaceLoading()) {
            return;
        }
        mLauncher.getModelWriter().persistWorkspaceScreenOrderSync(getPersistableScreenOrder());
    }

    private void applyScreenOrderToChildViews() {
        for (int i = 0; i < mScreenOrder.size(); i++) {
            CellLayout layout = mWorkspaceScreens.get(mScreenOrder.get(i));
            if (layout == null) {
                continue;
            }
            int currentIndex = indexOfChild(layout);
            if (currentIndex != i) {
                removeView(layout);
                addView(layout, i);
            }
        }
        updatePageScrollValues();
        // AresLauncher §22: this re-sorts the child views into the persisted screen order, which
        // slides the home list and the app-list pane onto whatever page ends up under them. That is
        // exactly how the pane came to sit three screens off-screen: it was correctly attached to
        // child 1 during the bind, and this reordering moved it to child 3 with nothing to put it
        // back. Re-assert the invariant afterwards.
        postSyncAresDualPane();
    }

    public void reorderBoundWorkspaceScreens(IntArray orderedScreenIds) {
        if (orderedScreenIds == null || orderedScreenIds.isEmpty()) {
            return;
        }
        IntArray reordered = new IntArray();
        for (int i = 0; i < orderedScreenIds.size(); i++) {
            int screenId = orderedScreenIds.get(i);
            if (mWorkspaceScreens.containsKey(screenId) && !reordered.contains(screenId)) {
                reordered.add(screenId);
            }
        }
        for (int i = 0; i < mScreenOrder.size(); i++) {
            int existingId = mScreenOrder.get(i);
            if (!reordered.contains(existingId)) {
                reordered.add(existingId);
            }
        }
        mScreenOrder.clear();
        mScreenOrder.addAll(reordered);
        applyScreenOrderToChildViews();
    }

    @VisibleForTesting
    static SparseIntArray createPageGroupSwapMap(
            IntArray screenOrder, int fromStart, int targetStart, int panelCount) {
        SparseIntArray swapMap = new SparseIntArray(panelCount * 2);
        for (int i = 0; i < panelCount; i++) {
            int fromScreen = screenOrder.get(fromStart + i);
            int targetScreen = screenOrder.get(targetStart + i);
            if (fromScreen != targetScreen) {
                swapMap.put(fromScreen, targetScreen);
                swapMap.put(targetScreen, fromScreen);
            }
        }
        return swapMap;
    }

    /**
     * Called at the end of a drag which originated on the workspace.
     */
    public void onDropCompleted(final View target, final DragObject d,
                                final boolean success) {
        if (success) {
            if (target != this && mDragInfo != null) {
                removeWorkspaceItem(mDragInfo.cell);
            }
        } else if (mDragInfo != null) {
            // When drag is cancelled, reattach content view back to its original parent.
            if (mDragInfo.cell instanceof LauncherAppWidgetHostView && d.dragView != null) {
                d.dragView.detachContentView(/* reattachToPreviousParent= */ true);
            }
            final CellLayout cellLayout = mLauncher.getCellLayout(
                    mDragInfo.container, mDragInfo.screenId);
            if (cellLayout != null) {
                cellLayout.onDropChild(mDragInfo.cell);
            } else if (FeatureFlags.IS_STUDIO_BUILD) {
                throw new RuntimeException("Invalid state: cellLayout == null in "
                        + "Workspace#onDropCompleted. Please file a bug. ");
            }
        }
        View cell = getViewByItemId(d.originalDragInfo.id);
        if (d.cancelled && cell != null) {
            cell.setVisibility(VISIBLE);
        }
        mDragInfo = null;
    }

    /**
     * For opposite operation. See {@link #addInScreen}.
     */
    public void removeWorkspaceItem(View v) {
        // AresLauncher §7: the counterpart of addInScreen's Strategy D redirect. A CONTAINER_DESKTOP
        // item is a RecyclerView row, so getParentCellLayoutForView() below returns null and the
        // removal is silently a no-op. That bit hardest in PendingAppWidgetHostView.reInflate(),
        // which swaps a configured widget in by removing the pending view and immediately rebinding
        // the real one -- with the removal missing, the rebind appended a *second* row for the same
        // widget rather than replacing it. Matched on identity or database id, because the rebind
        // hands back a different ItemInfo instance for the same row.
        if (mAresHomeList != null && v != null && v.getTag() instanceof ItemInfo info
                && info.container == CONTAINER_DESKTOP) {
            mAresHomeList.getAresAdapter().removeItems(other -> other == info
                    || (info.id != ItemInfo.NO_ID && other.id == info.id));
        }
        CellLayout parentCell = getParentCellLayoutForView(v);
        if (parentCell != null) {
            parentCell.removeView(v);
        } else if (FeatureFlags.IS_STUDIO_BUILD) {
            // When an app is uninstalled using the drop target, we wait until resume to remove
            // the icon. We also remove all the corresponding items from the workspace at
            // {@link Launcher#bindComponentsRemoved}. That call can come before or after
            // {@link Launcher#mOnResumeCallbacks} depending on how busy the worker thread is.
            Log.e(TAG, "mDragInfo.cell has null parent");
        }
        if (v instanceof DropTarget) {
            mDragController.removeDropTarget((DropTarget) v);
        }
    }

    /**
     * Removed widget from workspace by appWidgetId
     *
     * @param appWidgetId
     */
    public void removeWidget(int appWidgetId) {
        mapOverItems((info, view) -> {
            if (info instanceof LauncherAppWidgetInfo) {
                LauncherAppWidgetInfo appWidgetInfo = (LauncherAppWidgetInfo) info;
                if (appWidgetInfo.appWidgetId == appWidgetId) {
                    mLauncher.removeItem(view, appWidgetInfo, true,
                            "widget is removed in response to widget remove broadcast");
                    return true;
                }
            }
            return false;
        });
    }

    public boolean isDropEnabled() {
        return true;
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        // We don't dispatch restoreInstanceState to our children using this code path.
        // Some pages will be restored immediately as their items are bound immediately, and
        // others we will need to wait until after their items are bound.
        mSavedStates = container;
    }

    public void restoreInstanceStateForChild(int child) {
        if (mSavedStates != null) {
            mRestoredPages.add(child);
            CellLayout cl = (CellLayout) getChildAt(child);
            if (cl != null) {
                cl.restoreInstanceState(mSavedStates);
            }
        }
    }

    public void restoreInstanceStateForRemainingPages() {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            if (!mRestoredPages.contains(i)) {
                restoreInstanceStateForChild(i);
            }
        }
        mRestoredPages.clear();
        mSavedStates = null;
    }

    @Override
    public boolean scrollLeft() {
        boolean result = false;
        if (!mIsSwitchingState && workspaceInScrollableState()) {
            result = super.scrollLeft();
        }
        Folder openFolder = Folder.getOpen(mLauncher);
        if (openFolder != null) {
            openFolder.completeDragExit();
        }
        return result;
    }

    @Override
    public boolean scrollRight() {
        boolean result = false;
        if (!mIsSwitchingState && workspaceInScrollableState()) {
            result = super.scrollRight();
        }
        Folder openFolder = Folder.getOpen(mLauncher);
        if (openFolder != null) {
            openFolder.completeDragExit();
        }
        return result;
    }

    /**
     * Returns a specific CellLayout
     */
    CellLayout getParentCellLayoutForView(View v) {
        for (CellLayout layout : getWorkspaceAndHotseatCellLayouts()) {
            if (layout.getShortcutsAndWidgets().indexOfChild(v) > -1) {
                return layout;
            }
        }
        return null;
    }

    /**
     * Returns a list of all the CellLayouts on the Homescreen.
     */
    private CellLayout[] getWorkspaceAndHotseatCellLayouts() {
        int screenCount = getChildCount();
        CellLayout[] hotseatPages = mLauncher.getHotseat() != null
                ? mLauncher.getHotseat().getPageLayouts()
                : new CellLayout[0];
        final CellLayout[] layouts = new CellLayout[screenCount + hotseatPages.length];
        for (int screen = 0; screen < screenCount; screen++) {
            layouts[screen] = (CellLayout) getChildAt(screen);
        }
        System.arraycopy(hotseatPages, 0, layouts, screenCount, hotseatPages.length);
        return layouts;
    }

    public LauncherAppWidgetHostView getWidgetForAppWidgetId(final int appWidgetId) {
        // AresLauncher §7: mapOverItems walks CellLayout children, and Strategy D never puts a
        // desktop widget in one, so this returned null for every widget we host. Launcher relies on
        // it to find the PendingAppWidgetHostView it put up while a configure activity was running:
        // with it null, completeAddAppWidget() missed its "replace the pending view" branch, fell
        // through to addItemToDatabase() and wrote a **second** row for the same widget -- the user
        // added one clock and got two, one of them stuck mid-setup forever. Ask the home list first;
        // the CellLayout walk still serves the hotseat and any non-Ares surface.
        if (mAresHomeList != null) {
            LauncherAppWidgetHostView hostView = mAresHomeList.findWidgetForAppWidgetId(appWidgetId);
            if (hostView != null) {
                return hostView;
            }
        }
        return (LauncherAppWidgetHostView) mapOverItems((info, v) ->
                (info instanceof LauncherAppWidgetInfo lawi) && lawi.appWidgetId == appWidgetId);
    }

    void clearDropTargets() {
        mapOverItems(new ItemOperator() {
            @Override
            public boolean evaluate(ItemInfo info, View v) {
                if (v instanceof DropTarget) {
                    mDragController.removeDropTarget((DropTarget) v);
                }
                // not done, process all the shortcuts
                return false;
            }
        });
    }

    /**
     * Removes items that match the {@param matcher}. When applications are removed
     * as a part of an update, this is called to ensure that other widgets and application
     * shortcuts are not removed.
     *
     * @param persistChanges if true, any dependent changes will be persisted to the DB
     */
    public void removeItemsByMatcher(final Predicate<ItemInfo> matcher, boolean persistChanges) {
        // AresLauncher Strategy D: CONTAINER_DESKTOP items live in the Ares home list, not in any
        // CellLayout, so the walk below never sees them. Removing them here keeps this method's
        // contract intact for our rows too. Without it nothing is ever removed from the list:
        // ModelCallbacks.bindItemsUpdated updates an item by remove-then-rebind, so the rebind
        // appended a duplicate row (observed: Gmail bound once at cold boot, then again via
        // bindUpdatedWorkspaceItems, 7 rendered rows vs 6 in the database), and uninstalled apps
        // left stale rows behind.
        //
        // S1/R6: this now goes through the LIST rather than straight to its adapter, because the
        // adapter matches TOP-LEVEL ROWS ONLY. The FolderIcon branch below -- which takes an
        // uninstalled app out of a folder it lives in -- sits inside the CellLayout walk, and no
        // Ares folder icon is ever a CellLayout child, so it has never run for one of ours.
        // AresHomeListView.removeItems descends into FolderInfo.getContents() and calls the same
        // Folder.removeFolderContent this method calls one branch down.
        if (mAresHomeList != null) {
            mAresHomeList.removeItems(matcher);
        }
        for (CellLayout layout : getWorkspaceAndHotseatCellLayouts()) {
            ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
            // Iterate in reverse order as we are removing items
            for (int i = container.getChildCount() - 1; i >= 0; i--) {
                View child = container.getChildAt(i);
                ItemInfo info = (ItemInfo) child.getTag();

                if (matcher.test(info)) {
                    layout.removeViewInLayout(child);
                    if (child instanceof DropTarget) {
                        mDragController.removeDropTarget((DropTarget) child);
                    }
                } else if (child instanceof FolderIcon folderIcon) {
                    FolderInfo folderInfo = (FolderInfo) info;
                    ItemInfo[] matches = folderInfo.getContents().stream()
                            .filter(matcher)
                            .toArray(ItemInfo[]::new);
                    if (matches.length > 0) {
                        folderIcon.getFolder().removeFolderContent(false, matches);
                        if (folderIcon.getFolder().isOpen()) {
                            folderIcon.getFolder().close(false /* animate */);
                        }
                    }
                } else if (info instanceof AppPairInfo api) {
                    // If an app pair's member apps are being removed, delete the whole app pair.
                    if (api.anyMatch(matcher)) {
                        mLauncher.removeItem(child, info, persistChanges);
                    }
                }
            }
        }

        if (persistChanges) {
            stripEmptyScreens();
        }
    }

    @Nullable
    @Override
    public CellInfo getCellInfoForView(@NonNull View view) {
        ViewParent parent = view.getParent();
        if (parent != null
                && parent.getParent() instanceof CellLayout cl
                && view.getLayoutParams() instanceof CellLayoutLayoutParams lp) {
            int container = mLauncher.isHotseatLayout(cl) ? CONTAINER_HOTSEAT : CONTAINER_DESKTOP;
            CellPos pos = getCellPosMapper().mapPresenterToModel(
                    lp.getCellX(), lp.getCellY(), getCellLayoutId(cl), container);
            return new CellInfo(view,
                    pos.screenId, container, pos.cellX, pos.cellY, lp.cellHSpan, lp.cellVSpan);
        }
        return null;
    }

    @Override
    public boolean isContainerSupported(int container) {
        return container == CONTAINER_DESKTOP
                || container == CONTAINER_HOTSEAT
                || container == CONTAINER_ALL_APPS_PREDICTION
                || container == CONTAINER_HOTSEAT_PREDICTION;
    }

    @Override
    public View mapOverItems(@NonNull ItemOperator op) {
        return mapOverCellLayouts(getWorkspaceAndHotseatCellLayouts(), op);
    }

    /**
     * Perform {param op} over all the items in the provided {param layouts} until a match is found
     */
    public static View mapOverCellLayouts(CellLayout[] layouts, ItemOperator op) {
        for (CellLayout layout : layouts) {
            // TODO(b/128460496) Potential race condition where layout is not yet loaded
            if (layout == null) continue;

            ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
            // map over all the shortcuts on the layout
            final int itemCount = container.getChildCount();
            for (int itemIdx = 0; itemIdx < itemCount; itemIdx++) {
                View item = container.getChildAt(itemIdx);
                if (op.evaluate((ItemInfo) item.getTag(), item)) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * Remove workspace icons & widget information related to items in matcher.
     *
     * @param matcher the matcher generated by the caller.
     */
    public void persistRemoveItemsByMatcher(Predicate<ItemInfo> matcher,
                                            @Nullable final String reason) {
        mLauncher.getModelWriter().deleteItemsFromDatabase(matcher, reason);
        removeItemsByMatcher(matcher, true);
    }

    public boolean isOverlayShown() {
        return mOverlayShown;
    }

    /**
     * Calls {@link #snapToPage(int)} on the {@link #DEFAULT_PAGE}, then requests focus on it.
     */
    public void moveToDefaultScreen() {
        int page = getDefaultPage();
        if (!workspaceInModalState() && getNextPage() != page) {
            snapToPage(page);
        }
        View child = getChildAt(page);
        if (child != null) {
            child.requestFocus();
        }
    }

    /**
     * Returns the validated default home page index from user preferences.
     * Falls back to {@link #DEFAULT_PAGE} if the stored page is out of range.
     */
    public int getDefaultPage() {
        int storedPage = PreferenceCacheExtensionsKt.firstCached(mPreferenceManager2.getDefaultHomePage());
        int pageCount = getChildCount();
        if (storedPage >= 0 && storedPage < pageCount) {
            return storedPage;
        }
        return DEFAULT_PAGE;
    }

    /**
     * Sets the given page index as the default home page.
     */
    public void setDefaultPage(int pageIndex) {
        com.patrykmichalik.opto.core.PreferenceExtensionsKt.setBlocking(mPreferenceManager2.getDefaultHomePage(), pageIndex);
    }

    /**
     * Returns true if the current page is the default home page.
     */
    public boolean isCurrentPageDefault() {
        return getNextPage() == getDefaultPage();
    }

    /**
     * Set the given view's pivot point to match the workspace's, so that it scales together. Since
     * both this view and workspace can move, transform the point manually instead of using
     * dragLayer.getDescendantCoordRelativeToSelf and related methods.
     */
    public void setPivotToScaleWithSelf(View sibling) {
        sibling.setPivotY(getPivotY() + getTop()
                - sibling.getTop() - sibling.getTranslationY());
        sibling.setPivotX(getPivotX() + getLeft()
                - sibling.getLeft() - sibling.getTranslationX());
    }

    @Override
    public int getExpectedHeight() {
        return getMeasuredHeight() <= 0 || !mIsLayoutValid
                ? mLauncher.getDeviceProfile().getDeviceProperties().getHeightPx() : getMeasuredHeight();
    }

    @Override
    public int getExpectedWidth() {
        return getMeasuredWidth() <= 0 || !mIsLayoutValid
                ? mLauncher.getDeviceProfile().getDeviceProperties().getWidthPx() : getMeasuredWidth();
    }

    @Override
    protected boolean canAnnouncePageDescription() {
        return Float.compare(mOverlayProgress, 0f) == 0;
    }

    @Override
    protected void announcePageForAccessibility() {
        // Talkback focuses on AccessibilityActionView by default, so we need to modify the state
        // description there in order for the change in page scroll to be announced.
        updateAccessibilityViewPageDescription();
    }

    /**
     * Updates the state description that is set on the accessibility actions view for the
     * workspace.
     * <p>The updated value is called out when talkback focuses on the view and is not disruptive.
     * </p>
     */
    protected void updateAccessibilityViewPageDescription() {
        // Set the state description on accessibility action view so that when it is focused,
        // talkback describes the correct state of home screen pages.
        ViewCompat.setStateDescription(mLauncher.getAccessibilityActionView(),
                getCurrentPageDescription());
    }

    @Override
    protected String getCurrentPageDescription() {
        int pageIndex = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        return getPageDescription(pageIndex);
    }

    /**
     * @param page page index.
     * @return Description of the page at the given page index.
     */
    @Override
    public String getPageDescription(int page) {
        int nScreens = getChildCount();
        int extraScreenId = mScreenOrder.indexOf(EXTRA_EMPTY_SCREEN_ID);
        if (extraScreenId >= 0 && nScreens > 1) {
            if (page == extraScreenId || (isTwoPanelEnabled() && page == extraScreenId + 1)) {
                return getContext().getString(R.string.workspace_new_page);
            }
            nScreens--;
        }
        if (nScreens == 0) {
            // When the workspace is not loaded, we do not know how many screen will be bound.
            return getContext().getString(R.string.home_screen);
        }
        int panelCount = getPanelCount();
        int currentPage = (page / panelCount) + 1;
        int totalPages = nScreens / panelCount + nScreens % panelCount;

        // When dragging, a blank screen is added. This increases the total page count, but we still
        // want to describe the original page count where icons are currently pinned
        if (extraScreenId > 0) totalPages--;

        return getContext().getString(R.string.workspace_scroll_format, currentPage, totalPages);
    }

    @Override
    protected boolean isSignificantMove(float absoluteDelta, int pageOrientedSize) {
        DeviceProfile deviceProfile = mLauncher.getDeviceProfile();
        if (!deviceProfile.getDeviceProperties().isTablet()) {
            return super.isSignificantMove(absoluteDelta, pageOrientedSize);
        }

        return absoluteDelta
                > deviceProfile.getDeviceProperties().getAvailableWidthPx() * SIGNIFICANT_MOVE_SCREEN_WIDTH_PERCENTAGE;
    }

    @Override
    public CellPosMapper getCellPosMapper() {
        return mLauncher.getCellPosMapper();
    }

    private class StateTransitionListener extends AnimatorListenerAdapter
            implements AnimatorUpdateListener {

        @Override
        public void onAnimationUpdate(ValueAnimator anim) {
            mTransitionProgress = anim.getAnimatedFraction();
        }

        @Override
        public void onAnimationStart(Animator animation) {
            onStartStateTransition();
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            onEndStateTransition();
        }
    }

    /**
     * Recursively check view tag {@link R.id.perform_a11y_action_on_launcher_state_normal_tag} and
     * call {@link View#performAccessibilityAction(int, Bundle)} on view tree. The tag is cleared
     * after this call.
     */
    private static void performAccessibilityActionOnViewTree(View view) {
        Object tag = view.getTag(R.id.perform_a11y_action_on_launcher_state_normal_tag);
        if (tag instanceof Integer) {
            view.performAccessibilityAction((int) tag, null);
            view.setTag(R.id.perform_a11y_action_on_launcher_state_normal_tag, null);
        }
        if (view instanceof ViewGroup viewgroup) {
            for (int i = 0; i < viewgroup.getChildCount(); i++) {
                performAccessibilityActionOnViewTree(viewgroup.getChildAt(i));
            }
        }
    }
}
