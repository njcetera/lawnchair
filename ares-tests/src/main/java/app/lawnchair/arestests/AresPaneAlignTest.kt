package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unfolded dual-pane top alignment: the app list's first row must line up with the home grid's
 * first row, and stay lined up across a fold cycle.
 *
 * ## The bug this guards, and why the numbers are read, not reconstructed
 *
 * When unfolded, the home grid (left pane) and the app list (right pane) are side by side and the
 * owner reads their first rows against each other (owner 2026-08-25 "start at the same place as the
 * homepage"; owner report 2026-09-03 "the app list is above the home page"). The app-list pane sets
 * its rest padding by RECONSTRUCTING the home grid's position from `insets.top + workspacePadding.top
 * + homeListTopPaddingPx`. That reconstruction is only right if the home grid actually rests at
 * `insets.top + workspacePadding.top` — and on the owner's build it did not: the home grid's first
 * row DRIFTED after a fold cycle, so the two panes diverged (by ~55px on the Pixel, the opposite
 * sign on the emulator — a device-dependent break). The reconstruction can agree with itself and
 * still be wrong, so this test reads the REAL on-screen Y of each pane's first child from
 * [AresTestInfo.REQUEST_PANE_ALIGN] and compares those, never the formula.
 *
 * ## The measurement, taken before this test was written
 *
 * `ares-pane-align`, emulator-5554 and Pixel 59091FDCG000D1, unfolded:
 * ```
 *   before the header fix:  emulator 464 vs 488,  pixel 489 vs 513   delta=+24
 *   after  the header fix:  emulator 464 vs 464,  pixel 489 vs 489   delta=0
 * ```
 * Two separate defects sat on this seam. The home first row DRIFTING across a fold cycle was the
 * big one (the fold-guard fix pins it, and the second test here guards it). The remaining +24px was
 * the section header's own `layout_marginTop` -- 10dp of INTER-section separation -- being applied
 * to the FIRST header too, where there is nothing above it to separate from; the paddings
 * themselves already agreed (measured rvPad == homePad). Dropping it at position 0, and restoring
 * it on every other header because headers RECYCLE, lines the panes up exactly.
 *
 * A/B measured on emulator-5554: with the header fix off this test FAILS
 * "delta=24 expected at most 19"; with it on, both tests pass with 0 skips.
 *
 * Non-two-panel devices SKIP (the pane view does not exist), never pass: a check that could not run
 * must be louder than one that failed, not quieter.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresPaneAlignTest {

    private val TAG = "AresPaneAlign"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    /** At rest, unfolded: the two panes' first rows line up within the accepted header offset. */
    @Test
    fun firstRowsAlignWhenUnfolded() {
        val a = requireTwoPanel()
        Log.i(TAG, "align: ${a.raw}")
        assertWithMessage("app-list first row Y vs home grid first row Y\n${a.raw}")
            .that(kotlin.math.abs(a.delta)).isAtMost(tolPx())
    }

    /**
     * The home grid's first row must not MOVE across a fold cycle, and the panes must stay aligned.
     * This is the exact shape of the owner's break: the home row drifted after folding while the
     * pane stayed put. Reads the real Y before and after; a drift beyond a couple of pixels fails.
     */
    @Test
    fun firstRowsStayAlignedAcrossAFoldCycle() {
        val before = requireTwoPanel()
        assumeTrue("device has no foldable states to drive", ares.foldCycleAndRecover())
        val after = ares.paneAlign()
        assumeTrue("panes not laid out after fold cycle", after != null && after.bothPanesLaidOut)
        after!!
        Log.i(TAG, "before: ${before.raw}\nafter:  ${after.raw}")
        assertWithMessage(
            "home grid first row moved across a fold cycle\nbefore=${before.homeChild} after=${after.homeChild}",
        ).that(kotlin.math.abs(after.homeChild - before.homeChild)).isAtMost(DRIFT_TOLERANCE_PX)
        assertWithMessage("panes misaligned after a fold cycle\n${after.raw}")
            .that(kotlin.math.abs(after.delta)).isAtMost(tolPx())
    }

    /**
     * Reads the alignment, or SKIPs when the launcher is not in two-panel (unfolded) posture.
     *
     * WAITS for the panes first. The app-list pane seeds its store on attach, so for a stretch after
     * a launcher restart (every install does one) its recycler has NO children and `paneChild` reads
     * -1. Asserting straight away turned BOTH arms of an A/B into assumption SKIPs that printed
     * `OK (1 test)` -- measured 2026-09-03, and exactly the false-green this suite exists to avoid.
     * A genuine single-pane device still SKIPs, it just costs the timeout first.
     */
    private fun requireTwoPanel(): AresLauncherDriver.PaneAlign {
        var a = ares.paneAlign()
        val deadline = System.currentTimeMillis() + PANE_BIND_TIMEOUT_MS
        while (a != null && !a.bothPanesLaidOut && System.currentTimeMillis() < deadline) {
            Thread.sleep(1_000)
            a = ares.paneAlign()
        }
        assumeTrue("pane-align channel did not answer", a != null)
        assumeTrue("not in two-panel posture (single-pane device or folded): ${a!!.raw}", a.bothPanesLaidOut)
        return a
    }

    /** The alignment tolerance in px: the accepted ~10dp header offset plus margin, below the drift. */
    private fun tolPx(): Int {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        return (ALIGN_TOLERANCE_DP * density).toInt()
    }

    private companion object {
        /** The panes now align EXACTLY (delta=0 measured); 8dp still fails the 10dp header margin. */
        const val ALIGN_TOLERANCE_DP = 8f

        /** The app-list pane seeds its store on attach; give it time after a launcher restart. */
        const val PANE_BIND_TIMEOUT_MS = 40_000L

        /** The home first row held its Y to 0px across fold cycles on the fixed build; allow a hair. */
        const val DRIFT_TOLERANCE_PX = 4
    }
}
