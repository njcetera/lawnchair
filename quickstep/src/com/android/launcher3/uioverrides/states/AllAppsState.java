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
package com.android.launcher3.uioverrides.states;

import static com.android.app.animation.Interpolators.DECELERATE_2;
import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_ALLAPPS;

import android.content.Context;
import android.graphics.Color;

import com.android.internal.jank.Cuj;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Workspace;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.ScrimColors;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;

import java.util.concurrent.TimeUnit;


/**
 * Definition for AllApps state
 */
public class AllAppsState extends LauncherState {

    private static final int STATE_FLAGS =
            FLAG_WORKSPACE_INACCESSIBLE | FLAG_CLOSE_POPUPS | FLAG_HOTSEAT_INACCESSIBLE;
    private static final long BACK_CUJ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    /**
     * Fraction of screen width home travels left while the app-list pane comes in (§9).
     *
     * 1.0 -- home moves exactly as far as the pane, so the two behave as one rigid surface being
     * panned across, not two layers sliding at different rates. An earlier 0.25 parallax was
     * reported by the user as still reading like "a separate overlay pane"; differing rates are
     * precisely what communicates depth, which is the opposite of the intent here.
     */
    private static final float ARES_WORKSPACE_PAN_FRACTION = 1.0f;


    public AllAppsState(int id) {
        super(id, LAUNCHER_STATE_ALLAPPS, STATE_FLAGS);
    }

    @Override
    public int getTransitionDuration(ActivityContext context, boolean isToState) {
        return isToState
                ? context.getDeviceProfile().allAppsOpenDuration
                : context.getDeviceProfile().allAppsCloseDuration;
    }

    @Override
    public void onBackStarted(Launcher launcher) {
        // Because the back gesture can take longer time depending on when user release the finger,
        // we pass BACK_CUJ_TIMEOUT_MS as timeout to the jank monitor.
        InteractionJankMonitorWrapper.begin(launcher.getAppsView(),
                Cuj.CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK, BACK_CUJ_TIMEOUT_MS);
        super.onBackStarted(launcher);
    }

    @Override
    public void onBackInvoked(Launcher launcher) {
        // In predictive back swipe, onBackInvoked() will be called after onBackStarted().
        // In 3 button mode, onBackStarted() is not called but onBackInvoked() will be called.
        // Thus In onBackInvoked(), we should only begin instrumenting if we didn't call
        // onBackStarted() to start instrumenting CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK.
        if (!InteractionJankMonitorWrapper.isInstrumenting(Cuj.CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK)) {
            InteractionJankMonitorWrapper.begin(
                    launcher.getAppsView(), Cuj.CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK);
        }
        super.onBackInvoked(launcher);
    }

    /** Called when predictive back swipe is cancelled. */
    @Override
    public void onBackCancelled(Launcher launcher) {
        super.onBackCancelled(launcher);
        InteractionJankMonitorWrapper.cancel(Cuj.CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK);
    }

    @Override
    protected void onBackAnimationCompleted(boolean success) {
        if (success) {
            // Animation was successful.
            InteractionJankMonitorWrapper.end(Cuj.CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK);
        } else {
            // Animation was canceled.
            InteractionJankMonitorWrapper.cancel(Cuj.CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK);
        }
    }

    @Override
    public String getDescription(Launcher launcher) {
        return launcher.getAppsView().getDescription();
    }

    @Override
    public int getTitle() {
        return R.string.all_apps_list_label;
    }

    @Override
    public float getVerticalProgress(Launcher launcher) {
        return 0f;
    }

    @Override
    public ScaleAndTranslation getWorkspaceScaleAndTranslation(Launcher launcher) {
        // AresLauncher §9: NO_SCALE, not workspaceContentScale. A shrinking home reads as a layer
        // receding behind a sheet; on one canvas both regions stay the same size and simply move.
        return new ScaleAndTranslation(NO_SCALE,
                getAresWorkspaceTranslationX(launcher), NO_OFFSET);
    }

    /**
     * AresLauncher §9: how far home slides left as the app-list pane comes in from the right.
     *
     * Stock returns {@link #NO_OFFSET} here, so the workspace only scales and blurs while the pane
     * translates across it -- which reads as a sheet dropped on top of a stationary background. The
     * user's word for it was "more like an overlap than a pan". A Windows Phone Pivot moves *both*
     * surfaces as one canvas, so home has to travel too.
     *
     * The offset is the FULL screen width. Home leaving the screen is correct for a pan: you have
     * moved past it, not covered it. {@code isWorkspaceVisible()} still returns true because home
     * is genuinely visible throughout the drag -- it is only gone once the pan has completed.
     *
     * Returns {@link #NO_OFFSET} when two panels are active: unfolded, the pane is a persistent
     * panel in panel 1 and nothing should slide. {@code AresPaneSwipeController} already suppresses
     * the gesture there, but the state can still be entered by other means, and a translated
     * workspace under a permanently-visible pane would be wrong in either case.
     */
    private static float getAresWorkspaceTranslationX(Launcher launcher) {
        Workspace<?> workspace = launcher.getWorkspace();
        if (workspace != null && workspace.getPanelCount() > 1) {
            return NO_OFFSET;
        }
        return -launcher.getDeviceProfile().getDeviceProperties().getWidthPx()
                * ARES_WORKSPACE_PAN_FRACTION;
    }

    @Override
    public ScaleAndTranslation getHotseatScaleAndTranslation(Launcher launcher) {
        if (launcher.getDeviceProfile().shouldShowAllAppsOnSheet()) {
            return getWorkspaceScaleAndTranslation(launcher);
        } else {
            ScaleAndTranslation overviewScaleAndTranslation = LauncherState.OVERVIEW
                    .getWorkspaceScaleAndTranslation(launcher);
            return new ScaleAndTranslation(
                    launcher.getDeviceProfile().workspaceContentScale,
                    overviewScaleAndTranslation.translationX,
                    overviewScaleAndTranslation.translationY);
        }
    }

    /**
     * AresLauncher §9: no wallpaper depth or blur.
     *
     * Stock zooms and blurs the wallpaper so the all-apps sheet reads as floating above a receded
     * background. On one canvas there is no "behind" -- home and the app list are two regions of
     * the same flat surface over the same, unmodified wallpaper. Returning 0 matches
     * {@link LauncherState}'s own default, i.e. the NORMAL-state treatment.
     */
    @Override
    protected <DEVICE_PROFILE_CONTEXT extends Context & ActivityContext>
            float getDepthUnchecked(DEVICE_PROFILE_CONTEXT context) {
        return 0f;
    }

    /**
     * AresLauncher §9: never blur the workspace. Home is a region of the canvas being panned past,
     * not a backdrop to be pushed out of focus.
     */
    @Override
    public boolean shouldBlurWorkspace(LauncherState targetState) {
        return false;
    }

    @Override
    public PageAlphaProvider getWorkspacePageAlphaProvider(Launcher launcher) {
        PageAlphaProvider superPageAlphaProvider = super.getWorkspacePageAlphaProvider(launcher);
        return new PageAlphaProvider(DECELERATE_2) {
            @Override
            public float getPageAlpha(int pageIndex) {
                return isWorkspaceVisible(launcher.getDeviceProfile())
                        ? superPageAlphaProvider.getPageAlpha(pageIndex)
                        : 0;
            }
        };
    }

    @Override
    public int getVisibleElements(Launcher launcher) {
        int elements = ALL_APPS_CONTENT | FLOATING_SEARCH_BAR;
        if (isWorkspaceVisible(launcher.getDeviceProfile())) {
            elements |= HOTSEAT_ICONS;
        }
        return elements;
    }

    private static boolean isWorkspaceVisible(DeviceProfile deviceProfile) {
        // AresLauncher §9: always keep the workspace visible+scaled+blurred behind the all-apps
        // reveal (stock only does this on tablets by default) — the pane transition is meant to
        // read as one continuous canvas, not an opaque sheet covering a hidden home screen.
        return true;
    }

    @Override
    public int getFloatingSearchBarRestingMarginBottom(Launcher launcher) {
        return 0;
    }

    @Override
    public int getFloatingSearchBarRestingMarginStart(Launcher launcher) {
        DeviceProfile dp = launcher.getDeviceProfile();
        return dp.allAppsLeftRightMargin + dp.getAllAppsIconStartMargin(launcher);
    }

    @Override
    public int getFloatingSearchBarRestingMarginEnd(Launcher launcher) {
        DeviceProfile dp = launcher.getDeviceProfile();
        return dp.allAppsLeftRightMargin + dp.getAllAppsIconStartMargin(launcher);
    }

    @Override
    public boolean shouldFloatingSearchBarUsePillWhenUnfocused(Launcher launcher) {
        DeviceProfile dp = launcher.getDeviceProfile();
        return dp.getDeviceProperties().isPhone() && !dp.getDeviceProperties().isLandscape();
    }

    /**
     * AresLauncher §9: no workspace scrim.
     *
     * The scrim is a full-screen wash that darkens everything behind the all-apps surface -- the
     * single strongest cue that one thing is on top of another. It is what remained of the
     * "overlay" reading after {@code 588df013a3} removed the opaque bottom-sheet panel. On one
     * canvas nothing is behind anything, so it goes.
     *
     * Note this leaves app-list content sitting directly on the wallpaper. If contrast proves
     * insufficient, the fix is a subtle background on the PANE itself -- which travels with it, as
     * a page of the canvas would -- never a wash over the workspace behind it.
     */
    @Override
    public ScrimColors getWorkspaceScrimColor(Launcher launcher) {
        return new ScrimColors(Color.TRANSPARENT, /* foregroundColor */ Color.TRANSPARENT);
    }
}
