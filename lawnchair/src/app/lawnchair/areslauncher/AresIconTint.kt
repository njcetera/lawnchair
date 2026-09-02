package app.lawnchair.areslauncher

import android.content.Context
import android.content.res.Configuration
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
    // % dropped), 6=vibrant M3 primary/on-primary colours, 7=colours flipped (light bg + vibrant glyph),
    // 8=synth mono centred (use MonochromeIconFactory directly, not its ClippedMonoDrawable wrapper,
    //   which cropped the synth glyph into the top-left corner).
// 9=non-adaptive wash gets its own CustomAdaptiveIconDrawable theme background, so the legacy
//   BaseIconFactory wrapper cannot paint a palette-derived WHITE background behind it.
    private const val RENDER_VERSION = 9

    /** True when theming should be baked into generated icons. On/off only -- no strength. */
    fun isActive(prefs: PreferenceManager2): Boolean =
        prefs.aresIconTintEnabled.firstBlocking()

    /**
     * The Ares theming colour pair, `[background, glyph]`. Owner (2026-08-27) reviewed the vibrant
     * scheme (primary background, on-primary glyph) on device and asked to FLIP it: a LIGHT
     * background (M3 on-primary, near-white) with a VIBRANT accent GLYPH (M3 primary). Both track the
     * wallpaper-derived dynamic palette, so the theming follows Material You. Used for BOTH the
     * native-monochrome path and the synthesized-monochrome path so every themed icon shares one
     * consistent scheme.
     */
    fun themedColors(context: Context): IntArray = intArrayOf(
        ContextCompat.getColor(context, R.color.materialColorOnPrimary),
        ContextCompat.getColor(context, R.color.materialColorPrimary),
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
            // MonochromeIconFactory.wrap() has a SIDE EFFECT -- it bakes the icon into the factory's
            // internal mono bitmap -- and RETURNS a ClippedMonoDrawable (an InsetDrawable with a
            // NEGATIVE inset plus its own icon-mask clip). That wrapper is meant to be rasterised to a
            // bitmap, NOT used as a live AdaptiveIconDrawable foreground: wrapping it as the foreground
            // of CustomAdaptiveIconDrawable made the negative inset + inner clip shove the glyph into
            // the TOP-LEFT and crop it (owner 2026-08-28). The factory ITSELF is a plain Drawable
            // whose draw() scales the mono bitmap to fill its bounds -- exactly like a native
            // monochrome layer, which composes correctly -- so use it directly and let the single
            // outer CustomAdaptiveIconDrawable shape mask do the clipping.
            // MonochromeIconFactory doesn't honour setTint (it paints white), so colour it with a
            // SRC_IN blend filter.
            val factory = MonochromeIconFactory(size)
            factory.wrap(adaptive, adaptive.iconMask)
            factory.colorFilter = BlendModeColorFilter(accent, BlendMode.SRC_IN)
            factory
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
        // mutate() first so the wash never bleeds through a shared ConstantState to other users of
        // the same drawable (nightly 2026-08-28, finding 7).
        return icon.mutate().apply { colorFilter = fullWashFilter(accent) }
    }

    /**
     * State fragment for the icon cache key so a theming change invalidates cached bitmaps. When
     * theming is OFF the fragment is constant (icons are untinted). When ON it folds in, besides
     * [RENDER_VERSION]: the current NIGHT state and the RESOLVED accent pair -- because the theming
     * colours are wallpaper-derived and have light/dark variants, so a dark-mode switch or a Material
     * You palette change must regenerate the icons. Without this they kept their old-mode/old-accent
     * colours until a manual toggle or a version bump (nightly 2026-08-28, finding 1). A night switch
     * recreate()s the launcher (uiMode is not in the activity's configChanges) and a palette change
     * likewise reloads, so the differing key is re-read and stale icons regenerate.
     */
    fun stateFragment(context: Context, prefs: PreferenceManager2): String {
        if (!prefs.aresIconTintEnabled.firstBlocking()) return "tint=v$RENDER_VERSION:off"
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val c = themedColors(context)
        return "tint=v$RENDER_VERSION:on:n$night:${c[0]}:${c[1]}"
    }
}
