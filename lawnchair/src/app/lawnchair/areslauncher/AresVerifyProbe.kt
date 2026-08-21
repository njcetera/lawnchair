package app.lawnchair.areslauncher

import android.util.Log
import android.view.View

/**
 * Independent verifier's probe for S12 (2026-08-21). Not product code — remove with
 * [AresVerifyMarker].
 *
 * Reads the framework's own composed drawn offset, `View.getTranslationY()`, which is what
 * `MultiTranslateDelegate` finally writes after summing every channel. That is deliberately the
 * most primitive observable available: it is the number the view actually draws with, not any
 * Ares-side bookkeeping of what the lift is *believed* to be.
 *
 * Logs on CHANGE, not on a timer. The first version was time-throttled and caught exactly one
 * burst — the pre-layout pass, `top=0 h=0 ty=0` — because with animators off the folder edit sync
 * only re-enters while something else is invalidating, so the settled state was never sampled.
 * Keyed on the view's identity so a recycled BubbleTextView cannot inherit another icon's last
 * value.
 */
internal object AresVerifyProbe {
    private const val TAG = "VRFY_S12"
    private val seen = HashMap<Int, String>()

    fun folderIcon(view: View, index: Int, label: CharSequence?) {
        val state = "idx=$index label=$label top=${view.top} bottom=${view.bottom} " +
            "h=${view.height} ty=${view.translationY} tx=${view.translationX} " +
            "drawnTop=${view.top + view.translationY} " +
            "drawnCentreOffset=${view.translationY + view.height / 2f}"
        val key = System.identityHashCode(view)
        if (seen[key] == state) return
        seen[key] = state
        Log.i(TAG, state)
    }
}
