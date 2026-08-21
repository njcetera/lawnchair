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
 * Ledger row 25 — the empty-space popup's gesture claim, which used to undo itself.
 *
 * `fireEmptySpaceLongPress` calls `requestDisallowInterceptTouchEvent(true)`, then thirteen lines
 * later dispatches a synthetic `ACTION_CANCEL` through `super` to take the gesture off
 * RecyclerView. That cancel landed in `editModeTouchListener`'s `ACTION_CANCEL` branch, whose whole
 * job is to *let go* — including `requestDisallowInterceptTouchEvent(false)`, which propagates up
 * the chain and cleared `BaseDragLayer`'s `FLAG_DISALLOW_INTERCEPT` too. So the failure the claim
 * exists to prevent stayed live: hold empty space until the popup opens, keep the finger down, drag
 * sideways, and `AresPaneSwipeController` — whose `mNoIntercept` latched at a DOWN taken before the
 * popup existed and is never re-consulted — pans the app-list pane in underneath the open popup.
 * The fix re-asserts the claim after the cancel.
 *
 * ## The observable
 *
 * Pane movement is a state transition, so the tell is `inTransition` going true (or the state
 * leaving NORMAL) **while `optionsPopup` is still open**. Both come from one `ares-surface-state`
 * sample. This was marked "needs a real finger" before the channel existed; it does not — the
 * popup is raised by this fork's own hold timer, which a synthetic hold fires reliably (it is how
 * `64d3f80b97` was measured), and the failure mode is machine-readable.
 *
 * ## Where "empty space" is
 *
 * Only reachable when the grid can scroll (`canScrollTheGrid()` gates the whole path), and the
 * fixture is taller than the viewport, so: scroll to the end, then aim beside the lowest tile —
 * the last row is not full, which the test verifies rather than assumes. Kept clear of
 * `edgeMarginPx`, since `fireEmptySpaceLongPress` declines points near the bezel by design (that
 * band belongs to the §10 pan and the §20 edge-back).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresPopupPanTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.setAnimatorScale(1)
        ares.goHome()
        ares.exitEditMode()
    }

    @After
    fun tearDown() {
        runCatching { AresGestures.cancelStuckPointer() }
        // BACK closes the popup if a failure left it up.
        runCatching { ares.pressBack() }
        runCatching { ares.exitEditMode() }
        runCatching { ares.scrollGridToTop() }
    }

    @Test
    fun sidewaysDragUnderTheOpenPopupDoesNotPanThePane() {
        // Scroll to the end so empty space exists on screen. Plain vertical drags; no long-press
        // involved, so none of the long-press flakiness applies.
        var offset = ares.surfaceState().scrollOffset
        var guard = 0
        while (guard++ < 8) {
            AresGestures.dragPath(
                listOf(PointF(540f, 1800f), PointF(540f, 600f)),
                legMs = 350,
            )
            Thread.sleep(600)
            val now = ares.surfaceState().scrollOffset
            if (now == offset) break
            offset = now
        }
        assertThat(offset).isGreaterThan(0)

        // Empty space: beside the lowest tile, one half-tile out, same row. Verified empty by
        // checking no tile's box contains the point -- an assumption here would make a pass
        // vacuous if the fixture ever grows a full last row.
        val tiles = ares.tiles()
        val lowest = tiles.maxBy { it.containerOnScreen.y }
        val spot = PointF(
            lowest.containerOnScreen.x + lowest.size.x * lowest.scale + 90f,
            lowest.containerOnScreen.y + lowest.size.y * lowest.scale / 2f,
        )
        val occupied = tiles.any {
            spot.x >= it.containerOnScreen.x &&
                spot.x < it.containerOnScreen.x + it.size.x * it.scale &&
                spot.y >= it.containerOnScreen.y &&
                spot.y < it.containerOnScreen.y + it.size.y * it.scale
        }
        Log.i(TAG, "popup-pan spot=$spot occupied=$occupied lowest=${lowest.title}")
        assertThat(occupied).isFalse()

        // One continuous gesture: hold on empty space until the popup opens, then drag sideways
        // WITHOUT lifting. Sampling on its own thread, per AresSampler.
        data class S(val popup: Boolean, val inTransition: Boolean, val state: String)
        val sampler = AresSampler(intervalMs = 40L) {
            val s = ares.surfaceState()
            S(s.optionsPopupOpen, s.inTransition, s.state)
        }
        sampler.start()
        AresGestures.pressHoldDragRelease(
            start = spot,
            holdMs = HOLD_MS,
            travelMs = TRAVEL_MS,
            target = { PointF(spot.x - SIDEWAYS_PX, spot.y) },
            hangMs = HANG_MS,
        )
        val samples = sampler.stop()
        Log.i(
            TAG,
            "popup-pan popup=${samples.count { it.popup }}/${samples.size} " +
                "transitions=${samples.count { it.inTransition }} " +
                "states=${samples.map { it.state }.distinct()}",
        )

        // Precondition: the popup actually opened during the gesture. Without this, a hold that
        // quietly did nothing reports a clean pass -- the vacuity trap the first D4 test fell into.
        assertThat(samples.any { it.popup }).isTrue()

        // The assertion: from the first popup sample onward, no pane transition and no state
        // change. The drag travels well past the pan controller's touch slop, so if the claim is
        // cleared the pan WILL begin.
        val afterPopup = samples.dropWhile { !it.popup }
        assertThat(afterPopup.none { it.inTransition }).isTrue()
        assertThat(afterPopup.all { it.state == "NORMAL" }).isTrue()
    }

    private companion object {
        const val HOLD_MS = 900L
        const val TRAVEL_MS = 700L
        const val HANG_MS = 400L
        const val SIDEWAYS_PX = 420f
    }
}
