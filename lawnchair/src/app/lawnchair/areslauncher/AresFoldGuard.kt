package app.lawnchair.areslauncher

import kotlin.math.max
import kotlin.math.min

/**
 * The decision behind the mid-fold stale-profile guard, as pure arithmetic.
 *
 * ## Why this is a separate, dependency-free object
 *
 * The guard fires only on a TRANSIENT frame — the window has already resized to the unfolded panel
 * while the `DeviceProfile` still describes the folded one — and no at-rest test can observe that.
 * Three successive versions of this gate were wrong, and the first two looked fine for weeks by the
 * only measure available on a device (run the shape suite folded, run it unfolded, see no
 * regression). Pulling the decision out of `AresHomeListView` into plain arithmetic makes it
 * testable on the JVM device matrix, which is the only way this branch gets checked against
 * foldables nobody here owns.
 *
 * ## What it has to separate, and why neither a ratio nor the panel bounds could
 *
 * The gate began as `isMultiDisplay && width >= availableWidthPx * 1.5`. `isMultiDisplay` is
 * posture-DEPENDENT, so it was false in exactly the frame the guard exists for (ledger row 69).
 * Replacing it with `isFoldable` fixed that and opened row 69a: `isFoldable` is true in EVERY
 * posture, so the ratio alone now also catches a **folded phone rotated to landscape** —
 * 1080 → 2364 is 2.19×, well past 1.5× — and would halve the grid on a genuinely single-panel
 * window, which is the very defect the guard was written to prevent. With rotation enabled (the
 * owner confirmed 2026-09-03 that they use it) that is a live path, not a theoretical one.
 *
 * A bigger ratio cannot fix it: the folded-landscape width is LARGER than the unfolded panel's, so
 * no threshold orders those two cases correctly.
 *
 * Neither can the device's own `supportedBounds`, which was the obvious next idea and is **wrong**.
 * Measured on emulator-5554 across two fold cycles, the largest entry in that set during the stale
 * frame is `1080x2364` — the FOLDED display. `DisplayController.Info` builds `supportedBounds` from
 * `mPerDisplayBounds`, whose contents follow the active display, so it is no more posture-
 * independent than `isMultiDisplay` was. A version of this file that trusted it declined every real
 * stale frame on the device (`container=2036x1990 panel=1080x2364`) and would have silently undone
 * row 69's fix while showing no regression in any at-rest test — the third wrong version, caught
 * only because `AresHomeListView` logs declines as loudly as it logs fires.
 *
 * ## What does separate them: the container's own shape
 *
 * A foldable's inner panel is near-square and a phone in landscape is long and thin. That is a
 * physical fact about the hardware, needs no external reference to evaluate, and cannot go stale:
 *
 * ```
 *   Pixel Fold inner   2076x2152   aspect 1.04     OnePlus Open inner  2440x2268  aspect 1.08
 *   Z Fold inner       1812x2176   aspect 1.20     AresFold container  2036x1990  aspect 1.02
 *   ------------------------------------------- 1.50 -------------------------------------------
 *   16:9 phone, landscape           aspect 1.78    Pixel Fold cover    2092x1080  aspect 1.94
 *   19.5:9 phone, landscape         aspect 2.17    Z Fold cover        2316x904   aspect 2.56
 * ```
 *
 * The two populations are separated by a wide, empty band, and [MAX_PANEL_ASPECT] sits in it.
 *
 * The remaining asymmetry is deliberate. Declining a frame that deserved halving costs one
 * transient full-width frame — the behaviour before any of this existed. Firing on a frame that did
 * not costs a half-width home grid on a single-panel window with a permanently empty half, which is
 * a visible defect. So where the two risks meet, this errs toward declining.
 */
object AresFoldGuard {

    /**
     * The widest a window may be, relative to its own height, and still be a foldable's inner panel.
     *
     * Not tuned to a device: 1.5 is the middle of the empty band between every inner panel measured
     * (≤ 1.20) and the least elongated phone-in-landscape that exists (16:9 = 1.78). The container
     * is somewhat shorter than the window it sits in — insets, page padding — which pushes a
     * portrait-ish container's aspect UP, so the margin above 1.20 is doing real work; the measured
     * container on the AresFold is 2036x1990 against a 2036x2152-ish window and still only 1.02.
     */
    const val MAX_PANEL_ASPECT = 1.5f

    /** How much wider than the profile the container must be before the profile is in doubt. */
    const val STALE_WIDTH_RATIO = 1.5f

    /**
     * True when [containerW]×[containerH] looks like a foldable's unfolded panel while the profile
     * still describes a narrower (folded) window — i.e. the grid is about to lay out full width
     * across both panes and should be halved.
     *
     * [availableWidthPx] is what the CURRENT `DeviceProfile` believes the whole display's width to
     * be. Callers must already know the device is a foldable; a tablet rotating or a multi-window
     * drag-resize must never reach here.
     */
    @JvmStatic
    fun profileIsStale(containerW: Int, containerH: Int, availableWidthPx: Int): Boolean {
        if (containerW <= 0 || containerH <= 0) return false
        if (availableWidthPx <= 0) return false
        // The profile must actually disagree with the window. Without this the guard would also fire
        // once the profile has caught up, and halve a correctly-described two-panel window.
        if (containerW < availableWidthPx * STALE_WIDTH_RATIO) return false
        return aspect(containerW, containerH) <= MAX_PANEL_ASPECT
    }

    /** Long side over short side, so it does not matter which rotation the window is expressed in. */
    private fun aspect(w: Int, h: Int): Float = max(w, h).toFloat() / min(w, h).toFloat()
}
