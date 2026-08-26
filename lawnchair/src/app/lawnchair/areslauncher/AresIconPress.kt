package app.lawnchair.areslauncher

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A playful **press → launch** micro-interaction on an app icon (owner 2026-08-25): "on press it can
 * do a little squish animation... it subtly twists just a little bit. Then when it sprints forward,
 * it twists a little mid animation so it's the right way."
 *
 * This is the launch delight that the app-OPEN *window* transition can't give — that window is the
 * whole screen, so it can't overshoot without an edge gap. An icon, by contrast, is a small element
 * that can squash, twist and spring freely, exactly like the folder bloom and home reveal. So the
 * "fun" lives on the icon you touch:
 *
 *  - **press** (finger down): the icon **squashes** — a quick scale-down, slightly flatter than wide
 *    (squash-and-stretch), with a **subtle twist** ([TWIST_DEG]).
 *  - **release into a tap** (launch): it **springs forward** — an M3 Expressive spatial overshoot
 *    back up past its resting size (a lunge toward the viewer) while the twist **rotates through
 *    straight** and settles at 0 ([AresMotion.SPATIAL_DEFAULT] overshoots both).
 *  - **abandoned** (finger slides into a scroll/drag, or the gesture is cancelled): it just relaxes
 *    back to rest, no lunge.
 *
 * ## Why an observe-only touch listener
 *
 * The home grid's touch pipeline is famously finicky (scroll, long-press-to-edit, folder dwell, the
 * DragStarter). So this listener **never consumes** — it returns false for every event and only
 * *reads* them to drive the animation. The click, the long-press and the RecyclerView's own scroll
 * all keep working exactly as before; a scroll simply arrives here as the slop-cross (or an
 * ACTION_CANCEL when the parent intercepts), which relaxes the squash. Nothing here can wedge a
 * gesture because nothing here owns one.
 *
 * The icon view is re-set on every bind, so a transform can't strand across recycling; the caller
 * also resets it to identity before attaching, as insurance.
 */
object AresIconPress {

    private const val SQUASH = 0.86f          // resting 1.0 -> squashed scale on press
    private const val SQUASH_FLATTEN = 0.94f  // y a touch flatter than x, so it reads as a squash
    private const val TWIST_DEG = 4f          // the subtle twist while squashed

    private const val PRESS_MS = 90L          // fast, so the squash feels like a real press
    private const val SPRINT_MS = 280L        // the spring-forward-and-settle on launch
    private const val RELAX_MS = 200L         // the quiet relax when the press is abandoned

    /**
     * Attach the press→launch animation to [view] (an app icon). Idempotent per bind.
     *
     * [suppressed] gates the whole interaction OFF for a press that will not launch: in edit mode a
     * tap does not open the app, and the tile's scale belongs to the reorder-bounce, so a squash
     * there would both mislead and fight that scale. When it returns true at finger-down, this press
     * is left completely alone.
     */
    fun attach(view: View, suppressed: () -> Boolean) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var pressing = false

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (suppressed()) {
                        pressing = false
                    } else {
                        downX = e.x
                        downY = e.y
                        pressing = true
                        squash(v)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    // Slid far enough to be a scroll/drag, not a tap: relax, and stop tracking so the
                    // eventual UP does not fire the launch lunge.
                    if (pressing && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        pressing = false
                        relax(v, sprint = false)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (pressing) {
                        pressing = false
                        relax(v, sprint = true)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (pressing) {
                        pressing = false
                        relax(v, sprint = false)
                    }
                }
            }
            // NEVER consume: the click, long-press and scroll must all still see the event.
            false
        }
    }

    private fun squash(v: View) {
        v.animate().cancel()
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        v.animate()
            .scaleX(SQUASH)
            .scaleY(SQUASH * SQUASH_FLATTEN)
            .rotation(TWIST_DEG)
            .setInterpolator(AresMotion.EFFECTS_DEFAULT) // press-in, no overshoot
            .setDuration(PRESS_MS)
            .start()
    }

    private fun relax(v: View, sprint: Boolean) {
        v.animate().cancel()
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        // A launch releases with the spatial overshoot -- scale springs past 1 (the forward lunge)
        // and the twist rotates through straight -- so the icon "sprints forward" as the app opens.
        // An abandoned press just relaxes back.
        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setInterpolator(if (sprint) AresMotion.SPATIAL_DEFAULT else AresMotion.SPATIAL_FAST)
            .setDuration(if (sprint) SPRINT_MS else RELAX_MS)
            .start()
    }
}
