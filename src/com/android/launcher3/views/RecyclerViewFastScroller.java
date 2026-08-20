/*
 * Copyright (C) 2017 The Android Open Source Project
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
 */

package com.android.launcher3.views;

import static androidx.core.view.HapticFeedbackConstantsCompat.CLOCK_TICK;

import static androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE;

import static com.android.launcher3.views.RecyclerViewFastScroller.FastScrollerLocation.ALL_APPS_SCROLLER;
import static com.android.launcher3.views.RecyclerViewFastScroller.FastScrollerLocation.WIDGET_SCROLLER;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Property;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.FastScrollRecyclerView;
import com.android.launcher3.Flags;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.LetterListTextView;
import com.android.launcher3.graphics.FastScrollThumbDrawable;
import com.android.launcher3.util.Themes;

import java.util.Collections;
import java.util.List;

import app.lawnchair.areslauncher.AresAllApps;
import app.lawnchair.theme.color.tokens.ColorTokens;

/**
 * The track and scrollbar that shows when you scroll the list.
 */
public class RecyclerViewFastScroller extends View {

    /** FastScrollerLocation describes what RecyclerView the fast scroller is dedicated to. */
    public enum FastScrollerLocation {
        UNKNOWN_SCROLLER(0),
        ALL_APPS_SCROLLER(1),
        WIDGET_SCROLLER(2);

        public final int location;

        FastScrollerLocation(int location) {
            this.location = location;
        }
    }
    private static final String TAG = "RecyclerViewFastScroller";
    private static final boolean DEBUG = false;
    private static final int FASTSCROLL_THRESHOLD_MILLIS = 10;
    private static final int SCROLL_DELTA_THRESHOLD_DP = 4;

    // Track is very narrow to target and correctly. This is especially the case if a user is
    // using a hardware case. Even if x is offset by following amount, we consider it to be valid.
    private static final int SCROLLBAR_LEFT_OFFSET_TOUCH_DELEGATE_DP = 5;
    private static final Rect sTempRect = new Rect();

    private static final Property<RecyclerViewFastScroller, Integer> TRACK_WIDTH =
            new Property<RecyclerViewFastScroller, Integer>(Integer.class, "width") {

                @Override
                public Integer get(RecyclerViewFastScroller scrollBar) {
                    return scrollBar.mWidth;
                }

                @Override
                public void set(RecyclerViewFastScroller scrollBar, Integer value) {
                    scrollBar.setTrackWidth(value);
                }
            };

    private final static int MAX_TRACK_ALPHA = 30;
    private final static int SCROLL_BAR_VIS_DURATION = 150;

    private static final List<Rect> SYSTEM_GESTURE_EXCLUSION_RECT =
            Collections.singletonList(new Rect());

    /**
     * AresLauncher §13 follow-up: the app-list thumb, drawn wider and pulled inboard.
     *
     * The user said it was "a little bit hard to grab". Measured on the emulator before changing
     * anything, and the touch target was never the problem: the scroller view is
     * {@code fastscroll_width} = 58dp wide and its hit column runs the whole of that, of which
     * ~32dp lands on screen. What is small is what you can *see* — {@code fastscroll_end_margin} is
     * -26dp, so the view hangs 63px past the right edge and the thumb is drawn centred 8px from it,
     * leaving about 7dp of a 6dp-wide thumb visible and hard against the bezel. (Note
     * {@code fastscroll_thumb_touch_inset}, -24dp, is declared in dimens.xml and read nowhere in
     * this fork — it is not widening anything.)
     *
     * So this adds visual weight and moves the thumb fully into view, rather than enlarging a touch
     * area that is already generous. The dimens themselves are left alone: they are vendored and
     * shared with the Taskbar's all-apps sheet, the secondary-display list and the widget picker,
     * and editing them in place is the exact failure mode change-practices.md opens with.
     */
    private static final float ARES_TRACK_MIN_WIDTH_DP = 10f;
    private static final float ARES_TRACK_MAX_WIDTH_DP = 14f;
    private static final float ARES_THUMB_INSET_DP = 8f;

    /**
     * How far the section-letter bubble is kept clear of the scrollbar it points at.
     *
     * A feel value — see {@link #updatePopupX}. 48dp is a typical thumb-contact width and the
     * Material minimum touch target; tune on hardware.
     */
    private static final float ARES_POPUP_THUMB_CLEARANCE_DP = 48f;

    private int mMinWidth;
    private int mMaxWidth;
    /** Horizontal shift applied to the track and thumb; non-zero only on the Ares app list. */
    private int mAresThumbInset;
    /** Gap held between the popup's right edge and the scrollbar; Ares app list only. */
    private int mAresPopupClearance;
    /** True only on the Ares app-list pane, which is the one surface these metrics apply to. */
    private boolean mIsAresAppList;
    private final int mThumbPadding;

    /** Keeps the last known scrolling delta/velocity along y-axis. */
    private int mDy = 0;
    private final float mDeltaThreshold;
    private final float mScrollbarLeftOffsetTouchDelegate;

    private final ViewConfiguration mConfig;

    // Current width of the track
    private int mWidth;
    private ObjectAnimator mWidthAnimator;

    private final Paint mThumbPaint;
    protected final int mThumbHeight;
    private final RectF mThumbBounds = new RectF();
    private final Point mThumbDrawOffset = new Point();

    private final Paint mTrackPaint;
    private final int mThumbColor;
    private final int mThumbLetterScrollerColor;

    private float mLastTouchY;
    private boolean mIsDragging;
    /**
     * Tracks whether a keyboard hide request has been sent due to downward scrolling.
     * <p>
     * Set to true when scrolling down and reset when scrolling up to prevents redundant hide
     * requests during continuous downward scrolls.
     */
    private boolean mRequestedHideKeyboard;
    private boolean mIsThumbDetached;
    private final boolean mCanThumbDetach;
    private boolean mIgnoreDragGesture;
    private long mDownTimeStampMillis;

    // This is the offset from the top of the scrollbar when the user first starts touching.  To
    // prevent jumping, this offset is applied as the user scrolls.
    protected int mTouchOffsetY;
    protected int mThumbOffsetY;

    // Fast scroller popup
    private TextView mPopupView;
    private boolean mPopupVisible;
    private CharSequence mPopupSectionName;
    private Insets mSystemGestureInsets;

    protected FastScrollRecyclerView mRv;
    private RecyclerView.OnScrollListener mOnScrollListener;
    private final ActivityContext mActivityContext;

    private int mDownX;
    private int mDownY;
    private int mLastY;
    private FastScrollerLocation mFastScrollerLocation;

    public RecyclerViewFastScroller(Context context) {
        this(context, null);
    }

    public RecyclerViewFastScroller(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecyclerViewFastScroller(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mFastScrollerLocation = FastScrollerLocation.UNKNOWN_SCROLLER;
        mTrackPaint = new Paint();
        mTrackPaint.setColor(ColorTokens.TextColorPrimary.resolveColor(getContext()));
        mTrackPaint.setAlpha(MAX_TRACK_ALPHA);

        mThumbColor = Themes.getColorAccent(context);
        mThumbLetterScrollerColor = context.getColor(R.color.materialColorSurfaceBright);
        mThumbPaint = new Paint();
        mThumbPaint.setAntiAlias(true);
        mThumbPaint.setColor(mThumbColor);
        mThumbPaint.setStyle(Paint.Style.FILL);

        Resources res = getResources();
        mWidth = mMinWidth = res.getDimensionPixelSize(R.dimen.fastscroll_track_min_width);
        mMaxWidth = res.getDimensionPixelSize(R.dimen.fastscroll_track_max_width);

        mThumbPadding = res.getDimensionPixelSize(R.dimen.fastscroll_thumb_padding);
        mThumbHeight = res.getDimensionPixelSize(R.dimen.fastscroll_thumb_height);

        mConfig = ViewConfiguration.get(context);
        mDeltaThreshold = res.getDisplayMetrics().density * SCROLL_DELTA_THRESHOLD_DP;
        mScrollbarLeftOffsetTouchDelegate = res.getDisplayMetrics().density
                * SCROLLBAR_LEFT_OFFSET_TOUCH_DELEGATE_DP;
        mActivityContext = ActivityContext.lookupContext(context);
        TypedArray ta =
                context.obtainStyledAttributes(attrs, R.styleable.RecyclerViewFastScroller, defStyleAttr, 0);
        mCanThumbDetach = ta.getBoolean(R.styleable.RecyclerViewFastScroller_canThumbDetach, false);
        ta.recycle();
    }

    /** Sets the popup view to show while the scroller is being dragged */
    public void setPopupView(TextView popupView) {
        mPopupView = popupView;
        mPopupView.setBackground(
                new FastScrollThumbDrawable(mThumbPaint, Utilities.isRtl(getResources())));
    }

    public void setRecyclerView(FastScrollRecyclerView rv) {
        if (mRv != null && mOnScrollListener != null) {
            mRv.removeOnScrollListener(mOnScrollListener);
        }
        mRv = rv;

        mRv.addOnScrollListener(mOnScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                mDy = dy;

                // TODO(winsonc): If we want to animate the section heads while scrolling, we can
                //                initiate that here if the recycler view scroll state is not
                //                RecyclerView.SCROLL_STATE_IDLE.

                mRv.onUpdateScrollbar(dy);
            }
        });
    }

    public void reattachThumbToScroll() {
        mIsThumbDetached = false;
    }

    public void setThumbOffsetY(int y) {
        if (mThumbOffsetY == y) {
            return;
        }
        updatePopupY(y);
        mThumbOffsetY = y;
        invalidate();
    }

    public int getThumbOffsetY() {
        return mThumbOffsetY;
    }

    private void setTrackWidth(int width) {
        if (mWidth == width) {
            return;
        }
        mWidth = width;
        invalidate();
    }

    public int getThumbHeight() {
        return mThumbHeight;
    }

    public boolean isDraggingThumb() {
        return mIsDragging;
    }

    public boolean isThumbDetached() {
        return mIsThumbDetached;
    }

    /**
     * Handles the touch event and determines whether to show the fast scroller (or updates it if
     * it is already showing).
     */
    public boolean handleTouchEvent(MotionEvent ev, Point offset) {
        int x = (int) ev.getX() - offset.x;
        int y = (int) ev.getY() - offset.y;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Keep track of the down positions
                mDownX = x;
                mDownY = mLastY = y;
                mDownTimeStampMillis = ev.getDownTime();
                mRequestedHideKeyboard = false;

                if ((Math.abs(mDy) < mDeltaThreshold &&
                        mRv.getScrollState() != SCROLL_STATE_IDLE)) {
                    // now the touch events are being passed to the {@link WidgetCell} until the
                    // touch sequence goes over the touch slop.
                    mRv.stopScroll();
                }
                if (isNearThumb(x, y)) {
                    mTouchOffsetY = mDownY - mThumbOffsetY;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                boolean isScrollingDown = y > mLastY;
                mLastY = y;
                int absDeltaY = Math.abs(y - mDownY);
                int absDeltaX = Math.abs(x - mDownX);

                // Check if we should start scrolling, but ignore this fastscroll gesture if we have
                // exceeded some fixed movement
                mIgnoreDragGesture |= absDeltaY > mConfig.getScaledPagingTouchSlop();

                if (!mIsDragging && !mIgnoreDragGesture && mRv.supportsFastScrolling()) {
                    if ((isNearThumb(mDownX, mLastY) && ev.getEventTime() - mDownTimeStampMillis
                                    > FASTSCROLL_THRESHOLD_MILLIS)) {
                        calcTouchOffsetAndPrepToFastScroll(mDownY, mLastY);
                    }
                }
                if (mIsDragging) {
                    if (isScrollingDown) {
                        if (!mRequestedHideKeyboard) {
                            mActivityContext.hideKeyboard();
                        }
                        mRequestedHideKeyboard = true;
                    } else {
                        mRequestedHideKeyboard = false;
                    }
                    updateFastScrollSectionNameAndThumbOffset(y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endFastScrolling();
                break;
        }
        if (DEBUG) {
            Log.d(TAG, (ev.getAction() == MotionEvent.ACTION_DOWN ? "\n" : "")
                    + "handleTouchEvent " + MotionEvent.actionToString(ev.getAction())
                    + " (" + x + "," + y + ")" + " isDragging=" + mIsDragging
                    + " mIgnoreDragGesture=" + mIgnoreDragGesture);

        }
        return mIsDragging;
    }

    private void calcTouchOffsetAndPrepToFastScroll(int downY, int lastY) {
        mIsDragging = true;
        if (mCanThumbDetach) {
            mIsThumbDetached = true;
        }
        mTouchOffsetY += (lastY - downY);
        animatePopupVisibility(true);
        showActiveScrollbar(true);
    }

    private void updateFastScrollSectionNameAndThumbOffset(int y) {
        // Update the fastscroller section name at this touch position
        int bottom = mRv.getScrollbarTrackHeight() - mThumbHeight;
        float boundedY = (float) Math.max(0, Math.min(bottom, y - mTouchOffsetY));
        CharSequence sectionName = mRv.scrollToPositionAtProgress(boundedY / bottom);
        if (!sectionName.equals(mPopupSectionName)) {
            mPopupSectionName = sectionName;
            mPopupView.setText(sectionName);
            // AllApps haptics are taken care of by AllAppsFastScrollHelper.
            if (mFastScrollerLocation != ALL_APPS_SCROLLER) {
                performHapticFeedback(CLOCK_TICK);
            }
        }
        animatePopupVisibility(!TextUtils.isEmpty(sectionName));
        mLastTouchY = boundedY;
        setThumbOffsetY((int) mLastTouchY);
        // Position the bubble from the CURRENT scrub position, not as a side effect of the thumb
        // having moved.
        //
        // updatePopupY is otherwise only reachable through setThumbOffsetY, which early-returns
        // when the offset is unchanged. So a scrub that lands on the offset the scroller already
        // holds shows the bubble without ever positioning it, and it draws at its untranslated
        // layout position -- y=171, the top of the screen.
        //
        // That is not a corner case, because of which section sits there: recents is the FIRST
        // section, so scrubbing to it means thumb offset 0, which is exactly this field's initial
        // value. The first scrub to the bolt in a fresh process therefore drew it at the top of
        // the screen, and it fixed itself as soon as the thumb had been anywhere else once --
        // "the lightning icon that should render by my thumb renders at the top of the screen",
        // "only happens after a fresh install". Letters never showed it, since reaching one always
        // moves the thumb off 0.
        //
        // Cheap enough to do unconditionally: it is arithmetic and two setTranslation calls, on a
        // path that already re-measures text and may fire haptics.
        updatePopupY((int) mLastTouchY);
        updateFastScrollerLetterList(y);
    }

    private void updateFastScrollerLetterList(int y) {
        if (!shouldUseLetterFastScroller()) {
            return;
        }
        ConstraintLayout mLetterList = mRv.getLetterList();
        for (int i = 0; i < mLetterList.getChildCount(); i++) {
            LetterListTextView currentLetter = (LetterListTextView) mLetterList.getChildAt(i);
            currentLetter.animateBasedOnYPosition(y + mTouchOffsetY);
        }
    }

    /** End any active fast scrolling touch handling, if applicable. */
    public void endFastScrolling() {
        mRv.onFastScrollCompleted();
        mTouchOffsetY = 0;
        mLastTouchY = 0;
        mIgnoreDragGesture = false;
        mIsDragging = false;
        // AresLauncher: the tear-down runs whether or not this scroller still believes it is
        // dragging. It used to sit inside `if (mIsDragging)`, so any path that cleared that flag
        // without also tidying the affordances left them on screen with nothing left to take them
        // down -- the user's report was a section-letter bubble that survived the finger lift.
        // Both calls below are idempotent, so running them on a scroller that was never dragging
        // costs a no-op animation and nothing else.
        animatePopupVisibility(false);
        showActiveScrollbar(false);
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (mThumbOffsetY < 0 || mRv == null) {
            return;
        }
        int saveCount = canvas.save();
        // mAresThumbInset is 0 everywhere except the Ares app list, where it brings the thumb in
        // off the bezel; the gesture-exclusion rect below is offset by the same amount so it keeps
        // tracking where the thumb is actually drawn.
        int centreX = getWidth() / 2 - mAresThumbInset;
        canvas.translate(centreX, mRv.getScrollBarTop());
        mThumbDrawOffset.set(centreX, mRv.getScrollBarTop());
        // Draw the track
        float halfW = mWidth / 2;
        boolean useLetterFastScroller = shouldUseLetterFastScroller();
        if (useLetterFastScroller) {
            float translateX;
            if (mIsDragging) {
                // halfW * 3 is half circle.
                translateX = halfW * 3;
            } else {
                translateX = halfW * 5;
            }
            canvas.translate(translateX, mThumbOffsetY);
        } else {
            canvas.drawRoundRect(-halfW, 0, halfW, mRv.getScrollbarTrackHeight(),
                    mWidth, mWidth, mTrackPaint);
            canvas.translate(0, mThumbOffsetY);
        }
        mThumbDrawOffset.y += mThumbOffsetY;

        /* Draw half circle */
        halfW += mThumbPadding;
        float r = getScrollThumbRadius();
        if (useLetterFastScroller) {
            mThumbPaint.setColor(mThumbLetterScrollerColor);
            mThumbBounds.set(0, 0, 0, mThumbHeight);
            canvas.drawCircle(-halfW, halfW, r * 2, mThumbPaint);
        } else {
            mThumbPaint.setColor(mThumbColor);
            mThumbBounds.set(-halfW, 0, halfW, mThumbHeight);
            canvas.drawRoundRect(mThumbBounds, r, r, mThumbPaint);
        }
        mThumbBounds.roundOut(SYSTEM_GESTURE_EXCLUSION_RECT.get(0));
        // swiping very close to the thumb area (not just within it's bound)
        // will also prevent back gesture
        SYSTEM_GESTURE_EXCLUSION_RECT.get(0).offset(mThumbDrawOffset.x, mThumbDrawOffset.y);
        if (Utilities.ATLEAST_Q) {
            if (mSystemGestureInsets != null) {
                SYSTEM_GESTURE_EXCLUSION_RECT.get(0).left =
                    SYSTEM_GESTURE_EXCLUSION_RECT.get(0).right - mSystemGestureInsets.right;
            }
            setSystemGestureExclusionRects(SYSTEM_GESTURE_EXCLUSION_RECT);
        }
        canvas.restoreToCount(saveCount);
    }

    boolean shouldUseLetterFastScroller() {
        return Flags.letterFastScroller()
                && getScrollerLocation() == FastScrollerLocation.ALL_APPS_SCROLLER;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Utilities.ATLEAST_Q) {
            mSystemGestureInsets = insets.getSystemGestureInsets();
        } else {
            mSystemGestureInsets = null;
        }
        return super.onApplyWindowInsets(insets);
    }

    private float getScrollThumbRadius() {
        return mWidth + mThumbPadding + mThumbPadding;
    }

    /**
     * Animates the width of the scrollbar.
     */
    private void showActiveScrollbar(boolean isScrolling) {
        if (mWidthAnimator != null) {
            mWidthAnimator.cancel();
        }

        mWidthAnimator = ObjectAnimator.ofInt(this, TRACK_WIDTH,
                isScrolling ? mMaxWidth : mMinWidth);
        mWidthAnimator.setDuration(SCROLL_BAR_VIS_DURATION);
        mWidthAnimator.start();
    }

    /**
     * Returns whether the specified point is inside the thumb bounds.
     */
    private boolean isNearThumb(int x, int y) {
        int offset = y - mThumbOffsetY;

        return x >= 0 && x < getWidth() && offset >= 0 && offset <= mThumbHeight;
    }

    /**
     * Returns true if AllAppsTransitionController can handle vertical motion
     * beginning at this point.
     */
    public boolean shouldBlockIntercept(int x, int y) {
        return isNearThumb(x, y);
    }

    public FastScrollerLocation getScrollerLocation() {
        return mFastScrollerLocation;
    }

    public void setFastScrollerLocation(@NonNull FastScrollerLocation location) {
        mFastScrollerLocation = location;
        // Applied here rather than in the constructor because the location is not known until
        // bindFastScrollbar. Gated on BOTH the location and the host, so the widget picker (a
        // Launcher too) and the Taskbar's all-apps sheet (ALL_APPS_SCROLLER, but hosted by
        // TaskbarOverlayContext) both keep the stock metrics. See ARES_TRACK_MIN_WIDTH_DP.
        if (location == ALL_APPS_SCROLLER && AresAllApps.isAresAppListPane(mActivityContext)) {
            float density = getResources().getDisplayMetrics().density;
            mIsAresAppList = true;
            mMinWidth = Math.round(ARES_TRACK_MIN_WIDTH_DP * density);
            mMaxWidth = Math.round(ARES_TRACK_MAX_WIDTH_DP * density);
            mAresThumbInset = Math.round(ARES_THUMB_INSET_DP * density);
            mAresPopupClearance = Math.round(ARES_POPUP_THUMB_CLEARANCE_DP * density);
            setTrackWidth(mMinWidth);
        }
    }

    /**
     * Shows or hides whatever this scroller puts on screen while the thumb is being dragged.
     *
     * ## AresLauncher: hiding is unconditional, and covers BOTH affordances
     *
     * There are two of them — the section-letter bubble ({@link #mPopupView}) and the A–Z rail
     * ({@code getLetterList()}) — and {@link #shouldUseLetterFastScroller()} picks one. Showing may
     * stay selective, because only one of them is the affordance in use. **Hiding may not**: a
     * branch-selective hide can only ever take down the one the branch currently names, so anything
     * the other branch put up (or that was left up by an earlier state) stays on screen with
     * nothing left to remove it. The user's report — a letter bubble that survives the finger lift
     * — is that shape.
     *
     * The asymmetry is real in stock and not hypothetical:
     * {@link #updateFastScrollSectionNameAndThumbOffset} calls {@code mPopupView.setText(...)} on
     * *both* branches, so the popup is fed by a shared path and hidden by a selective one.
     *
     * The `mPopupVisible != visible` guard is likewise dropped on the hide. It exists to avoid
     * restarting an animation that is already running, which is a show-side concern; on the hide
     * side it is exactly what makes a stale flag permanent, because a scroller that believes it is
     * already hidden will decline to hide anything.
     *
     * The invariant worth keeping, rather than this one fix: **hide must cover every view show can
     * touch.** If a third affordance is ever added, it belongs in both halves.
     */
    private void animatePopupVisibility(boolean visible) {
        if (!visible) {
            mPopupVisible = false;
            mPopupView.animate().cancel();
            mPopupView.animate().alpha(0f).setDuration(150).start();
            // Null on every FastScrollRecyclerView except AllAppsRecyclerView -- the widget
            // picker's scroller has no rail to hide.
            ConstraintLayout letterList = mRv.getLetterList();
            if (letterList != null) {
                letterList.animate().cancel();
                letterList.animate().alpha(0f).setDuration(150).start();
            }
            return;
        }
        if (!mPopupVisible) {
            mPopupVisible = true;
            if (shouldUseLetterFastScroller()) {
                mRv.getLetterList().animate().alpha(1f).setDuration(200).start();
            } else {
                mPopupView.animate().cancel();
                mPopupView.animate().alpha(1f).setDuration(200).start();
            }
        }
    }

    /**
     * AresLauncher: slides the section-letter bubble inboard, out from under the thumb.
     *
     * *"my thumb kinda covers the letter on the drag bar when im holding it, is there any way to
     * extend it out a bit more so I can see the letter without my thumb covering it?"*
     *
     * The bubble does not merely sit near the bar, it **overlaps** it. Measured on the emulator and
     * on the user's Pixel, folded, both 1080 wide and identical to the pixel:
     *
     * <pre>
     *   fast_scroller_popup    851 .. 1034
     *   scroll_letter_layout   963 .. 1104
     *   fast_scroller         1002 .. 1143
     * </pre>
     *
     * So the bubble's right edge is 32px *inside* the scroller and a thumb resting on the bar
     * covers its right third — which is where the glyph is.
     *
     * X comes from layout ({@code layout_alignParentEnd} plus {@code fastscroll_popup_margin}), not
     * from {@link #updatePopupY}, which only writes {@code translationY}. It is corrected here with
     * a {@code translationX} rather than by editing the layout or the dimen: both are vendored and
     * shared with the Taskbar's all-apps sheet, the secondary-display list and the widget picker,
     * and this is gated to the Ares app list exactly as the track and thumb metrics are.
     *
     * Anchored on the **scroller's** left edge, because that is where the thumb physically is and
     * it is the only one of the three that is drawn. {@code scroll_letter_layout} is also to the
     * left of it, but in this build it is an empty, permanently-transparent strip
     * ({@code Flags.letterFastScroller()} is hardcoded false), so anchoring on it would be
     * anchoring on nothing. If the rail is ever finished and turned on it becomes the live
     * affordance, and the {@code shouldUseLetterFastScroller()} branch below clears that instead.
     *
     * {@link #ARES_POPUP_THUMB_CLEARANCE_DP} is a **feel value the emulator cannot judge**: 48dp is
     * a typical thumb-contact width and the Material minimum touch target, and erring generous is
     * right for a complaint that reads "I cannot see it". Expect the user to adjust it.
     */
    private void updatePopupX() {
        if (!mIsAresAppList) {
            return;
        }
        // Siblings under the same parent (all_apps_fast_scroller.xml is a <merge>), so these
        // left/right edges are directly comparable.
        int barLeft = getLeft();
        if (shouldUseLetterFastScroller()) {
            ConstraintLayout letterList = mRv.getLetterList();
            if (letterList != null) {
                barLeft = Math.min(barLeft, letterList.getLeft());
            }
        }
        // Never pushed right of where layout put it -- this only ever moves the bubble further
        // from the bar, so a narrow screen that already clears the thumb is left alone.
        mPopupView.setTranslationX(
                Math.min(0f, (barLeft - mAresPopupClearance) - mPopupView.getRight()));
    }

    private void updatePopupY(int lastTouchY) {
        updatePopupX();
        int height = mPopupView.getHeight();
        // Stock aligns the bubble's rounded corner with the TOP of the thumb, which leaves it
        // sitting most of a thumb-height above the finger -- "the renders just slightly high".
        //
        // AresLauncher centres it on the thumb instead. `lastTouchY` is the thumb's own top offset
        // (setThumbOffsetY passes the same value), so biasing by half the thumb height puts the
        // bubble's centre exactly on the thumb's centre. The arithmetic, at 390dpi:
        //
        //   thumb height   52dp = 127px      -> stock bias  radius/2 = 19px
        //   thumb radius   14 + 1 + 1 = 38px -> ares  bias  height/2 = 63px
        //   difference     44px (18dp) of upward offset removed
        //
        // Gated, like every other metric in this file, because RecyclerViewFastScroller also backs
        // the Taskbar's all-apps sheet, the secondary-display list and the widget picker, and
        // stock's alignment is deliberate upstream.
        float bias = mIsAresAppList ? (mThumbHeight / 2f) : (getScrollThumbRadius() / 2f);
        float top = mRv.getScrollBarTop() + lastTouchY + bias - (height / 2f);
        // The clamp is stock's, and it is inert in our configuration -- worth recording, because it
        // was written against a full-height track and ours is the middle half. Folded: track top
        // 548, track height 1096, popup height 151, so `top` runs 536..1505 against a permitted
        // 0..1655. It does not bite at either end of the travel, before or after the bias change.
        top = Utilities.boundToRange(top, 0,
                getTop() + mRv.getScrollBarTop() + mRv.getScrollbarTrackHeight() - height);
        mPopupView.setTranslationY(top);
    }

    public boolean isHitInParent(float x, float y, Point outOffset) {
        if (mThumbOffsetY < 0) {
            return false;
        }
        getHitRect(sTempRect);
        sTempRect.top += mRv.getScrollBarTop();
        if (outOffset != null) {
            outOffset.set(sTempRect.left, sTempRect.top);
        }
        return sTempRect.contains((int) x, (int) y);
    }

    @Override
    public boolean hasOverlappingRendering() {
        // There is actually some overlap between the track and the thumb. But since the track
        // alpha is so low, it does not matter.
        return false;
    }
}
