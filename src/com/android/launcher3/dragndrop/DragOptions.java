/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.launcher3.dragndrop;

import android.graphics.Point;

import com.android.launcher3.DropTarget;

/**
 * Set of options to control the drag and drop behavior.
 */
public class DragOptions {

    /** Whether or not an accessible drag operation is in progress. */
    public boolean isAccessibleDrag = false;

    /** Whether or not the drag operation is controlled by keyboard. */
    public boolean isKeyboardDrag = false;

    /**
     * Specifies the start location for a simulated DnD (like system drag or accessibility drag),
     * null when using internal DnD
     */
    public Point simulatedDndStartPoint = null;

    /** Determines when a pre-drag should transition to a drag. By default, this is immediate. */
    public PreDragCondition preDragCondition = null;

    /**
     * AresLauncher: suppress the long-press popup {@code Workspace.beginDragShared} would otherwise
     * raise, and with it the pre-drag it installs.
     *
     * <p>Set only by drags this launcher starts itself, where the user has already decided to move
     * something and a menu would be both wrong and in the way — a plain touch-and-drag inside an
     * open folder while edit mode is on (see {@code AresFolderDrag}). It matters for more than the
     * menu: {@code startLongPressAction()} returns the popup's {@code PreDragCondition}, and a
     * non-null one puts {@code DragController} into PRE-drag, where {@code checkTouchMove} refuses
     * to look for a drop target and {@code drop()}'s {@code !mIsInPreDrag} gate never opens. A drag
     * that begins from an explicit gesture has no threshold left to clear.
     *
     * <p>Default false, so every stock caller is unaffected.
     */
    public boolean aresSuppressLongPressPopup = false;

    /**
     * AresLauncher: how much bigger than its source the {@code DragView} settles at, as a multiple.
     *
     * <p>The user's edit-mode request — <em>"when selecting an item in edit mode, it slightly
     * enlarges to really highlight that its been selected"</em> — applies to an icon inside an open
     * folder as much as to a tile on the home grid, because the two are one mode. On the grid the
     * real tile is what moves, so the host scales it directly; in a folder the moving thing is a
     * {@code DragView} in the drag layer, and this is what tells it to arrive bigger.
     *
     * <p>Fed into {@code LauncherDragController}'s existing {@code scalePx}, so the enlargement is
     * stock's own {@code DragView} zoom (150ms, one animator) rather than a second animator laid on
     * top of it.
     *
     * <p>Default 1f, which computes a zero {@code scalePx} — byte-identical to the previous
     * hardcoded {@code 0f}, so every stock drag is unaffected by construction.
     */
    public float aresPickupScale = 1f;

    /**
     * A drag scale that scales the original drag view size when the preDragCondition is met (or
     * is ignored if preDragEndScale is 0).
     */
    public float preDragEndScale;

    /** Scale of the icons over the workspace icon size. */
    public float intrinsicIconScaleFactor = 1f;

    public boolean isFlingToDelete;

    /**
     * Specifies a condition that must be met before DragListener#onDragStart() is called.
     * By default, there is no condition and onDragStart() is called immediately following
     * DragController#startDrag().
     *
     * This condition can be overridden, and callbacks are provided for the following cases:
     * - The pre-drag starts, but onDragStart() is deferred (onPreDragStart()).
     * - The pre-drag ends before the condition is met (onPreDragEnd(false)).
     * - The actual drag starts when the condition is met (onPreDragEnd(true)).
     */
    public interface PreDragCondition {

        public boolean shouldStartDrag(double distanceDragged);

        /**
         * The pre-drag has started, but onDragStart() is
         * deferred until shouldStartDrag() returns true.
         */
        void onPreDragStart(DropTarget.DragObject dragObject);

        /**
         * The pre-drag has ended. This gets called at the same time as onDragStart()
         * if the condition is met, otherwise at the same time as onDragEnd().
         * @param dragStarted Whether the pre-drag ended because the actual drag started.
         *                    This will be true if the condition was met, otherwise false.
         */
        void onPreDragEnd(DropTarget.DragObject dragObject, boolean dragStarted);

        /**
         * The offset points that should be overridden to update the dragLayer.
         */
        default Point getDragOffset() {
            return new Point(0,0);
        }
    }
}
