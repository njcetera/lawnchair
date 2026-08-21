package app.lawnchair.arestests

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The gesture substrate, lifted verbatim in shape from AOSP TAPL's `LauncherInstrumentation`.
 *
 * ## Why this and not `adb shell input`
 *
 * The PowerShell harness sends one `adb shell input motionevent` per event. Each of those runs as
 * **its own process** and stamps a **fresh `downTime`**, so the launcher receives a stream of
 * unrelated one-event gestures rather than one gesture. That is not a theoretical objection: it is
 * recorded in `AresHomeListView.heldLongEnough`'s doc, which had to stop reading
 * `MotionEvent.getDownTime()` and keep its own `SystemClock.uptimeMillis()` stamp because every
 * synthetic MOVE reported `eventTime - downTime == 0`. Product code was changed to accommodate a
 * broken harness.
 *
 * Here every event in a gesture carries the SAME `downTime`, MOVEs are interpolated at
 * [GESTURE_STEP_MS], and each event is injected with `UiAutomation.injectInputEvent(event, sync)`
 * so the call returns only once the event has been dispatched.
 */
object AresGestures {

    /** TAPL's own cadence: one MOVE per frame at 60Hz. */
    const val GESTURE_STEP_MS = 16L

    private val uiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float):
        MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun send(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val e = event(downTime, eventTime, action, x, y)
        try {
            check(uiAutomation.injectInputEvent(e, true)) { "injectInputEvent failed: $e" }
        } finally {
            e.recycle()
        }
    }

    /**
     * A single continuous gesture: press at [start], hold for [holdMs], then travel to whatever
     * [target] returns, then lift.
     *
     * [target] is a lambda and is evaluated **after** the hold on purpose. Entering edit mode
     * scales the whole grid by `AresHomeListView.EDIT_MODE_SCALE` (0.92), which moves every tile on
     * screen; a destination computed before the press is aimed at the pre-edit layout. The test
     * channel answers over IPC while this gesture is mid-flight, so the destination can be read
     * from the live, already-scaled grid -- something a shell-driven harness cannot do without
     * ending the gesture.
     *
     * [onStep] is called after each MOVE, so a caller can sample the launcher **during** the drag.
     */
    fun pressHoldDragRelease(
        start: PointF,
        holdMs: Long,
        travelMs: Long,
        target: () -> PointF,
        onStep: (step: Int, at: PointF) -> Unit = { _, _ -> },
        hangMs: Long = 0,
        onHangStep: (step: Int) -> Unit = { },
    ) {
        val downTime = SystemClock.uptimeMillis()
        var now = downTime
        send(downTime, now, MotionEvent.ACTION_DOWN, start.x, start.y)

        // Deliberately no MOVEs during the hold. A MOVE past the touch slop before the long-press
        // callback fires cancels it, and AresHomeListView additionally latches `pickUpForfeited`
        // for the rest of the gesture if the move beats PICKUP_HOLD_MS.
        SystemClock.sleep(holdMs)
        now += holdMs

        val end = target()
        val steps = (travelMs / GESTURE_STEP_MS).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            SystemClock.sleep(GESTURE_STEP_MS)
            now += GESTURE_STEP_MS
            val progress = i.toFloat() / steps
            val p = PointF(
                start.x + progress * (end.x - start.x),
                start.y + progress * (end.y - start.y),
            )
            send(downTime, now, MotionEvent.ACTION_MOVE, p.x, p.y)
            onStep(i, p)
        }

        // Hold motionless at the destination, still inside the same gesture. This is the state the
        // widget-swap feedback loop needs: the finger stops, the grid does not.
        val hangSteps = (hangMs / GESTURE_STEP_MS).toInt()
        for (i in 1..hangSteps) {
            SystemClock.sleep(GESTURE_STEP_MS)
            now += GESTURE_STEP_MS
            send(downTime, now, MotionEvent.ACTION_MOVE, end.x, end.y)
            onHangStep(i)
        }

        send(downTime, now, MotionEvent.ACTION_UP, end.x, end.y)
    }

    /**
     * One continuous gesture through [points], no hold: DOWN at the first, interpolated MOVEs
     * through each leg, UP at the last.
     *
     * For pane pans and overscrolls, which need travel that REVERSES — something
     * [pressHoldDragRelease]'s single destination cannot express. No hold on purpose: a pan must
     * start moving before the long-press timeout or it becomes a long-press.
     */
    fun dragPath(points: List<PointF>, legMs: Long, onStep: (at: PointF) -> Unit = { }) {
        require(points.size >= 2) { "a path needs at least two points" }
        val downTime = SystemClock.uptimeMillis()
        var now = downTime
        send(downTime, now, MotionEvent.ACTION_DOWN, points.first().x, points.first().y)
        for (leg in 1 until points.size) {
            val from = points[leg - 1]
            val to = points[leg]
            val steps = (legMs / GESTURE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                SystemClock.sleep(GESTURE_STEP_MS)
                now += GESTURE_STEP_MS
                val p = i.toFloat() / steps
                val at = PointF(from.x + p * (to.x - from.x), from.y + p * (to.y - from.y))
                send(downTime, now, MotionEvent.ACTION_MOVE, at.x, at.y)
                onStep(at)
            }
        }
        send(downTime, now, MotionEvent.ACTION_UP, points.last().x, points.last().y)
    }
}
