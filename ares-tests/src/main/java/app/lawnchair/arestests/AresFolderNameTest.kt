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
import kotlin.math.abs

/**
 * D9 — *"with three items in a folder, starting to move one in edit mode sends the folder name from
 * the bottom to the centre."*
 *
 * ## Why the name moves, when nothing repositions it
 *
 * The whole chain is layout:
 *
 *  1. `Folder.onDragStart` calls `mContent.removeItem`, so the dragged icon stops being a child.
 *  2. `CellLayout.getDesiredHeightForOccupiedRows()` counts **child views**, so losing the last
 *     row's only occupant drops the content by a whole row.
 *  3. The folder is a vertical `LinearLayout` with default TOP gravity, so the footer — and the name
 *     in it — is laid out immediately under the content and follows it up.
 *  4. The folder's own box does *not* follow, because `BaseDragLayer.onLayout` lays a
 *     `customPosition` child at `lp.height`, which `centerAboutIcon()` fixed when it opened.
 *
 * So the name ends up at the vertical centre of a box that did not move. The fix freezes
 * `getContentAreaHeight()` at its pre-drag value for the duration of the drag.
 *
 * ## Which icon you drag decides whether it reproduces at all
 *
 * **It has to be an icon that is alone on the last row.** Lifting one that shares its row leaves a
 * hole, the row stays occupied, and nothing moves — a two-item fixture cannot reproduce this, and
 * neither can dragging the first of three. Both were measured at 0px before the third was tried.
 * This test therefore asserts its own precondition rather than trusting the fixture: the last icon
 * must sit lower than every other one.
 *
 * ## Why the gesture is real rather than a channel call
 *
 * A bare long-press does **not** start a drag in this fork — `AresFolderDrag.onFolderItemLongClick`
 * consumes it, and `Folder.startDrag` is reachable only from `DragStarter` once the finger travels
 * past the touch slop. Driving `onDragStart` directly would skip exactly the code that decides
 * whether a drag begins, which is the half of D4 that took two attempts to find. The folder is
 * opened and put into edit mode through the channel (both product entry points), because *getting
 * there* by gesture is what this harness is unreliable at; the drag itself is a real event stream.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderNameTest {

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
        runCatching { ares.setFolderEdit(false) }
        runCatching { ares.pressBack() }
    }

    @Test
    fun folderNameStaysPutWhileAnIconIsDragged() {
        assertThat(ares.openFolder()).isTrue()
        ares.waitFor("folder to open") { ares.folderIcons().size >= 3 }
        assertThat(ares.setFolderEdit(true)).isTrue()
        ares.waitFor("folder edit mode") { ares.folderIcons().any { it.stateLift > 0f } }

        val icons = ares.folderIcons()
        val last = icons.last()
        Log.i(TAG, "d9 icons: $icons")

        // The precondition, asserted rather than assumed -- see the class doc. If the last icon is
        // not alone on its row, this test cannot reproduce the defect and a pass would be vacuous.
        val othersAbove = icons.dropLast(1).all { it.screenY < last.screenY }
        assertThat(othersAbove).isTrue()

        val nameAtRest = ares.folderNameTop()
        Log.i(TAG, "d9 nameTop at rest: $nameAtRest")
        assertThat(nameAtRest).isGreaterThan(0)

        val samples = mutableListOf<Int>()
        val start = last.center()
        AresGestures.pressHoldDragRelease(
            start = start,
            holdMs = HOLD_MS,
            travelMs = TRAVEL_MS,
            // Straight up, and only far enough to clear the touch slop and let the drag arm. Kept
            // well inside the folder: leaving it would hand the item to the home grid, which is a
            // different behaviour (§C4) and not what this measures.
            target = { PointF(start.x, start.y - TRAVEL_PX) },
            onStep = { _, _ -> samples += ares.folderNameTop() },
            hangMs = HANG_MS,
            onHangStep = { samples += ares.folderNameTop() },
        )

        val moved = samples.filter { it > 0 }.map { abs(it - nameAtRest) }
        val worst = moved.maxOrNull() ?: 0
        Log.i(TAG, "d9 nameTop drift: worst=$worst over ${moved.size} samples")

        assertThat(moved).isNotEmpty()
        // One cell row is ~231px on this profile, which is what the defect moved it by. The
        // tolerance is well below that and well above layout jitter.
        assertThat(worst).isLessThan(MAX_DRIFT_PX)
    }

    private companion object {
        const val HOLD_MS = 700L
        const val TRAVEL_MS = 600L
        const val HANG_MS = 500L
        const val TRAVEL_PX = 130f
        const val MAX_DRIFT_PX = 40
    }
}
