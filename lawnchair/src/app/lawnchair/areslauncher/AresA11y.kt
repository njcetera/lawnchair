package app.lawnchair.areslauncher

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * Accessibility touch-ups shared by the edit-mode affordances.
 *
 * ## What was and was not wrong
 *
 * A previous investigation reported that the × badges and the resize chevron "never reach the
 * accessibility tree", on the evidence that `uiautomator dump` found no such nodes. **That was a
 * measurement artefact, and it is worth recording because it will happen again.** Edit mode runs a
 * perpetual wiggle animator, so the main looper is never idle and `uiautomator dump` always fails
 * with `ERROR: could not get idle state` — writing *no file at all*. Anything read back from the
 * target path afterwards is a stale dump from an earlier run, and a stale dump of a home screen
 * that was not in edit mode naturally contains no affordances.
 *
 * Measured on the AresFold emulator: with `animator_duration_scale 0` (which is also what the
 * system's own "Remove animations" setting gives a motion-sensitive user), the dump succeeds and
 * every badge and chevron is present, `clickable="true"`, `focusable="true"`, with a content
 * description. Nothing prunes them. To inspect edit mode, disable animations first, or read the
 * view tree from `dumpsys activity` — which needs no idle.
 *
 * ## What is genuinely fixed here
 *
 * The nodes existed but described themselves poorly, which is a real defect and a smaller one:
 * every badge announced the bare word "Remove", so a screen-reader user swiping across a grid of
 * six tiles heard the same word six times with nothing to tell them apart. The affordances now name
 * their item, and report themselves as buttons rather than as images.
 */
object AresA11y {

    /**
     * Makes [view] announce itself as a button.
     *
     * These affordances are `ImageView`s — chosen for how they draw, not for what they are — and a
     * bare `ImageView` is announced as an image even when it is clickable. Overriding the node's
     * class name is the standard way to state the role without changing the widget: swapping in
     * `ImageButton` would drag its style along with it, and the × deliberately has no background.
     *
     * The framework delegate is used rather than the AndroidX one because nothing here needs
     * back-compat shimming; `onInitializeAccessibilityNodeInfo` has behaved this way since well
     * before this app's `minSdk`.
     */
    fun describeAsButton(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }
}
