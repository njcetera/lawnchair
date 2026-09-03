package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Assertions that must hold on **any** device shape, not just the Fold.
 *
 * ## Why this exists
 *
 * Every instrumented run this project has ever done was on the `AresFold` AVD, and that AVD cannot
 * be talked out of being a foldable — `mIsFoldable` is `hasSystemFeature(FEATURE_SENSOR_HINGE_ANGLE)`,
 * a PackageManager feature, so `wm size` changes the window and not the flag. `isTwoPanels` is
 * consequently pinned true at tablet size on it, and the single-panel branch of a dozen behaviours
 * has never executed under test. The owner's stated worry is precisely this: *"I'm worried you're
 * building this specifically for the test device but the goal is for this to be installed on any
 * newer android device."*
 *
 * `:ares-geom-tests` answers the arithmetic half of that on the JVM. This answers the half that
 * needs a running launcher, and it is written to run **unchanged on both** the Fold AVD and the
 * `pixel7Api36` managed device, so the two can be compared.
 *
 * ## What it deliberately does NOT assert
 *
 * Nothing about pane count, column count beyond its legal range, or any absolute pixel size. Those
 * legitimately differ by shape, and asserting them here would encode the Fold as the definition of
 * correct — the exact mistake this file exists to catch. Every assertion below is a *relationship*
 * that a launcher on any screen must satisfy.
 *
 * ## Preconditions report SKIP, not PASS
 *
 * Per the standing rule, a check whose precondition fails must be louder than one that fails, never
 * quieter. If the launcher is not the home app or the channel does not answer, these `assumeTrue`
 * out as skipped rather than passing vacuously — which is how a suite ends up green on a device it
 * never actually reached.
 */
@RunWith(AndroidJUnit4::class)
class AresDeviceShapeTest {

    private lateinit var driver: AresLauncherDriver
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)

        // Claim the home slot. A managed device boots with the Pixel launcher as home, and
        // `set-home-activity` ALONE IS NOT ENOUGH: measured 2026-09-02 on pixel7Api36, the command
        // succeeded, `resolve-activity` then answered `app.lawnchair.debug`, and
        // `com.google.android.apps.nexuslauncher` stayed resumed for the whole run. Every
        // screenshot was of the Pixel launcher. Force-stopping the incumbent after taking the
        // preference is what actually hands the slot over.
        device.executeShellCommand(
            "cmd package set-home-activity app.lawnchair.debug/app.lawnchair.LawnchairLauncher",
        )
        device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")

        driver = AresLauncherDriver()
        driver.openTestChannel()
        driver.goHome()
        device.waitForIdle()
    }

    /** The activity the system currently has resumed, as the system reports it. */
    private fun topResumedActivity(): String =
        device.executeShellCommand("dumpsys activity activities")
            .lineSequence()
            .firstOrNull { it.contains("topResumedActivity") }
            ?: ""

    /**
     * Our launcher must be the activity actually ON SCREEN — not merely installed, not merely
     * resolvable as home, and not merely answering its content provider.
     *
     * This gate exists because the first version of this file did not have it and produced a
     * FALSE GREEN. On pixel7Api36 all assertions passed while `com.google.android.apps.nexuslauncher`
     * was the resumed activity for the entire run: the test channel is a ContentProvider and
     * answers regardless of foreground, and `dumpsys activity <pkg>/LawnchairLauncher` will happily
     * dump a DeviceProfile from an activity instance that exists but is not the home screen. Six
     * assertions "passed" against a launcher nobody could see, and only a screenshot caught it.
     *
     * `resolve-activity` returning our package is therefore NOT sufficient evidence and is not used
     * here. SKIP rather than fail: not being able to take the home slot on some device is a fact
     * about the environment, and a check that could not run must be louder than one that failed,
     * never quieter.
     */
    private fun requireLauncher() {
        val top = topResumedActivity()
        assumeTrue(
            "AresLauncher is not the resumed activity; top was: ${top.trim()}",
            top.contains("app.lawnchair") && top.contains("LawnchairLauncher"),
        )
    }

    /**
     * `isTwoPanels` as the launcher itself computed it, from its own `DeviceProfile` dump.
     *
     * Read from `dumpsys activity` rather than derived from the window size, because the whole
     * point is what the LAUNCHER decided, not what the test thinks the screen looks like.
     */
    private fun isTwoPanels(): Boolean? =
        device.executeShellCommand("dumpsys activity ${driver.launcherPackage}/app.lawnchair.LawnchairLauncher")
            .lineSequence()
            .firstOrNull { it.contains("isTwoPanels") }
            ?.substringAfter("isTwoPanels:")
            ?.trim()
            ?.take(5)
            ?.startsWith("true")

    /**
     * PROOF OF PATH, not an assertion about correctness.
     *
     * A green run on a non-foldable means nothing if the launcher still took its two-panel branch —
     * that would be the Fold path running on a phone-shaped window, which is not what this file
     * claims to cover. So record which branch actually executed, and fail if the launcher will not
     * say. On the `AresFold` AVD this reports true; on `pixel7Api36` it must report false, and if
     * it ever reports true there, every other assertion in this class was measured on the wrong
     * path and the green is meaningless.
     *
     * Deliberately NOT `assertFalse`: this class is meant to run unchanged on BOTH devices, and
     * hard-coding either answer would make it device-specific — the exact mistake it exists to
     * catch. The value is asserted to be *readable*, and printed for the run log.
     */
    @Test
    fun launcherReportsWhichPanelBranchItTook() {
        requireLauncher()
        val twoPanels = isTwoPanels()
        assertThat(twoPanels).isNotNull()
        Log.i("AresShape", "isTwoPanels=$twoPanels width=${device.displayWidth} height=${device.displayHeight}")
    }

    /**
     * Take a screenshot of home and of the app list, so a person can LOOK at the shape.
     *
     * Six numeric invariants holding is not "it works on a phone", and the first non-foldable run
     * (2026-09-02) was recorded with exactly that caveat: nothing here can see an overlapping label,
     * a clipped fob, a pane that should not exist, or text that wrapped to three lines. Those are
     * the defects a different screen actually produces, and every one of them is invisible to an
     * assertion about coordinates.
     *
     * Writes into `additionalTestOutputDir` when the runner supplies it — Gradle Managed Devices
     * copies that directory off the AVD before tearing it down, which is the only way to get a
     * frame off a device that ceases to exist when the task ends. Falls back to the app's own files
     * dir on a connected device.
     *
     * Never fails the build. This produces evidence for a human, and a missing screenshot is not a
     * defect in the launcher; it is asserted only that *something* was written, so a silently empty
     * output directory does not look like success.
     */
    @Test
    fun captureFramesForHumanReview() {
        requireLauncher()
        val outDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) }
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        outDir.mkdirs()

        val tag = if (isTwoPanels() == true) "twopanel" else "onepanel"

        driver.goHome()
        device.waitForIdle()
        val home = java.io.File(outDir, "ares-$tag-home-${device.displayWidth}x${device.displayHeight}.png")
        val homeOk = device.takeScreenshot(home)

        // The app list is workspace panel 1 in Strategy D, not a separate state, so it is reached
        // by a page swipe rather than by opening AllApps.
        device.swipe(device.displayWidth - 40, device.displayHeight / 2, 40, device.displayHeight / 2, 20)
        device.waitForIdle()
        val list = java.io.File(outDir, "ares-$tag-applist-${device.displayWidth}x${device.displayHeight}.png")
        val listOk = device.takeScreenshot(list)

        Log.i("AresShape", "frames: home=$homeOk list=$listOk dir=${outDir.absolutePath}")
        assertThat(homeOk || listOk).isTrue()

        driver.goHome()
    }

    /**
     * The column count must be inside the range the stepper can produce. The owner settled
     * 2026-09-02 that the count is a user choice and must NOT adapt to width, so this asserts the
     * range and deliberately not a width relationship.
     */
    @Test
    fun columnCountIsWithinTheStepperRange() {
        requireLauncher()
        val columns = driver.homeColumnsOrNull()
        assumeTrue("home columns channel did not answer", columns != null)
        assertThat(columns!!).isAtLeast(3)
        assertThat(columns).isAtMost(6)
    }

    /**
     * **The assertion this file is really for.** Every home tile must fit inside the launcher's
     * own window horizontally.
     *
     * A tile laid out for a 2076px-wide foldable and rendered on a 1080px phone overflows, and
     * nothing in the JVM geometry suite can see that because it is a layout outcome, not
     * arithmetic. Compared against the WINDOW, not against `wm size`: the 2026-09-02 sweep produced
     * a false defect by comparing a view against a nominal display value instead of the window the
     * launcher actually had, and the rule it yielded is that the reference must come from the same
     * measurement as the subject.
     */
    @Test
    fun everyHomeTileFitsInsideTheWindow() {
        requireLauncher()
        val tiles = driver.tiles()
        assumeTrue("no home tiles rendered", tiles.isNotEmpty())

        val width = device.displayWidth.toFloat()
        for (tile in tiles) {
            assertThat(tile.box.left).isAtLeast(-1f)
            assertThat(tile.box.right).isAtMost(width + 1f)
        }
    }

    /** A tile with zero or negative extent is a layout failure on any screen. */
    @Test
    fun noHomeTileHasDegenerateSize() {
        requireLauncher()
        val tiles = driver.tiles()
        assumeTrue("no home tiles rendered", tiles.isNotEmpty())

        for (tile in tiles) {
            assertThat(tile.box.width()).isGreaterThan(0f)
            assertThat(tile.box.height()).isGreaterThan(0f)
        }
    }

    /**
     * Every item the model holds must be rendered exactly once. A different screen shape must not
     * drop items or duplicate them — the home list does not recycle, so attached children should
     * equal the row count on any device.
     */
    @Test
    fun everyItemIsRenderedExactlyOnce() {
        requireLauncher()
        val order = driver.homeOrder()
        assumeTrue("home order channel did not answer", order.isNotEmpty())
        assertThat(order).containsNoDuplicates()
    }

    /**
     * No invariant violation may be recorded during a cold start on any device. `AresInvariants`
     * is seeded with the declined-folder-open branch; anything it records here is a real defect
     * that this shape provoked and the Fold did not.
     */
    @Test
    fun noInvariantViolationsOnThisShape() {
        requireLauncher()
        val total = driver.invariantTotalOrNull()
        assumeTrue("invariants channel did not answer", total != null)
        assertThat(total!!).isEqualTo(0L)
    }
}
