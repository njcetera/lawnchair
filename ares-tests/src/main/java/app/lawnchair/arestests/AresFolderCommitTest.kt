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

        var sawPreview = false
        val sampler = AresSampler(intervalMs = 40L) {
            ares.folderIcons().isNotEmpty().also { if (it) sawPreview = true }
        }
        sampler.start()
        ares.enterEditModeAndDrag(
            fromIndex = dragged.position,
            toIndex = folder.position,
            holdMs = 700,
            travelMs = 600,
            // Long enough for the 500ms dwell plus the open animation, with margin.
            hangMs = DWELL_HANG_MS,
            // Then OUT, and release on arrival -- well inside the 400ms grace, which is the point.
            secondTargetIndex = widget.position,
            secondTravelMs = EXIT_TRAVEL_MS,
        )
        sampler.stop()
        Log.i(TAG, "s4 sawPreview=$sawPreview")

        // Precondition: the folder actually opened under the dwell. A drag that never opened it
        // exercises nothing -- the D4 vacuity lesson, applied forward.
        assertThat(sawPreview).isTrue()

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
        const val EXIT_TRAVEL_MS = 110L
    }
}
