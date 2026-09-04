package app.lawnchair.areslauncher

import com.android.launcher3.DropTarget
import com.android.launcher3.Launcher
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragOptions

/**
 * Records every `DragController` drag and the DragLayer's state at the moment each one ends.
 *
 * ## Why a listener and not a sampler
 *
 * [AresTestInfo.REQUEST_DRAG_STATE] answers "is there a stranded `DragView` right now", which is the
 * right question for a ghost that PERSISTS. It is the wrong instrument for "did a DragController
 * drag happen at all", and ledger row 84 needs both: a `dragViews=0` at rest is only meaningful once
 * a non-zero has been seen, and the obvious way to produce one turned out not to exist.
 *
 * Measured 2026-09-04: polling the channel across a full green `AresHomeReorderTest` (4 tests, ~48s)
 * returned `dragging=false` on **all 45 samples**. Not a near miss — a home-grid reorder is an
 * `ItemTouchHelper` drag and never touches the `DragController`, so no sample rate would have caught
 * one. A `content call` round trip is ~1s anyway, against drags that last a fraction of that.
 *
 * So stop sampling. A `DragListener` is woken BY the event, cannot miss one, and costs two counter
 * writes per drag. The interesting reading is taken in [onDragEnd], which is the exact moment the
 * row-84 hypothesis is about.
 *
 * ## What it is for, precisely
 *
 * Row 84's narrowed hypothesis is that an ACCEPTED drop whose target never starts a `DragLayer` drop
 * animation leaves `onDeferredEndDrag` unrun — which strands the `DragView` **and** skips
 * `callOnDragEnd`. If that is what happens, [onDragEnd] here never fires either, and the signature is
 * `starts` climbing while `ends` does not. That asymmetry is the finding; a stranded view seen at
 * rest with `ends == starts` would mean something else entirely.
 *
 * Read-only. It registers, counts, and answers questions; it changes no drag behaviour, and nothing
 * in the product reads its state.
 */
object AresDragWatch : DragController.DragListener {

    /** Drags the controller actually started, since process start. */
    @Volatile private var starts = 0

    /** Drags that reached `callOnDragEnd`. Lags [starts] by one only while a drag is in flight. */
    @Volatile private var ends = 0

    /** DragLayer `DragView` children counted at the last [onDragEnd]; -1 until one has happened. */
    @Volatile private var viewsAtLastEnd = -1

    /** [AresFolderExitHandoff.isActive] at the last [onDragEnd] — see the class doc. */
    @Volatile private var handoffAtLastEnd = false

    /** The launcher this is registered on, so a re-register cannot double-count. */
    private var registeredOn: Launcher? = null

    /**
     * Idempotent. Registering twice would double every count, and the launcher is recreated on a
     * theme switch and a fold — both of which run this again with a fresh `DragController`.
     */
    @JvmStatic
    fun register(launcher: Launcher) {
        if (registeredOn === launcher) return
        registeredOn?.dragController?.removeDragListener(this)
        launcher.dragController?.addDragListener(this)
        registeredOn = launcher
    }

    /**
     * Drops the registration when [launcher]'s activity goes away.
     *
     * Holding a dead `Launcher` here is the S5 shape this seam has already been burned by twice, and
     * an object that outlives the activity is exactly where it hides.
     */
    @JvmStatic
    fun onLauncherDestroyed(launcher: Launcher) {
        if (registeredOn !== launcher) return
        launcher.dragController?.removeDragListener(this)
        registeredOn = null
    }

    override fun onDragStart(dragObject: DropTarget.DragObject?, options: DragOptions?) {
        starts++
    }

    override fun onDragEnd() {
        ends++
        val layer = registeredOn?.dragLayer
        viewsAtLastEnd = if (layer == null) {
            -1
        } else {
            (0 until layer.childCount).count {
                layer.getChildAt(it) is com.android.launcher3.dragndrop.DragView<*>
            }
        }
        handoffAtLastEnd = AresFolderExitHandoff.isActive()
    }

    /** `starts=<n>|ends=<n>|viewsAtLastEnd=<n>|handoffAtLastEnd=<bool>|registered=<bool>` */
    @JvmStatic
    fun summary(): String =
        "starts=$starts|ends=$ends|viewsAtLastEnd=$viewsAtLastEnd|" +
            "handoffAtLastEnd=$handoffAtLastEnd|registered=${registeredOn != null}"
}
