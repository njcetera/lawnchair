package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ledger row 86: the app-list pane's top padding must agree with the LIVE DeviceProfile.
 *
 * ## The defect this guards
 *
 * The padding is derived from `dp.insets.top + dp.workspacePadding.top` and computed only in
 * `ActivityAllAppsContainerView.setupHeader()`. `onDeviceProfileChanged` never re-derives it, so
 * after a profile change the pane can hold the PREVIOUS profile's numbers for the life of the
 * process. Measured on the owner's Pixel 2026-09-04: `rvPad=422` against a correct `489`, the app
 * list resting 70px above the home grid, persistent, and cured only by a force-stop.
 *
 * ## Why this test asserts the INVARIANT and not the alignment
 *
 * [AresPaneAlignTest] already asserts the two panes line up, and it passed throughout — because the
 * emulator does not reproduce this defect. Three fold cycles and a rotation on unmodified code all
 * read `delta=0`. A test that folds and then checks alignment therefore passes without ever
 * exercising the seam, which is the failure mode this project keeps paying for.
 *
 * So this asserts the thing that was actually wrong: `have == want`. That is checkable at rest, on
 * any device, without needing to provoke the transition that causes the drift.
 *
 * ## The negative control, which is the point
 *
 * `ares-pane-pad --arg stale` forces the padding 67px short — the exact shortfall measured on the
 * Pixel, i.e. the `workspacePadding.top` term going missing. [theInvariantDetectsAStalePadding]
 * requires the check to NOTICE that. Without it, `have == want` passing would be indistinguishable
 * from a probe that reads the same field twice.
 *
 * ## Cleanup is a launcher restart, deliberately
 *
 * MEASURED: putting the correct value back does NOT restore the rendered position. After forcing a
 * stale padding and recomputing the right one, `rvPad` read 464 while the first row was still drawn
 * 67px high, even with an explicit `requestLayout()` on the recyclers and the pane. The recycler
 * keeps its laid-out children. A force-stop does recover it, so that is what [restore] does —
 * leaving a misaligned pane behind would contaminate every later class in the suite.
 *
 * That finding matters beyond this test: whatever eventually fixes row 86 has to drive a real
 * re-layout, because a correct recompute alone does not recover a pane already laid out wrong.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresPaneStalePaddingTest {

    private val TAG = "AresPanePad"
    private lateinit var ares: AresLauncherDriver
    private var forced = false

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    @After
    fun restore() {
        if (!::ares.isInitialized || !forced) return
        // Not `reset`: see the class doc -- the value comes back, the layout does not.
        runCatching { ares.shell("am force-stop ${ares.launcherPackage}") }
        runCatching { ares.goHome() }
    }

    /** The invariant. On a healthy launcher the pane's padding equals what the profile implies. */
    @Test
    fun paneTopPaddingAgreesWithTheLiveProfile() {
        val state = ares.panePad()
        assumeTrue("no app-list pane (folded, or single-panel device)", state != null && state != "no-pane")
        Log.i(TAG, "at rest: $state")
        assertWithMessage(
            "the app-list pane's top padding disagrees with the live DeviceProfile, which is " +
                "ledger row 86 -- the pane is holding a previous profile's numbers and the app " +
                "list will rest above or below the home grid.\n$state",
        ).that(staleOf(state!!)).isFalse()
    }

    /**
     * NEGATIVE CONTROL. Forces the defect and requires the invariant above to catch it.
     *
     * Runs the identical read the first test uses, so a pass here proves that test can fail.
     */
    @Test
    fun theInvariantDetectsAStalePadding() {
        val before = ares.panePad()
        assumeTrue("no app-list pane (folded, or single-panel device)", before != null && before != "no-pane")
        assumeTrue("pane already stale before forcing -- cannot control", !staleOf(before!!))

        forced = true
        val forcedState = ares.panePad("stale")
        Log.i(TAG, "forced: $forcedState")
        assertWithMessage(
            "forcing a 67px-short padding did not register as stale, so the invariant in " +
                "paneTopPaddingAgreesWithTheLiveProfile cannot detect the defect it claims to " +
                "guard and its green means nothing.\nforced=$forcedState",
        ).that(staleOf(forcedState!!)).isTrue()
    }

    /** `have=<n>|want=<n>|stale=<bool>` */
    private fun staleOf(state: String): Boolean = state.contains("stale=true")
}
