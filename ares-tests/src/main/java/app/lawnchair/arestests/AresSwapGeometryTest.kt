package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Two §4 defects that the three-tile fixture could not see, from the home-grid review.
 *
 * **Ledger row 26 — the swap guard was an index, not an identity.** `lastSwapFrom` stored the
 * *dragged* item's old adapter position, and `moveItem` is remove-then-insert, so only for an
 * ADJACENT swap does that position end up holding the item just swapped with. For `from=0, to=2`
 * the list goes `[W,X,A] → [X,A,W]`: the guarded index holds a bystander while the real return-leg
 * target sits unguarded. Non-adjacent is the normal case here — `chooseDropTarget` scans every
 * attached child and the widget move threshold is 0.02. Fixed by guarding the target `ItemInfo`
 * (`lastSwapTarget`). The observable is [AresWidgetSwapLoopTest]'s own: order transitions and
 * revisits during a motionless hang, on a **non-adjacent** target this time.
 *
 * **Ledger row 27 — the inherited `onMoved` jumped the grid.** `ItemTouchHelper.moveIfNecessary`
 * calls `Callback.onMoved` after every swap, and the stock default calls
 * `recyclerView.scrollToPosition(toPos)` whenever the target touches an edge — with our padding
 * `0,top,0,0` that is any partially visible bottom-row tile. `AresMasonryLayoutManager`'s
 * `scrollToPosition` is an ABSOLUTE jump putting the target's row at the viewport top, computed
 * against pre-move packing. Fixed by overriding `onMoved` to a no-op. The observable is
 * `scrollOffset` sampled across the drag: `ItemTouchHelper`'s legitimate edge auto-scroll moves it
 * *gradually* (small per-frame deltas), the defect moves it in ONE step — so the assertion is on
 * the largest sample-to-sample delta, not on total travel, which keeps the two mechanisms apart.
 *
 * Both were invisible on the three-tile fixture: `maxScroll == 0` made `scrollToPosition` a no-op,
 * and one dragged widget against one target is the only shape an index guard covers.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresSwapGeometryTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.setAnimatorScale(1)
        ares.goHome()
        ares.exitEditMode()
        ares.scrollGridToTop()
        ares.requireWidgetFixture()
    }

    @After
    fun tearDown() {
        runCatching { AresGestures.cancelStuckPointer() }
        runCatching { ares.exitEditMode() }
        runCatching { ares.scrollGridToTop() }
    }

    @Test
    fun nonAdjacentWidgetSwapSettlesWithoutChurn() {
        val tiles = ares.tiles()
        val widget = tiles.first { it.isWidget }
        // A target at least three adapter positions away, so the old index guard's blind spot is
        // exactly what is exercised. On screen, so the gesture can reach it.
        val target = tiles.first { !it.isWidget && abs(it.position - widget.position) >= 3 }
        Log.i(TAG, "swap-geometry non-adjacent: widget@${widget.position} -> ${target.title}@${target.position}")

        val orders = mutableListOf<List<String>>()
        val sampler = AresSampler(intervalMs = 60L) { ares.homeOrder() }
        sampler.start()
        ares.enterEditModeAndDrag(
            fromIndex = widget.position,
            toIndex = target.position,
            holdMs = 700,
            travelMs = 800,
            hangMs = HANG_MS,
        )
        val samples = sampler.stop().also { orders += it }

        // Transitions and revisits over the WHOLE gesture; churn during the hang is the defect.
        val distinct = samples.distinct()
        var transitions = 0
        var revisits = 0
        val seen = mutableSetOf<List<String>>()
        var prev: List<String>? = null
        for (s in samples) {
            val p = prev
            if (p != null && s != p) {
                transitions++
                if (s in seen) revisits++
                // Which items actually moved, so a churny run names its cycle in the log.
                val moved = s.indices.filter { it < p.size && s[it] != p[it] }
                Log.i(
                    TAG,
                    "swap-geometry non-adjacent: shift@$transitions " +
                        moved.joinToString(prefix = "[", postfix = "]") { "$it:${p[it]}->${s[it]}" },
                )
            }
            seen += s
            prev = s
        }
        Log.i(TAG, "swap-geometry non-adjacent: transitions=$transitions distinct=${distinct.size} revisits=$revisits")

        // Same bar the adjacent-case falsification established: a settling grid changes a bounded
        // number of times and stops; the broken guard's cycle revisits orders it has been in.
        assertThat(transitions).isGreaterThan(0) // precondition: the swap actually happened
        assertThat(revisits).isAtMost(1)
    }

    @Test
    fun swapIntoTheBottomRowDoesNotJumpTheGrid() {
        val tiles = ares.tiles()
        val dragged = tiles.first { !it.isWidget }
        // The lowest visible tile: the shape that trips the stock onMoved's bottom-edge test.
        val target = tiles.maxBy { it.containerOnScreen.y }
        Log.i(TAG, "swap-geometry bottom-row: ${dragged.title}@${dragged.position} -> ${target.title}@${target.position}")

        val orderBefore = ares.homeOrder()
        // PER DRAWN FRAME, from an in-product recorder -- not a channel poll and not a ViewCapture.
        //
        // The poll was tried and aliases: ledger row 68a measured a "40ms" sampler actually returning
        // 44-115ms gaps (3-7 frames at 60fps), summing several frames of legitimate edge auto-scroll
        // into one apparent teleport and failing a launcher that was behaving.
        //
        // The ViewCapture that replaced it was then standing red for weeks WITHOUT EVER DESCRIBING A
        // REAL DEFECT, because ViewCapture cannot see a ViewPropertyAnimator translation at all
        // (ledger row 75): an in-product probe reading the Views' own translationX/Y mid-flight found
        // tiles at exactly HALF their armed value at t=100ms of a 200ms animation, while the capture
        // of those same tiles showed 20+ flat frames and then a single-frame 762px jump. Every reflow
        // therefore read as a teleport. Owner decision 2026-09-03: assert on the number itself.
        //
        // AresScrollTrace samples the masonry's own scroll offset on the grid's onDraw, so a frame
        // the grid skipped is simply absent rather than interpolated, and nothing has to be visible
        // to an external instrument.
        ares.scrollTrace("start")
        ares.enterEditModeAndDrag(
            fromIndex = dragged.position,
            toIndex = target.position,
            holdMs = 700,
            travelMs = 900,
            hangMs = HANG_MS,
        )
        ares.scrollTrace("stop")
        val trace = ares.scrollTraceDump()
        Log.i(TAG, "swap-geometry bottom-row: $trace")

        // Precondition: the SWAP actually happened. A drag that armed edit mode but never displaced
        // anything would pass everything below vacuously -- its sibling test asserts
        // `transitions > 0` for the same reason (adversarial-review finding, 2026-08-21). The order
        // is the direct witness.
        assertThat(ares.homeOrder()).isNotEqualTo(orderBefore)

        // A floor, ASSERTED: a trace of a handful of frames describes no drag, and a "nothing
        // jumped" answer over it is the vacuous green this suite exists to avoid. The drag alone is
        // ~1.6s, so a real recording is ~90+ frames.
        assertWithMessage("scroll trace recorded almost nothing: $trace")
            .that(trace.frames).isAtLeast(MIN_FRAMES)

        // THE ASSERTION: over how many DRAWN FRAMES was the motion distributed?
        //
        // Row 27's defect is `scrollToPosition`, an ABSOLUTE seek: the whole distance lands in a
        // single frame. A healthy scroll is an interpolated ramp and necessarily draws several. That
        // is the difference, and it is the ONLY formulation of it that survived measurement.
        //
        // Two earlier formulations did not, both calibrated here and both discarded on data:
        //
        //  - `maxStep <= 231px`. Five runs on HEALTHY code measured 234/246/267/275/297 and it
        //    failed every one. The first frame of an ease-out is legitimately large, and it scales
        //    with how far the grid has to travel, so no pixel constant can be right for two drags.
        //  - `maxStep / total <= 0.5`. Better, but not robust: healthy runs measured 0.25, 0.26,
        //    0.27, 0.28, 0.46 and then 0.82 -- against a teleport's 1.00, leaving no gap at all. The
        //    cause is sampling, not the product: when the emulator drew 151 frames instead of 283
        //    the same 21-frame ramp collapsed into ~5 steps and each covered far more ground. It is
        //    the aliasing that killed the original channel poll (ledger row 68a), returning in a
        //    per-draw sampler on a host that drops draws.
        //
        // The frame COUNT is the quantity dropped frames cannot inflate -- they can only push a
        // healthy run DOWN toward the defect, so the check errs strict rather than vacuous. Measured
        // in exactly that degraded regime (~160 frames drawn, the emulator's bad days): healthy runs
        // gave 9/11/10/12/9/9 and the teleport control gave 1, six times out of six.
        assertWithMessage(
            "the home grid moved over only ${trace.movingFrames} drawn frames (${trace.totalPx}px " +
                "total, largest single step ${trace.maxStepPx}px at +${trace.maxStepAtMs}ms, " +
                "${trace.frames} frames recorded). An interpolated scroll is spread over many " +
                "frames; landing it in one or two is the ledger row 27 `scrollToPosition` seek.",
        ).that(trace.movingFrames).isAtLeast(MIN_MOVING_FRAMES)
    }

    /**
     * NEGATIVE CONTROL for the check above: the metric must REPORT a teleport, not merely fail to
     * find one. Without this, a green from `swapIntoTheBottomRowDoesNotJumpTheGrid` is
     * indistinguishable from a green off an instrument that stopped recording -- the exact way the
     * previous ViewCapture-based check went bad, and the reason the project rule says an assertion
     * is not coverage until it has been made to fail.
     *
     * `ares-scroll-trace teleport` drives a real `RecyclerView.scrollToPosition`, which is the
     * defect's own mechanism rather than a simulation of it.
     */
    @Test
    fun theTraceDetectsATeleport() {
        // Start from the top so a seek to the LAST item has a long way to travel -- no edit mode
        // needed, `scrollToPosition` is an ordinary RecyclerView call.
        ares.scrollGridToTop()
        ares.scrollTrace("start")
        Thread.sleep(300)
        Log.i(TAG, "teleport control: ${ares.scrollTrace("teleport")}")
        Thread.sleep(600)
        ares.scrollTrace("stop")
        val trace = ares.scrollTraceDump()
        Log.i(TAG, "teleport control: $trace")

        assertWithMessage("the control did not move the grid at all: $trace")
            .that(trace.totalPx).isAtLeast(MIN_TRAVEL_PX)
        assertWithMessage(
            "a `scrollToPosition` seek lands in ONE frame, so the trace must show it moving over " +
                "fewer than $MIN_MOVING_FRAMES drawn frames -- but it reported ${trace.movingFrames} " +
                "($trace). The metric the sibling test relies on is not seeing the defect it exists " +
                "for, so a green from that test would mean nothing.",
        ).that(trace.movingFrames).isLessThan(MIN_MOVING_FRAMES)
    }

    private companion object {
        const val HANG_MS = 1_200L
        /**
         * The fewest DRAWN FRAMES a legitimate scroll of the home grid may be spread over.
         *
         * Not tuned: 4 is the middle of the empty band between an interpolated scroll (measured 9
         * to 12, in the frame-dropping regime where the number is at its lowest) and an absolute
         * `scrollToPosition` seek (1 by construction, measured 1 six times out of six). Same design
         * as `AresFoldGuard.MAX_PANEL_ASPECT`: pick the gap between the two populations, not a
         * number that happens to pass on one device.
         */
        const val MIN_MOVING_FRAMES = 4

        /** Below this the grid did not really scroll and there is no motion to characterise. */
        const val MIN_TRAVEL_PX = 200

        /** A capture of a few frames describes no drag; the drag alone runs ~1.6s (~96 frames). */
        const val MIN_FRAMES = 20
    }
}
