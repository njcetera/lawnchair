package com.android.launcher3;

import static com.android.launcher3.config.FeatureFlags.SEPARATE_RECENTS_ACTIVITY;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.ViewDebug;
import android.view.WindowInsets;

import com.android.launcher3.graphics.SysUiScrim;
import androidx.core.graphics.ColorUtils;
import app.lawnchair.areslauncher.AresAllApps;
import com.android.launcher3.statemanager.StatefulContainer;

import com.android.launcher3.util.window.WindowManagerProxy;
import com.android.launcher3.views.ActivityContext;

import java.util.Collections;
import java.util.List;

import com.hoko.blur.HokoBlur;
import app.lawnchair.preferences2.PreferenceCacheExtensionsKt;
import app.lawnchair.preferences.PreferenceManager;
import app.lawnchair.preferences2.PreferenceManager2;
import app.lawnchair.util.FileAccessManager;
import app.lawnchair.util.FileAccessState;

public class LauncherRootView extends InsettableFrameLayout {

    private final Rect mTempRect = new Rect();

    private final StatefulContainer mStatefulContainer;

    @ViewDebug.ExportedProperty(category = "launcher")
    private static final List<Rect> SYSTEM_GESTURE_EXCLUSION_RECT =
            Collections.singletonList(new Rect());

    private WindowStateListener mWindowStateListener;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mDisallowBackGesture;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mForceHideBackArrow;

    private final SysUiScrim mSysUiScrim;
    private final boolean mEnableTaskbarOnPhone;

    private final PreferenceManager pref;

    /**
     * AresLauncher §9 wallpaper dim, 0..1. Drawn in {@link #dispatchDraw} before the children so it
     * darkens ONLY the wallpaper -- every launcher view (workspace icons, widgets, and the app-list
     * content) draws on top of it at full brightness. Driven by {@code LawnchairLauncher}'s state
     * listener: fades in only once the app list is fully shown and out over a long, gentle fade that
     * outlives the pane, since this surface (unlike the pane) stays on screen through the whole fade.
     */
    private float mAresWallpaperDimProgress = 0f;

    /** §9 unfolded wallpaper dim, on whenever the workspace is showing two panels. */
    private boolean mAresUnfoldedWallpaperDim = false;

    public LauncherRootView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStatefulContainer = ActivityContext.lookupContext(context);
        mSysUiScrim = new SysUiScrim(this);

        pref = PreferenceManager.getInstance(context);
        PreferenceManager2 prefs2 = PreferenceManager2.getInstance(context);
        
        mEnableTaskbarOnPhone = PreferenceCacheExtensionsKt.firstCached(prefs2.getEnableTaskbarOnPhone());

        FileAccessManager fileAccessManager = FileAccessManager.getInstance(context);
        FileAccessState wallpaperAccessState = fileAccessManager.getWallpaperAccessState().getValue();
        if (pref.getEnableWallpaperBlur().get() && wallpaperAccessState != FileAccessState.Denied.INSTANCE) {
            setUpBlur(context);
        }
    }

    private void setUpBlur(Context context) {
        var display = mStatefulContainer.getDeviceProfile();
        int width = display.getDeviceProperties().getWidthPx();
        int height = display.getDeviceProperties().getHeightPx();

        var wallpaper = getScaledWallpaperDrawable(width, height);
        if (wallpaper == null) {
            return;
        }

        Bitmap originalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(originalBitmap);

        wallpaper.setBounds(0, 0, width, height);
        wallpaper.draw(canvas);

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setAlpha((int) (0.2 * 255));
        canvas.drawRect(0, 0, width, height, paint);

        Bitmap blurredBitmap = HokoBlur.with(context)
                .forceCopy(true)
                .scheme(HokoBlur.SCHEME_OPENGL)
                .sampleFactor(pref.getWallpaperBlurFactorThreshold().get())
                .radius(pref.getWallpaperBlur().get())
                .blur(originalBitmap);

        setBackground(new BitmapDrawable(getContext().getResources(), blurredBitmap));
    }

    private Drawable getScaledWallpaperDrawable(int width, int height) {
        WallpaperManager wallpaperManager = WallpaperManager.getInstance(getContext());
        Drawable wallpaperDrawable = wallpaperManager.getDrawable();

        if (wallpaperDrawable != null) {
            Bitmap originalBitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(originalBitmap);

            wallpaperDrawable.setBounds(0, 0, width, height);
            wallpaperDrawable.draw(canvas);

            return new BitmapDrawable(getContext().getResources(), originalBitmap);
        }
        return null;
    }

    private void handleSystemWindowInsets(Rect insets) {
        // Update device profile before notifying the children.
        mStatefulContainer.getDeviceProfile().updateInsets(insets);
        boolean resetState = !insets.equals(mInsets);
        setInsets(insets);

        if (resetState) {
            mStatefulContainer.getStateManager().reapplyState(true /* cancelCurrentAnimation */);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mStatefulContainer.onRootViewDispatchKeyEvent(event)
                || super.dispatchKeyEvent(event);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mStatefulContainer.handleConfigurationChanged(
                mStatefulContainer.asContext().getResources().getConfiguration());
        return updateInsets(insets);
    }

    private WindowInsets updateInsets(WindowInsets insets) {
        insets = WindowManagerProxy.INSTANCE.get(getContext())
                .normalizeWindowInsets(getContext(), insets, mTempRect);
        handleSystemWindowInsets(mTempRect);
        return insets;
    }

    @Override
    public void setInsets(Rect insets) {
        // If the insets haven't changed, this is a no-op. Avoid unnecessary layout caused by
        // modifying child layout params.
        if (!insets.equals(mInsets)) {
            super.setInsets(insets);
            mSysUiScrim.onInsetsChanged(insets);
        }
    }

    public void dispatchInsets() {
        if (isAttachedToWindow()) {
            updateInsets(getRootWindowInsets());
        } else {
            mStatefulContainer.getDeviceProfile().updateInsets(mInsets);
        }
        super.setInsets(mInsets);
    }

    public void setWindowStateListener(WindowStateListener listener) {
        mWindowStateListener = listener;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mWindowStateListener != null) {
            mWindowStateListener.onWindowFocusChanged(hasWindowFocus);
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (mWindowStateListener != null) {
            mWindowStateListener.onWindowVisibilityChanged(visibility);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        mSysUiScrim.draw(canvas);
        // §9 wallpaper dim: over the wallpaper, under every launcher view, so only the wallpaper
        // darkens. See mAresWallpaperDimProgress.
        if (mAresWallpaperDimProgress > 0f) {
            int base = AresAllApps.appListWallpaperDim(getContext());
            int alpha = Math.round(Color.alpha(base) * mAresWallpaperDimProgress);
            canvas.drawColor(ColorUtils.setAlphaComponent(base, alpha));
        }
        // §9 UNFOLDED: a constant dim over the WHOLE wallpaper, not just the app-list half. Folded,
        // the dim above rides the ALL_APPS state because folded the app list IS a separate page.
        // Unfolded, home and the app list share one canvas and the launcher stays in NORMAL, so that
        // term never fires -- and scoping a dim to the pane instead put a visible brightness step
        // down the middle of the open device, which the owner rejected after three falloff widths.
        if (mAresUnfoldedWallpaperDim) {
            canvas.drawColor(getContext().getColor(R.color.ares_wallpaper_dim_unfolded));
        }
        super.dispatchDraw(canvas);
    }

    /** §9 wallpaper dim strength, 0..1. Set by {@code LawnchairLauncher}'s state listener. */
    public void setAresWallpaperDimProgress(float progress) {
        progress = Utilities.boundToRange(progress, 0f, 1f);
        if (mAresWallpaperDimProgress != progress) {
            mAresWallpaperDimProgress = progress;
            invalidate();
        }
    }

    public float getAresWallpaperDimProgress() {
        return mAresWallpaperDimProgress;
    }

    /**
     * §9 unfolded wallpaper dim. Driven from {@link com.android.launcher3.Workspace} on every
     * posture sync, which is where two-panel state is already resolved.
     */
    public void setAresUnfoldedWallpaperDim(boolean on) {
        if (mAresUnfoldedWallpaperDim != on) {
            mAresUnfoldedWallpaperDim = on;
            invalidate();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        SYSTEM_GESTURE_EXCLUSION_RECT.get(0).set(l, t, r, b);
        setDisallowBackGesture(mDisallowBackGesture);
        mSysUiScrim.setSize(r - l, b - t);
    }

    public void setForceHideBackArrow(boolean forceHideBackArrow) {
        this.mForceHideBackArrow = forceHideBackArrow;
        setDisallowBackGesture(mDisallowBackGesture);
    }

    public void setDisallowBackGesture(boolean disallowBackGesture) {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        mDisallowBackGesture = disallowBackGesture;
        if (Utilities.ATLEAST_Q) {
            setSystemGestureExclusionRects((mForceHideBackArrow || mDisallowBackGesture)
                    ? SYSTEM_GESTURE_EXCLUSION_RECT
                    : Collections.emptyList());
        }
    }

    public SysUiScrim getSysUiScrim() {
        return mSysUiScrim;
    }

    public interface WindowStateListener {

        void onWindowFocusChanged(boolean hasFocus);

        void onWindowVisibilityChanged(int visibility);
    }
}
