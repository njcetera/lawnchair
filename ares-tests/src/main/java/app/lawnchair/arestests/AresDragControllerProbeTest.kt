package app.lawnchair.arestests

import android.graphics.PointF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * POSITIVE CONTROL for `AresDragWatch` / [AresTestInfo.REQUEST_DRAG_STATE] — ledger row 84.
 *
 * ## Why this class exists at all
 *
 * Row 84's whole question is whether a `DragView` is ever stranded after a folder-exit handoff, and
 * the probe that answers it reports `dragViews=0`. That number is worth nothing until the probe has
 * been made to report something else — the project's standing rule, and the one that caught the
 * inert-detector problem on the ViewCapture surface.
 *
 * Two cheaper attempts failed, and each failed for a reason worth keeping:
 *
 *  - **Polling the channel across a green `AresHomeReorderTest`** read `dragging=false` on all 45
 *    samples. Not a sampling miss: a home-grid reorder is an `ItemTouchHelper` drag and never
 *    touches the `DragController` at all, so no sample rate would have caught one.
 *  - **`adb shell input draganddrop`** on an app-list icon left `starts=0`. It interpolates from the
 *    first event and never holds still, so the long-press that arms a drag never fires. CLAUDE.md
 *    already records shell-driven arming as unreliable here.
 *
 * So the control has to come from the real injector, which is what this uses.
 *
 * ## What it proves, and what it deliberately does NOT
 *
 * An app-list icon carries `ItemInfo.NO_ID`, so `AresFolderExitHandoff.maybeTakeOver` DECLINES it.
 * That is fine and intended: this test is not about the handoff. It only has to show that a
 * `DragController` drag can be started and counted on this device — i.e. that `starts` moving is
 * observable — so that a later `starts=0` over a folder gesture is evidence of absence rather than
 * evidence of a dead instrument.
 *
 * Deliberately NOT in the standing list in `run-ares-tests.sh`. It depends on synthetic long-press
 * arming, which CLAUDE.md records decaying with emulator uptime, and the regression programme's own
 * history says an intermittently-red check is worse than no check. Run it by name when row 84 needs
 * its control; promote it only if it proves steady.
 *
 * ## THIS TEST IS CURRENTLY RED, ON PURPOSE, AND THAT IS THE FINDING
 *
 * First run on emulator-5554 (uptime ~5h, well inside the healthy window): it FOUND an app-list
 * icon — so it did not skip — dragged it, and `starts` stayed at **0**. A follow-up plain
 * `input swipe` long-press on the same icon produced **no popup and no visible response at all**.
 *
 * That is the third independent route to a `DragController` drag that has come back empty, after
 * the reorder-suite poll and `input draganddrop`. Together with the static picture — the only
 * `beginDragShared` callers carrying an item that already has a DB row are the OVERLAY `Folder`
 * (removed by the WP migration) and the hotseat — the accumulating evidence is that the
 * `DragController` is largely unused on the Ares home surface, and that row 84's ghost may be on a
 * dead path.
 *
 * **NOT concluded.** Whether this fork's app-list rail is even supposed to start a drag is
 * unverified, and a failure to arm is indistinguishable from a surface that never drags. Leaving
 * the test red rather than deleting or `@Ignore`-ing it keeps the open question visible, which is
 * the point: it is a control that has not yet been achieved, not a defect in the product.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresDragControllerProbeTest {

    private val TAG = "AresDragProbe"
    private lateinit var ares: AresLauncherDriver
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    /**
     * A long-press-and-drag on an app-list icon must make `AresDragWatch.starts` move.
     *
     * The target app is one that exists ONLY in the app list, never on the home grid — otherwise
     * `By.text` can match the home tile instead and the gesture drags the wrong surface, which
     * would read as a pass for the wrong reason.
     */
    @Test
    fun anAppListDragIsCountedByTheDragWatch() {
        val before = ares.dragStarts()
        assumeTrue("drag-state channel did not answer", before >= 0)

        val icon = APP_LIST_ONLY.firstNotNullOfOrNull { name ->
            device.findObject(By.pkg(ares.launcherPackage).text(name))
        }
        assumeTrue(
            "no app-list-only icon on screen -- folded, or the fixture changed",
            icon != null,
        )
        val bounds = icon!!.visibleBounds
        Log.i(TAG, "dragging app-list icon at $bounds; starts before = $before")

        AresGestures.pressHoldDragRelease(
            start = PointF(bounds.exactCenterX(), bounds.exactCenterY()),
            holdMs = 800,
            travelMs = 700,
            // Into the home grid's half of the screen. Where it lands does not matter; only that a
            // DragController drag was started.
            target = { PointF(bounds.exactCenterX() / 3f, bounds.exactCenterY().toFloat()) },
        )

        val after = ares.dragStarts()
        Log.i(TAG, "starts after = $after (${ares.dragWatch()})")
        assertWithMessage(
            "an app-list long-press-drag did not register as a DragController drag.\n" +
                "Either the gesture never armed (CLAUDE.md: synthetic long-press arming decays " +
                "with emulator uptime -- restart the emulator PROCESS and retry) or AresDragWatch " +
                "is not registered. Until this passes, a dragViews=0 reading proves nothing.\n" +
                "watch=${ares.dragWatch()}",
        ).that(after).isGreaterThan(before)
    }

    private companion object {
        /** On the fixture these exist in the drawer only, so a match cannot be a home tile. */
        val APP_LIST_ONLY = listOf("Calendar", "Drive", "DarkLegacyProbe", "Clock")
    }
}
