package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W1 — "deleting a widget keeps it rendered in the background", reported by the owner.
 *
 * ## The observable, and why this one is trustworthy
 *
 * A `RecyclerView` has two child counts that normally agree:
 *
 *  - `RecyclerView.getChildCount()` is plain `ViewGroup` — it counts **every** attached child,
 *    including ones `ChildHelper` is hiding.
 *  - `LayoutManager.getChildCount()` is `getUnhiddenChildCount()` — it does not.
 *
 * A ghost is exactly the gap between them. `animateDisappearance` re-attaches a removed view
 * *hidden* (`addAnimatingView` → `attachViewToParent(view, -1, lp, true)`) and relies on the
 * animation-finished listener to take it back out again. When that never happens the view stays a
 * real child forever: the layout manager cannot see it, so it is never laid out, never recycled and
 * never repositioned — it just keeps the bounds it had at the moment it was deleted.
 *
 * That is the same divergence the ledger recorded by hand as *"17 attached children against 16
 * database rows"*, read straight off the two counters instead of parsed out of `dumpsys` — which
 * matters, because the indentation-based `dumpsys` parser has produced a known-false widget count
 * here before.
 *
 * ## Made to fail before being trusted
 *
 * Measured on emulator-5554 with animators, window and transition scales all at 1, by disabling
 * `AresHomeAdapter.releaseForRemoval` and rebuilding:
 *
 * ```
 *   fix disabled, remove WIDGET   32|32|32 -> 32|31|31   FAIL   viewGroup one ahead: ghost
 *   fix disabled, remove icon     30|30|30 -> 29|29|29   pass
 *   fix disabled, remove icon     29|29|29 -> 28|28|28   pass
 *   fix restored, remove WIDGET   29|29|31 -> 30|30|30   pass
 *   fix restored, remove WIDGET   28|28|32 -> 29|29|31   pass
 * ```
 *
 * The icon rows are the control, and they are the point: on the **same broken build** an icon
 * removal leaves no ghost. `setIsRecyclable` is a counter, and an icon holder's runs 0 → 1 → 0 so
 * `FLAG_NOT_RECYCLABLE` clears; a widget holder starts at 1 because [AresHomeAdapter] opts it out
 * of recycling permanently, so it runs 1 → 2 → 1 and the flag never clears. That is why the owner
 * reported this for widgets and not for icons, and a fix that did not explain that asymmetry would
 * have been the wrong fix.
 *
 * ## One trap this test hit while being written
 *
 * A ghost **persists until the activity is recreated**, so it contaminates every later measurement
 * in the same process. The first icon control run here read `32|31` before it even started and
 * reported a failure that belonged to the widget removal before it. Each case therefore restarts
 * the launcher and re-reads the baseline, and [assertNoGhost] asserts on the *gap*, never on an
 * absolute count.
 *
 * Requires the two-widget fixture: `design/scripts/seed-widget-fixture.sh emulator-5554`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresGhostWidgetTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    /**
     * The precondition is asserted here rather than in `setUp` on purpose.
     *
     * The runner re-seeds per **class**, not per method, and this method consumes a widget — so a
     * blanket `setUp` check failed [removingAnIconLeavesNoHiddenChild], which needs no widget at
     * all, for a fixture only this method spends. Requiring what you actually use keeps the failure
     * pointing at the right thing.
     */
    @Test
    fun removingAWidgetLeavesNoHiddenChild() {
        ares.requireWidgetFixture()
        assertNoGhost("widget")
    }

    /**
     * The control, and it must keep passing on a build where the widget case fails.
     *
     * If this ever starts failing too, the cause is not the one documented above — the recyclable
     * counter is widget-only — and the fix should not be looked for in [AresHomeAdapter].
     */
    @Test
    fun removingAnIconLeavesNoHiddenChild() = assertNoGhost("icon")

    private fun assertNoGhost(kind: String) {
        // Fresh process per case: a ghost outlives everything short of activity recreation, so a
        // stale one from an earlier case would be attributed to this one.
        ares.restartLauncher()
        val before = ares.childCensus()
        Log.i(TAG, "ghost/$kind before: $before")
        assertThat(before.viewGroup).isEqualTo(before.layoutManager)

        val removedId = ares.removeFirstItem(kind)
        assertThat(removedId).isNotEqualTo(-1)

        // The disappearance animation has to finish before the counts can agree -- the whole
        // mechanism lives in its completion listener, so sampling early would pass on both builds.
        ares.waitFor("$kind removal to settle") {
            ares.childCensus().let { it.adapter == before.adapter - 1 }
        }
        Thread.sleep(SETTLE_MS)

        val after = ares.childCensus()
        Log.i(TAG, "ghost/$kind after: $after (removed id=$removedId)")
        assertThat(after.adapter).isEqualTo(before.adapter - 1)
        // The assertion, stated as the gap rather than as a count: how many children are attached
        // varies with how much fits on screen, and removing a tall widget makes MORE fit, so the
        // absolute number legitimately goes up.
        assertThat(after.viewGroup - after.layoutManager).isEqualTo(0)
    }

    private companion object {
        /** Past `DefaultItemAnimator`'s remove-then-move sequence with margin. */
        const val SETTLE_MS = 1_500L
    }
}
