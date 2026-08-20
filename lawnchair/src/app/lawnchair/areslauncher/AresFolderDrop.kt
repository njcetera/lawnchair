package app.lawnchair.areslauncher

import android.util.Log
import android.view.View
import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import kotlin.math.hypot

/**
 * **Dwell to drop in, and dwell to create** — holding an icon still over another tile makes that
 * tile a drop target (§4, §17, §18).
 *
 * One dwell, two resolutions, decided entirely by what is underneath:
 *
 *  - over a **folder** → the folder **opens**, mid-drag, so the position inside it can be chosen
 *    ([AresFolderPreview]); a release then files the icon in at that position;
 *  - over another **icon** → a new folder is created holding both.
 *
 * §17's rule is that an operation with several outcomes must be *one* implementation. So the target
 * hit-test, the timer, the highlight and the release are shared verbatim, and only [Kind] differs at
 * the moment of the write. Building the second as its own dwell is what the audit in
 * design/strategy-d-dead-paths.md explicitly warned would drift.
 *
 * ## Why a dwell, and not a plain hover
 *
 * §4 specifies **live reflow**: the grid physically repacks around the finger continuously, which
 * is the signature Windows Phone feel. That is fundamentally at odds with "hover over a folder to
 * drop into it" — the folder is being *pushed aside* by the very drag approaching it, so there is
 * no stable hover target by construction.
 *
 * Put to the user as a product call, their answer was a dwell:
 *
 * > *"hold still over a folder for roughly half a second, the reflow freezes, the folder highlights
 * > as a target, and releasing drops the icon inside."*
 *
 * So the reflow is unchanged everywhere else, and a folder drop becomes a deliberate act rather
 * than something that happens by accident mid-drag. [DWELL_MS] is the one knob.
 *
 * ## One mechanism, two sources, two resolutions
 *
 * §17's rule is that an operation with several entry points must be *one implementation*, because
 * parallel ones drift. Two drag pipelines reach this object, and they could not be less alike:
 *
 *  - **A drag inside the home grid** is an [androidx.recyclerview.widget.ItemTouchHelper] reorder
 *    and never enters `DragController` at all. It feeds this from
 *    [AresHomeReorder.Callback.onChildDraw].
 *  - **A drag from the app list, the widget picker or out of an open folder** is a real
 *    `DragController` drag. It feeds this from `Workspace.onDragOver`.
 *
 * Everything after the position update — target hit-testing, the dwell timer, the highlight, and
 * the write — is shared, so a folder behaves identically regardless of where the icon came from.
 * That was an explicit requirement, not an optimisation.
 *
 * ## Hard constraint: stock's folder drop path is a live crash here
 *
 * `FolderIcon.onDragEnter` opens with
 *
 * ```java
 * CellLayoutLayoutParams lp = (CellLayoutLayoutParams) getLayoutParams();
 * CellLayout cl = (CellLayout) getParent().getParent();
 * ```
 *
 * Our folder icons are RecyclerView rows, so both casts throw. Routing drags there would convert
 * today's silent no-op into a crash (design/strategy-d-dead-paths.md). The highlight is therefore
 * ours ([AresHomeListView.setFolderDropTarget]) and the write goes through
 * [Folder.addFolderContent], which touches only the folder's *internal* `CellLayout` — that one is
 * real under Strategy D and stock's folder machinery is intact behind it.
 *
 * `Launcher.removeItem` is likewise unusable: its `Workspace.getViewByItemId` walks `CellLayout`
 * children and can never find our rows. The adapter is asked directly instead, the same route
 * [AresFolderEdit.Session.removeFromFolder] already had to take.
 */
object AresFolderDrop {

    private const val TAG = "AresFolderDrop"

    /**
     * How long the drag must hold still over a folder before it becomes the drop target.
     *
     * The user described "roughly half a second". Stock's comparable delays bracket it — the
     * spring-load open is `ON_OPEN_DELAY = 800`, the folder's own drag-exit close is 400 — and
     * 500ms sits between a flick past a folder and a wait that feels like the launcher has hung.
     * One constant, expected to be tuned in the hand.
     */
    const val DWELL_MS = 500L

    /**
     * How far the drag may drift while dwelling before the timer restarts.
     *
     * Not zero: a real finger jitters by a pixel or two even when the user believes it is still,
     * and a strict test would make the dwell unreachable on hardware while passing under synthetic
     * input — the worst possible split between the device and the harness.
     */
    private const val DWELL_SLOP_PX = 18f

    /** The grid the current drag is over, or null when nothing is being tracked. */
    private var grid: AresHomeListView? = null

    /** Item being dragged. Held so hit-testing can exclude it and the drop can resolve it. */
    private var dragged: ItemInfo? = null

    /** Holder container currently under the drag point, whether or not the dwell has elapsed. */
    private var candidate: View? = null

    /** [candidate]'s model item. */
    private var candidateInfo: ItemInfo? = null

    /** What releasing on [candidateInfo] would do. Never [Kind.NONE] while a candidate is held. */
    private var candidateKind = Kind.NONE

    /** Where the drag was when the dwell timer was last armed. */
    private var anchorX = 0f
    private var anchorY = 0f

    /** True once the dwell has elapsed: the reflow is frozen and the target is highlighted. */
    private var armed = false

    /** True while the drag is outside an open folder and the close countdown is running. */
    private var previewExiting = false

    private val dwellElapsed = Runnable { arm() }

    private val previewExitElapsed = Runnable { closePreview() }

    /**
     * Reports where the drag currently is, in [list]'s own coordinate space.
     *
     * Idempotent and cheap: it is called on every frame of an in-grid drag and every move event of
     * an external one. It only restarts the timer when the answer actually changes, which is what
     * makes "held still" mean what it says.
     */
    @JvmStatic
    fun onDragPoint(list: AresHomeListView, item: ItemInfo?, x: Float, y: Float) {
        if (item == null) {
            clear()
            return
        }
        if (grid !== list || dragged !== item) {
            clear()
            grid = list
            dragged = item
        }

        // AN OPEN FOLDER OWNS THE POINTER, and the grid underneath must not be consulted at all.
        //
        // The folder is drawn over the grid, so a point inside it is also inside whatever tile
        // happens to sit behind it. Asking the grid what is under the finger would answer with some
        // unrelated icon, retarget the dwell onto it, and offer to build a folder out of two items
        // the user cannot even see. The only question that matters while a folder is open is where
        // the finger is *relative to that folder*.
        if (AresFolderPreview.isOpen()) {
            if (AresFolderPreview.onDragPoint(x, y)) {
                cancelPreviewExit()
            } else if (!previewExiting) {
                // Out of the folder, but not for long enough yet. The wait is what makes the
                // interaction reversible without being twitchy -- a finger that clips the edge on
                // its way to a slot must not close the folder out from under it.
                previewExiting = true
                list.postDelayed(previewExitElapsed, AresFolderPreview.EXIT_CLOSE_MS)
            }
            return
        }

        val view = list.dropCandidateUnder(x, y, item.id)
        val info = view?.let { list.aresAdapter.itemAt(list.getChildAdapterPosition(it)) }
        val kind = if (info == null) Kind.NONE else kindOf(info, item)
        if (view == null || info == null || kind == Kind.NONE) {
            clearTarget()
            return
        }
        candidateKind = kind

        if (info !== candidateInfo) {
            // Logged on change only -- this runs on every frame of a drag, so per-frame logging is
            // not free. It earns its place because the failure it exposes is otherwise invisible: a
            // drop point mapped into the wrong coordinate space does not throw, it just never finds
            // a tile, and the whole feature then looks like it "does not work" with nothing
            // anywhere saying why. That is exactly how this was built wrong twice.
            Log.d(TAG, "dwell target: (${x.toInt()},${y.toInt()}) -> item ${info.id}")
            // A different tile: start counting again from here.
            clearTarget()
            candidate = view
            candidateInfo = info
            restart(list, x, y)
            return
        }
        // Same tile. Only a real move restarts the count -- see DWELL_SLOP_PX.
        //
        // A move restarts the count even when the dwell has already ARMED, and the `!armed` guard
        // that used to be here was wrong in two directions. The user's own words are "holding one
        // app over another for a moment", and they asked for the whole thing to stay reversible
        // "before ever letting go" -- so a finger that moves on is no longer holding, the ring must
        // drop, and the release must be an ordinary reorder again. Left sticky, an arm survived any
        // amount of subsequent travel as long as the drag stayed within the same tile, and the only
        // way to undo it was to leave that tile entirely.
        //
        // It also mattered for the harness, which is how it was noticed: synthetic input arrives as
        // a handful of discrete `input motionevent` calls hundreds of milliseconds apart, so an
        // automated drag looks like a sequence of half-second holds and can arm on any tile it
        // passes over. Disarming on movement is what keeps a scripted reorder a reorder.
        if (hypot(x - anchorX, y - anchorY) > DWELL_SLOP_PX) {
            if (armed) {
                armed = false
                list.setFolderDropTarget(null)
            }
            restart(list, x, y)
        }
    }

    /**
     * True while the live reflow must stand down, so the tile the drag is aiming at stays put.
     *
     * The dwell has to be *reachable* before it can be satisfied: under §4's live reflow a tile is
     * swapped aside by the very drag approaching it, so a target that moves can never be dwelt on
     * and can never carry a highlight the user can see.
     *
     * ## Why the two resolutions freeze differently, and why that is not a second mechanism
     *
     * A **folder** freezes the moment the drag enters it. That is the shipped behaviour and it is
     * cheap because folders are rare — a grid has one or two, so suspending the reflow over them
     * costs almost nothing.
     *
     * An **icon** must not, because *every* tile is a create-a-folder candidate. Freezing on entry
     * would suspend the reflow over the whole grid and drag-to-reorder would stop working
     * altogether: [AresHomeReorder.Callback.chooseDropTarget] declines the swap while this is true,
     * and the drag is always over some tile under masonry, so it would never be false again.
     *
     * What holds an icon still instead is
     * [AresHomeReorder.Callback.SWAP_TRAVEL_FRACTION] — a tile is displaced once the drag *reaches
     * its centre*, not the instant it touches its edge. That leaves the tile's leading half as a
     * region where it is under the drag and has not moved, which is exactly the region a dwell
     * needs, and it costs nothing anywhere else because a drag that keeps going still displaces it.
     *
     * Once the dwell has **armed**, the freeze applies to both: the ring is up, the user is being
     * shown a target, and a stray pixel of drift must not yank it out from under them.
     *
     * ## And it holds for the whole life of an open folder
     *
     * While [AresFolderPreview] has a folder open, the grid behind it is not being looked at — see
     * [onDragPoint] — so nothing would retarget the reflow, but nothing would *stop* it either. It
     * has to stay frozen: the drag point wanders freely inside the folder, and letting the grid
     * repack around a finger the user is aiming at a folder slot would rearrange the home screen
     * behind their back, and then show them the result the moment the folder closed.
     */
    @JvmStatic
    fun isFrozen(): Boolean = AresFolderPreview.isOpen() ||
        (candidateInfo != null && (candidateKind == Kind.ADD || armed))

    /** True once the dwell has elapsed: the target is highlighted and a release drops into it. */
    @JvmStatic
    fun isArmed(): Boolean = armed

    /** Forgets everything about the current drag. Safe to call at any time. */
    @JvmStatic
    fun cancel() = clear()

    private fun restart(list: AresHomeListView, x: Float, y: Float) {
        anchorX = x
        anchorY = y
        list.removeCallbacks(dwellElapsed)
        list.postDelayed(dwellElapsed, DWELL_MS)
    }

    /**
     * The dwell has elapsed. What that *means* depends on what is underneath, and this is the only
     * place the two resolutions diverge before the drop.
     *
     *  - Over a **folder**, the folder OPENS ([AresFolderPreview]) so the position inside it can be
     *    chosen. The open folder is the feedback, so no ring is raised — a highlight around a tile
     *    that has just expanded to fill the screen would be describing something that is no longer
     *    there.
     *  - Over an **icon**, the ring is raised and a release builds a folder of the two.
     *
     * The ring is the fallback when a folder declines to open — an app-drawer folder, or one whose
     * contents have gone. Declining silently would leave the drag with an armed target the user
     * cannot see, and a release would then do something they were never shown.
     */
    private fun arm() {
        val list = grid ?: return
        val view = candidate ?: return
        armed = true
        if (candidateKind == Kind.ADD) {
            val icon = folderIconOf(view)
            if (icon != null && AresFolderPreview.open(list.launcher, list, icon)) {
                list.setFolderDropTarget(null)
                Log.i(TAG, "dwell elapsed on ${candidateInfo?.id}; folder opened for placement")
                return
            }
        }
        list.setFolderDropTarget(view)
        Log.i(TAG, "dwell elapsed on ${candidateInfo?.id}; reflow frozen, target armed")
    }

    /**
     * The drag has been outside the open folder long enough: close it, and disarm.
     *
     * Disarming is the half that makes the interaction *repeat*. The user asked to be able to pull
     * an icon back out "before ever letting go" and then place it on the grid — or dwell again and
     * reopen. Leaving the folder armed after closing it would mean the next release still filed the
     * icon away, which is the opposite of what pulling out expresses.
     */
    private fun closePreview() {
        previewExiting = false
        AresFolderPreview.close()
        clearTarget()
    }

    private fun cancelPreviewExit() {
        if (!previewExiting) return
        previewExiting = false
        grid?.removeCallbacks(previewExitElapsed)
    }

    private fun clearTarget() {
        grid?.removeCallbacks(dwellElapsed)
        if (armed) grid?.setFolderDropTarget(null)
        armed = false
        candidate = null
        candidateInfo = null
        candidateKind = Kind.NONE
    }

    private fun clear() {
        cancelPreviewExit()
        // Abandons an open preview without committing, which is the correct reading of every path
        // that reaches here: a CANCEL, a new drag, or the end of one. The user's rule is that only
        // a manual release adds an item to a folder.
        AresFolderPreview.close()
        clearTarget()
        grid = null
        dragged = null
    }

    /** What dwelling on [target] with [source] in hand would do. */
    private enum class Kind {
        /** Nothing; the pair is not foldable and the reflow is left alone. */
        NONE,

        /** [target] is a folder: file the source into it. */
        ADD,

        /** [target] is an icon: build a new folder holding both. */
        CREATE,
    }

    /**
     * Whether [target] can take [source], and how.
     *
     * `Folder.willAccept` is stock's own predicate for "may live inside a folder" — apps, deep
     * shortcuts and app pairs, never widgets — so the question is asked with the same rule stock
     * uses rather than a second one that could disagree with it. It is also what keeps this out of
     * the way of ordinary reordering: dragging a widget past anything is never eligible, and
     * nothing can be folded into a widget.
     *
     * Note the asymmetry is stock's, not ours: a **folder** is not itself foldable
     * (`willAcceptItemType` excludes `ITEM_TYPE_FOLDER`), so dwelling a folder on an icon does
     * nothing and dwelling an icon on a folder is [Kind.ADD]. Only two leaves make [Kind.CREATE].
     */
    private fun kindOf(target: ItemInfo, source: ItemInfo): Kind {
        if (target.id == source.id) return Kind.NONE
        if (!Folder.willAccept(source)) return Kind.NONE
        if (target is FolderInfo) {
            return if (FolderInfo.willAcceptItemType(source.itemType)) Kind.ADD else Kind.NONE
        }
        return if (Folder.willAccept(target)) Kind.CREATE else Kind.NONE
    }

    /**
     * Completes a drop that the dwell armed, moving [item] into the folder it locked onto.
     *
     * ## Why this resolves against the armed target rather than re-hit-testing the drop point
     *
     * There is no usable drop point at either call site. In the grid, this is reached from
     * `ItemTouchHelper`'s `clearView`, which fires at the **end of the settle animation** — the
     * dragged view's translation has already been animated back to zero by then, so its position
     * describes where the tile landed, not where the finger let go. Externally,
     * `DragController.drop` calls `onDragExit` on the target *before* `onDrop`, so the arm cannot
     * be torn down on exit either.
     *
     * The armed target is the sound answer regardless: arming requires the drag to have held still
     * over one tile, and any move onto a different tile or off the grid disarms it in
     * [onDragPoint]. What is worth re-checking is that the tile is still *there* — a rebind or a
     * model update can retire a holder mid-drag — and that is what the position lookup below does.
     *
     * @return true when the item was consumed into a folder, so the caller must not also place it
     *   on the grid.
     */
    @JvmStatic
    fun commitDrop(launcher: Launcher, item: ItemInfo): Boolean {
        // An open folder resolves against the slot the user positioned, not against the tile the
        // dwell originally locked onto -- the folder covers the grid by then, so there is no
        // meaningful tile any more.
        if (AresFolderPreview.isOpen()) {
            cancelPreviewExit()
            val done = AresFolderPreview.commit(launcher, item)
            clear()
            return done
        }
        if (!armed) return false
        val list = grid
        val view = candidate
        val info = candidateInfo
        if (list == null || view == null || info == null || view.parent !== list) {
            Log.i(TAG, "armed target went away before the drop; declined")
            clear()
            return false
        }
        if (list.aresAdapter.itemAt(list.getChildAdapterPosition(view)) !== info) {
            Log.i(TAG, "armed target ${info.id} no longer binds that holder; declined")
            clear()
            return false
        }

        val done = when (kindOf(info, item)) {
            Kind.ADD -> addToFolder(launcher, list, view, info as FolderInfo, item)
            Kind.CREATE -> createFolder(launcher, list, info, item)
            Kind.NONE -> false
        }
        clear()
        return done
    }

    /**
     * Files [item] into the existing folder [folderInfo], at the end.
     *
     * **This is now the fallback, not the normal path.** A dwell over a folder opens it
     * ([AresFolderPreview]) and the release lands at the position the user chose inside it. This
     * runs only when the folder *declined* to open — an app-drawer folder, or one whose contents
     * have gone — where appending is the best answer available and is still better than refusing
     * a drop the ring told the user would work.
     *
     * [Folder.addFolderContent] is the whole write: it inserts into the `FolderInfo`, has
     * `FolderGridOrganizer` assign a legal rank and in-folder cell, persists with
     * `addOrMoveItemInDatabase` — a *move*, since the item already owns a row — re-ranks the rest
     * through `updateItemLocationsInDatabaseBatch`, and refreshes the icon's preview. Everything it
     * touches is folder-internal, where the `CellLayout` is real.
     *
     * The desktop half is ours: drop the row from the adapter and renumber what is left, so the
     * grid packs closed over the gap exactly as it does for any other removal.
     *
     * ## The model write is INLINE; only the adapter mutation is posted
     *
     * The split is load-bearing, and getting it wrong is silent data loss.
     *
     * This returns `true`, and `AresHomeDrop.handleExternalDrop` returns `true`, and
     * `DragController.drop` then calls `Folder.onDropCompleted(success = true)` on the *source*
     * folder **synchronously**. If that folder is now down to one item, that runs
     * `replaceFolderWithFinalItem` → `deleteCollectionAndContentsFromDatabase`, whose first
     * statement is `DELETE FROM favorites WHERE container = <source folder id>`. Meanwhile
     * `Folder.onDragStart` removed the item from `getContents()` **in memory only** — its row still
     * names the source folder as its container until something writes it. So a deferred write means
     * the row is deleted first and the write then updates zero rows, with no error anywhere.
     *
     * It happens to survive today only because `LauncherDelegate` takes an asynchronous branch
     * while the source folder still has bound views; the synchronous branch is one `unbindItems()`
     * away. So the write goes first, inline, and only the adapter half is posted.
     *
     * That half is posted because in the in-grid case this is reached from
     * [AresHomeReorder.Callback.clearView], which `ItemTouchHelper` calls from inside a recover
     * animation's end callback — notifying a removal there risks mutating the adapter while
     * RecyclerView is mid-frame, and the cost of waiting a frame is invisible.
     */
    private fun addToFolder(
        launcher: Launcher,
        list: AresHomeListView,
        targetView: View,
        folderInfo: FolderInfo,
        item: ItemInfo,
    ): Boolean {
        val folder = folderOf(targetView) ?: run {
            Log.e(TAG, "folder icon ${folderInfo.id} has no Folder view; drop declined")
            return false
        }
        folder.addFolderContent(item, folderInfo.getContents().size, true)
        // Force-write the ranks for the reason given on Folder#aresPersistContentRanks: stock's
        // batch skips rows whose in-memory rank already matches their index, and that is not always
        // what the database holds, so an append can read back as a prepend after a reload.
        folder.aresPersistContentRanks()
        Log.i(TAG, "moved item ${item.id} into folder ${folderInfo.id}")
        list.post {
            list.aresAdapter.removeItems { it.id == item.id }
            AresHomeReorder.persistOrder(launcher, list.aresAdapter.snapshot())
        }
        return true
    }

    /**
     * Builds a new folder holding [targetInfo] and [item], and puts it where [targetInfo] was.
     *
     * ## The write order is the whole correctness argument
     *
     * `ModelDbController.deleteUnparentedApps()` runs on **every** load and permanently deletes any
     * row whose `container` names an id that is not in the table
     * (design/model-persistence.md's second ⛔ banner). So the folder's own row has to exist before
     * either member points at it — not "eventually", but in that order.
     *
     * It does, and by construction rather than by timing. `addItemToDatabase` assigns
     * `folderInfo.id` **synchronously** on the calling thread and then enqueues the INSERT on
     * `MODEL_EXECUTOR`; every `addFolderContent` below enqueues its UPDATE on the same single
     * executor afterwards. FIFO on one thread is the ordering guarantee — there is never an instant
     * at which the table holds a member row naming a folder row that is not there.
     *
     * The reverse hazard does not arise either: nothing is deleted here. Both members are *moved*
     * (`addOrMoveItemInDatabase` on a row that already exists is a move), so no id is ever retired.
     *
     * ## Why the folder is inflated at all, when the view is thrown away
     *
     * The adapter is data-backed and re-inflates its own `FolderIcon` on bind, so this one is
     * discarded the moment it has served its purpose. Its purpose is the `Folder` behind it:
     * [Folder.addFolderContent] is the same call [addToFolder] uses, and it is what assigns a legal
     * in-folder rank and cell through `FolderGridOrganizer`, persists, re-ranks the rest and
     * refreshes the preview. Reproducing that by hand is how the two resolutions would drift.
     *
     * Everything it touches is folder-internal, where the `CellLayout` is real. In particular
     * `FolderIcon.onDragEnter` — whose first two lines cast to `CellLayoutLayoutParams` and
     * `CellLayout` — is never reached, and neither is `Launcher.addFolder`, whose tail calls
     * `getParentCellLayoutForView(newFolder).getShortcutsAndWidgets()` on a view that
     * `Workspace.addInScreen` has just discarded.
     *
     * `animate = false`, unlike [addToFolder]: this icon is detached and about to be dropped, so a
     * preview animation on it would run against nothing.
     *
     * ## Where it lands
     *
     * At the **target's** index, never appended. The target is the tile the user aimed at, so that
     * is the position the folder inherits; appending would move the result away from the finger.
     * The freeze in [isFrozen] is what makes that index mean something — the target has not been
     * reflowed aside, so it is still where it was when the dwell armed.
     */
    private fun createFolder(
        launcher: Launcher,
        list: AresHomeListView,
        targetInfo: ItemInfo,
        item: ItemInfo,
    ): Boolean {
        // A legal desktop cell before anything is written. Order under masonry is `rank` alone, but
        // LoaderCursor.checkItemPlacement validates cells on every load and deletes what it
        // rejects. Excluding the target guarantees an answer: its own cell is about to be vacated
        // by the move into the folder, so at worst the folder takes the slot the target had.
        val cell = IntArray(2)
        val screenId = AresWidgetAdd.findFreeCell(launcher, 1, 1, cell, targetInfo.id)
        if (screenId == AresWidgetAdd.NO_SCREEN) {
            Log.e(TAG, "no free cell for a new folder; drop declined")
            return false
        }

        val folderInfo = FolderInfo()
        // Not final -- persistOrder below renumbers the whole grid densely -- but it means the row
        // is right on its first pass instead of arriving at 0 and sorting to the top.
        folderInfo.rank = targetInfo.rank
        launcher.modelWriter.addItemToDatabase(
            folderInfo,
            Favorites.CONTAINER_DESKTOP,
            screenId,
            cell[0],
            cell[1],
        )
        if (folderInfo.id == ItemInfo.NO_ID) {
            Log.e(TAG, "folder row was not given an id; drop declined")
            return false
        }

        val folder = FolderIcon
            .inflateFolderAndIcon(R.layout.folder_icon, launcher, list, folderInfo)
            .folder
        if (folder == null) {
            Log.e(TAG, "new folder ${folderInfo.id} inflated without a Folder; drop declined")
            return false
        }

        // Target first, so the tile that was already on the grid keeps the leading position in the
        // preview -- the same order stock uses in Workspace.createUserFolderIfNecessary.
        folder.addFolderContent(targetInfo, 0, false)
        folder.addFolderContent(item, 1, false)
        Log.i(
            TAG,
            "created folder ${folderInfo.id} at screen=$screenId cell=(${cell[0]},${cell[1]}) " +
                "from items ${targetInfo.id} and ${item.id}",
        )

        // Posted for the reason spelled out on addToFolder: in the in-grid case this is reached
        // from ItemTouchHelper's clearView, inside a recover animation's end callback, and
        // notifying adapter changes there mutates RecyclerView mid-frame. The model write above is
        // deliberately NOT posted.
        list.post {
            val adapter = list.aresAdapter
            val targetAt = adapter.indexOf(targetInfo)
            val sourceAt = adapter.indexOf(item)
            // Both rows leave; the folder takes the target's slot. Dropping the source first
            // shifts the target up by one when it sat earlier in the order, so account for it
            // before the removal rather than re-deriving an index from a list that has changed.
            var at = if (targetAt >= 0) targetAt else adapter.itemCount
            if (sourceAt in 0 until at) at--
            adapter.removeItems { it.id == targetInfo.id || it.id == item.id }
            adapter.addItemAt(folderInfo, at)
            AresHomeReorder.persistOrder(launcher, adapter.snapshot())
        }
        return true
    }

    /** The [FolderIcon] a holder container hosts, or null when it hosts anything else. */
    private fun folderIconOf(container: View): FolderIcon? =
        (container as? android.view.ViewGroup)?.getChildAt(0) as? FolderIcon

    /** The [Folder] behind a holder container hosting a [FolderIcon], or null. */
    private fun folderOf(container: View): Folder? = folderIconOf(container)?.folder

    /**
     * Feeds a `DragController` drag (app list, widget picker, or an app leaving an open folder)
     * into the same dwell the in-grid reorder uses.
     *
     * ## It resolves against the TOUCH POINT, not `DragObject.getVisualCenter()`
     *
     * Stock resolves every drop against the visual centre, so that was the obvious choice. It is
     * wrong on this launcher, and the difference is not small — measured on the emulator during an
     * app-list drag, with the finger at DragLayer `(234,508)`:
     *
     * ```
     * ext dl=(147,280) list@dl=Rect(133,426 - 947,2093) scale=0.78 -> local=(18,-186)
     * ```
     *
     * `getVisualCenter` answered **228px above and 87px left of the finger** — off the top of the
     * grid entirely, so every hit-test returned nothing and the dwell could never arm. The drag
     * view is drawn *at* the finger (confirmed from a screenshot taken mid-drag), so the visual
     * centre is not describing what is on screen: `DragPreviewProvider` derives the registration
     * point and drag region from a stock icon-above-label cell, and our app-list rows are Niagara
     * rows — icon left, label right — so the geometry it computes does not match the view.
     *
     * **This applies to any drop-point resolution on this surface**, not only the dwell.
     *
     * ## And `DragObject.x`/`y` is in the DROP TARGET's space, not the DragLayer's
     *
     * The second half of the same bug, and it looks identical from the outside.
     * `DragController.findDropTarget` runs the touch point through
     * `mapCoordInSelfToDescendant(target.getDropView(), ...)` before storing it, so by the time
     * `Workspace.onDragOver` sees it, it is already in **Workspace** coordinates — the workspace
     * scale and page scroll are taken out of it. Mapping it a second time from the DragLayer
     * produced `(20,-184)`, within two pixels of the visual-centre answer, which is exactly the
     * kind of near-miss that reads as "the mapping is roughly right, something else is wrong".
     *
     * `Workspace` is the only drop target that calls this, so the space is known. The mapping still
     * goes through `Utilities.mapCoordInSelfToDescendant` rather than arithmetic on `getLeft()`: it
     * is the same primitive stock's own `mapPointFromDropLayout` uses, and a hand-rolled version
     * that is subtly wrong does not fail loudly — it simply picks the wrong tile, or none.
     */
    @JvmStatic
    fun onExternalDragOver(launcher: Launcher, d: DropTarget.DragObject) {
        val list = launcher.workspace?.aresHomeList ?: return
        val local = toListSpace(launcher, list, d.x.toFloat(), d.y.toFloat())
        onDragPoint(list, d.dragInfo, local[0], local[1])
    }

    /**
     * Maps a point in **Workspace** coordinates — which is what `DropTarget.DragObject.x`/`y`
     * carries once the Workspace is the drop target — into [list]'s own coordinates.
     */
    @JvmStatic
    fun toListSpace(launcher: Launcher, list: AresHomeListView, x: Float, y: Float): FloatArray {
        val coord = floatArrayOf(x, y)
        val workspace = launcher.workspace ?: return coord
        Utilities.mapCoordInSelfToDescendant(list, workspace, coord)
        return coord
    }
}
