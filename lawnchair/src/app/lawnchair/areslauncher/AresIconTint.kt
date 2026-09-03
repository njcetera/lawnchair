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
import androidx.core.graphics.drawable.toDrawable
import app.lawnchair.icons.CustomAdaptiveIconDrawable
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.Utilities
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

    // Bump when the theming RENDERING changes so cached icons invalidate and regenerate even though
    // the app's versionCode is fixed across debug builds. 1=uniform wash, 2=hybrid, 3=hybrid+system
    // mono, 4=system mono outside the icon-pack gate, 5=full theming (synth mono for every app;
    // % dropped), 6=vibrant M3 primary/on-primary colours, 7=colours flipped (light bg + vibrant glyph),
    // 8=synth mono centred (use MonochromeIconFactory directly, not its ClippedMonoDrawable wrapper,
    //   which cropped the synth glyph into the top-left corner).
// 9=non-adaptive wash gets its own CustomAdaptiveIconDrawable theme background, so the legacy
//   BaseIconFactory wrapper cannot paint a palette-derived WHITE background behind it.
// 10=the wash gains a FLOOR, so dark legacy artwork no longer collapses into its own tile.
// 11=the floor is REPLACED by a tile-aware pole: the wash ramps from the accent toward white on a
//   dark tile and toward black on a light one, so its worst pixel equals the accent instead of a
//   dimmed accent. Owner 2026-09-02: floor 0.80 was still "not enough contrast" beside the
//   monochrome icons, which render at the accent. The span is also in the key -- see stateFragment.
// 12=legacy (non-adaptive) icons go through MonochromeIconFactory too, instead of any colour
//   wash, so they are a flat accent glyph on the theme tile exactly like every other themed icon
//   (owner 2026-09-02: "get the glyph one color and the background another").
    private const val RENDER_VERSION = 12

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
     * Synthesize a monochrome from a NON-adaptive (legacy) icon, so it goes through the exact same
     * generator every other themed icon does and matches them by construction.
     *
     * Owner 2026-09-02, after the wash's second revision still did not satisfy: *"I feel like we
     * just gotta try to get the glyph one color and the background another? then it'll match the
     * monochromatic icons."* Right, and my earlier objection to that was wrong. I rejected a plain
     * tint because "many legacy icons are fully opaque squares with no alpha shape, so SRC_IN
     * renders them as solid colour blocks" — true of tinting the raw bitmap, and irrelevant to
     * [MonochromeIconFactory], which **never looks at the source alpha**. It draws the icon on
     * black, sets `alpha = average(R,G,B)` so LUMINANCE becomes the mask, contrast-stretches
     * min..max, and pushes the result toward 0/255. Opaque artwork with no alpha is precisely what
     * it was built for.
     *
     * **Why the icon must fill the layer rect here.** `generateMono` decides whether to INVERT by
     * averaging the canvas's top and bottom edge pixels, on the assumption that the edges are the
     * icon's own background. Wrapping a legacy icon at its usual shrunken legacy scale would put
     * black padding at those edges, the polarity check would read that padding instead of the
     * artwork, and a dark-logo-on-white icon would come out inverted — the white background
     * becoming the glyph. Passing the raw drawable as a full-bleed foreground puts the artwork's
     * own background at the edges, which makes all four cases resolve correctly: dark-on-light and
     * light-on-dark both flip as needed, and either on transparent reads the black filler as
     * background and does not flip.
     *
     * The caller scales the result back down to legacy geometry afterwards; this returns the
     * full-canvas mask.
     */
    fun generateMonoFromLegacy(context: Context, legacy: Drawable, accent: Int): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val full = CustomAdaptiveIconDrawable(
                Color.TRANSPARENT.toDrawable(),
                legacy.mutate(),
            )
            val size = LauncherAppState.getIDP(context).iconBitmapSize.coerceAtLeast(1)
            val factory = MonochromeIconFactory(size)
            factory.wrap(full, full.iconMask)
            factory.colorFilter = flatGlyphFilter(accent)
            factory
        } catch (t: Throwable) {
            Log.w(TAG, "legacy monochrome synthesis failed", t)
            null
        }
    }

    /** Steepness of the alpha threshold in [flatGlyphFilter]. 8 makes the ramp ±16/255 wide. */
    private const val GLYPH_ALPHA_STEEPNESS = 8f

    /**
     * Paint the mask as a FLAT accent glyph: every pixel is either the accent or nothing.
     *
     * The plain `BlendModeColorFilter(accent, SRC_IN)` that [generateMono] uses keeps the mask's
     * partial alpha, which is right for a native monochrome layer (already designed as a glyph) and
     * wrong for a synthesized one. Measured 2026-09-02 on a dark-artwork legacy probe: the
     * generator produced a largely MID-alpha mask, so the disc composited to `#576F8F` over a
     * `#2A415F` tile — a washed-out mid-tone, visibly flatter and dimmer than the `#B0C8EC` that
     * adaptive icons render at, and worse than the colour wash it was meant to replace.
     *
     * The owner's ask is a two-tone icon — "the glyph one color and the background another" — so
     * the alpha is thresholded rather than passed through. This matrix does both jobs at once:
     * the RGB rows are constant offsets (the accent, ignoring the source colour entirely, exactly
     * like SRC_IN), and the alpha row is a steep ramp centred on 50%, which drives anything
     * meaningfully opaque to fully opaque and everything else to nothing. Antialiasing at the glyph
     * edge survives as the narrow ±16/255 transition band, so edges stay smooth rather than jagged.
     */
    private fun flatGlyphFilter(accent: Int): ColorFilter {
        val k = GLYPH_ALPHA_STEEPNESS
        return ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, Color.red(accent).toFloat(),
                    0f, 0f, 0f, 0f, Color.green(accent).toFloat(),
                    0f, 0f, 0f, 0f, Color.blue(accent).toFloat(),
                    0f, 0f, 0f, k, 128f - k * 128f,
                ),
            ),
        )
    }

    /**
     * Escape hatch back to the colour wash for the legacy path, for a one-build A/B and in case a
     * real icon turns up that the mono generator mangles. `setprop debug.ares.legacy_mono 0`.
     */
    val legacyMonoEnabled: Boolean by lazy {
        (Utilities.getSystemProperty("debug.ares.legacy_mono", "") != "0")
            .also { if (!it) Log.w(TAG, "legacy mono DISABLED via debug.ares.legacy_mono, using wash") }
    }

    /**
     * The shading range actually in force, and the one-build A/B hooks around it.
     *
     * Normally [AresWashMath.DEFAULT_WASH_SPAN]; `setprop debug.ares.wash_span 0.35` retunes it
     * without a rebuild, which matters because "how much shading" is a taste question the owner has
     * to answer by looking. `setprop debug.ares.wash_legacy 1` reproduces the ORIGINAL
     * `accent * luminance` rendering from these exact bytes, so the fix can be verified against a
     * real negative control rather than a before/after of two different builds.
     *
     * Read ONCE per process, deliberately: both values are folded into the icon cache key
     * ([stateFragment]), so changing one mid-process could only produce a mix of icons baked two
     * different ways. The A/B procedure is setprop -> force-stop -> relaunch, which re-reads them.
     */
    private val washSpanOverride: Float? by lazy {
        Utilities.getSystemProperty("debug.ares.wash_span", "")
            .toFloatOrNull()
            ?.takeIf { it in 0f..1f }
            ?.also { Log.w(TAG, "wash span OVERRIDDEN to $it via debug.ares.wash_span") }
    }

    private val washLegacy: Boolean by lazy {
        (Utilities.getSystemProperty("debug.ares.wash_legacy", "") == "1")
            .also { if (it) Log.w(TAG, "wash LEGACY (pre-fix) path via debug.ares.wash_legacy") }
    }

    private fun washSpan(): Float = washSpanOverride ?: AresWashMath.DEFAULT_WASH_SPAN

    /**
     * Duotone colour filter for the non-adaptive fallback in [wash]: a band running from [accent]
     * to whichever pole is furthest from [tile]. The arithmetic and the reasoning live in
     * [AresWashMath] -- an Android-free file so `:ares-geom-tests` can assert on the matrix the
     * device actually uses rather than on a copy of it.
     *
     * [tile] is not decoration. The original formula ramped toward a FIXED black pole, which is
     * away from a light tile (fine) and straight into a dark one (the bug). Passing the tile is
     * what lets the ramp choose its direction.
     */
    private fun fullWashFilter(accent: Int, tile: Int): ColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            if (washLegacy) {
                AresWashMath.legacyWashMatrix(Color.red(accent), Color.green(accent), Color.blue(accent))
            } else {
                AresWashMath.washMatrix(
                    Color.red(accent), Color.green(accent), Color.blue(accent),
                    Color.red(tile), Color.green(tile), Color.blue(tile),
                    washSpan(),
                )
            },
        ),
    )

    /**
     * Fallback for a NON-adaptive icon (no layers to monochrome): if theming is active, fold the
     * icon onto a readable band between [accent] and the pole away from [tile] (in place) and
     * return it; else return it untouched.
     *
     * [tile] must be the colour this icon is actually painted on -- `ares[0]`, the same value the
     * caller uses for the adaptive background. Pass the wrong one and the ramp picks the wrong
     * direction, which is precisely the defect being fixed.
     */
    fun wash(icon: Drawable, prefs: PreferenceManager2, accent: Int, tile: Int): Drawable {
        if (!prefs.aresIconTintEnabled.firstBlocking()) return icon
        // mutate() first so the wash never bleeds through a shared ConstantState to other users of
        // the same drawable (nightly 2026-08-28, finding 7).
        return icon.mutate().apply { colorFilter = fullWashFilter(accent, tile) }
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
        // The span and the legacy flag are part of the KEY, not just the rendering. Without that a
        // `setprop debug.ares.wash_*` A/B would compare two runs that both served the same cached
        // bitmaps and report no difference -- the same shape of silent false-green a stale Gradle
        // UP-TO-DATE produced here on 2026-09-02, where four negative controls "passed" without
        // ever running.
        return "tint=v$RENDER_VERSION:on:n$night:${c[0]}:${c[1]}:s${washSpan()}:l$washLegacy:m$legacyMonoEnabled"
    }
}
