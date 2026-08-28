package app.lawnchair.areslauncher

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.icons.MonochromeIconFactory
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Applies the edit-mode **icon theming** personalization (owner 2026-08-26/27): a single on/off
 * Material You theming toggle that renders every app icon as an accent-tinted monochrome, matching
 * Pixel "Themed icons", across the home grid, the app list, and folder contents.
 *
 * **Full theming (owner 2026-08-27).** Earlier revisions did a hybrid: apps that ship a native
 * monochrome layer got the real themed icon, and everything else got a graduated colour "wash" whose
 * intensity a strength stepper controlled. The owner asked to theme *everything* like Android 16
 * QPR2, so [app.lawnchair.icons.LawnchairIconProvider.getIcon] now, when theming is on:
 *   1. uses the app's OWN monochrome layer (`AdaptiveIconDrawable.getMonochrome()`) if present, else
 *   2. an icon pack's themed layer via `ThemedIconCompat`, else
 *   3. [generateMono] synthesizes a monochrome from the regular adaptive icon (Lawnchair's
 *      [MonochromeIconFactory], the same generator Android 16's auto-theming uses).
 * Because every adaptive app now themes, there is nothing left to partially wash, so the strength
 * stepper is gone and theming is a plain on/off. The [wash] path survives only as a fallback for the
 * rare NON-adaptive icon (no layers to monochrome), rendered at full accent.
 *
 * A theming change is folded into `LawnchairThemeManager`'s icon state, so every icon regenerates in
 * place (`onThemeChanged`) -- an icon reload, NOT a recreate, so edit mode is retained (same live
 * path the shape pill uses).
 */
object AresIconTint {

    private const val TAG = "AresIconTint"

    // Luminance weights (Rec. 601), used to fold colour onto the accent for the non-adaptive fallback.
    private const val LR = 0.299f
    private const val LG = 0.587f
    private const val LB = 0.114f

    // Bump when the theming RENDERING changes so cached icons invalidate and regenerate even though
    // the app's versionCode is fixed across debug builds. 1=uniform wash, 2=hybrid, 3=hybrid+system
    // mono, 4=system mono outside the icon-pack gate, 5=full theming (synth mono for every app;
    // % dropped), 6=vibrant M3 primary/on-primary colours (was pale accent1_100/dark glyph).
    private const val RENDER_VERSION = 6

    /** True when theming should be baked into generated icons. On/off only -- no strength. */
    fun isActive(prefs: PreferenceManager2): Boolean =
        prefs.aresIconTintEnabled.firstBlocking()

    /**
     * The Ares theming colour pair, `[background, glyph]`. Owner (2026-08-27) wants the vibrant
     * native look: a saturated Material You accent BACKGROUND with a LIGHT glyph -- the M3
     * primary / on-primary roles -- NOT the pale primary-container background + dark glyph that stock
     * Android themed icons use in light mode (which reads washed out). Both track the wallpaper-derived
     * dynamic palette, so the theming follows Material You. Used for BOTH the native-monochrome path
     * and the synthesized-monochrome path so every themed icon shares one consistent, vibrant scheme.
     */
    fun themedColors(context: Context): IntArray = intArrayOf(
        ContextCompat.getColor(context, R.color.materialColorPrimary),
        ContextCompat.getColor(context, R.color.materialColorOnPrimary),
    )

    /**
     * Synthesize an accent-tinted monochrome from [adaptive] for an app that ships no monochrome
     * layer (step 3 above), using [MonochromeIconFactory] -- the same generator Android 16 uses to
     * auto-theme mono-less apps. Returns null below API 33 or on any failure (caller falls back).
     * Runs on the icon-loading worker thread (getIcon), as [MonochromeIconFactory.wrap] requires.
     */
    fun generateMono(context: Context, adaptive: AdaptiveIconDrawable, accent: Int): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val size = LauncherAppState.getIDP(context).iconBitmapSize.coerceAtLeast(1)
            // MonochromeIconFactory doesn't honour setTint (it paints white), so colour it with a
            // SRC_IN blend filter, which InsetDrawable/ClippedMonoDrawable forwards to the generator.
            MonochromeIconFactory(size).wrap(adaptive, adaptive.iconMask).apply {
                colorFilter = BlendModeColorFilter(accent, BlendMode.SRC_IN)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "monochrome synthesis failed", t)
            null
        }
    }

    /**
     * Full-accent duotone colour filter toward [accent], or `null` for a no-op. Used only for the
     * non-adaptive fallback in [wash].
     */
    private fun fullWashFilter(accent: Int): ColorFilter {
        val ar = Color.red(accent) / 255f
        val ag = Color.green(accent) / 255f
        val ab = Color.blue(accent) / 255f
        val m = floatArrayOf(
            ar * LR, ar * LG, ar * LB, 0f, 0f,
            ag * LR, ag * LG, ag * LB, 0f, 0f,
            ab * LR, ab * LG, ab * LB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        return ColorMatrixColorFilter(ColorMatrix(m))
    }

    /**
     * Fallback for a NON-adaptive icon (no layers to monochrome): if theming is active, fold the
     * icon toward the [accent] at full intensity (in place) and return it; else return it untouched.
     */
    fun wash(icon: Drawable, prefs: PreferenceManager2, accent: Int): Drawable {
        if (!prefs.aresIconTintEnabled.firstBlocking()) return icon
        icon.colorFilter = fullWashFilter(accent)
        return icon
    }

    /** State fragment for the icon cache key so a theming change (or render-version bump) invalidates
     *  cached bitmaps. Including [RENDER_VERSION] forces a one-time regen when the rendering changes. */
    fun stateFragment(prefs: PreferenceManager2): String =
        "tint=v$RENDER_VERSION:${prefs.aresIconTintEnabled.firstBlocking()}"
}
