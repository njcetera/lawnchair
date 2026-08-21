package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D4 — *"holding an app inside a folder to enter edit mode leaves that app with no frost/×/!"*.
 *
 * ## The chrome was never the bug. The drag was.
 *
 * A bare hold armed a drag that then never completed. `AresFolderDrag.DragStarter` was installed
 * *mid-gesture* — the long-press that created it had already consumed the DOWN — so it had no
 * recorded press point, and the first MOVE was measured against a phantom origin at `0,0`:
 *
 * ```
 * DragStarter.startDrag v=230641199 down=0.0,0.0 now=107.03613,119.53516
 * ```
 *
 * `hypot(107, 119)` is 160px, comfortably past the touch slop, so the drag armed on the first
 * jitter. `Folder.onDragStart` then lifted that icon out of the container into a `DragView`, and
 * the edit chrome for the vacated cell was correctly torn down — giving exactly the reported
 * symptom: every other app has its frost box, × and !, and the one being held does not.
 *
 * The fix is `haveOrigin`: the first MOVE after a mid-gesture install *sets* the reference point
 * instead of being measured against one that does not exist.
 *
 * ## The observable
 *
 * Two numbers, and they move together:
 *
 *  - `Folder.iconsInReadingOrder` **loses the dragged icon**, because `onDragStart` calls
 *    `mContent.removeItem` and invalidates the cached list. 3 icons become 2.
 *  - a `DragView` appears in the `DragLayer`.
 *
 * The tree mid-defect read `icons=2 EditCells=2 DragViews=1` on a three-app folder.
 *
 * ## Why the gesture travels zero pixels
 *
 * That *is* the reproduction. The bug was never about real travel — it was that a MOVE at the same
 * coordinates as the press still measured 160px away from a phantom origin. So this sends MOVEs
 * that do not move, which is the strongest form of the case: if a drag arms here, it armed on
 * nothing at all.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderHoldTest {

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
    fun aBareHoldInsideAFolderDoesNotStartADrag() {
        assertThat(ares.openFolder()).isTrue()
        ares.waitFor("folder to open") { ares.folderIcons().size >= 3 }

        // Folder edit mode is entered BY THE GESTURE, not through the channel, and that is the
        // whole test. An earlier version attached it up front and then pressed -- which passed on
        // the broken build, because ACTION_DOWN reached DragStarter normally and takeOrigin ran.
        // The defect only exists when the long-press INSTALLS DragStarter half way through a
        // gesture whose DOWN it never saw. Setting up the state first removes the defect from the
        // test. That is exactly how folder-edit-chrome came to pass on a build it should have
        // failed.
        val before = ares.folderIcons()
        Log.i(TAG, "d4 icons before: ${before.size} dragViews=${ares.dragViewCount()}")
        assertThat(before.size).isAtLeast(3)
        assertThat(ares.dragViewCount()).isEqualTo(0)

        val held = before.first()
        val counts = mutableListOf<Triple<Int, Int, Boolean>>()
        AresGestures.pressHoldDragRelease(
            start = held.center(),
            holdMs = HOLD_MS,
            travelMs = 0,
            // Zero travel: MOVEs at the press point. See the class doc -- this is the case, not a
            // weaker version of it.
            target = { held.center() },
            hangMs = HANG_MS,
            onHangStep = {
                val icons = ares.folderIcons()
                counts += Triple(
                    icons.size,
                    ares.dragViewCount(),
                    icons.any { it.stateLift > 0f },
                )
            },
        )

        Log.i(TAG, "d4 during hold: ${counts.distinct()}")
        assertThat(counts).isNotEmpty()

        // THE PRECONDITION, and the reason this test is not vacuous: the long-press must actually
        // have entered folder edit mode DURING the gesture. That is what installs DragStarter
        // mid-gesture, which is the only situation in which D4 exists. Without this assertion a
        // gesture that quietly did nothing would report a clean pass.
        assertThat(counts.any { it.third }).isTrue()

        counts.forEach { (icons, dragViews, _) ->
            assertThat(icons).isEqualTo(before.size)
            assertThat(dragViews).isEqualTo(0)
        }

        // And it recovers: the chrome is still there once the finger lifts.
        ares.waitFor("folder to settle after the hold") {
            ares.folderIcons().size == before.size && ares.dragViewCount() == 0
        }
        val after = ares.folderIcons()
        Log.i(TAG, "d4 icons after: ${after.size}")
        assertThat(after.size).isEqualTo(before.size)
        assertThat(after.all { it.stateLift > 0f }).isTrue()
    }

    private companion object {
        const val HOLD_MS = 900L
        const val HANG_MS = 1_200L
    }
}
