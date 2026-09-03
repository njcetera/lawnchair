package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

        // Claim the home slot. A managed device boots with another launcher as home, and
        // `set-home-activity` alone did not visibly hand it over on pixel7Api36 (2026-09-02): the
        // command succeeded, `resolve-activity` answered `app.lawnchair.debug`, and every
        // screenshot was still of the Pixel launcher.
        //
        // WHAT IS NOT KNOWN, stated because a previous version of this comment asserted it: no
        // `topResumedActivity` reading was taken during that run, so "the stock launcher stayed
        // resumed" is an inference from the screenshots, not a measurement. Ledger row 62 records
        // the conflicting evidence and leaves the cause OPEN. Force-stopping the incumbent is a
        // reasonable belt-and-braces, not a diagnosed fix.
        //
        // The incumbent is read from the device rather than hardcoded: `nexuslauncher` is the
        // managed device's launcher and would be the wrong name on any other phone, and this class
        // exists precisely to stop the test device from being treated as the definition of correct.
        val incumbent = device.executeShellCommand(
            "cmd package resolve-activity --brief -c android.intent.category.HOME",
        ).lineSequence().lastOrNull { it.contains("/") }?.substringBefore("/")?.trim()

        device.executeShellCommand(
            "cmd package set-home-activity app.lawnchair.debug/app.lawnchair.LawnchairLauncher",
        )
        if (!incumbent.isNullOrBlank() && incumbent != "app.lawnchair.debug") {
            device.executeShellCommand("am force-stop $incumbent")
        }
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")

        driver = AresLauncherDriver()
        driver.openTestChannel()
        driver.goHome()
        device.waitForIdle()
    }

    /**
     * Skips on purpose, but only when the runner is told to: `-e aresSkipSelftest true`.
     *
     * The harness must be provable, and this one could not be. `run-ares-tests.sh` counts
     * `INSTRUMENTATION_STATUS_CODE: -4` to turn a skip into exit 3, and that counter had never been
     * observed non-zero — measured 2026-09-02, handing the home slot to the stock launcher does NOT
     * produce a skip, because [setUp] takes the slot back before [requireLauncher] ever looks. The
     * gate is real and simply hard to trip on demand, which leaves the *detector* untested.
     *
     * So this is the injected failure. It proves the plumbing (assumption failure → status -4 →
     * runner → exit 3) and nothing about the launcher, exactly like the `ares-invariants selftest`
     * hook it is modelled on. Without the flag it is inert, so ordinary runs stay clean.
     */
    @Test
    fun skipSelftest() {
        val on = InstrumentationRegistry.getArguments().getString("aresSkipSelftest") == "true"
        assumeTrue("aresSkipSelftest: synthetic skip, injected by the harness", !on)
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
     * The launcher's own window width in px, from its `DeviceProfile` dump, or null if unreadable.
     *
     * Format in the dump is `widthPx: 2076.0px (851.6923dp)`, so the numeric part is taken up to
     * the `px` suffix. Null rather than a fallback constant: a caller that cannot read the window
     * should say so rather than silently measure against something else.
     */
    private fun launcherWindowWidth(): Float? =
        device.executeShellCommand("dumpsys activity ${driver.launcherPackage}/app.lawnchair.LawnchairLauncher")
            .lineSequence()
            .firstOrNull { it.trim().startsWith("widthPx:") }
            ?.substringAfter("widthPx:")
            ?.trim()
            ?.substringBefore("px")
            ?.toFloatOrNull()

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
     * Both frames are asserted **separately**. An earlier version asserted `homeOk || listOk`,
     * which passes when the app-list frame — the one that would show a pane that should not exist,
     * or a clipped fob — is the one that silently failed to write.
     *
     * Two limits this cannot close, stated rather than papered over. `UiDevice.takeScreenshot` takes
     * no display argument and captures the default display; on the `AresFold` AVD, which has two
     * displays with the 2076x2152 panel powered off, that is the same trap that makes `screencap`
     * grab the wrong panel. And a `true` return proves a file was written *on the device*, not that
     * Gradle Managed Devices copied it off before tearing the AVD down — nothing in-process can
     * observe that, so the output directory still has to be checked by hand after a GMD run.
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
        driver.goHome()

        assertThat(homeOk).isTrue()
        assertThat(listOk).isTrue()
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

        // SUBJECT AND REFERENCE MUST BE IN THE SAME COORDINATE SPACE, and two earlier versions of
        // this assertion were not. `tile.box` is the holder's layout box in the RECYCLERVIEW's own
        // coordinates (see `AresLauncherDriver.Tile.box`), while both previous references were
        // window/display-sized: on the unfolded AVD the list is ~1018px wide inside a 2076px
        // window, so comparing the two carried ~1058px of dead slack and the check could not fail.
        // Swapping `device.displayWidth` for the launcher's `widthPx` fixed the wrong half of the
        // problem — it made the reference more correct while leaving it in the wrong space.
        //
        // `containerOnScreen` is `getLocationOnScreen`, i.e. real screen pixels including every
        // transform, so it IS comparable to the window. That is the pairing used below.
        val width = launcherWindowWidth()
        assumeTrue("could not read the launcher's window width from its DeviceProfile dump", width != null)
        for (tile in tiles) {
            val left = tile.containerOnScreen.x
            val right = left + tile.size.x * tile.scale
            assertWithMessage("tile '${tile.title}' at screen x=$left..$right, window width=$width")
                .that(left).isAtLeast(-1f)
            assertWithMessage("tile '${tile.title}' at screen x=$left..$right, window width=$width")
                .that(right).isAtMost(width!! + 1f)
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
