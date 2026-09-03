package app.lawnchair.arestests

import android.os.SystemClock
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
 * S4 (ledger row 15) on the WP surface — *a release OUTSIDE a folder must never file the item INTO
 * it*, and pulling a child out must land it on the grid. Folder spec B2/B3.
 *
 * ## What the original caught, and why the surface moved
 *
 * On the OVERLAY folder, a dwell opened the folder and leaving it posted a 400ms grace
 * (`EXIT_CLOSE_MS`) before it closed — the grace being about whether the FOLDER stays open. But
 * `commitDrop`'s open-folder branch keyed only on `isOpen()`, so a release during that window —
 * after the user had deliberately pulled the icon OUT — still resolved INTO the folder. Everyone
 * decelerates and releases promptly after pulling out, so that window was the common case, not a
 * corner. The WP migration deleted the overlay and the grace with it, so the old gesture test could
 * no longer open a folder at all (ledger row 67) and timed out every run.
 *
 * The INVARIANT is spec, not mechanism, so it survives: *only a deliberate drop inside a folder adds
 * to it.* On the WP surface a folder expands inline and drop resolution is decided by
 * `AresWpMembership`, so B2/B3 is now a statement about what that classifier returns.
 *
 * ## Why the classifier and not a gesture
 *
 * Measured on emulator-5554 while porting the sibling D4 test: a synthetic drag of a folder child
 * out onto the grid changed neither the home order nor the membership, while edit mode armed — the
 * drag either never cleared touch slop or resolved to a non-extract action. A gesture that cannot be
 * made to perform the action cannot be trusted to prove it is declined either. `ares-wp-resolve-drag`
 * runs the SAME classifier the real drop path uses, without performing a drag, so it is deterministic
 * where the gesture is not. The cost is honest and stated: this covers the DECISION, not the commit
 * animation or the DB write that follows it.
 *
 * ## Why these cases cannot pass vacuously
 *
 * They are each other's control. The measured table, folder 40 expanded, three apps inside:
 *
 * ```
 *   outside -> a CHILD      AddToFolder(40)      the deliberate drop inside
 *   outside -> empty grid   None                 a release outside files nothing
 *   child   -> empty grid   Extract(40)          pulled out, lands on the grid
 *   child   -> a sibling    ReorderInFolder(40)
 * ```
 *
 * A classifier stuck on `None` fails [aDropOnAChildAddsToTheFolder]; one stuck on `AddToFolder`
 * fails [aReleaseOutsideTheFolderAddsNothing]. Neither test can be green on a build where the
 * distinction has collapsed, which is exactly what row 15 was.
 *
 * SKIPs when the fixture has no WP folder, or when the grid has no app outside it to drag.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderCommitTest {

    private val TAG = "AresFolderCommit"
    private lateinit var ares: AresLauncherDriver

    @Before
    fun setUp() {
        ares = AresLauncherDriver()
        ares.openTestChannel()
        ares.goHome()
        ares.exitEditMode()
    }

    @After
    fun tearDown() {
        runCatching { ares.exitEditMode() }
    }

    /** B2/B3, the positive half: dropping an outside app onto a child files it into the folder. */
    @Test
    fun aDropOnAChildAddsToTheFolder() {
        val s = scene()
        val action = ares.wpResolveDrag(s.outsideId, s.childId.toString())
        Log.i(TAG, "outside ${s.outsideId} -> child ${s.childId} = $action")
        assertWithMessage("a deliberate drop onto a folder child should file the app into the folder")
            .that(action).startsWith("AddToFolder")
    }

    /**
     * B2/B3, the half row 15 broke: releasing an outside app on empty grid must file NOTHING.
     *
     * This is the direct descendant of the original defect — there, a release outside the folder
     * still resolved into it because the commit keyed on the folder merely being open.
     */
    @Test
    fun aReleaseOutsideTheFolderAddsNothing() {
        val s = scene()
        val onEmpty = ares.wpResolveDrag(s.outsideId, "none")
        val onFolderItself = ares.wpResolveDrag(s.outsideId, s.folderId.toString())
        Log.i(TAG, "outside ${s.outsideId} -> none = $onEmpty ; -> folder ${s.folderId} = $onFolderItself")
        assertWithMessage("releasing an app on empty grid must not file it into the open folder")
            .that(onEmpty).isEqualTo("None")
        assertWithMessage("releasing an app on the folder's own tile must not file it in either")
            .that(onFolderItself).isEqualTo("None")
    }

    /** The other direction, and the old test's name: pulled out and released, the child lands out. */
    @Test
    fun pullingAChildOutLandsItOnTheGrid() {
        val s = scene()
        val action = ares.wpResolveDrag(s.childId, "none")
        Log.i(TAG, "child ${s.childId} -> none = $action")
        assertWithMessage("a child released on empty grid should leave the folder, not stay in it")
            .that(action).startsWith("Extract")
    }

    private data class Scene(val folderId: Int, val childId: Int, val outsideId: Int)

    /**
     * Expands the folder and names one child of it and one app that is NOT in it.
     *
     * The outside app is taken from BEFORE the folder in the home order, which is the reliable way
     * to get a non-member: the children render as the run immediately AFTER the folder, so anything
     * ahead of it is top level. Widgets are skipped — `type4` is not draggable into a folder.
     */
    private fun scene(): Scene {
        val folderId = ares.findWpFolderId()
        assumeTrue("no WP folder on the home grid", folderId != null)
        folderId!!
        val responses = mutableListOf<String>()
        // A `for` with a real `break`, NOT repeat + return@repeat -- that returns from the LAMBDA,
        // i.e. continues, so the loop would keep toggling past the state it just reached and could
        // leave the folder collapsed for the queries below.
        var expanded = false
        for (i in 0 until 6) {
            val r = ares.wpExpand(folderId)
            responses += r
            if (r.startsWith("expanded=true")) {
                expanded = true
                break
            }
            SystemClock.sleep(400)
        }
        assumeTrue("folder would not expand; wp-expand said $responses", expanded)

        val order = ares.homeOrder()
        val folderIndex = order.indexOfFirst { it.substringBefore('/') == folderId.toString() }
        assumeTrue("expanded folder not in the home order", folderIndex >= 0)
        val childId = order.getOrNull(folderIndex + 1)?.substringBefore('/')?.toIntOrNull()
        assumeTrue("folder has no child after it in the order", childId != null)
        val outsideId = order.take(folderIndex)
            .firstOrNull { !it.endsWith("/type4") }
            ?.substringBefore('/')?.toIntOrNull()
        assumeTrue("no non-widget app outside the folder to drag", outsideId != null)
        return Scene(folderId, childId!!, outsideId!!)
    }
}
