/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.launcher3.allapps;

import static androidx.core.view.HapticFeedbackConstantsCompat.CLOCK_TICK;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.launcher3.allapps.AlphabeticalAppsList.FastScrollSectionInfo;

/**
 * Moves the app list to the section the fast-scroll thumb is pointing at.
 *
 * <h2>Why this jumps rather than animating (AresLauncher §10/§12)</h2>
 *
 * This used to hand the target to a {@link androidx.recyclerview.widget.LinearSmoothScroller},
 * whose duration is <b>proportional to the distance travelled</b> (25ms per inch). That reads as
 * pleasant for a tap-to-jump and as lag for a thumb being dragged, because the list is always
 * animating toward where the finger <i>was</i> — and it gets worse the longer the list is, which is
 * exactly how the user described it: <i>"take a sec for the app list to catch up if it's a large
 * list."</i>
 *
 * <p>Measured on the AresFold emulator against a list whose whole scroll range is only 3574px, with
 * the fast scroll instrumented: dragging the thumb the length of the track moved the list
 * <b>0px</b> before the finger lifted and then took <b>318-413ms</b> to travel the 1646px
 * afterwards. Shorter drags scaled with it — 523px took 134-168ms, 1187px took 338ms — i.e. a
 * near-constant 0.19-0.32 ms/px. That proportionality is the diagnosis: the cost is in the
 * animation's distance-derived duration, not in per-frame binding, which would not wait until the
 * finger lifted to spend itself. On a real list of a few hundred apps the same rate is seconds.
 *
 * <p>After the change the same drags settle in <b>4-14ms</b> regardless of distance.
 *
 * <p>There is nothing left for an animated settle to do on release: every call here happens
 * <i>during</i> a drag — {@link com.android.launcher3.views.RecyclerViewFastScroller} only calls
 * {@link AllAppsRecyclerView#scrollToPositionAtProgress} from its {@code ACTION_MOVE} branch, and
 * only while {@code mIsDragging} — so by the time the finger lifts the list is already exactly
 * where the thumb put it.
 *
 * <p>A second defect goes with it. The old scroller used {@code SNAP_TO_ANY}, which does nothing at
 * all when the target is already on screen and otherwise parks the section at the <i>bottom</i>
 * edge scrolling down and the <i>top</i> edge scrolling up. Short thumb drags therefore moved the
 * list not at all (measured: 0px), and the resting position depended on which way you came from.
 * {@code scrollToPositionWithOffset(position, 0)} puts the section under the top padding either
 * way, which is what the thumb's position is claiming.
 */
public class AllAppsFastScrollHelper {

    private static final int NO_POSITION = -1;

    private int mTargetFastScrollPosition = NO_POSITION;

    private AllAppsRecyclerView mRv;
    private ViewHolder mLastSelectedViewHolder;

    public AllAppsFastScrollHelper(AllAppsRecyclerView rv) {
        mRv = rv;
    }

    /**
     * Puts the given section at the top of the list, immediately.
     */
    public void scrollToSection(FastScrollSectionInfo info) {
        if (mTargetFastScrollPosition == info.position) {
            return;
        }
        mTargetFastScrollPosition = info.position;

        // One tick per section crossed. RecyclerViewFastScroller deliberately skips its own haptic
        // for ALL_APPS_SCROLLER because this class owns it; it used to be fired as a side effect of
        // getVerticalSnapPreference(), which went with the smooth scroller.
        mRv.performHapticFeedback(CLOCK_TICK);

        RecyclerView.LayoutManager layoutManager = mRv.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(info.position, 0);
        } else {
            // All-apps always uses a ScrollableLayoutManager (a GridLayoutManager), so this is a
            // guard rather than a live path -- but it still has to be a jump, not an animation.
            mRv.scrollToPosition(info.position);
        }

        final int position = info.position;
        // Posted rather than inline: scrollToPositionWithOffset only requests a layout, so the
        // target's holder does not exist yet. A Runnable queued now runs *after* the traversal,
        // because requestLayout installs a sync barrier that only doTraversal removes.
        mRv.post(() -> selectHolderAt(position));
    }

    public void onFastScrollCompleted() {
        mTargetFastScrollPosition = NO_POSITION;
        setLastHolderSelected(false);
        mLastSelectedViewHolder = null;
    }

    /**
     * Grows the icon the thumb is currently pointing at (the {@code state_activated} scale in
     * {@code all_apps_fastscroll_icon_anim}), and holds it out of the recycler while it is shown.
     */
    private void selectHolderAt(int position) {
        if (position != mTargetFastScrollPosition) {
            // A later section overtook this one before its layout landed.
            return;
        }
        ViewHolder currentHolder = mRv.findViewHolderForAdapterPosition(position);
        if (currentHolder == mLastSelectedViewHolder) {
            return;
        }
        setLastHolderSelected(false);
        mLastSelectedViewHolder = currentHolder;
        setLastHolderSelected(true);
    }

    private void setLastHolderSelected(boolean isSelected) {
        if (mLastSelectedViewHolder != null) {
            mLastSelectedViewHolder.itemView.setActivated(isSelected);
            mLastSelectedViewHolder.setIsRecyclable(!isSelected);
        }
    }
}
