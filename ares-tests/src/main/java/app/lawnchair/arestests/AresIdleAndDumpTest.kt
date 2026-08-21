package app.lawnchair.arestests

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Claim 1, measured rather than argued.
 *
 * `CLAUDE.md` records that `adb shell uiautomator dump` fails whenever edit mode is active -- the
 * float animator keeps the looper busy -- and writes no file, which is one of the reasons the
 * PowerShell harness runs with `animator_duration_scale 0`. That has a cost: `AresEditWiggle.start`
 * and `AresEditMotion.displaceTo` both early-out on `!ValueAnimator.areAnimatorsEnabled()`, so at
 * scale 0 the float and the reflow spring are not merely invisible, they do not run.
 *
 * The claim under test is that the uiautomator **library** does not have that failure mode, because
 * `QueryController.waitForIdle` catches its own `TimeoutException` and proceeds:
 *
 * ```
 * try { mDevice.getUiAutomation().waitForIdle(QUIET_TIME..., timeout); }
 * catch (TimeoutException e) { Log.w(TAG, "Could not detect idle state."); }
 * ```
 *
 * This test runs both, back to back, in the same never-idle state.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AresIdleAndDumpTest {

    private val TAG = "AresSpike"
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
    fun libraryQueriesWorkWhileTheEditFloatIsRunning() {
        // Enter edit mode with a bare long-press and LEAVE IT ON. The float is now orbiting every
        // tile every frame, which is the state that breaks the shell tool.
        AresGestures.pressHoldDragRelease(
            start = ares.tiles().first { it.position == 0 }.screenCenter(),
            holdMs = 800,
            travelMs = 0,
            target = { ares.tiles().first { it.position == 0 }.screenCenter() },
        )
        assertThat(ares.isEditMode()).isTrue()

        // A: the shell tool, exactly as the PowerShell harness invokes it.
        val shellOut = device.executeShellCommand("uiautomator dump /sdcard/ares-spike-dump.xml")
        val shellSize = device.executeShellCommand("wc -c < /sdcard/ares-spike-dump.xml").trim()
        Log.i(TAG, "SHELL uiautomator dump -> '${shellOut.trim()}' file bytes=$shellSize")

        // B: the library, in-process, same moment, same never-idle looper.
        val t0 = System.currentTimeMillis()
        val objects = device.findObjects(By.pkg(ares.launcherPackage))
        val findMs = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val buffer = ByteArrayOutputStream()
        device.dumpWindowHierarchy(buffer)
        val dumpMs = System.currentTimeMillis() - t1

        Log.i(
            TAG,
            "LIBRARY findObjects=${objects.size} in ${findMs}ms; " +
                "dumpWindowHierarchy=${buffer.size()} bytes in ${dumpMs}ms; " +
                "editMode=${ares.isEditMode()}",
        )

        // The library must produce a real hierarchy while the float is running. That is the whole
        // claim; if this fails, the framework inherits the harness's blind spot.
        assertThat(buffer.size()).isGreaterThan(1000)
        assertThat(objects).isNotEmpty()
        assertThat(ares.isEditMode()).isTrue()
    }

    /**
     * The float and the reflow spring are gated on `ValueAnimator.areAnimatorsEnabled()`, which is
     * driven by `animator_duration_scale`. Records what the scales actually are during this run so
     * no later reader has to take it on trust.
     */
    @Test
    fun recordsAnimationScales() {
        listOf(
            "animator_duration_scale",
            "window_animation_scale",
            "transition_animation_scale",
        ).forEach {
            Log.i(TAG, "SCALE $it = ${device.executeShellCommand("settings get global $it").trim()}")
        }
    }
}
