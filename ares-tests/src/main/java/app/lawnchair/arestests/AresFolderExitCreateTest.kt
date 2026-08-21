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
 * Ledger row 32 — owner-reported: dragging an app OUT of a folder and overlapping it with another
 * app never offers/creates a NEW folder. Spec A1 (folder creation) via the external pipeline.
 *
 * ## What this measures, and what it deliberately does not assume
 *
 * The mechanics say this SHOULD work: `AresFolderDrop.onExternalDragOver` feeds the same
 * `onDragPoint` dwell the in-grid pipeline uses, `kindOf(icon, icon)` answers CREATE, and
 * `AresHomeDrop.handleExternalDrop` consults `commitDrop` before placing. Two candidate breaks:
 *
 *  1. **The dwell cannot complete** because the §C4 drop-slot gap keeps REFLOWING the grid under
 *     a still finger — the target icon slides away mid-hover and every slide disarms the dwell.
 *     The in-grid pipeline freezes reflow near the finger; the external one may not.
 *  2. **Nothing is broken mechanically** and the owner's report is spec A2's known gap: the offer
 *     is invisible while it arms (500ms still hover, no forming preview), so a person releases
 *     before it exists. Then the fix is A2's preview, not new plumbing.
 *
 * The probe separates them: it samples the tile under the finger across the hang (slide = case 1),
 * and asserts the spec outcome (a new folder exists after release). A FAIL with slide evidence is
 * case 1; a PASS is case 2 — either way the row gets a measured mechanism.
 *
 * Scenario-retry per the folder-drag arming rate (~2-in-5); assertions never retry.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresFolderExitCreateTest {

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
    fun pullingAnAppOutOfAFolderOntoAnIconCreatesAFolder() {
        var attempt = 0
        var result: Attempt? = null
        while (attempt < MAX_ATTEMPTS && result == null) {
            attempt++
            val r = attemptCreate()
            if (r.sawDrag) result = r else Log.i(TAG, "row32 attempt $attempt never armed; retrying")
        }
        val r = requireNotNull(result) {
            "no attempt in $MAX_ATTEMPTS armed a drag out of the folder; the scenario could not " +
                "be reproduced. That is NOT the same as folder creation failing."
        }

        Log.i(
            TAG,
            "row32 armed on attempt $attempt; targetAtStart=${r.targetStart} " +
                "targetAtEnd=${r.targetEnd} slidPx=${r.slidPx} " +
                "underFingerAtEnd=${r.underFingerAtEnd}",
        )
        Log.i(TAG, "row32 after: newFolders=${r.newFolderIds} targetStillTopLevel=${r.targetStillTop}")

        // The spec outcome (A1 by way of the external pipeline): the two icons merged. The dragged
        // item left folder 40 at drag start, so top-level arithmetic is: target icon replaced by a
        // NEW folder containing both.
        assertThat(r.newFolderIds).isNotEmpty()
        assertThat(r.targetStillTop).isFalse()
    }

    private data class Attempt(
        val sawDrag: Boolean,
        val targetStart: PointF,
        val targetEnd: PointF,
        val slidPx: Float,
        val underFingerAtEnd: String,
        val newFolderIds: List<String>,
        val targetStillTop: Boolean,
    )

    /** One press-hold-drag from inside the folder onto a grid ICON, dwell, release. */
    private fun attemptCreate(): Attempt {
        runCatching { ares.setFolderEdit(false) }
        ares.pressBack()
        ares.goHome()
        ares.exitEditMode()

        // The folder itself and the two widget rows cannot be creation targets; pick the first
        // plain icon that sits ABOVE the open folder's sheet so the exit leg has somewhere to go.
        // Resolved before the folder opens: the sheet covers the grid afterwards.
        val tiles = ares.tiles()
        val target = tiles.first {
            !it.isWidget && it.itemType != 2 && it.containerOnScreen.y < FOLDER_TOP_Y
        }
        val targetId = target.title.substringBefore('/')
        val ordersBefore = ares.homeOrder()
        val foldersBefore = ordersBefore.filter { it.endsWith("/type2") || it.contains("Stuff") }

        assertThat(ares.openFolder()).isTrue()
        ares.waitFor("folder to open") { ares.folderIcons().size >= 3 }
        val held = ares.folderIcons().first()

        var sawDrag = false
        val sampler = AresSampler(intervalMs = 40L) {
            ares.dragViewCount().also { if (it > 0) sawDrag = true }
        }
        sampler.start()
        AresGestures.pressHoldDragRelease(
            start = held.center(),
            holdMs = HOLD_MS,
            travelMs = TRAVEL_MS,
            target = { target.screenCenter() },
            // Long enough for DWELL_MS (500) plus the slide-vs-still question to answer itself.
            hangMs = HANG_MS,
        )
        sampler.stop()

        // Where did the target END UP, and what sits under the release point now? A big slide is
        // the reflow-under-a-still-finger mechanism; ~0 slide with no folder is a dwell that
        // never armed for another reason.
        Thread.sleep(600)
        val after = ares.tiles()
        val targetAfter = after.firstOrNull { it.title.startsWith("$targetId/") }
        val end = targetAfter?.screenCenter() ?: PointF(-1f, -1f)
        val slid = if (targetAfter != null) {
            kotlin.math.hypot(
                (end.x - target.screenCenter().x).toDouble(),
                (end.y - target.screenCenter().y).toDouble(),
            ).toFloat()
        } else {
            -1f
        }
        val release = target.screenCenter()
        val under = after.firstOrNull {
            release.x >= it.containerOnScreen.x &&
                release.x < it.containerOnScreen.x + it.size.x * it.scale &&
                release.y >= it.containerOnScreen.y &&
                release.y < it.containerOnScreen.y + it.size.y * it.scale
        }?.title ?: "nothing"

        ares.exitEditMode()
        Thread.sleep(400)
        val ordersAfter = ares.homeOrder()
        val foldersAfter = ordersAfter.filter { it.endsWith("/type2") || it.contains("Stuff") }
        val newFolders = foldersAfter.filterNot { it in foldersBefore }
        val targetStillTop = ordersAfter.any { it.startsWith("$targetId/") }

        return Attempt(
            sawDrag = sawDrag,
            targetStart = target.screenCenter(),
            targetEnd = end,
            slidPx = slid,
            underFingerAtEnd = under,
            newFolderIds = newFolders,
            targetStillTop = targetStillTop,
        )
    }

    private companion object {
        const val HOLD_MS = 900L
        const val TRAVEL_MS = 900L
        const val HANG_MS = 1_600L
        const val MAX_ATTEMPTS = 5

        /** The folder sheet's top when open; targets above this stay reachable. */
        const val FOLDER_TOP_Y = 1400f
    }
}
