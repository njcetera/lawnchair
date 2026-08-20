package app.lawnchair.areslauncher

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.Folder
import kotlin.math.hypot

/**
 * What a long-press on an app **inside an open folder** does (§18).
 *
 * ## The gesture stock gives that folder, and why it is wrong here
 *
 * `FolderPagedView.createNewView` hands every icon `setOnLongClickListener(mFolder)`, so a
 * long-press lands in `Folder.onLongClick` and goes straight to `Folder.startDrag`. Two things then
 * happen at once, and both are wrong for this launcher:
 *
 *  - `Workspace.beginDragShared` calls `btv.startLongPressAction()`, which raises that app's
 *    `PopupContainerWithArrow`. The user asked for the opposite: *"holding an app down to go into
 *    edit mode instead pull up the menu rather than going into edit mode"*.
 *  - The drag is **armed by the press alone**, before any movement. `Folder.onDragStart` then pulls
 *    the item out of the folder immediately (`mContent.removeItem` plus a suppressed
 *    `removeFolderContent`), which is the *"cause the icon to derender just for a moment"* half of
 *    the same report — and, before `529276c113` refused the drop, it is how an app silently left a
 *    folder for good with no drag ever performed.
 *
 * The popup is not merely in the way, it is what makes the gesture unavailable: it consumes the
 * long-press, so nothing else can be bound to it.
 *
 * ## The rule, and why it matches the home grid exactly
 *
 * An open folder should behave like the home grid, so the split is the same one
 * [AresHomeAdapter]'s long-click listener already makes:
 *
 * | gesture | not editing | editing |
 * |---|---|---|
 * | long-press an icon | enter edit mode | show that app's popup |
 * | touch and drag an icon | (nothing — stock's press-then-drag is gone) | move it |
 *
 * One gesture raises one thing. Nothing is lost: App info, Uninstall and an app's shortcuts are
 * still a long-press away, made deliberately from a surface that is already in the mode.
 */
object AresFolderDrag {

    /**
     * True when [dragSource] is a **home-screen folder on the Ares home surface** — i.e. this drag
     * came out of one of our open folders.
     *
     * ## What it is for: suppressing stock's drag *presentation*
     *
     * Un-muzzling the `DragController` gave us the drag we needed and, with it, the workspace-level
     * chrome we did not. `Workspace.onDragStart` is a `DragListener`, so any drag — including one
     * confined to a folder — drove the launcher into `SPRING_LOADED`. Captured from the user's
     * Pixel while it was on screen:
     *
     * ```
     * mState:        SpringLoaded
     * DropTargetBar  VISIBLE at 10,239-1070,376
     * Folder         open     26,324-672,1000
     * ```
     *
     * They described it as *"it pops the screen out"* — `SPRING_LOADED` scales the workspace down
     * and raises the drop-target bar behind the still-open folder.
     *
     * Neither belongs here. Edit mode (§4) is this launcher's own model and `SPRING_LOADED` is
     * stock's competing one; the home grid's own reorder ([AresHomeReorder]) never enters it
     * either, so *not* entering it is the consistent behaviour rather than a special case.
     *
     * ## The bar is suppressed for **this** drag, and the reasoning is not general
     *
     * An earlier version of this comment said the drop-target bar's Remove / App-info targets "are
     * not part of the interaction model" — a claim broad enough to describe every drag in the
     * launcher, while this predicate only ever gates folder-sourced ones. It read as a decision
     * about the product and is actually a decision about one gesture, and that gap is a trap: the
     * **app-list and widget-picker drags still raise the bar with live targets** (panel finding
     * R7), so anyone reading the general claim would conclude something about those paths that is
     * false.
     *
     * The narrow reason this drag suppresses it: the folder is *open* on top of the workspace while
     * the drag runs, and the bar's measured hit rect reaches into the top of it (`10,239-1070,376`
     * against a folder at `26,324-672,1000`). A Remove target live behind an open folder is how an
     * app dragged upward out of one silently disappeared — the defect `529276c113` and `e9bfe85edf`
     * were written to close. Whether the bar is right for a *drag that starts on the app list* is a
     * separate, still-open question; R7 records that its geometry is wrong there too, and nothing
     * here decides it.
     *
     * Dropping `SPRING_LOADED` costs nothing downstream: `NORMAL` also carries
     * `FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED`, so `Workspace.transitionStateShouldAllowDrop` still
     * lets the drag out of a folder land (see [AresHomeDrop.handleExternalDrop]).
     *
     * ## Not the same question as "is this drag intentional"
     *
     * That distinction — the one that separates the old data-loss defect from the drag-out feature
     * — is settled at the *start* of the gesture, not by where it lands: a press with no movement
     * can no longer begin a drag at all. This predicate is about presentation only, and it is true
     * for the whole life of a folder-sourced drag, inside the folder and after it has left.
     */
    @JvmStatic
    fun isFolderDrag(launcher: Launcher?, dragSource: Any?): Boolean {
        if (launcher == null || !AresWidgetAdd.isAresHome(launcher)) return false
        val folder = dragSource as? Folder ?: return false
        // App-drawer folders are Lawnchair's own and live outside the launcher model; they keep
        // stock behaviour here as everywhere else in this file.
        return !folder.isInAppDrawer
    }

    /**
     * Handles a long-press on the icon [v] inside the open [folder].
     *
     * Not editing yet: the press enters edit mode, here and on the home grid behind — they are one
     * mode, and §18 has the folder close back into a still-editing grid. [AresFolderEdit] is
     * attached in the same breath, because the usual attach point is the *tap* on the folder icon
     * that opened it ([AresHomeListView]'s touch listener) and that tap never happened when the
     * folder was opened before the mode was.
     *
     * Already editing: the press raises the app's popup, which is the only thing a long-press does
     * once the mode is on. That is where App info, Uninstall and an app's shortcuts live, and it
     * mirrors the home grid exactly.
     *
     * @return true when this consumed the gesture, so `Folder.onLongClick` must not fall through to
     *   `Folder.startDrag`.
     */
    @JvmStatic
    fun onFolderItemLongClick(launcher: Launcher, folder: Folder, v: View): Boolean {
        if (!AresWidgetAdd.isAresHome(launcher)) return false
        if (folder.isDestroyed) return false
        val grid = launcher.workspace?.aresHomeList ?: return false

        if (grid.isEditMode()) {
            (v as? BubbleTextView)?.startLongPressAction()
        } else {
            grid.enterEditMode()
            folder.folderIcon?.let { AresFolderEdit.attach(launcher, it) }
        }
        // Consumed either way. Falling through would arm the drag this whole object exists to
        // stop, and would raise the popup a second time on the editing branch.
        return true
    }

    /**
     * Starts a folder drag from a **plain touch-and-drag**, once the finger passes the touch slop.
     *
     * ## Why a touch listener rather than stock's press-then-drag
     *
     * The user's rule for edit mode, given for the home grid and applied here because the two are
     * one mode: *"while wiggling, I should [not] have to hold to edit before I can move an icon,
     * since it's already in edit mode I should be able to quickly drag to move and place."* That is
     * the same reason [AresHomeReorder.Callback.isLongPressDragEnabled] is false on the grid.
     *
     * Installed by [AresFolderEdit] for the life of an editing session, so outside edit mode a
     * folder behaves exactly as before.
     *
     * ## The two things it has to get right
     *
     * **Winning the gesture from `FolderPagedView`.** It is a `PagedView`, and its
     * `determineScrollingStart` claims any horizontal move past `getScaledTouchSlop()` — the same
     * threshold this uses, so a sideways drag is a race. `requestDisallowInterceptTouchEvent(true)`
     * at DOWN settles it. That flag is cleared again the moment the drag starts, because it
     * suppresses `onInterceptTouchEvent` all the way up the tree — including the `DragLayer`'s,
     * which is how `DragController` takes over the gesture it has just been handed. It is safe to
     * hold it until then: nothing above needs to intercept while no drag exists, and
     * `ViewGroup.dispatchTouchEvent` clears the flag at every ACTION_DOWN anyway, *before*
     * `onInterceptTouchEvent` runs, so `DragController` still sees the DOWN it reads `mMotionDown`
     * from.
     *
     * **Not stealing the × badge's tap.** The badge is a 48dp target on a ~83dp cell, so it covers
     * a large share of the icon; a gesture that starts on it is a tap on a control, never a drag
     * handle. Same carve-out the grid makes for the resize chevron.
     */
    class DragStarter(private val folder: Folder) : View.OnTouchListener {

        private var downX = 0f
        private var downY = 0f
        private var startedOnBadge = false
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    dragging = false
                    // The badge is a sibling cell drawn over the icon, so its bounds are in the
                    // icon's coordinate space already -- no transform mapping needed, unlike the
                    // grid, whose tiles carry edit mode's scale.
                    startedOnBadge = AresFolderEdit.isPointOnBadgeFor(folder, v, e.x, e.y)
                    if (!startedOnBadge) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging || startedOnBadge) return false
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (hypot(e.x - downX, e.y - downY) <= slop) return false
                    dragging = true
                    // Released before starting, so the DragLayer can intercept the next MOVE and
                    // hand the gesture to DragController. Held any longer, the drag view is created
                    // and then never receives a single move.
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    folder.startDrag(
                        v,
                        DragOptions().apply {
                            aresSuppressLongPressPopup = true
                            // The picked-up icon swells, exactly as a tile on the home grid does --
                            // one mode, one cue. Routed through stock's own DragView zoom rather
                            // than a second animator; see DragOptions.aresPickupScale.
                            aresPickupScale = AresEditMotion.PICKUP_SCALE_FACTOR
                        },
                    )
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    startedOnBadge = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            // Never consumed: the icon still needs its own click and long-press handling, and
            // DragController owns the stream from the moment the drag starts.
            return false
        }
    }
}
