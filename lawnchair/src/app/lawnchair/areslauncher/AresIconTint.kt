package app.lawnchair.areslauncher

import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import app.lawnchair.preferences2.PreferenceManager2
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Applies the edit-mode **icon tint** personalization (owner 2026-08-26/27): a unified-intensity
 * Material You wash that cross-fades every icon from its normal look (0%) toward a themed,
 * accent-monochrome look (100%), across the home grid, the app list, and folder contents.
 *
 * **Option A (owner 2026-08-27) -- uniform wash.** For strength `s` in 0..1 each icon is rendered
 * through a [ColorMatrix] that linearly interpolates identity with an accent duotone (map luminance
 * onto the Material You accent). Because the matrix is linear, the interpolation is exactly
 * `out = (1-s)*original + s*duotone(original)` -- one coherent look for every app, no per-app
 * monochrome layer required. `s=0` is untouched; `s=1` is an accent monochrome.
 *
 * The wash is applied as a plain `colorFilter` on the final icon [Drawable] in
 * [app.lawnchair.icons.LawnchairIconProvider.getIcon]; the icon factory bakes it into the cached
 * bitmap. Changing the tint prefs runs `reloadHelper.reloadIcons()` (icon-cache clear +
 * `reloadIfActive`) -- an icon reload, NOT a recreate, so edit mode is retained (same live path the
 * shape pill uses).
 */
object AresIconTint {

    // Luminance weights (Rec. 601), used to fold colour onto the accent.
    private const val LR = 0.299f
    private const val LG = 0.587f
    private const val LB = 0.114f

    /** True when a tint should be baked into generated icons. */
    fun isActive(prefs: PreferenceManager2): Boolean =
        prefs.aresIconTintEnabled.firstBlocking() && prefs.aresIconTintStrength.firstBlocking() > 0

    /**
     * The wash colour filter for [strength] (0..100) toward [accent], or `null` for a no-op
     * (`strength <= 0`), so callers can skip work entirely at zero.
     */
    fun washFilter(strength: Int, accent: Int): ColorFilter? {
        val s = (strength.coerceIn(0, 100)) / 100f
        if (s <= 0f) return null
        val ar = Color.red(accent) / 255f
        val ag = Color.green(accent) / 255f
        val ab = Color.blue(accent) / 255f
        // M = (1-s)*I + s*D, D = luminance -> accent duotone. Alpha row is identity (preserve shape).
        val m = floatArrayOf(
            (1 - s) + s * ar * LR, s * ar * LG, s * ar * LB, 0f, 0f,
            s * ag * LR, (1 - s) + s * ag * LG, s * ag * LB, 0f, 0f,
            s * ab * LR, s * ab * LG, (1 - s) + s * ab * LB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        return ColorMatrixColorFilter(ColorMatrix(m))
    }

    /**
     * If the tint is active, set the wash [ColorFilter] on [icon] (in place) and return it; else
     * return [icon] untouched. [accent] is the Material You accent to fold toward.
     */
    fun wash(icon: Drawable, prefs: PreferenceManager2, accent: Int): Drawable {
        if (!prefs.aresIconTintEnabled.firstBlocking()) return icon
        val filter = washFilter(prefs.aresIconTintStrength.firstBlocking(), accent) ?: return icon
        icon.colorFilter = filter
        return icon
    }

    /** State fragment for the icon cache key so a tint change invalidates cached bitmaps. */
    fun stateFragment(prefs: PreferenceManager2): String =
        "tint=${prefs.aresIconTintEnabled.firstBlocking()}:${prefs.aresIconTintStrength.firstBlocking()}"
}
