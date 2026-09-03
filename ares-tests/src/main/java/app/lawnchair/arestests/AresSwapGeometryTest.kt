package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
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
        val offsets = mutableListOf<Int>()
        val sampler = AresSampler(intervalMs = 40L) { ares.surfaceState().scrollOffset }
        sampler.start()
        ares.enterEditModeAndDrag(
            fromIndex = dragged.position,
            toIndex = target.position,
            holdMs = 700,
            travelMs = 900,
            hangMs = HANG_MS,
        )
        val samples = sampler.stop().also { offsets += it }

        val jumps = samples.zipWithNext { a, b -> abs(b - a) }
        val worst = jumps.maxOrNull() ?: 0
        val gaps = sampler.intervals()
        Log.i(TAG, "swap-geometry bottom-row: offsets=${samples.distinct()} worstJump=$worst")
        Log.i(TAG, "swap-geometry bottom-row: sampleGapsMs=$gaps (nominal ${40}ms)")

        assertThat(samples).isNotEmpty()
        // Precondition: the SWAP actually happened. A drag that armed edit mode but never
        // displaced anything produces a flat offset trace and, without this, a vacuous pass --
        // its sibling test asserts `transitions > 0` for the same reason
        // (adversarial-review finding, 2026-08-21). The order is the direct witness.
        assertThat(ares.homeOrder()).isNotEqualTo(orderBefore)
        // One cell row is 231px here. The defect is a single absolute jump that puts the target's
        // row at the viewport top — hundreds of px in one layout pass. Edge auto-scroll, which is
        // legitimate and stays, moves far less than a row between 40ms samples.
        assertThat(worst).isLessThan(MAX_STEP_PX)
    }

    private companion object {
        const val HANG_MS = 1_200L
        const val MAX_STEP_PX = 231
    }
}
