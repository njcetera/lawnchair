package app.lawnchair.areslauncher

import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.View
import com.android.launcher3.Reorderable
import com.android.launcher3.util.MultiTranslateDelegate.INDEX_REORDER_BOUNCE_OFFSET
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp

/**
 * **Every tunable that decides how edit mode feels, and the one writer of a tile's translation.**
 *
 * Two things live here because they are the same problem. Edit mode now has several things that
 * want to move a tile — the float's orbit ([AresEditWiggle]), the reflow that carries a displaced
 * tile to its new cell, plus `ItemTouchHelper` on the tile in the user's hand and the layout
 * manager's repack animation. Views expose exactly one `translationX`, so anything that writes it
 * directly is in a fight it cannot see. This composes the two *continuous* contributors into one
 * write, and the two *exclusive* ones are documented below as taking the view over outright.
 *
 * ## Writer precedence on `translationX/Y`, stated once
 *
 * | writer | when | how it composes |
 * |---|---|---|
 * | orbit ([AresEditWiggle]) | whole of edit mode, per tile | summed here, via [setOrbit] |
 * | reflow (this file) | during a drag, on **displaced** tiles | summed here, via [displaceTo] |
 * | `ItemTouchHelper` | the dragged tile only, for the drag's life | **exclusive** — the host suspends the float and the layout manager exempts the tile ([AresHomeListView.setFloatSuspendedFor]), so nothing here writes to it at all |
 * | [AresMasonryLayoutManager.animateFromPreviousBounds] | a resize or a removal, 200ms | **contended, not exclusive** — see the note directly below. It cannot overlap a *drag*: its two triggers are affordance taps, and a gesture that starts on an affordance can never become a drag |
 *
 * **The repack animation is not an exclusive owner, and this table used to say it was.** It calls
 * [clearReflow], but that zeroes only `dx/dy/vx/vy` — **the orbit survives**, and
 * [AresEditWiggle]'s update listener keeps calling [setOrbit] → `apply` → `write` on every frame
 * for the whole of edit mode. Meanwhile `AresMasonryLayoutManager` writes `translationX/Y`
 * directly and starts a `ViewPropertyAnimator` on the same property. Both of that animation's
 * triggers are edit-mode-only, so the float is *guaranteed* to be running when it starts: the two
 * are always contending, never taking turns.
 *
 * In practice the `ViewPropertyAnimator` registers later in `AnimationHandler`'s callback list and
 * wins the frame, so the visible cost is a ~200ms pause in the float rather than a fight. That is
 * an observed ordering, not a guarantee, and no ordering in which the wiggle wins has been
 * demonstrated — which is why this is a corrected comment rather than a code change. If §9D's
 * repack or §11C's resize is ever seen not to play, start here: the fix is for
 * `animateFromPreviousBounds` to suspend the float for that tile the way a drag does
 * ([AresHomeListView.setFloatSuspendedFor]), which is what "exclusive" would actually require.
 * | lift ([AresEditLabel]) | whole of edit mode, per **item view** | summed here, via [setLift] — and it is the one row of this table that writes the item view rather than the holder container, so it shares a view with none of the others |
 *
 * The scale is a separate property with a separate, simpler story: the edit-mode 0.92, the
 * pick-up bump ([PICKUP_SCALE_FACTOR]) and the repack's size animation all multiply through
 * `AresMasonryLayoutManager.restScale`, which is the single source for "what size is this tile
 * resting at".
 *
 * ## Why the reflow is a spring and not another `ViewPropertyAnimator`
 *
 * The obvious implementation — call `animateNextLayout()` on every packing change during a drag —
 * was measured and is wrong. It captures previous bounds and starts a fresh 200ms
 * `ViewPropertyAnimator` **per child**; during a drag the packing changes many times a second, so
 * those stack and fight, and each restart discards the velocity the last one had built. Restarting
 * is precisely what produces the snap it is meant to remove.
 *
 * A spring has a *target* rather than a duration, so it retargets mid-flight for free: when the
 * packer moves a tile again, the layout box moves under it, its displacement is recomputed and its
 * velocity carries straight through. That is the whole reason for the hand-rolled integrator —
 * `SpringAnimation` cannot have its current value changed once running, which is the exact
 * operation a retarget is.
 *
 * The spring is **critically damped**, so a tile never overshoots into the neighbour it is packing
 * against. Adding a little bounce would mean a damping ratio below 1 and the general
 * under-damped solution; it is deliberately not offered until someone asks for it.
 */
object AresEditMotion {

    // ------------------------------------------------------------------ the tunables

    /**
     * How much bigger a tile gets the moment it is picked up, **as a multiple of whatever it was
     * resting at**.
     *
     * Relative rather than absolute because the two surfaces rest at different sizes and have to
     * read as the same gesture: the home grid holds `EDIT_MODE_SCALE` = 0.92 while editing, and an
     * icon inside an open folder rests at 1.0. At 1.12 that lands on **1.03 absolute on the home
     * grid** (0.92 × 1.12) and **1.12 absolute inside a folder** — the same 12% pop in both places,
     * and on the grid it lifts the tile just past its true size, which is what makes "picked up"
     * read as above the surface rather than merely different.
     *
     * Applied on the home grid by [AresHomeListView.setPickedUp], and inside a folder by handing
     * the same factor to stock's own `DragView` zoom (`DragOptions.aresPickupScale`) rather than
     * adding a second animator to it.
     */
    const val PICKUP_SCALE_FACTOR = 1.12f

    /**
     * How long the pick-up bump takes.
     *
     * Close to stock's own `DragView.VIEW_ZOOM_DURATION` (150ms), so the grid and the folder — which
     * uses that animation — arrive at the same moment.
     */
    const val PICKUP_MS = 140L

    /**
     * Roughly how long a displaced tile takes to reach its new cell, in ms.
     *
     * This is a spring, not a duration: the number sets the stiffness and a tile that is displaced
     * again mid-flight simply keeps going. Shorter reads as crisper and closer to a snap; longer
     * reads as floatier and starts to lag the finger.
     */
    const val REFLOW_SETTLE_MS = 260L

    /**
     * How long a drag inside an open folder must sit over a new position before the folder
     * rearranges, in ms.
     *
     * Stock's `Folder.REORDER_DELAY` is **250ms**, and that — not a missing animation — is why
     * reordering inside a folder reads as a jump. `FolderPagedView.realTimeReorder` already
     * animates (230ms per icon, staggered 30ms), but `Folder.onDragOver` re-arms the alarm on
     * *every* change of target rank, so while the finger keeps moving the alarm never fires and
     * nothing moves at all. Hold still for a quarter of a second and the whole arrangement then
     * shuffles at once.
     *
     * Short enough to flow with the finger, long enough to still coalesce a fast sweep across
     * several cells into one rearrangement rather than one per cell.
     */
    const val FOLDER_REORDER_DELAY_MS = 80

    /**
     * Radius, in dp, of the disc at a tile's exact centre that always belongs to the **drag** and
     * never to an affordance.
     *
     * The × badge and the resize chevron are 48dp touch targets inset into opposite corners. On a
     * 1×1 home tile they meet near the middle and leave about 3dp of clearance at the centre; on an
     * icon inside an open folder the single badge's target reaches the centre outright. Aiming at
     * the middle of a small icon — which is what anyone does to pick something up — therefore grabs
     * a control instead, and in a folder it *removes the app*.
     *
     * Chosen against the geometry rather than by eye: the 28dp glyph drawn inside the 48dp target
     * reaches about 10.6dp from a 1×1 home tile's centre, so at 10dp this covers no drawn glyph
     * there. On the smaller folder cell it clips the outer corner of the × glyph; the glyph's own
     * centre stays ~19dp away and fully tappable. On a large widget nothing is near the centre and
     * this changes nothing. Bigger makes small tiles easier to pick up at the cost of that corner.
     *
     * §26 improved the folder case rather than disturbing it, which is worth stating because the
     * paragraph above reads as though it might not have. The zone is measured from the **cell's**
     * centre, and hiding the label slides the icon to exactly that point — so the disc that always
     * belongs to the drag now sits on the picture instead of near the × the badge's target used to
     * reach. Aiming at the middle of a folder icon is aiming at the icon.
     *
     * See [AresHomeListView.isInDragPriorityZone] and `AresFolderEdit.EditCell`.
     */
    const val DRAG_PRIORITY_RADIUS_DP = 10f

    /** True when [x],[y] — in [view]'s own coordinates — fall in [DRAG_PRIORITY_RADIUS_DP]. */
    fun isInDragPriorityZone(view: View, x: Float, y: Float): Boolean {
        val radius = DRAG_PRIORITY_RADIUS_DP * view.resources.displayMetrics.density
        val dx = x - view.width / 2f
        val dy = y - view.height / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    // ------------------------------------------------------------------ composition

    private class Motion {
        var orbitX = 0f
        var orbitY = 0f

        /** Current reflow displacement from the layout box, in px. Springs toward zero. */
        var dx = 0f
        var dy = 0f

        /** Reflow velocity, px/s. Preserved across a retarget — that is the point of a spring. */
        var vx = 0f
        var vy = 0f

        /**
         * An offset copied verbatim from another view each frame, with no motion of its own.
         *
         * One user: the × badge inside an open folder, which shadows an icon that stock slides via
         * its own animator. See [setFollow].
         */
        var followX = 0f
        var followY = 0f

        /**
         * A vertical offset that centres a labelless icon in its cell. Y only — nothing about
         * dropping a label moves a tile sideways. See [setLift].
         */
        var liftY = 0f

        val settled: Boolean
            get() = dx == 0f && dy == 0f && vx == 0f && vy == 0f
    }

    private val motions = WeakHashMap<View, Motion>()

    /** Reused per frame so the tick allocates nothing; the map is never mutated while iterating. */
    private val scratch = ArrayList<View>()

    private var running = false

    /**
     * Sets [view]'s orbit contribution — the edit-mode float's offset from rest.
     *
     * Called from [AresEditWiggle]'s frame callback instead of writing `translationX` there, so the
     * orbit and any in-flight reflow sum rather than erase one another.
     */
    fun setOrbit(view: View, x: Float, y: Float) {
        val m = motions.getOrPut(view) { Motion() }
        m.orbitX = x
        m.orbitY = y
        apply(view, m)
    }

    /**
     * Gives [view] a fixed extra offset, on top of its orbit and any reflow.
     *
     * For a view that must sit exactly on top of another one that something *else* is animating.
     * The × badge inside an open folder is the case: it is a sibling cell whose bounds are copied
     * from the icon's layout params, and a folder rearrangement moves the icon with a translation
     * (`CellLayout.animateChildToPosition`) while leaving those layout params where they were. Left
     * alone the badge would sit at the old cell for the whole 230ms slide and then jump.
     *
     * Re-asserted every pre-draw by [AresFolderEdit], so it tracks rather than animates — there is
     * no second animator here, only a copy of the first one's current value.
     */
    fun setFollow(view: View, x: Float, y: Float) {
        // Nothing to record, and no reason to create an entry, for the resting case.
        val existing = motions[view]
        if (existing == null && x == 0f && y == 0f) return
        val m = existing ?: motions.getOrPut(view) { Motion() }
        if (m.followX == x && m.followY == y) return
        m.followX = x
        m.followY = y
        apply(view, m)
    }

    /**
     * Slides [view] down by [y] so its icon sits in the visual centre of the cell.
     *
     * The one user is [AresEditLabel], which hides a home tile's label while editing. An icon sits
     * high in its cell precisely to leave room for the text underneath it; with the text gone it
     * reads as hanging off the top, so the icon has to come down to where the whole cell's centre
     * is.
     *
     * A fixed offset with no motion of its own, like [setFollow] — [AresEditLabel] drives its own
     * animator and calls this each frame, rather than a second animator appearing here.
     *
     * **On the home grid this is the one contribution written to the ITEM view rather than the
     * holder container.** Everything else in this file moves the container, because that is what
     * carries the badges and the frost with it. The lift must not: the badges mark the *cell*, and
     * the frost outlines it (§21), so both stay put while only the icon moves.
     *
     * Inside an open folder there is no container to separate them — the icon is the cell's own
     * child — so the lift lands on the same view as the float, and [apply] sums them. The badges
     * still stay put, but for a different reason worth stating where it can be seen: a folder badge
     * rides in a *sibling* cell whose only link to the icon is `AresFolderEdit` copying the icon's
     * `INDEX_REORDER_PREVIEW_OFFSET` into [setFollow] each pre-draw. Everything this file writes
     * goes to `INDEX_REORDER_BOUNCE_OFFSET` instead, so the badge tracks the stock slide and never
     * sees the lift. Change either channel and that separation goes with it.
     */
    fun setLift(view: View, y: Float) {
        // Nothing to record, and no reason to create an entry, for the resting case.
        val existing = motions[view]
        if (existing == null && y == 0f) return
        val m = existing ?: motions.getOrPut(view) { Motion() }
        if (m.liftY == y) return
        m.liftY = y
        apply(view, m)
    }

    /** [view]'s current reflow displacement, so a caller can measure where the tile is *drawn*. */
    fun reflowX(view: View): Float = motions[view]?.dx ?: 0f

    /** @see reflowX */
    fun reflowY(view: View): Float = motions[view]?.dy ?: 0f

    /**
     * Displaces [view] by ([dx], [dy]) from its layout box and springs it back to zero.
     *
     * The caller computes the displacement as *where the tile was drawn* minus *where it is now
     * laid out*, so this is a **retarget**: the value jumps, the velocity does not, and the tile
     * continues from the motion it already had rather than restarting.
     *
     * With the system animator scale at zero ("Remove animations") this snaps instead, matching
     * what the float, the grid dots and the drop ring already do in that state.
     */
    fun displaceTo(view: View, dx: Float, dy: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            clearReflow(view)
            return
        }
        val m = motions.getOrPut(view) { Motion() }
        m.dx = dx
        m.dy = dy
        apply(view, m)
        ensureRunning()
    }

    /** Puts [view] back on its layout box immediately, leaving the orbit alone. */
    fun clearReflow(view: View) {
        val m = motions[view] ?: return
        if (m.settled) return
        m.dx = 0f
        m.dy = 0f
        m.vx = 0f
        m.vy = 0f
        apply(view, m)
    }

    /**
     * Drops every contribution for [view] and puts it back at rest.
     *
     * Called by [AresEditWiggle.reset], which is the funnel for "this view is no longer editing" —
     * a recycled row, a detached child, or the whole mode ending.
     */
    fun clear(view: View) {
        motions.remove(view)
        write(view, 0f, 0f)
    }

    private fun apply(view: View, m: Motion) {
        write(view, m.orbitX + m.dx + m.followX, m.orbitY + m.dy + m.followY + m.liftY)
    }

    /**
     * Re-writes [view]'s composite translation from its recorded contributions, if it has any.
     *
     * A safety re-assert for a view whose translation channel was zeroed underneath us by something
     * outside this file — `FolderPagedView.arrangeChildren` does exactly that on open/close, wiping
     * the `INDEX_REORDER_BOUNCE_OFFSET` value the orbit+lift live in after the orbit already wrote
     * it that frame, so the icon draws un-lifted for one frame until the next orbit tick. Called
     * from `AresFolderEdit.sync` (a pre-draw listener, so it runs AFTER the arrange in the same
     * frame) to restore the value before the draw. Idempotent: writes the same total the orbit
     * would, so it is harmless when nothing reset it.
     */
    fun reapply(view: View) {
        val m = motions[view] ?: return
        apply(view, m)
    }

    /**
     * The single write.
     *
     * A [Reorderable] — every `BubbleTextView`, so every icon inside an open folder — must **not**
     * be given `translationX` directly. Its translation is owned by a [com.android.launcher3.util.MultiTranslateDelegate],
     * which sums several independent channels and writes the total; a direct write is erased by the
     * next channel update and erases it in turn. That is not theoretical: `CellLayout.animateChildToPosition`
     * (what a folder rearrangement is) animates `INDEX_REORDER_PREVIEW_OFFSET`, so a float writing
     * `translationX` every frame cancels the very reorder animation this work is about.
     *
     * `INDEX_REORDER_BOUNCE_OFFSET` is the channel to use inside a folder: its only stock writer is
     * `ReorderPreviewAnimation`, created by `CellLayout.beginOrAdjustReorderPreviewAnimations`,
     * which a folder never calls — folder rearrangement goes through `FolderPagedView.realTimeReorder`
     * instead.
     *
     * **The home grid takes BOTH branches, and this comment used to claim it took only one.** Its
     * holder containers are plain `FrameLayout`s and do take the direct branch — that part was and
     * is true, and it is where the orbit, the reflow and the follow are written. But §26's lift is
     * written to the ITEM view instead of the holder (see [setLift]), and every home item view —
     * `BubbleTextView`, `FolderIcon`, `AppPairIcon` — implements [Reorderable], so the lift goes
     * through `INDEX_REORDER_BOUNCE_OFFSET` on the grid as well.
     *
     * No functional consequence today: nothing on the grid writes that channel on those views,
     * because they are not children of a `CellLayout`. Corrected anyway, because a stale claim about
     * which channel a write lands on is exactly the error §26 records this project making twice in
     * one change — and the next person to reason from this paragraph would inherit it.
     */
    private fun write(view: View, x: Float, y: Float) {
        val reorderable = view as? Reorderable
        if (reorderable != null) {
            reorderable.translateDelegate.setTranslation(INDEX_REORDER_BOUNCE_OFFSET, x, y)
        } else {
            view.translationX = x
            view.translationY = y
        }
    }

    // ------------------------------------------------------------------ the spring

    /**
     * Angular frequency, rad/s.
     *
     * A critically damped system settles to within 2% in about 5.8/ω, so reading the settle time
     * off [REFLOW_SETTLE_MS] and inverting it keeps the constant meaningful in the units someone
     * tuning it would think in.
     */
    private val omega: Float
        get() = SETTLE_CONSTANTS / (REFLOW_SETTLE_MS / 1000f)

    private val frameCallback = Choreographer.FrameCallback { frameNanos -> tick(frameNanos) }

    private var lastFrameNanos = 0L

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun tick(frameNanos: Long) {
        val dtSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            // Clamped: a dropped frame or a paused process must not integrate a huge step, which
            // for a spring means the tile arriving instantly and the motion being lost.
            ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_STEP_SECONDS)
        }
        lastFrameNanos = frameNanos

        scratch.clear()
        scratch.addAll(motions.keys)
        var active = false
        for (i in scratch.indices) {
            val view = scratch[i]
            val m = motions[view] ?: continue
            if (m.settled) continue
            if (dtSeconds > 0f) step(m, dtSeconds)
            apply(view, m)
            if (!m.settled) active = true
        }
        scratch.clear()

        if (active) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            running = false
        }
    }

    /**
     * One exact step of a critically damped spring toward zero.
     *
     * Closed form rather than an Euler integration: `x(t) = (x0 + (v0 + ωx0)t)e^(-ωt)` is the exact
     * solution of `ẍ + 2ωẋ + ω²x = 0`, so it is unconditionally stable at any frame interval. A
     * naive integrator diverges on exactly the long frames a launcher produces under load.
     */
    private fun step(m: Motion, dt: Float) {
        val w = omega
        val decay = exp(-w * dt)

        val cx = m.vx + w * m.dx
        m.dx = (m.dx + cx * dt) * decay
        m.vx = (m.vx - w * cx * dt) * decay

        val cy = m.vy + w * m.dy
        m.dy = (m.dy + cy * dt) * decay
        m.vy = (m.vy - w * cy * dt) * decay

        if (abs(m.dx) < REST_PX && abs(m.vx) < REST_PX_PER_SECOND) {
            m.dx = 0f
            m.vx = 0f
        }
        if (abs(m.dy) < REST_PX && abs(m.vy) < REST_PX_PER_SECOND) {
            m.dy = 0f
            m.vy = 0f
        }
    }

    /** Settling time of a critically damped spring, in time constants, to within 2%. */
    private const val SETTLE_CONSTANTS = 5.8f

    /** Below half a pixel of travel there is nothing left to draw; stop rather than asymptote. */
    private const val REST_PX = 0.5f
    private const val REST_PX_PER_SECOND = 4f

    /** 50ms — three dropped frames. Longer than this is a stall, not motion. */
    private const val MAX_STEP_SECONDS = 0.05f
}
