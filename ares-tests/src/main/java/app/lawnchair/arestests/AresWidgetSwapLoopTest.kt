package app.lawnchair.arestests

import android.graphics.PointF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE FALSIFICATION TEST.
 *
 * A check counts here only once it has been made to fail on a known-broken build. `folder-edit-
 * chrome` passed on a known-broken build and is the counterexample this project cites.
 *
 * The known-broken build is `git revert 50712b41c0`, which removes the two guards that stopped the
 * widget-swap feedback loop. That commit's own measurement, on this emulator with animators ON:
 *
 * ```
 *   no guard   pos=6 alternating 520,945-780,1177 <-> 0,1177-260,1409,
 *              displaced -487,217 -> +492,-219 -> -519,231, a lap every ~350ms
 *   both       2 packing changes for the whole sweep, nothing further across three 700ms holds
 * ```
 *
 * So the observable is: **hold a widget motionless over another widget and count how many times the
 * grid order changes.** A settling grid changes a bounded number of times and then stops. An
 * oscillating one keeps going for as long as the finger is down, and -- the part a summary line
 * cannot see -- it *returns to an order it has already been in*.
 *
 * Requires the two-widget fixture: `design/scripts/seed-widget-fixture.sh emulator-5554`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresWidgetSwapLoopTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
        // Both cases here need two widgets, and AresGhostWidgetTest deletes one by design, so this
        // says so rather than failing later with `expected at least 2, but was 0` -- which reads
        // like a product defect and is not one. The runner re-seeds per class; see
        // AresLauncherDriver.requireWidgetFixture for why that cannot happen in-process.
        ares.requireWidgetFixture()
    }

    @After
    fun tearDown() {
        runCatching { ares.exitEditMode() }
    }

    @Test
    fun holdingAWidgetOverAnotherDoesNotChurnTheGrid() {
        val widgets = ares.tiles().filter { it.isWidget }
        Log.i(
            TAG,
            "tiles=${ares.tiles().size} widgets=${widgets.size} " +
                widgets.joinToString { "'${it.title}' pos=${it.position} span=${it.span}" },
        )
        // Loud, not quiet: a check that could not run must be more visible than one that failed.
        assertThat(widgets.size).isAtLeast(2)

        val small = widgets.minByOrNull { it.span.x * it.span.y }!!
        val large = widgets.maxByOrNull { it.span.x * it.span.y }!!
        assertThat(small.position).isNotEqualTo(large.position)

        // Sampled in TWO PHASES, and WHICH PHASE CARRIES THE SIGNAL WAS MEASURED, NOT REASONED.
        //
        // The obvious check is the hang: stop the finger and see whether the grid keeps churning.
        // It reads as the rigorous one -- constant input, so any further change is the grid driving
        // itself -- and 50712b41c0's own wording ("nothing further across three 700ms hold
        // samples") invites it.
        //
        // IT DOES NOT WORK. Measured, three runs on each build with the fixture re-seeded:
        //
        //   fixed   hang transitions=0 distinct=1 revisits=0   (3/3)
        //   broken  hang transitions=0 distinct=1 revisits=0   (3/3)   <-- PASSES ON THE BUG
        //
        // Asserting on the hang would have produced another `folder-edit-chrome`: a check that
        // looks stricter than the one it replaced and detects nothing. Recorded here rather than
        // deleted, because the next person will have the same idea.
        //
        // The DRAG phase is where the loop lives, and there it is unmissable:
        //
        //   fixed   drag transitions=3  distinct=3  ->  1 revisit   (3/3, PASS)
        //   broken  drag transitions=25 distinct=3  -> 23 revisits  (3/3, FAIL)
        //
        // 25 order changes among 3 arrangements is A -> B -> A by arithmetic. A settling reflow
        // never returns to an arrangement it has already left; only a cycle does.
        val dragOrders = mutableListOf<List<String>>()
        val hangOrders = mutableListOf<List<String>>()
        val sampleInto = { into: MutableList<List<String>> ->
            val o = ares.homeOrder()
            if (into.isEmpty() || into.last() != o) into.add(o)
        }

        sampleInto(dragOrders)
        val startOrder = dragOrders.first()

        ares.enterEditModeAndDrag(
            fromIndex = small.position,
            toIndex = large.position,
            holdMs = 700,
            travelMs = 600,
            // The pathological case is a finger that has STOPPED. The tiles keep moving under it,
            // and each move re-decides the drop target against the bounds the last swap moved.
            hangMs = 2400,
            onDragStep = { sampleInto(dragOrders) },
            onHangStep = { sampleInto(hangOrders) },
        )

        dragOrders.forEachIndexed { i, o -> Log.i(TAG, "DRAG-ORDER[$i] $o") }

        val dragTransitions = (dragOrders.size - 1).coerceAtLeast(0)
        val dragRevisits = dragOrders.size - dragOrders.distinct().size
        val hangTransitions = (hangOrders.size - 1).coerceAtLeast(0)
        val hangRevisits = hangOrders.size - hangOrders.distinct().size
        Log.i(
            TAG,
            "SWAP-LOOP drag: transitions=$dragTransitions " +
                "distinct=${dragOrders.distinct().size} revisits=$dragRevisits" +
                " | hang(2400ms motionless): transitions=$hangTransitions " +
                "distinct=${hangOrders.distinct().size} revisits=$hangRevisits",
        )
        Log.i(TAG, "SWAP-LOOP startOrder=$startOrder")

        // Precondition first: the swap actually happened. Ceilings alone pass on a drag that
        // armed edit mode and displaced nothing -- zero transitions clears any isAtMost
        // (adversarial-review finding, 2026-08-21).
        assertThat(dragTransitions).isGreaterThan(0)

        // THE ASSERTION. Bounds sit an order of magnitude from both measured populations, so this
        // is a separation and not a fitted threshold. Fixed build measured 1-2 revisits / 3-4
        // transitions across six runs; the reverted one measured 23 / 25 in every run.
        assertThat(dragRevisits).isAtMost(6)
        assertThat(dragTransitions).isAtMost(10)
    }

    /**
     * The same hold, watched through the reflow displacement instead of the order.
     *
     * `AresMasonryLayoutManager`'s summary line ("reflow: 5 tile(s), furthest 402px") reads
     * identically whether the grid is settling or oscillating -- 50712b41c0 records that explicitly.
     * What distinguishes them is a tile's box against its live displacement over consecutive passes,
     * and the point of this test is whether the TestInformation seam can see that pair at all.
     */
    @Test
    fun reflowDisplacementIsObservableDuringAHeldWidget() {
        val widgets = ares.tiles().filter { it.isWidget }
        assertThat(widgets.size).isAtLeast(2)
        val small = widgets.minByOrNull { it.span.x * it.span.y }!!
        val large = widgets.maxByOrNull { it.span.x * it.span.y }!!

        val trace = mutableListOf<String>()
        var n = 0
        val sample = {
            n++
            ares.tiles()
                .filter { kotlin.math.abs(it.reflow.x) > 1f || kotlin.math.abs(it.reflow.y) > 1f }
                .forEach {
                    trace.add(
                        "n=$n pos=${it.position} '${it.title}' " +
                            "box=${it.box.left.toInt()},${it.box.top.toInt()}-" +
                            "${it.box.right.toInt()},${it.box.bottom.toInt()} " +
                            "displaced=${it.reflow.x.toInt()},${it.reflow.y.toInt()}",
                    )
                }
        }

        ares.enterEditModeAndDrag(
            fromIndex = small.position,
            toIndex = large.position,
            holdMs = 700,
            travelMs = 600,
            hangMs = 2400,
            onDragStep = { if (it % 4 == 0) sample() },
            onHangStep = { if (it % 4 == 0) sample() },
        )

        trace.forEach { Log.i(TAG, "REFLOW $it") }
        Log.i(TAG, "REFLOW samples with displacement: ${trace.size} over $n polls")
        // Evidence that the spring ran at all. At animator_duration_scale 0 this is always zero,
        // which is precisely why this whole module refuses to run at scale 0.
        assertThat(trace).isNotEmpty()
    }

    private fun PointF.str() = "${x.toInt()},${y.toInt()}"
}
