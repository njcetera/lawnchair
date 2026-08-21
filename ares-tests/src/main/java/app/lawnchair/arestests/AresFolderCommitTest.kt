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
 * S4 (ledger row 15) — releasing OUTSIDE an open folder within the 400ms grace still filed the
 * item into it. Spec B2/B3: only a manual release **inside** a folder adds an item to it.
 *
 * ## The mechanism
 *
 * A dwell over a folder opens it. Leaving the open folder posts a 400ms grace
 * (`EXIT_CLOSE_MS`) before it closes, so a finger that clips the edge on its way to a slot does
 * not lose the folder — the grace is about whether the FOLDER stays open. But `commitDrop`'s
 * open-folder branch keyed only on `isOpen()`, so a release during that window — after the user
 * had deliberately pulled the icon OUT — still resolved into the folder. Everyone decelerates and
 * releases promptly after pulling out, so the window was the common case, not a corner. The fix
 * declines the commit while `previewExiting` (the drag's last known point was outside).
 *
 * ## The shape of the gesture, and why the exit leg lands on the big widget
 *
 * One continuous gesture: long-press an icon (edit mode), drag onto the folder, hold until the
 * dwell opens it, pull OUT, release within the grace. The exit leg aims at the 4×3 widget's
 * centre deliberately: a folder cannot be created from a widget (`kindOf` answers NONE), so the
 * point disarms the dwell without arming a new one — exiting over another icon would offer to
 * CREATE a folder, which is a different behaviour and a different test.
 *
 * ## Observables
 *
 * Both sides of the same write: `homeOrder().size` (a folder-add removes the item from the top
 * level) and the folder's own count, read by reopening it after the drag. The precondition — the
 * preview actually opened mid-gesture — is sampled from `ares-folder-metrics`, which answers only
 * while a folder is open; without it, a drag that never dwelled long enough would pass vacuously.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderCommitTest {

    private val TAG = "AresSpike"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.setAnimatorScale(1)
        ares.goHome()
        ares.exitEditMode()
        ares.scrollGridToTop()
    }

    @After
    fun tearDown() {
        runCatching { AresGestures.cancelStuckPointer() }
        runCatching { ares.setFolderEdit(false) }
        runCatching { ares.pressBack() }
        runCatching { ares.exitEditMode() }
    }

    @Test
    fun pullingOutOfAnOpenFolderAndReleasingLandsOnTheGrid() {
        val tiles = ares.tiles()
        val folder = tiles.first { it.itemType == 2 }
        val widget = tiles.first { it.isWidget && it.position != folder.position }
        val dragged = tiles.first {
            !it.isWidget && it.itemType != 2 && it.position != folder.position
        }
        val topLevelBefore = ares.homeOrder().size
        Log.i(
            TAG,
            "s4 drag ${dragged.title}@${dragged.position} onto folder@${folder.position}, " +
                "exit via widget@${widget.position}, topLevel=$topLevelBefore",
        )

        // The SCENARIO needs two witnesses, and the whole gesture is retried until both hold
        // (assertions below are never retried). sawPreview: the folder actually opened under the
        // dwell. The decline counter: the S4 branch actually RAN -- commitDrop fires ~250ms of
        // settle after the UP, and the 400ms grace started mid-exit-leg, so the branch's window
        // is tens of milliseconds wide and one loaded-emulator stutter pushes the release past
        // it; the preview self-closes, the item lands on the grid for the WRONG reason, and
        // every outcome assertion would pass (adversarial review, 2026-08-21 -- and the very
        // first suite run with the counter caught exactly that slip). The launcher counts the
        // branch; the scenario requires the count to move.
        var exercised = false
        for (attempt in 1..MAX_ATTEMPTS) {
            ares.exitEditMode()
            Thread.sleep(400)
            val tries = ares.tiles()
            val tFolder = tries.first { it.itemType == 2 }
            val tWidget = tries.first { it.isWidget && it.position != tFolder.position }
            val tDragged = tries.first {
                !it.isWidget && it.itemType != 2 && it.position != tFolder.position
            }
            val declinedBefore = ares.folderDropDeclinedCount()
            var sawPreview = false
            val sampler = AresSampler(intervalMs = 40L) {
                ares.folderIcons().isNotEmpty().also { if (it) sawPreview = true }
            }
            sampler.start()
            ares.enterEditModeAndDrag(
                fromIndex = tDragged.position,
                toIndex = tFolder.position,
                holdMs = 700,
                travelMs = 600,
                // Long enough for the 500ms dwell plus the open animation, with margin.
                hangMs = DWELL_HANG_MS,
                // Then OUT, and release on arrival -- inside the 400ms grace, which is the point.
                secondTargetIndex = tWidget.position,
                secondTravelMs = EXIT_TRAVEL_MS,
            )
            sampler.stop()
            // commitDrop runs from clearView, ~250ms of settle AFTER the UP the gesture just
            // returned from -- reading the counter immediately races it (measured: three
            // attempts in a row declined 0->1->2 each just after their read said unchanged). A
            // bounded poll is a precondition-wait, not an assertion retry.
            var declinedAfter = declinedBefore
            repeat(12) {
                declinedAfter = ares.folderDropDeclinedCount()
                if (declinedAfter > declinedBefore) return@repeat
                Thread.sleep(100)
            }
            Log.i(
                TAG,
                "s4 attempt $attempt: sawPreview=$sawPreview " +
                    "declined=$declinedBefore->$declinedAfter",
            )
            if (sawPreview && declinedBefore >= 0 && declinedAfter > declinedBefore) {
                exercised = true
                break
            }
            Thread.sleep(400)
        }
        check(exercised) {
            "the S4 scenario could not be produced in $MAX_ATTEMPTS attempts (preview open AND " +
                "decline branch run); nothing about S4 was measured"
        }

        // The assertion, from both sides of the would-be write. Edit mode is still active from the
        // drag and a tap routes differently under it, so leave it before reopening the folder.
        ares.waitFor("grid to settle after the drop") { ares.folderIcons().isEmpty() }
        ares.exitEditMode()
        Thread.sleep(400)
        val topLevelAfter = ares.homeOrder().size
        assertThat(ares.openFolder()).isTrue()
        ares.waitFor("folder to reopen") { ares.folderIcons().isNotEmpty() }
        val folderCount = ares.folderIcons().size
        Log.i(TAG, "s4 topLevel=$topLevelBefore->$topLevelAfter folderCount=$folderCount")

        assertThat(topLevelAfter).isEqualTo(topLevelBefore)
        assertThat(folderCount).isEqualTo(3)
    }

    private companion object {
        const val DWELL_HANG_MS = 1_400L

        /**
         * The exit leg's duration. The decline moment is `exit + ~250ms settle`, and the grace
         * expires at `exit + 400ms`, so the margin is roughly `400 - travel - 250`. At 110ms that
         * was ~40ms and one loaded-emulator stutter blew it; 60ms doubles it while still giving
         * the injected stream a handful of MOVEs to register the exit.
         */
        const val EXIT_TRAVEL_MS = 60L

        /** Scenario attempts, NOT assertion retries. See AresFolderExitTest. */
        const val MAX_ATTEMPTS = 3
    }
}
