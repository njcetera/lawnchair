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
 * D4, on the WP surface — *holding an app inside a folder must not drag it out of the folder*.
 *
 * ## What this is a port OF, and what moved
 *
 * The original D4 opened an OVERLAY folder and asserted that a bare hold on one of its icons did
 * not start a drag. The reported symptom was that the held app lost its edit chrome (frost box, ×,
 * !) while every sibling kept theirs — because a drag really had armed: `AresFolderDrag.DragStarter`
 * was installed mid-gesture, so the long-press had already eaten the DOWN and the first MOVE was
 * measured against a phantom origin at `0,0`. `hypot(107,119) = 160px` cleared touch slop instantly,
 * `Folder.onDragStart` lifted the icon into a `DragView`, and the vacated cell's chrome was torn
 * down. The fix was `haveOrigin`: the first MOVE after a mid-gesture install SETS the reference
 * point instead of being measured against one that never existed.
 *
 * The WP migration deleted the overlay, so the old test could never open a folder again and timed
 * out every run (ledger row 67). The INVARIANT did not go away — it moved. A WP folder expands
 * inline and its children ARE home-grid tiles, so "hold an app inside a folder" is now "hold a tile
 * that belongs to an expanded folder", and the thing that must not happen is that the child gets
 * dragged out (extract-by-drag, Phase 3 #4, is the modern equivalent of the overlay's `onDragStart`).
 *
 * ## Why this is not a duplicate of AresHomeReorderTest
 *
 * `AresHomeReorderTest.longPressEntersEditModeWithoutStartingADrag` asserts the same invariant for
 * an ORDINARY home tile. This one holds a tile that is a folder CHILD while the folder is expanded,
 * which is a different arbitration path: the expanded surface additionally has extract-by-drag
 * watching the same gesture. A bare hold must arm edit mode and leave membership alone.
 *
 * ## The observable
 *
 * Two things, checked together, because either alone can read correct on a broken build:
 *  - [AresLauncherDriver.homeOrder] is UNCHANGED — an extracted child re-ranks the grid.
 *  - the folder's `contents` count is unchanged — read from the collapse at the end, since
 *    `ares-wp-expand` is a toggle and cannot be read without moving the state.
 *
 * ## Falsification, and why the control exists
 *
 * The obvious falsification -- make the gesture a REAL drag-out and watch it fail -- did NOT work:
 * with 600ms of travel over 700px the order and the membership were byte-identical, and edit mode
 * had armed (`editMode=true`), so the drag either never cleared touch slop or resolved to a
 * non-extract action. A gesture that cannot be made to break the invariant cannot prove the
 * assertion sees it. Hence [extractionIsReachableForThatSameChild], which asserts through the
 * classifier that an Extract IS the resolution for exactly this child, so a green above means "the
 * hold declined an action that was available" rather than "nothing could have happened anyway".
 *
 * SKIPs when the fixture has no WP folder; a check that could not run must be louder than one that
 * failed, never quieter.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderHoldTest {

    private val TAG = "AresFolderHold"
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

    @Test
    fun aBareHoldOnAFolderChildDoesNotPullItOut() {
        val folderId = ares.findWpFolderId()
        assumeTrue("no WP folder on the home grid to expand", folderId != null)
        folderId!!

        val contentsBefore = expand(folderId)
        assumeTrue("folder would not expand; wp-expand said $lastResponses", contentsBefore != null)
        assumeTrue("folder is empty, so it has no child to hold", (contentsBefore ?: 0) > 0)

        val orderBefore = ares.homeOrder()
        val folderIndex = orderBefore.indexOfFirst { it.substringBefore('/') == folderId.toString() }
        assumeTrue("expanded folder not found in the home order", folderIndex >= 0)
        // A WP folder's children render as the tiles immediately after it, so the next position is
        // a child of THIS folder rather than an unrelated app.
        val childPos = folderIndex + 1
        val child = ares.tiles().firstOrNull { it.position == childPos }
        assumeTrue("no child tile at position $childPos", child != null)
        Log.i(TAG, "folder=$folderId contents=$contentsBefore child='${child!!.title}' order=$orderBefore")

        // A bare hold: zero travel, so nothing past touch slop is ever produced. On the broken
        // build the phantom 0,0 origin made the first MOVE read as a 160px drag anyway.
        AresGestures.pressHoldDragRelease(
            start = child.screenCenter(),
            holdMs = 800,
            travelMs = 0,
            target = { ares.tiles().firstOrNull { it.position == childPos }?.screenCenter() ?: child.screenCenter() },
        )

        val orderAfter = ares.homeOrder()
        Log.i(TAG, "after hold: editMode=${ares.isEditMode()} order=$orderAfter")

        // THE PRECONDITION, asserted. folder-spec, "Why scripted input has been unreliable here":
        // *any folder test must assert that the state it intends to create actually exists before
        // asserting anything about behaviour.* Without this the test passes when the synthetic hold
        // silently degrades to a TAP -- which CLAUDE.md records happening on an aged emulator, where
        // folder long-press arming fails 0-for-5 while grid long-presses in the same suite still
        // work. A build where a bare hold DID pull the child out would then read green, because
        // nothing happened at all. The predecessor of this test carried the same check and its
        // comment called it load-bearing; the port dropped it.
        check(ares.isEditMode()) {
            "the bare hold never armed edit mode, so nothing about D4 was measured -- " +
                "this is the aged-emulator arming decay, not a product result"
        }
        assertWithMessage("a bare hold on a folder child re-ranked the grid, i.e. it pulled the child out")
            .that(orderAfter).isEqualTo(orderBefore)

        ares.exitEditMode()
        // Collapsing returns the live contents count; equality proves membership never moved.
        val contentsAfter = collapse(folderId)
        assertWithMessage("folder membership changed across a bare hold")
            .that(contentsAfter).isEqualTo(contentsBefore)
    }

    /**
     * The control: extraction really IS reachable for the tile the hold test presses.
     *
     * Without this, `aBareHoldOnAFolderChildDoesNotPullItOut` is not coverage — it would pass just
     * as happily on a build where a child can never leave its folder at all, which is the vacuous
     * green this project keeps getting caught by. Measured while writing it, folder 40 expanded:
     *
     * ```
     *   900 -> none  Extract(40)          the drag the hold must NOT perform
     *   900 -> 901   ReorderInFolder(40)  a sibling drop is a reorder, not an extract
     * ```
     *
     * `ares-wp-resolve-drag` runs the classifier WITHOUT performing a drag, which is deliberate: an
     * actual synthetic drag-out of a child changed nothing here (it either never armed past slop or
     * resolved to a non-extract action), so a gesture makes an unreliable control on this surface
     * while the classifier is deterministic.
     */
    @Test
    fun extractionIsReachableForThatSameChild() {
        val folderId = ares.findWpFolderId()
        assumeTrue("no WP folder on the home grid to expand", folderId != null)
        folderId!!
        val contents = expand(folderId)
        assumeTrue("folder would not expand; wp-expand said $lastResponses", contents != null)

        val order = ares.homeOrder()
        val folderIndex = order.indexOfFirst { it.substringBefore('/') == folderId.toString() }
        assumeTrue("expanded folder not found in the home order", folderIndex >= 0)
        val childId = order.getOrNull(folderIndex + 1)?.substringBefore('/')?.toIntOrNull()
        assumeTrue("no child id after the folder", childId != null)

        val ontoEmpty = ares.wpResolveDrag(childId!!, "none")
        Log.i(TAG, "control: child $childId -> none = $ontoEmpty")
        assertWithMessage(
            "dragging this child onto empty grid should classify as an Extract; if it does not, the " +
                "bare-hold test above proves nothing because extraction was never possible",
        ).that(ontoEmpty).startsWith("Extract")
    }

    /**
     * Drives the folder to [wanted] and returns the contents count it reported, or null.
     *
     * `ares-wp-expand` is a TOGGLE, so reaching a known state costs at most one extra call — but it
     * is driven in a bounded retry rather than a fixed two, because the model can rebind underneath
     * (a re-seed, a reload) and answer `no-folder(id)` for a beat. Every response seen is recorded
     * in [lastResponses] so a SKIP says what it actually saw instead of just asserting it gave up.
     */
    private fun drive(folderId: Int, wanted: Boolean): Int? {
        val marker = "expanded=$wanted"
        repeat(6) {
            val r = ares.wpExpand(folderId)
            lastResponses += r
            if (r.startsWith(marker)) return contentsOf(r)
            SystemClock.sleep(400)
        }
        return null
    }

    private fun expand(folderId: Int): Int? = drive(folderId, wanted = true)

    private fun collapse(folderId: Int): Int? = drive(folderId, wanted = false)

    private val lastResponses = mutableListOf<String>()

    private fun contentsOf(response: String): Int? =
        Regex("contents=(\\d+)").find(response)?.groupValues?.get(1)?.toIntOrNull()
}
