package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The load-bearing spike: enter edit mode on the home grid, drag one tile past another, and assert
 * the order actually changed -- **with animations at their normal scale**.
 *
 * Nothing here disables animators. That is the point. `AresEditWiggle.start()` and
 * `AresEditMotion.displaceTo()` both early-out on `!ValueAnimator.areAnimatorsEnabled()`, so at
 * `animator_duration_scale 0` -- which the PowerShell harness sets -- the float and the reflow
 * spring do not exist and the grid under test is not the grid on the device.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresHomeReorderTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    @Test
    fun animationScalesAreNotZero() {
        // Guard, not decoration. Every claim this module makes about reflow and float behaviour is
        // void at scale 0, and a previous run of the PowerShell harness is enough to leave the
        // emulator in that state -- which is exactly how the widget-swap loop stayed invisible for
        // three attempts (see 50712b41c0).
        val scales = listOf(
            "animator_duration_scale",
            "window_animation_scale",
            "transition_animation_scale",
        ).associateWith { readGlobal(it) }
        Log.i(TAG, "animation scales: $scales")
        assertThat(scales["animator_duration_scale"]).isNotEqualTo("0")
        assertThat(scales["animator_duration_scale"]).isNotEqualTo("0.0")
    }

    @Test
    fun longPressEntersEditModeWithoutStartingADrag() {
        val before = ares.homeOrder()
        // A bare long-press with no travel. In stock Launcher3 this starts a drag; here it must
        // only enter edit mode and leave the order alone.
        AresGestures.pressHoldDragRelease(
            start = ares.tiles().first { it.position == 0 }.screenCenter(),
            holdMs = 800,
            travelMs = 0,
            target = { ares.tiles().first { it.position == 0 }.screenCenter() },
        )
        val after = ares.homeOrder()
        Log.i(TAG, "bare long-press: before=$before after=$after editMode=${ares.isEditMode()}")
        assertThat(after).isEqualTo(before)
        ares.exitEditMode()
    }

    /**
     * THE SPIKE. Enter edit mode, drag tile A past tile B, assert the order changed.
     *
     * A and B are two **adjacent plain icons**, picked from the live grid rather than hard-coded.
     * Not positions 0 and 2: position 0 on the current fixture is a folder and position 2 a 4x3
     * widget, and dragging a folder at a widget exercises `AresFolderDrop`'s dwell and the widget
     * coverage branch rather than the ordinary reorder this is meant to prove.
     */
    @Test
    fun dragMovesTilePastNeighbour() {
        val icons = ares.iconTiles()
        assertThat(icons.size).isAtLeast(2)
        // Adjacent in visual order, on the same row, B physically to the right of A, and THAT ROW
        // MUST CONTAIN NO WIDGET.
        //
        // Every clause was earned by a failure, and none of them is fussiness:
        //
        //  - `box.left` is not implied by `position + 1`. The masonry packer places by rank into
        //    whatever cell fits, so the next item in adapter order need not be the next one across:
        //    on the seeded fixture positions 4 and 5 sat on one row with 5 to the LEFT of 4, and
        //    the drag travelled leftwards over two other tiles.
        //  - the no-widget clause is the one that matters most. With a 4x3 widget sharing the row,
        //    the first swap repacks the whole row -- the widget moves, every icon around it moves
        //    with it, and `chooseDropTarget` then nominates whatever has slid under the finger.
        //    Measured: dragging '711/Chrome'@4 one cell right onto '712/Photos'@5 left 711 at index
        //    2, having gone BACKWARD past two tiles. A single-hop swap is only single-hop when the
        //    row is uniform.
        val rowHasWidget = { t: AresLauncherDriver.Tile ->
            ares.tiles().any { it.isWidget && it.box.bottom > t.box.top && it.box.top < t.box.bottom }
        }
        val pair = icons.zipWithNext().firstOrNull { (a, b) ->
            b.position == a.position + 1 &&
                a.box.top == b.box.top &&
                b.box.left >= a.box.right &&
                !rowHasWidget(a)
        }
        assertThat(pair).isNotNull()
        val (a, b) = pair!!
        Log.i(TAG, "dragging '${a.title}'@${a.position} onto '${b.title}'@${b.position}")

        val before = ares.homeOrder()
        Log.i(TAG, "order before: $before")

        ares.enterEditModeAndDrag(fromIndex = a.position, toIndex = b.position)

        ares.waitFor("order to change") { ares.homeOrder() != before }
        val after = ares.homeOrder()
        Log.i(TAG, "order after:  $after")

        // Same items, different order, and specifically A and B swapped RELATIVE TO EACH OTHER --
        // which is the claim, and is independent of whatever else the grid did around them.
        assertThat(after).containsExactlyElementsIn(before)
        assertThat(after).isNotEqualTo(before)
        assertThat(before.indexOf(a.title)).isLessThan(before.indexOf(b.title))
        assertThat(after.indexOf(a.title)).isGreaterThan(after.indexOf(b.title))
        ares.exitEditMode()
    }

    /**
     * Deliverable 4: can the seam see where a tile is actually DRAWN?
     *
     * Samples every tile mid-drag and prints the three candidate answers side by side -- the layout
     * box, the box plus the container's translation, and `getLocationOnScreen`. They are not the
     * same number, and the test records by how much.
     */
    @Test
    fun drawnPositionIsObservableMidDrag() {
        val samples = mutableListOf<String>()
        ares.enterEditModeAndDrag(
            fromIndex = 0,
            toIndex = 2,
            travelMs = 900,
            onDragStep = { step ->
                if (step % 8 == 0) {
                    ares.tiles().forEach { t ->
                        samples.add(
                            "step=$step pos=${t.position} '${t.title}' " +
                                "box=${t.box.left.toInt()},${t.box.top.toInt()} " +
                                "containerT=${t.containerTranslation.x.toInt()}," +
                                "${t.containerTranslation.y.toInt()} " +
                                "reflow=${t.reflow.x.toInt()},${t.reflow.y.toInt()} " +
                                "itemT=${t.itemTranslation.x.toInt()}," +
                                "${t.itemTranslation.y.toInt()} " +
                                "screen=${t.containerOnScreen.x.toInt()}," +
                                "${t.containerOnScreen.y.toInt()} " +
                                "icon=${t.itemOnScreen.x.toInt()},${t.itemOnScreen.y.toInt()} " +
                                "scale=${t.scale}",
                        )
                    }
                }
            },
        )
        samples.forEach { Log.i(TAG, "DRAWN $it") }
        // A drag that produced no non-zero reflow anywhere means the spring never ran, which at
        // normal animator scale would itself be the bug.
        val sawReflow = samples.any { it.contains(Regex("""reflow=-?[1-9]"""))}
        Log.i(TAG, "saw a non-zero reflow at some point: $sawReflow")
        assertThat(samples).isNotEmpty()
        ares.exitEditMode()
    }

    private fun readGlobal(key: String): String =
        androidx.test.uiautomator.UiDevice
            .getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("settings get global $key")
            .trim()
}
