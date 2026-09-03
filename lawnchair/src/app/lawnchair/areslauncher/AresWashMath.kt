package app.lawnchair.areslauncher

/**
 * The colour arithmetic behind the non-adaptive icon "wash" (see [AresIconTint.wash]).
 *
 * **Why this is a separate, Android-free file.** Same reason as [AresGeometry]: it is compiled into
 * BOTH the app and the `:ares-geom-tests` JVM module, so the tests assert on the arithmetic the
 * device actually runs rather than on a copy of it. The hard constraint that follows is that this
 * file must never import anything from `android.*` -- if it does, `:ares-geom-tests` stops
 * compiling, which is the intended alarm.
 *
 * ## The bug this encodes, in one line
 *
 * The wash shipped as `out = accent * luminance(src)`. Read as an interpolation, that is a ramp from
 * **black** to the accent -- and black is a *fixed* pole, chosen without reference to the tile the
 * glyph is painted on. On a LIGHT tile black is the pole furthest from the tile, so the formula was
 * right by accident (19.9:1). On a DARK tile black is the pole *closest* to the tile, so dark
 * artwork was driven into its own background: 1.44:1, and two owner reports of "the glyph is a bit
 * dark".
 *
 * A floor (`accent * (floor + (1-floor) * lum)`) was tried first and is only a patch on the
 * symptom: it lifts the dark end off the tile but still ramps toward black, so the best it can do
 * is a dimmed accent -- measured 3.96:1 on the owner's palette against the 6.06:1 that the same
 * palette's *adaptive* icons render at. The owner saw the two side by side and said it was not
 * enough. They were right, and the floor was answering the wrong question.
 *
 * ## What this does instead
 *
 * Pick the pole from the tile, and let the band start at the accent:
 *
 * ```
 *   dark tile  ->  pole = white,  band = accent .. lerp(accent, white, span)
 *   light tile ->  pole = black,  band = lerp(accent, black, span) .. accent
 * ```
 *
 * Source luminance maps monotonically across that band in both cases, so artwork shading survives
 * and dark artwork still reads darker than light artwork. The property that matters:
 *
 * > **One endpoint of the band is the accent itself, and the other is strictly further from the
 * > tile. So the worst pixel in any washed icon has exactly the contrast of the accent -- which is
 * > what the monochrome path renders, and which Material 3 already guarantees is a safe pair.**
 *
 * That makes legacy icons match themed ones at their dimmest instead of merely clearing a bar.
 * Measured on the owner's palette (accent `#FF88B4`, tile `#610033`): worst case 3.96:1 with the
 * floor, **6.06:1** with this.
 */
object AresWashMath {

    // Luminance weights (Rec. 601). These are the weights the wash MATRIX uses to collapse a source
    // pixel to a single shading value. They are NOT the WCAG weights in [relativeLuminance], and
    // conflating the two silently skews every contrast number.
    private const val LR = 0.299f
    private const val LG = 0.587f
    private const val LB = 0.114f

    /**
     * How far along the band toward the pole the *other* end sits, i.e. how much shading range a
     * washed icon keeps. 0 collapses the icon to a flat accent silhouette (which is what a plain
     * `SRC_IN` tint would do, and why that is the wrong fix: many legacy icons are fully opaque
     * squares with no alpha shape, so a flat tint renders them as solid colour blocks). 1 runs the
     * band all the way to pure white/black, which loses the accent's identity at the far end.
     *
     * 0.50 keeps the far end clearly tinted (`#FFC4DA` on the owner's palette, still pink) while
     * giving a visible ramp. Tunable at runtime via `debug.ares.wash_span` without a rebuild.
     */
    const val DEFAULT_WASH_SPAN = 0.50f

    /** The WCAG bar for non-text graphics and UI components (SC 1.4.11). An icon glyph is one. */
    const val MIN_GLYPH_CONTRAST = 3.0

    /**
     * WCAG 2.x relative luminance of an sRGB colour. Weights are 0.2126/0.7152/0.0722 on
     * LINEARISED channels -- deliberately not [LR]/[LG]/[LB], which are the old NTSC weights on
     * gamma-encoded channels that the wash matrix uses for a different purpose.
     */
    fun relativeLuminance(r: Int, g: Int, b: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** WCAG contrast ratio between two colours, always >= 1.0. */
    fun contrastRatio(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
        val l1 = relativeLuminance(r1, g1, b1)
        val l2 = relativeLuminance(r2, g2, b2)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * True when the glyph should ramp toward WHITE, i.e. when the tile it sits on is dark. This one
     * predicate is the whole fix; everything else here is bookkeeping around it.
     */
    fun poleIsWhite(tileR: Int, tileG: Int, tileB: Int): Boolean =
        relativeLuminance(tileR, tileG, tileB) < 0.5

    /**
     * The two ends of the band, as `[loR, loG, loB, hiR, hiG, hiB]`, where `lo` is the colour a
     * fully black source pixel gets and `hi` the colour a fully white one gets.
     *
     * Exposed separately from [washMatrix] because it is the honest statement of the design and the
     * thing the tests assert on: one of these two IS the accent, and the other is further from the
     * tile.
     */
    fun washBand(
        accentR: Int, accentG: Int, accentB: Int,
        tileR: Int, tileG: Int, tileB: Int,
        span: Float,
    ): IntArray {
        val s = span.coerceIn(0f, 1f)
        fun toward(c: Int, pole: Int) = (c + (pole - c) * s).toInt().coerceIn(0, 255)
        return if (poleIsWhite(tileR, tileG, tileB)) {
            // Dark tile: the accent is the DARK end, white is away from the tile.
            // Dark artwork sits at the accent; lighter artwork climbs toward white.
            intArrayOf(
                accentR, accentG, accentB,
                toward(accentR, 255), toward(accentG, 255), toward(accentB, 255),
            )
        } else {
            // Light tile: the accent is the LIGHT end, black is away from the tile.
            // Light artwork sits at the accent; darker artwork sinks toward black. This preserves
            // the behaviour that shipped for light mode all along -- it was never the broken case.
            intArrayOf(
                toward(accentR, 0), toward(accentG, 0), toward(accentB, 0),
                accentR, accentG, accentB,
            )
        }
    }

    /**
     * The 4x5 `ColorMatrix` that maps a source pixel onto the band from [washBand].
     *
     * `out = lo + (hi - lo) * luminance(src)`, so the luminance coefficients carry `(hi - lo)` and
     * `lo` moves into the offset column.
     *
     * **The offset column is in 0..255 units, not 0..1.** Writing it as 0..1 compiles, runs, and
     * produces a near-black glyph -- which is to say it silently reintroduces the original bug.
     * That is the single most important fact about this function and the reason it is tested rather
     * than trusted.
     */
    fun washMatrix(
        accentR: Int, accentG: Int, accentB: Int,
        tileR: Int, tileG: Int, tileB: Int,
        span: Float,
    ): FloatArray {
        val band = washBand(accentR, accentG, accentB, tileR, tileG, tileB, span)
        fun row(i: Int): FloatArray {
            val lo = band[i].toFloat()
            val d = (band[i + 3] - band[i]) / 255f
            return floatArrayOf(d * LR, d * LG, d * LB, 0f, lo)
        }
        val r = row(0)
        val g = row(1)
        val b = row(2)
        return floatArrayOf(
            r[0], r[1], r[2], r[3], r[4],
            g[0], g[1], g[2], g[3], g[4],
            b[0], b[1], b[2], b[3], b[4],
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * The ORIGINAL formula, `out = accent * luminance(src)` -- a ramp from a fixed black pole.
     * Kept so the pre-fix rendering can be reproduced from the shipped bytes for a negative
     * control, both on device (`debug.ares.wash_legacy=1`) and in the JVM tests.
     */
    fun legacyWashMatrix(accentR: Int, accentG: Int, accentB: Int): FloatArray {
        val ar = accentR / 255f
        val ag = accentG / 255f
        val ab = accentB / 255f
        return floatArrayOf(
            ar * LR, ar * LG, ar * LB, 0f, 0f,
            ag * LR, ag * LG, ag * LB, 0f, 0f,
            ab * LR, ab * LG, ab * LB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Apply [matrix] to one opaque sRGB pixel the way `ColorMatrixColorFilter` does, returning
     * `[r, g, b]` clamped to 0..255. Exists so a test can drive the SHIPPED matrix rather than
     * re-deriving what it thinks the matrix means.
     */
    fun applyMatrix(matrix: FloatArray, r: Int, g: Int, b: Int, a: Int = 255): IntArray {
        fun row(i: Int): Int {
            val o = i * 5
            val v = matrix[o] * r + matrix[o + 1] * g + matrix[o + 2] * b + matrix[o + 3] * a + matrix[o + 4]
            return v.toInt().coerceIn(0, 255)
        }
        return intArrayOf(row(0), row(1), row(2))
    }

    /**
     * Contrast of the WORST pixel any washed icon can contain, against its tile.
     *
     * The wash is monotonic in source luminance, so the worst pixel is at one end of the band or
     * the other -- no need to sweep. By construction that worst end is the accent itself.
     */
    fun worstCaseGlyphContrast(
        accentR: Int, accentG: Int, accentB: Int,
        tileR: Int, tileG: Int, tileB: Int,
        span: Float,
    ): Double {
        val band = washBand(accentR, accentG, accentB, tileR, tileG, tileB, span)
        val lo = contrastRatio(band[0], band[1], band[2], tileR, tileG, tileB)
        val hi = contrastRatio(band[3], band[4], band[5], tileR, tileG, tileB)
        return minOf(lo, hi)
    }

    /** The same number for the pre-fix formula, for negative controls. */
    fun worstCaseGlyphContrastLegacy(
        accentR: Int, accentG: Int, accentB: Int,
        tileR: Int, tileG: Int, tileB: Int,
    ): Double {
        val m = legacyWashMatrix(accentR, accentG, accentB)
        val black = applyMatrix(m, 0, 0, 0)
        val white = applyMatrix(m, 255, 255, 255)
        return minOf(
            contrastRatio(black[0], black[1], black[2], tileR, tileG, tileB),
            contrastRatio(white[0], white[1], white[2], tileR, tileG, tileB),
        )
    }
}
