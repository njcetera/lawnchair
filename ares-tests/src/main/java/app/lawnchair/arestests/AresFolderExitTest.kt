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
 * §C4 — *"the home screen does not readjust around the app until release."*
 *
 * ## Two drag pipelines, and only one of them reflowed
 *
 * The grid has exactly one reflow and it is driven by `ItemTouchHelper`: `onMove` calls
 * `AresHomeAdapter.moveItem`, RecyclerView animates, a tile slides aside. That pipeline exists only
 * for a drag that *starts* on the grid.
 *
 * A drag out of an open folder is the other pipeline — a `DragController` drag, with the item in a
 * `DragView` and nothing in the adapter to move. Nobody was asking the grid to make room, so it did
 * not. Measured before the fix existed, the attached-tile signature was **byte-identical** between
 * rest and mid-drag, and the new tile appeared only on release.
 *
 * ## The observable is the gap itself, not a redrawn layout
 *
 * The fix does not draw a ghost rectangle. It puts a real entry in the adapter, because an ordered
 * sequence is the *only* position model this grid has — `rank` plus a footprint, no stored x/y — and
 * a second, parallel layout would agree with the packer only by luck. The reflow that follows is
 * then literally the in-grid one: same `notifyItemMoved`, same animation, same packer.
 *
 * So the entry shows up in `ares-home-order` like any other item, carrying `DROP_SLOT_ID`
 * (`Int.MIN_VALUE`). Asserting on its presence tests the mechanism rather than a rendering of it.
 *
 * A sampled run, fixed build, reads:
 *
 * ```
 * 3/0/-   3/0/-   ...   2/1/-   2/1/S   0/-1/S   0/-1/S
 * ```
 *
 * — icons in the folder / DragViews / slot present. The icon leaves the folder into a `DragView`
 * (`3→2`, `dv 1`), the slot appears once the finger is over the grid rather than over the folder,
 * and the folder then closes behind it (`icons 0`, and `dv -1` because there is no open folder left
 * to report on). With the preview disabled every sample reads `S` as `-`.
 *
 * ## Why the precondition is retried and the assertion is not
 *
 * The synthetic long-press arms a drag about two times in five. Measured over five consecutive runs
 * with sampling already off the gesture thread: `sawDrag = true, false, false, false, true`, and in
 * the failures the sequence is `3/0/-` from start to finish — the icon never leaves the folder and
 * no `DragView` is ever created. Nothing happens at all. That is the same class of unreliability
 * that makes `stress-edit-mode` and `reorder-persists` known-flaky in the PowerShell harness.
 *
 * So the *scenario* is retried up to [MAX_ATTEMPTS] times, and the *assertion* never is. Those are
 * different things, and conflating them is how a suite starts lying — retrying until a check passes
 * hides regressions, while retrying until the scenario exists is setup. If no attempt arms a drag
 * this fails loudly, and it says so in the words "could not be reproduced", which is not the same
 * statement as "the grid failed to open a gap".
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderExitTest {

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
        runCatching { ares.setFolderEdit(false) }
        runCatching { ares.pressBack() }
    }

    @Test
    fun theGridOpensAGapWhileAnAppIsDraggedOutOfAFolder() {
        var attempt = 0
        var armed: List<Sample>? = null
        while (attempt < MAX_ATTEMPTS && armed == null) {
            attempt++
            val samples = attemptDrag()
            if (samples.any { it.dragViews > 0 }) {
                armed = samples
            } else {
                Log.i(TAG, "c4 attempt $attempt never armed a drag; retrying")
            }
        }

        val samples = requireNotNull(armed) {
            "no attempt in $MAX_ATTEMPTS armed a drag out of the folder, so the scenario could not " +
                "be reproduced. That is NOT the same as the grid failing to open a gap."
        }
        Log.i(TAG, "c4 armed on attempt $attempt; slot in ${samples.count { it.slot }}/${samples.size}")

        assertThat(samples.any { it.edit }).isTrue()
        // The assertion, and it is never retried.
        assertThat(samples.any { it.slot }).isTrue()

        // And the gap must not survive the drop: a hole left behind is worse than no hole.
        ares.waitFor("drop slot to be taken") { !ares.hasDropSlot() }
        assertThat(ares.hasDropSlot()).isFalse()
    }

    private data class Sample(
        val icons: Int,
        val dragViews: Int,
        val slot: Boolean,
        val edit: Boolean,
    )

    /** One press-hold-drag out of the folder onto the grid, with what was sampled during it. */
    private fun attemptDrag(): List<Sample> {
        // Close and reopen rather than restarting the launcher. A force-stop immediately before the
        // gesture left the long-press failing to register at all -- twelve consecutive attempts
        // never entered edit mode, where the same press-and-hold in AresFolderHoldTest enters it
        // reliably without one. Not root-caused; the restart simply is not needed, since a drag
        // that never armed moved nothing.
        runCatching { ares.setFolderEdit(false) }
        ares.pressBack()
        ares.goHome()
        ares.exitEditMode()
        assertThat(ares.openFolder()).isTrue()
        ares.waitFor("folder to open") { ares.folderIcons().size >= 3 }
        assertThat(ares.hasDropSlot()).isFalse()

        val held = ares.folderIcons().first()

        // Sampling runs on its own thread rather than from the gesture's onStep. Three IPC
        // round-trips between injected MOVEs stall the event stream badly enough to matter — see
        // AresSampler for the measurement that established it.
        val sampler = AresSampler(intervalMs = SAMPLE_MS) {
            val icons = ares.folderIcons()
            Sample(
                icons.size,
                ares.dragViewCount(),
                ares.hasDropSlot(),
                icons.any { it.stateLift > 0f },
            )
        }

        sampler.start()
        AresGestures.pressHoldDragRelease(
            start = held.center(),
            holdMs = HOLD_MS,
            travelMs = TRAVEL_MS,
            // Straight up and well clear of the folder, onto the grid behind it. The folder's box
            // sat at y 1562..2220 when this was written; TARGET_Y is far above that.
            target = { PointF(held.center().x, TARGET_Y) },
            hangMs = HANG_MS,
        )
        val samples = sampler.stop()

        Log.i(
            TAG,
            "c4 seq: " + samples.joinToString(" ") {
                "${it.icons}/${it.dragViews}/${if (it.slot) "S" else "-"}"
            },
        )
        return samples
    }

    private companion object {
        const val HOLD_MS = 900L
        const val TRAVEL_MS = 900L
        const val HANG_MS = 900L
        const val TARGET_Y = 700f
        const val SAMPLE_MS = 40L
        const val MAX_ATTEMPTS = 4
    }
}
