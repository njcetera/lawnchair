package app.lawnchair.areslauncher

import android.util.Log
import android.view.View
import android.view.ViewConfiguration
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
     * Mark a freshly-built desktop folder as a Windows-Phone-style (inline) folder and give it the
     * default title -- exactly what the (now-removed) new-folder FAB did. Every folder AresLauncher
     * creates is a WP folder now (owner 2026-08-25, "disable the old folder style"): the drag-merge
     * that used to build a stock OVERLAY folder builds a WP one instead, so a tap inline-expands it
     * rather than opening the overlay. Called BEFORE `addItemToDatabase`, so the `FLAG_ARES_WP` bit
     * is persisted with the row and every later load reads it back as WP.
     */
    private fun stampAresWp(launcher: Launcher, folderInfo: FolderInfo) {
        folderInfo.options = folderInfo.options or FolderInfo.FLAG_ARES_WP
        folderInfo.title = launcher.getString(R.string.ares_wp_folder_default_title)
    }

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
     * Upper bound on the [liveArming] latch (Mo2). The synthetic UP that ends the in-grid drag lands
     * in `clearView -> commitDrop` within a frame or two on a healthy create; this is comfortably
     * longer than that so it never pre-empts a real create, yet short enough that a dropped UP can't
     * leave the pane-swipe muted for a human-noticeable span.
     */
    private const val LIVE_ARM_FAILSAFE_MS = 300L

    /**
     * How long to wait before re-trying a dwell that landed mid folder-animation.
     *
     * Well under [DWELL_MS], because this is not a fresh dwell — the user has already held still
     * long enough to earn one, and making them pay the full 500ms again for the launcher's own
     * animation would read as the dwell simply not working. The close set runs ~200ms
     * (`config_materialFolderExpandDuration`), so a couple of these land about when it ends.
     */
    private const val ANIMATING_RETRY_MS = 80L

    /**
     * How far the drag may drift while dwelling before the timer restarts, and what counts as a
     * layout reframe rather than a finger move.
     *
     * Both were raw pixel constants (`18f` and `60f`) until 2026-09-02, tuned on the Pixel Fold and
     * applied unscaled to every device. `scaledTouchSlop` is 8dp -- 19.5px at the Fold's density
     * 2.4375, but **32px at density 4.0**, where an 18px dwell tolerance sat BELOW the threshold
     * Android itself uses to decide a finger has moved, making dwell-to-create-folder unreachable.
     *
     * Now derived from the platform's own slop via [AresGeometry], which is plain Kotlin so the
     * `:ares-geom-tests` JVM module can assert `touchSlop <= dwellSlop < reframeJump` across a
     * density matrix without a device. Not zero, and not a fixed pixel count: a real finger jitters
     * by a pixel or two even when the user believes it is still, and how many pixels that is
     * depends entirely on the screen.
     */
    private fun dwellSlopPx(v: View): Float =
        AresGeometry.dwellSlopPx(ViewConfiguration.get(v.context).scaledTouchSlop)

    /** See [dwellSlopPx]. Fingers are continuous; frames are not. */
    private fun reframeJumpPx(v: View): Float =
        AresGeometry.reframeJumpPx(ViewConfiguration.get(v.context).scaledTouchSlop)

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

    /**
     * How many times the S4 decline branch has actually run since process start. Exists for the
     * test channel only: the S4 journey's margin between "exercised" and "vacuously green" is a
     * few dozen milliseconds of settle timing, and a pass that never entered the branch must be
     * detectable (adversarial review, 2026-08-21). Monotonic; never reset.
     */
    private var declinedExiting = 0L

    /** See [declinedExiting]. */
    @JvmStatic
    fun declinedExitingCount(): Long = declinedExiting

    /**
     * §25 live-create gate. OFF: dwelling an icon on an icon draws the ring and the folder forms on
     * release ([createFolder]). ON (owner-requested default, 2026-08-23): the folder forms and OPENS
     * on the hold ([createLiveFolder]) with both apps editable inside, and the held app can be
     * dragged back out to dissolve it — the owner wants create to mirror the add-to-existing-folder
     * flow. Enabled now that the exit-handoff/dissolve machinery it leans on is owner-verified stable
     * (eager-dissolve + relay-through-freeze + slot-centre DOWN, 2026-08-23). The synthetic-UP
     * teardown ([arm]/[commitDrop]) and edit-session attach ([openLiveFolderWhenReady]) are the
     * reviewed create+open half; the "without ever letting go" continuous drag-OUT is the measured
     * next step per live-create-enter-handoff-design.md, not yet wired. Still togglable from the test
     * channel via [setLiveCreateEnabled].
     */
    private var liveCreateEnabled = true

    @JvmStatic
    fun setLiveCreateEnabled(on: Boolean) {
        liveCreateEnabled = on
        Log.i(TAG, "live-create gate ${if (on) "ENABLED" else "disabled"}")
    }

    @JvmStatic
    fun isLiveCreateEnabled(): Boolean = liveCreateEnabled

    /**
     * True between a live-create dwell arming and the folder being formed: the window in which the
     * seam has fired a synthetic UP to end the in-grid drag and is waiting for `clearView ->
     * commitDrop` to build the folder. The reorder Callback returns a 0-length settle while this is
     * set so the end is instant, and `commitDrop` routes to `createLiveFolder` rather than the
     * ordinary create-on-release.
     */
    private var liveArming = false

    /** See [liveArming]. Read by [AresHomeReorder.Callback.getAnimationDuration]. */
    @JvmStatic
    fun isLiveArming(): Boolean = liveArming

    /**
     * Which pipeline is feeding this drag: the in-grid `ItemTouchHelper` reorder, or a
     * `DragController` drag from the app list, the widget picker or another folder.
     *
     * Only one thing branches on it — whether a dwell over a folder **opens** it — and the reason
     * is measured rather than tidy. See [arm].
     */
    private var fromGrid = true

    private val dwellElapsed = Runnable { arm() }

    private val previewExitElapsed = Runnable { closePreview() }

    /**
     * Time-based failsafe for the [liveArming] latch (adversarial review 2026-08-23, Mo2).
     *
     * [arm] sets `liveArming` and fires a synthetic UP, trusting `clearView -> commitDrop` (or a
     * non-UP `clearTarget`) to clear it. Both existing resets are event-driven: they only run if a
     * *further* drag event arrives. If the synthetic UP is dropped and no event follows, the latch
     * stays set forever -- pinning `getAnimationDuration` to 0 for every later grid drop and muting
     * the pane-swipe via [isLiveArming]. This bounds that window: if `liveArming` is still set
     * [LIVE_ARM_FAILSAFE_MS] after arming, force it down. commitDrop/clearTarget cancel this the
     * instant they clear the latch the normal way, so it never fires on a healthy create.
     */
    private val liveArmFailsafe = Runnable {
        if (liveArming) {
            liveArming = false
            Log.w(TAG, "live-create: liveArming failsafe fired; latch was never cleared by a drop")
        }
    }

    /**
     * Reports where the drag currently is, in [list]'s own coordinate space.
     *
     * Idempotent and cheap: it is called on every frame of an in-grid drag and every move event of
     * an external one. It only restarts the timer when the answer actually changes, which is what
     * makes "held still" mean what it says.
     */
    @JvmStatic
    @JvmOverloads
    fun onDragPoint(
        list: AresHomeListView,
        item: ItemInfo?,
        x: Float,
        y: Float,
        fromGrid: Boolean = true,
    ) {
        if (item == null) {
            clear()
            return
        }
        if (grid !== list || dragged !== item) {
            clear()
            grid = list
            dragged = item
        }
        this.fromGrid = fromGrid

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
            if (candidateInfo != null) {
                Log.d(TAG, "dwell lost at (${x.toInt()},${y.toInt()}): view=${view != null} info=${info?.id} kind=$kind")
            }
            clearTarget()
            return
        }
        candidateKind = kind

        // By ID, not instance. A model write landing mid-hold (the folder-exit handoff persists
        // at crossing time; any package event does it too) rebinds the grid and every ItemInfo is
        // a fresh object -- an `!==` check then read the SAME tile as a new target and restarted
        // the dwell, so a hold across any rebind could never complete. Same id = same tile; the
        // instances are refreshed below so the eventual commit acts on the live objects.
        if (info.id != candidateInfo?.id) {
            // NOTE candidateKind is re-asserted below, after clearTarget(). It is set here too
            // because the same-tile path falls through to the slop check and needs it.
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
            // AFTER clearTarget, which resets it to NONE. Without this the kind stayed NONE for
            // the whole frame a target changed on -- so the reflow was not frozen over a folder
            // the drag had just entered, and an arm landing in that frame would have raised the
            // ring instead of opening the folder.
            candidateKind = kind
            restart(list, x, y)
            return
        }
        // Same tile: refresh the instances (a mid-hold rebind swaps them; see the id check above)
        // so the commit resolves against the live view and item.
        candidate = view
        candidateInfo = info
        // Same tile. Only a real move restarts the count -- see AresGeometry.dwellSlopPx.
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
        val drift = hypot(x - anchorX, y - anchorY)
        if (drift > reframeJumpPx(list)) {
            // A REFRAME, not a move. The in-grid feed reports the dragged VIEW's position, and a
            // mid-hold rank change re-homes that view's layout frame -- the reported point then
            // teleports (measured: 180px in one frame) while the finger, and the visual locked to
            // it, never moved. A finger cannot teleport, so a same-tile jump past this bound is
            // the frame changing under the point: the anchor rebases and the timer KEEPS COUNTING.
            // Without this, any relayout during a hold restarted the dwell forever, and a dwell
            // over a tile that reflow touches could never complete.
            Log.d(TAG, "dwell rebase: drift=${drift.toInt()} at (${x.toInt()},${y.toInt()})")
            anchorX = x
            anchorY = y
        } else if (drift > dwellSlopPx(list)) {
            Log.d(TAG, "dwell slip: drift=${drift.toInt()} at (${x.toInt()},${y.toInt()})")
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

    /**
     * True while ANY dwell candidate is held — armed or still counting. The edge auto-scroll
     * consults this: a grid that scrolls under a finger deliberately holding still over a tile
     * moves the list-space anchor past the dwell slop (`AresGeometry.dwellSlopPx`) every frame, so the timer restarts forever
     * and a dwell near the screen edge can never complete (measured: list-local y walked 232 →
     * 52 across one still hold while the target stayed the same tile). Holding still over a
     * target is the user saying "this one" — scrolling them away from it is never what they meant.
     */
    @JvmStatic
    fun hasCandidate(): Boolean = candidateInfo != null

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
     *
     * ## Why only an IN-GRID drag opens the folder
     *
     * §17's rule is that one interaction has one implementation, and this is a deliberate,
     * measured exception rather than drift. A `DragController` drag runs the launcher in
     * `SPRING_LOADED`; opening a folder on top of that and then **closing it again** — which
     * reversibility requires — leaves the workspace behind it invisible. Measured on
     * emulator-5554 with an app-list drag: after the preview closed, the page outline was still
     * drawn, every tile was still in the view tree at its right bounds, and not one of them was
     * visible; `Workspace.onDragOver` stopped arriving, so the dwell could not even re-arm. The
     * launcher recovered the moment the finger lifted, so it is transient — and it is transient
     * for exactly the span in which the user is trying to place something.
     *
     * The cause is on the shared surface, not in this file: Lawnchair's folder close restores the
     * workspace scale and scrim to their *resting* values (`restoreLauncherAfterFolderDismissed`),
     * which is right in `NORMAL` and wrong under `SPRING_LOADED`. Making that state-aware is a
     * change to the folder open/close animation for every surface that uses it — a much wider
     * blast radius than this feature earns, and precisely the kind of shared-surface edit
     * design/change-practices.md says not to make without sweeping its consumers first.
     *
     * So an app-list drag keeps the behaviour it already shipped with: the dwell arms, the tile
     * highlights, and a release files the icon into the folder at the end ([addToFolder]). What it
     * does not get is the choice of position. That gap is deliberate and recorded rather than
     * quietly papered over.
     */
    private fun arm() {
        val list = grid ?: return
        val view = candidate ?: return

        // NOT YET is not the same as NEVER.
        //
        // `aresBeginPreviewDrag` refuses while a close animation still owns the folder, which is a
        // window B2 walks straight into: dragging an icon back out closes the folder, and dwelling
        // again a moment later is the specified way to reopen it. Treating that refusal like a
        // permanent one falls through to the highlight ring below, so the user sees a ring where
        // they asked for an open folder -- and a release then files the icon into a folder they
        // were never shown, which is B1 unmet and a commit they did not ask for.
        //
        // So: leave it UNARMED and come back. The close set runs ~200ms
        // (config_materialFolderExpandDuration), so a short retry reopens the folder about when the
        // animation ends rather than making the user lift and dwell again.
        if (candidateKind == Kind.ADD && fromGrid) {
            val animating = folderIconOf(view)?.folder?.aresIsAnimating() == true
            if (animating) {
                list.removeCallbacks(dwellElapsed)
                list.postDelayed(dwellElapsed, ANIMATING_RETRY_MS)
                Log.i(TAG, "dwell elapsed on ${candidateInfo?.id} mid-animation; retrying")
                return
            }
        }

        armed = true
        if (candidateKind == Kind.ADD && fromGrid) {
            val icon = folderIconOf(view)
            // AresFolderFlow: the zombie's state at the moment the user dwells to put an app back.
            // itemCount==1 here is the degenerate 1-item folder pending dissolution.
            val f = icon?.folder
            Log.i(
                "AresFolderFlow",
                "dwell ADD target folder=${candidateInfo?.id} itemCount=${f?.itemCount} " +
                    "destroyed=${f?.isDestroyed} open=${f?.isOpen} animating=${f?.aresIsAnimating()}",
            )
            // WP folders (design/wp-folder-design.md, MJ-2) NEVER open the AresFolderPreview overlay.
            // A dwell-add over a collapsed WP folder tile falls through to the highlight-ring path
            // below (the same path an app-list drag already uses), so a release files the app into
            // the folder via addToFolder -- inline, no overlay. The add MECHANISM (addToFolder ->
            // Folder.addFolderContent) is identical to the verified overlay-folder add; only the
            // placement-choice overlay is skipped. Overlay folders keep the preview.
            val isWpFolder = (candidateInfo as? FolderInfo)?.isAresWpFolder == true
            if (icon != null && !isWpFolder && AresFolderPreview.open(list.launcher, list, icon)) {
                list.setFolderDropTarget(null)
                Log.i(TAG, "dwell elapsed on ${candidateInfo?.id}; folder opened for placement")
                return
            }
        }
        // §25 live-create: dwelling an icon on an icon forms the real folder mid-hold and opens it,
        // rather than drawing a ring and creating on release. Gated OFF by default.
        //
        // The seam ENDS the in-grid drag cleanly FIRST, then forms the folder from the ended drag --
        // it does NOT remove the dragged row from under a still-active ItemTouchHelper drag. A
        // synthetic UP (with the Callback's settle forced to 0 while `liveArming`, see
        // getAnimationDuration) ends ITH, so `clearView -> commitDrop` runs `createLiveFolder` on a
        // fully-ended drag; the per-frame dwell feed (`onChildDraw`) stops the instant the drag
        // leaves the active state, so nothing re-arms behind the open folder. Corrections that drove
        // this (adversarial review 2026-08-21) are in live-create-enter-handoff-design.md.
        if (liveCreateEnabled && candidateKind == Kind.CREATE && fromGrid &&
            candidateInfo != null && dragged != null
        ) {
            liveArming = true
            list.removeCallbacks(liveArmFailsafe)
            list.postDelayed(liveArmFailsafe, LIVE_ARM_FAILSAFE_MS)
            val now = android.os.SystemClock.uptimeMillis()
            list.dispatchSyntheticHandoffEvent(
                android.view.MotionEvent.ACTION_UP, now, now, anchorX, anchorY,
            )
            Log.i(TAG, "live-create arming: ended the in-grid drag to form a folder on ${candidateInfo?.id}")
            return
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
        // Failsafe reset for the live-create latch (adversarial re-review 2026-08-21, N1). commitDrop
        // clears it on the normal UP path, but if the armed in-grid drag ends via a NON-UP clearView
        // (a system CANCEL racing the synthetic UP, or dragGestureEnd already latched non-UP),
        // commitDrop never runs. A stuck flag forces getAnimationDuration=0 on EVERY later grid drop
        // (all reorders lose their settle) and commitDrop's liveArming branch does not re-check the
        // gate -- so it would hijack the next drop even with live-create OFF. Resetting here, the
        // fundamental teardown that every non-UP end routes through, makes it invariant-by-construction.
        liveArming = false
        grid?.removeCallbacks(liveArmFailsafe)
    }

    private fun clear() {
        cancelPreviewExit()
        // Abandons an open preview without committing, which is the correct reading of every path
        // that reaches here: a CANCEL, a new drag, or the end of one. The user's rule is that only
        // a manual release adds an item to a folder.
        AresFolderPreview.close()
        clearTarget() // also resets the live-create latch (see clearTarget, N1)
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
        // WP folders: an inline-expanded folder CHILD is spliced into the grid as an ordinary tile
        // but its container is the folder, not the desktop. It must NEVER be a dwell target --
        // dwelling a dragged desktop app onto a spliced child would otherwise resolve to CREATE and
        // build a stray OVERLAY folder out of that child, yanking it out of its WP folder into a
        // wrong container (adversarial review 2026-08-23, finding 1: the reachable half of the
        // deferred MJ-1/MJ-4 hazard). Only real desktop items are foldable targets.
        if (target.container != Favorites.CONTAINER_DESKTOP) return Kind.NONE
        // Symmetric SOURCE guard (adversarial review 2026-08-24, finding 4): a dragged item that is
        // itself an inline-expanded folder CHILD (container is the folder, not the desktop) must
        // never arm a dwell either. A child being EXTRACTED and paused over a desktop app would
        // otherwise resolve to CREATE and flash the overlay create ring/preview mid-extract -- the
        // very overlay WP folders must never raise (MJ-2). AresHomeReorder owns the extract drag;
        // the dwell has no business in it.
        if (source.container != Favorites.CONTAINER_DESKTOP) return Kind.NONE
        if (!Folder.willAccept(source)) return Kind.NONE
        if (target is FolderInfo) {
            return if (FolderInfo.willAcceptItemType(source.itemType)) Kind.ADD else Kind.NONE
        }
        return if (Folder.willAccept(target)) Kind.CREATE else Kind.NONE
    }

    /**
     * Whether a dwell over [target] while holding [source] could ever arm — the eligibility half
     * of [kindOf], for callers that must not displace a tile the user may be aiming at (row 32:
     * the external drop-slot mover). Deliberately NOT the freeze: candidacy alone must keep the
     * slot from shoving the aim aside, but only an actual arm may stop the slot moving elsewhere.
     */
    @JvmStatic
    fun couldAcceptDwell(target: ItemInfo, source: ItemInfo): Boolean =
        kindOf(target, source) != Kind.NONE

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
        // §25 live-create takes priority over every ordinary drop resolution below: the synthetic UP
        // that ended the in-grid drag lands here, and [item] is the app that was being dragged. Form
        // the real folder from the ended drag ([createLiveFolder] opens it + attaches the edit
        // session so it can be dragged back apart); if it declines, fall back to the ordinary
        // create-on-release so the pair still becomes a folder. Either way the drop is consumed.
        if (liveArming) {
            liveArming = false
            grid?.removeCallbacks(liveArmFailsafe)
            val list = grid
            val target = candidateInfo
            val done = try {
                if (list != null && target != null && candidateKind == Kind.CREATE) {
                    if (createLiveFolder(launcher, list, target, item) != null) {
                        true
                    } else {
                        createFolder(launcher, list, target, item)
                    }
                } else {
                    false
                }
            } catch (t: Throwable) {
                // A double-fault -- createLiveFolder AND the createFolder fallback both throwing at
                // the same model write -- must not propagate out of clearView and skip the clear()
                // below, which would leak the dwell state (N3, adversarial re-review 2026-08-21).
                // Consume it; the pair simply does not fold.
                Log.e(TAG, "live-create: both commit paths failed on ${target?.id}", t)
                false
            }
            clear()
            return done
        }
        // An open folder resolves against the slot the user positioned, not against the tile the
        // dwell originally locked onto -- the folder covers the grid by then, so there is no
        // meaningful tile any more.
        if (AresFolderPreview.isOpen()) {
            // UNLESS the finger is currently OUTSIDE it (S4, spec B2/B3). Leaving the folder posts
            // a 400ms grace before it closes, so the interaction stays reversible -- but the grace
            // is about whether the FOLDER stays open, not about where a release lands. A release
            // during that window used to take this branch and file the item into a folder the
            // finger had already left: `AresFolderPreview.commit` does not hit-test, it files at
            // the preview rank unconditionally. Measured on the pre-fix build -- "dropped item 710
            // into folder 40 at rank 3" on a release over a WIDGET two tiles away.
            //
            // The in-grid pipeline partially masks this by accident: commitDrop runs from
            // clearView, which fires only after the settle animation (~250ms), so only a fast
            // release lands inside the grace. The external pipeline commits synchronously at the
            // drop and had the full 400ms exposed.
            //
            // `previewExiting` is exactly "the drag's last known point was outside the open
            // folder", maintained per move by onDragPoint. Outside means the release belongs to
            // the grid: close the preview and decline, and the ordinary placement path takes it.
            if (previewExiting) {
                // Cancel BEFORE closing: closePreview() drops the previewExiting flag, and
                // cancelPreviewExit() early-returns on a clear flag -- run in the other order the
                // posted 400ms `previewExitElapsed` survives this branch and fires into the NEXT
                // drag's state, the S1 class reintroduced (adversarial review, 2026-08-21).
                cancelPreviewExit()
                closePreview()
                clear()
                declinedExiting++
                return false
            }
            cancelPreviewExit()
            val done = AresFolderPreview.commit(launcher, item)
            clear()
            return done
        }
        // clear(), not a bare return. Every other exit from this function clears; this one did not,
        // and it is the one the COMMON case takes -- a drop that never armed a dwell.
        //
        // clear() -> clearTarget() is what removes the pending `dwellElapsed` callback. Left
        // posted, it fires up to DWELL_MS after the drag is over, with `grid` and `candidate` still
        // populated and no in-progress drag to guard against: arm() then raises a drop ring around
        // a tile nobody is dragging onto (nothing takes it down again, setFolderDropTarget(null)
        // having already run), or opens a folder by itself with a phantom empty slot in it. It
        // self-heals on the next drag, so it presents as three unrelated intermittent reports
        // rather than one bug.
        //
        // Everyone decelerates before releasing, so a move within the last DWELL_MS is the norm.
        if (!armed) {
            clear()
            return false
        }
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
        // Never commit into a DESTROYED folder (§25 dissolve-vs-drag-out race, traced on the Pixel
        // 2026-08-23). If the target dissolved mid-drag, filing the item is refused by
        // Folder.addFolderContent -- but this method would still post adapter.removeItems for the
        // item's grid tile and return true, so the item loses its tile without ever entering a
        // folder: the app "disappears" from the home screen. Decline the whole commit; the item
        // stays on the grid where AresFolderExitHandoff already placed it.
        if (folder.isDestroyed) {
            Log.w(TAG, "addToFolder declined: folder ${folderInfo.id} is destroyed; " +
                "item ${item.id} stays on the grid")
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
            // If this folder is inline-OPEN, splice the new member into its run so it renders now,
            // not only after a reload (owner bug 2026-08-24). No-op when the folder is collapsed.
            list.aresAdapter.addChildToExpandedRun(folderInfo, item)
            // As in createFolder: this lands after the drag, so spring the tiles closing the gap.
            list.animateNextRelayout()
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
        android.util.Log.i(
            "AresFolderFlow",
            "createFolder-attempt target=${targetInfo.id} container=${targetInfo.container} " +
                "dragged=${item.id} container=${item.container}",
        )
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
        stampAresWp(launcher, folderInfo)
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
            // Spring the tiles that shift to close the two vacated slots, rather than snapping
            // them: this post runs after the drag ended, so the drag-time reflow is already off.
            list.animateNextRelayout()
            AresHomeReorder.persistOrder(launcher, adapter.snapshot())
            // The visible answer to "what just happened". Dropping into an EXISTING folder already
            // animates -- addFolderContent refreshes the icon's preview with animate = true -- and
            // creation had nothing at all, so the two halves of one dwell read as different events:
            // "when holding an app over another app to generate a folder, there is no folder
            // creation animation to indicate folder creation".
            list.playFolderCreated(folderInfo)
        }
        return true
    }

    /**
     * §25 live-create core: build a REAL 2-item folder {[target], [dragged]} mid-drag and OPEN it,
     * so both apps are visible in the folder without a release.
     *
     * Two hard facts from the spikes shape this:
     *  - A 1-item folder is destroyed by the platform within ~150ms of being rendered (child
     *    promoted to the desktop, folder row deleted). So BOTH items are filed **atomically** here
     *    -- there is never a 1-item frame to catch. (Drag-out later removes one, and that same
     *    cleanup is what dissolves the folder -- see §25.)
     *  - `animateOpen` reads the **rendered** folder tile's geometry; the transient [FolderIcon]
     *    from [FolderIcon.inflateFolderAndIcon] (used only to file content into the model) is not
     *    laid out and yields NaN. So the open goes through the adapter's rendered tile, after a
     *    forced layout pass, exactly as the working spike did -- and via [Folder.animateOpen], NOT
     *    [AresFolderPreview], whose preview open reserves an extra empty slot for an incoming item
     *    this already-2-item folder does not have.
     *
     * Returns the created [FolderInfo], or null if no folder could be placed. The open is posted
     * (the row needs its layout pass first). Reuses [createFolder]'s insertion verbatim in spirit;
     * TODO(§17): once owner-verified, lift the shared create+insert core out of [createFolder] so
     * release-create and hold-create cannot drift.
     */
    fun createLiveFolder(
        launcher: Launcher,
        list: AresHomeListView,
        target: ItemInfo,
        dragged: ItemInfo,
    ): FolderInfo? {
        android.util.Log.i(
            "AresFolderFlow",
            "createLiveFolder-attempt target=${target.id} container=${target.container} " +
                "dragged=${dragged.id} container=${dragged.container}",
        )
        val cell = IntArray(2)
        val screenId = AresWidgetAdd.findFreeCell(launcher, 1, 1, cell, target.id)
        if (screenId == AresWidgetAdd.NO_SCREEN) {
            Log.e(TAG, "live-create declined: no free cell")
            return null
        }
        val folderInfo = FolderInfo()
        stampAresWp(launcher, folderInfo)
        folderInfo.rank = target.rank
        launcher.modelWriter.addItemToDatabase(
            folderInfo, Favorites.CONTAINER_DESKTOP, screenId, cell[0], cell[1],
        )
        if (folderInfo.id == ItemInfo.NO_ID) {
            Log.e(TAG, "live-create declined: folder row got no id")
            return null
        }
        // File BOTH items (target leading, as stock does) in one synchronous stack, so the row is a
        // legal 2-item folder the instant it renders. On ANY failure after the DB row exists, delete
        // the row: an orphaned 0-item folder on CONTAINER_DESKTOP is NOT reclaimed by
        // deleteUnparentedApps and would render as an empty tile (adversarial review 2026-08-21).
        // `filer` is a transient Folder used only to run these model writes; TODO(§17) fold its
        // disposal into the shared create+insert core lift (a minor per-folder listener leak).
        try {
            val filer = FolderIcon
                .inflateFolderAndIcon(R.layout.folder_icon, launcher, list, folderInfo)
                .folder ?: throw IllegalStateException("inflated without a Folder")
            filer.addFolderContent(target, 0, false)
            filer.addFolderContent(dragged, 1, false)
        } catch (t: Throwable) {
            Log.e(TAG, "live-create: filing ${folderInfo.id} failed; deleting the orphan row", t)
            launcher.modelWriter.deleteItemFromDatabase(folderInfo, "ares-live-create-failed")
            return null
        }
        Log.i(TAG, "live-create: folder ${folderInfo.id} from ${target.id}+${dragged.id}")

        list.post {
            val adapter = list.aresAdapter
            val targetAt = adapter.indexOf(target).let { if (it >= 0) it else adapter.itemCount }
            val sourceAt = adapter.indexOf(dragged)
            var at = targetAt
            if (sourceAt in 0 until at) at--
            adapter.removeItems { it.id == target.id || it.id == dragged.id }
            adapter.addItemAt(folderInfo, at)
            list.animateNextRelayout()
            AresHomeReorder.persistOrder(launcher, adapter.snapshot())
            // Open once the tile has bound + laid out. A first attempt after ~150ms (the
            // preview-background radius animateOpen reads is computed on a draw pass, so a same-frame
            // layout gives scaleX=NaN), then a bounded per-frame retry if the tile is not rendered
            // yet -- NOT a bare fixed delay, whose silent miss trapped the apps in a never-opened
            // folder (adversarial review 2026-08-21).
            list.postDelayed({ openLiveFolderWhenReady(launcher, list, folderInfo.id, attemptsLeft = 6) }, 150)
        }
        return folderInfo
    }

    /**
     * Opens the just-created live folder once its grid tile is bound + laid out, then attaches the
     * edit session.
     *
     * The attach is the load-bearing line: `AresFolderEdit.attach` is what installs
     * [AresFolderDrag.DragStarter] on the folder's app tiles, and DragStarter is the ONLY thing that
     * calls `folder.startDrag`. Without it a folder opened by bare [Folder.animateOpen] has inert
     * apps: nothing can be dragged out, `isFolderDrag` never becomes true, [AresFolderExitHandoff]
     * never engages, and the folder never dissolves — §25's drag-out is unreachable (adversarial
     * review 2026-08-21). A live-create dwell is always inside an in-grid drag, i.e. edit mode, so
     * attaching is correct here exactly as [AresFolderPreview.open] does it.
     *
     * If the tile never renders within budget, or the open throws, the folder is left CLOSED — a
     * normal, tappable 2-item folder — rather than trapping the apps.
     */
    private fun openLiveFolderWhenReady(
        launcher: Launcher,
        list: AresHomeListView,
        folderId: Int,
        attemptsLeft: Int,
    ) {
        val gridIcon = folderIconForId(list, folderId)
        val folder = gridIcon?.folder
        if (gridIcon == null || folder == null || !gridIcon.isLaidOut || gridIcon.width == 0) {
            if (attemptsLeft <= 0) {
                Log.w(TAG, "live-create: folder $folderId tile never rendered; left closed")
                return
            }
            list.postOnAnimation { openLiveFolderWhenReady(launcher, list, folderId, attemptsLeft - 1) }
            return
        }
        if (folder.isDestroyed) return
        // WP folders (every folder AresLauncher creates now) NEVER open the overlay -- they
        // inline-expand on the home grid (owner 2026-08-25). The tile is laid out (checked above),
        // which is what the expand's teardrop/bloom geometry needs. This replaces the overlay
        // `animateOpen()` + `AresFolderEdit.attach` path, which stays only as the fallback for any
        // hypothetical non-WP folder reaching here.
        val info = folder.info
        if (info.isAresWpFolder) {
            list.aresAdapter.toggleWpFolder(info)
            Log.i(TAG, "live-create: WP folder $folderId inline-expanded contents=${info.getContents().size}")
            return
        }
        try {
            folder.animateOpen()
        } catch (t: Throwable) {
            // Do NOT retry animateOpen after a throw: a partial open (mIsOpen set, added to the
            // DragLayer, then the animator math threw) would be re-entered and double-open (N4,
            // adversarial re-review 2026-08-21). The +150ms first attempt is past the geometry-settle
            // point in practice; on the rare throw leave a normal CLOSED, tappable 2-item folder.
            Log.w(TAG, "live-create: folder $folderId could not open (${t.message}); left closed")
            return
        }
        if (list.isEditMode()) AresFolderEdit.attach(launcher, gridIcon)
        Log.i(
            TAG,
            "live-create: opened folder $folderId contents=${folder.info.getContents().size} " +
                "editAttached=${list.isEditMode()}",
        )
    }

    /** The rendered [FolderIcon] for [id] in the grid, or null if no such tile is bound. */
    private fun folderIconForId(list: AresHomeListView, id: Int): FolderIcon? {
        val holder = list.findViewHolderForItemId(id.toLong()) ?: return null
        return (holder.itemView as? android.view.ViewGroup)?.getChildAt(0) as? FolderIcon
    }

    /**
     * §25 create+open verification harness: drives [createLiveFolder] gesture-free from the test
     * channel with the first two folderable seeds, so the create+open half is verifiable without a
     * real drag (the drag-continuation half is owner-Pixel only). Leaves the folder open on success.
     * Named `spike*` for the channel it is wired to; kept as the create+open regression check.
     */
    @JvmStatic
    fun spikeOneItemFolder(launcher: Launcher, list: AresHomeListView): String = try {
        // container == DESKTOP: with a WP folder expanded, snapshot() also holds its spliced
        // children (container = folder id); seeding a folder-create from one would double-parent it
        // (adversarial review 2026-08-23, finding 2 / MJ-4). Only loose desktop items are seeds.
        val seeds = list.aresAdapter.snapshot()
            .filter { it !is FolderInfo && it.container == Favorites.CONTAINER_DESKTOP && Folder.willAccept(it) }
            .take(2)
        if (seeds.size < 2) {
            "need 2 folderable seeds, found ${seeds.size}"
        } else {
            val fi = createLiveFolder(launcher, list, seeds[0], seeds[1])
            if (fi == null) {
                "createLiveFolder declined"
            } else {
                "live folder ${fi.id} created from ${seeds[0].id}+${seeds[1].id}; open POSTED -- see logcat live-create"
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "spikeOneItemFolder threw", t)
        "EXCEPTION ${t.javaClass.simpleName}: ${t.message}"
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
        // A handed-off drag (rows 31/32) is an in-grid drag now; its dwell is fed by the in-grid
        // pipeline like any other, and feeding it a second time from here would double-drive it.
        if (AresFolderExitHandoff.isActive()) return
        val list = launcher.workspace?.aresHomeList ?: return
        val local = toListSpace(launcher, list, d.x.toFloat(), d.y.toFloat())
        onDragPoint(list, d.dragInfo, local[0], local[1], fromGrid = false)
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
