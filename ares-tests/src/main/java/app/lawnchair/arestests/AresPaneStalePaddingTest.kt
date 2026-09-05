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
 * Ledger row 86: the app-list pane's top padding must agree with the LIVE DeviceProfile, and must
 * come back into agreement after a profile change.
 *
 * ## The defect this guards
 *
 * The padding is derived from `dp.insets.top + dp.workspacePadding.top` and was computed only in
 * `ActivityAllAppsContainerView.setupHeader()`. Nothing re-derived it on a profile change, so after
 * a fold or a rotation the pane held the PREVIOUS profile's numbers for the life of the process.
 * Measured on the owner's Pixel 2026-09-04: `rvPad=422` against a correct `489`, the app list
 * resting 67px above the home grid in BOTH postures, persistent, cured only by a force-stop.
 *
 * The fix is a debounced, validity-gated resync (see `ARES_PAD_RESYNC_DELAY_MS`). These three tests
 * cover the invariant it maintains, the instrument's ability to see a violation, and the recovery.
 *
 * ## Why the first test asserts the INVARIANT and not the alignment
 *
 * [AresPaneAlignTest] already asserts the two panes line up, and it passed throughout the defect --
 * because the emulator does not reproduce it spontaneously. Three fold cycles and a rotation on
 * unmodified code all read `delta=0`. A test that folds and then checks alignment therefore passes
 * without ever exercising the seam, which is the failure mode this project keeps paying for.
 *
 * So the invariant asserted is `have == want`: checkable at rest, on any device, without needing to
 * provoke the transition that causes the drift.
 *
 * ## The negative control, which is the point
 *
 * `ares-pane-pad --arg stale` forces the padding 67px short -- the exact shortfall measured on the
 * Pixel, i.e. the `workspacePadding.top` term going missing. [theInvariantDetectsAStalePadding]
 * requires the check to NOTICE that. Without it, `have == want` passing would be indistinguishable
 * from a probe that reads the same field twice.
 *
 * ## Recovery IS possible, and the earlier note here was wrong
 *
 * An earlier version of this doc recorded that putting the correct value back does not restore the
 * rendered position. That was measured with `scrollToTop=false`. Re-measured 2026-09-04 with
 * `scrollToTop=true` -- which is what `setupHeader` has always passed -- the recompute takes the
 * pane from `delta=-67` straight back to `delta=0`. Repositioning the children was the missing half.
 *
 * That correction is what made the fix tractable: because recovery from an already-wrong layout is
 * complete, the resync does not have to win a race against the fold. It only has to eventually run
 * against a settled profile, which is why it can afford to be debounced and to reject transients.
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

    /**
     * A stale padding left behind would contaminate every later class in the suite, so restore
     * unconditionally when a test forced one -- even though the product now heals it, since a
     * FAILING run is exactly the run where it did not.
     *
     * Restore through the channel FIRST and kill the process only if that did not take. This class
     * runs ahead of gesture classes in the standing suite, and CLAUDE.md records that a force-stop
     * immediately before a synthetic long-press stops it registering -- a contamination that
     * presents as the NEXT class failing its arming precondition (adversarial review 2026-09-05,
     * F8b). The kill stays as the fallback because `reset` is the same product path this class
     * exists to distrust.
     */
    @After
    fun restore() {
        if (!::ares.isInitialized || !forced) return
        runCatching { ares.panePad("reset") }
        val after = runCatching { ares.panePad() }.getOrNull()
        if (after == null || after == "no-pane" || staleOf(after)) {
            Log.w(TAG, "channel reset did not restore the pane ($after); force-stopping")
            runCatching { ares.shell("am force-stop ${ares.launcherPackage}") }
            // Settle, so a long-press in the next class is not the first touch after a kill.
            Thread.sleep(3_000)
        }
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

    /**
     * THE FIX ITSELF: a stale padding must HEAL across a fold cycle.
     *
     * This is the test that would have caught row 86, and the one that guards the fix from being
     * quietly undone. The two above describe the state; this one describes the recovery.
     *
     * It forces the defect deliberately rather than waiting for it, because the emulator does not
     * reproduce it on its own -- measured across three fold cycles and a rotation on the unfixed
     * build, all `delta=0`. Waiting for a natural recurrence here would produce a test that is green
     * on a broken build, which is worse than none.
     *
     * The wait is generous on purpose. The resync is debounced (350ms) and REJECTS a profile that
     * has not settled, retrying up to 8 times -- measured across a real fold cycle it declined all
     * eight times with `wsPadTop=0`, including the `insetsTop=152` transient that regressed an
     * earlier fix attempt, and then applied on the pane's re-attach. So the healing legitimately
     * arrives seconds after the fold, not milliseconds.
     */
    @Test
    fun aStalePaddingHealsAcrossAFoldCycle() {
        val before = ares.panePad()
        assumeTrue("no app-list pane (folded, or single-panel device)", before != null && before != "no-pane")
        assumeTrue("pane already stale before forcing -- cannot control", !staleOf(before!!))

        forced = true
        val forcedState = ares.panePad("stale")
        assumeTrue("could not force the defect -- see theInvariantDetectsAStalePadding", staleOf(forcedState!!))
        Log.i(TAG, "forced before fold: $forcedState")

        assumeTrue("device has no fold states", ares.foldCycleAndRecover())

        var last: String? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            last = ares.panePad()
            if (last != null && last != "no-pane" && !staleOf(last)) break
            Thread.sleep(500)
        }
        Log.i(TAG, "after fold cycle: $last")
        assertWithMessage(
            "a stale pane padding did NOT heal across a fold cycle -- ledger row 86 has " +
                "regressed. The app list will rest above the home grid until the launcher is " +
                "force-stopped. Check the AresPaneAlign log for a `resync` line: a run of DECLINED " +
                "`profile not settled` lines with no apply means the settled profile never " +
                "arrived within the retry budget; no lines at all means the resync is not being " +
                "triggered (or debug.ares.pane_pad_resync is 0).\nlast=$last",
        ).that(last != null && last != "no-pane" && !staleOf(last)).isTrue()
    }

    /** `have=<n>|want=<n>|stale=<bool>` */
    private fun staleOf(state: String): Boolean = state.contains("stale=true")
}
