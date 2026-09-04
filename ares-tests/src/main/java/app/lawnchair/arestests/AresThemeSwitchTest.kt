package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher must survive a dark-mode switch.
 *
 * ## Why this exists
 *
 * It did not. Measured 2026-09-03 (ledger row 76): `cmd uimode night yes|no` relaunches the
 * activity, and the destroy half died with a `NullPointerException` inside
 * `ViewGroup.dispatchDetachedFromWindow` under `DecorView.clearContentView` — a null slot in a child
 * array — taking `SIG: 9` and restarting the process on **4 to 5 of every 6 switches**. The cause
 * was `AresPanelAllAppsContainerView.releaseSearchPill()` removing the pill from the shared DragLayer
 * synchronously from inside its own `onDetachedFromWindow`, which compacts that array underneath the
 * teardown's own iteration. Fixed in `4d16cc2479`.
 *
 * Nothing guarded it, and it had been shipping. This project's own regression review found seven
 * mechanisms built and all seven decayed within three weeks, so a fix without a gate has a shelf
 * life. This is the gate.
 *
 * ## What it asserts, and why that is the right assertion
 *
 * The launcher's **pid**, before and after. A death is not subtle here — the process is killed and
 * respawned — and the suite runs OUT of process, so the runner is still alive to see it. Asserting
 * on the pid rather than on a screenshot or a log line means the check cannot quietly pass while
 * the thing it exists for is broken.
 *
 * UNFOLDED on purpose. The crash needed the app-list pane attached: folded measured **0/6** even on
 * the broken build, while unfolded measured 5/6. A folded run would be a vacuous green.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresThemeSwitchTest {

    private val driver = AresLauncherDriver()
    private var originalNight: String = ""

    @Before
    fun setUp() {
        driver.openTestChannel()
        originalNight = currentNight()
        Log.i(TAG, "original night mode = $originalNight")
    }

    @After
    fun tearDown() {
        if (originalNight.isNotEmpty()) {
            driver.shell("cmd uimode night $originalNight")
            settle()
        }
    }

    @Test
    fun theLauncherSurvivesRepeatedThemeSwitches() {
        val before = driver.launcherPid()
        assertWithMessage("launcher not running at the start; nothing to measure")
            .that(before).isNotEmpty()

        val deaths = ArrayList<String>()
        var previous = before
        for (i in 1..SWITCHES) {
            val target = if (i % 2 == 1) "yes" else "no"
            driver.shell("cmd uimode night $target")
            settle()
            val now = driver.launcherPid()
            Log.i(TAG, "switch $i -> night=$target: pid $previous -> $now")
            if (now != previous) {
                deaths += "switch $i (night=$target): pid $previous -> ${now.ifEmpty { "GONE" }}"
            }
            previous = now
        }

        assertWithMessage(
            "The launcher process died during a dark-mode switch -- ledger row 76 has regressed.\n" +
                deaths.joinToString("\n") { "  $it" } +
                "\nPre-fix this measured 4-5 deaths per 6 switches; the fix measured 0/8.",
        ).that(deaths).isEmpty()

        // A surviving pid is necessary but not sufficient: a launcher that is alive and wedged would
        // pass the check above. Require it to still answer.
        assertThat(driver.launcherPid()).isEqualTo(before)
        assertWithMessage("launcher alive but its test channel stopped answering")
            .that(driver.homeOrder()).isNotEmpty()
    }

    private fun currentNight(): String {
        // "Night mode: no" / "Night mode: yes"
        val out = driver.shell("cmd uimode night").trim()
        return out.substringAfterLast(':').trim().ifEmpty { "" }
    }

    /**
     * A theme switch CLEARS the home-activity preference, and the "Select a Home app" resolver then
     * steals the foreground -- measured, and it makes every later probe describe the resolver rather
     * than the launcher. Re-pin before looking at anything.
     */
    private fun settle() {
        Thread.sleep(SETTLE_MS)
        driver.shell(
            "cmd package set-home-activity " +
                "${driver.launcherPackage}/app.lawnchair.LawnchairLauncher",
        )
        driver.goHome()
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val TAG = "AresThemeSwitch"

        /** Pre-fix this crashed 4-5 times in 6, so four switches catch a regression with margin. */
        const val SWITCHES = 4
        const val SETTLE_MS = 4_000L
    }
}
