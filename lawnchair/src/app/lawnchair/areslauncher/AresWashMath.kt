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
 * **Why it exists at all.** The wash shipped for weeks as a plain luminance multiply,
 * `out = accent * luminance(src)`, which sets a ceiling and no floor: a black source pixel stays
 * black however bright the accent is, and it is then drawn on the themed tile. Measured on
 * 2026-09-02 that put a dark-artwork legacy icon at **1.44:1** against its own tile -- invisible --
 * and even the light fixture the path had been "verified" against sat at 1.20:1 at its darkest
 * pixel. Two separate owner reports of "the glyph is a bit dark" were this.
 *
 * The bug was arithmetic, and arithmetic is exactly what a JVM test can hold still.
 */
object AresWashMath {

    // Luminance weights (Rec. 601). These are the weights the wash MATRIX uses. They are NOT the
    // WCAG weights below, and conflating the two silently skews every contrast number -- see
    // [relativeLuminance].
    const val LR = 0.299f
    const val LG = 0.587f
    const val LB = 0.114f

    /**
     * How much of the accent a washed glyph keeps at its DARKEST.
     *
     * Measured trade-off, not a taste call (accent #FF88B4 on tile #610033, the palette in play when
     * the defect was reported; reproduce with `design/scripts/icon-wash-contrast.ps1`):
     *
     *   floor   dark artwork          light artwork
     *   0.00    1.44:1 (the bug)      2.49:1 mean, 1.20:1 at its darkest
     *   0.55    2.30:1                4.17:1 mean
     *   0.70    3.28:1                4.75:1 mean
     *   0.80    4.08:1                5.15:1 mean, 4.59:1 at its darkest
     *
     * 0.80 is the lowest value that puts BOTH probes clear of the 3:1 WCAG bar for non-text
     * graphics with margin. The cost is that shading compresses into a 20% band; that is deliberate,
     * because the luminance term's job here is only to carry SHAPE.
     */
    const val DEFAULT_WASH_FLOOR = 0.80f

    /** The WCAG bar for non-text graphics and UI components (SC 1.4.11). An icon glyph is one. */
    const val MIN_GLYPH_CONTRAST = 3.0

    /**
     * The fraction of the accent a source pixel of luminance [srcLum] keeps:
     * `floor + (1 - floor) * srcLum`, i.e. the luminance term remapped from `[0,1]` into
     * `[floor, 1]`. At `floor = 0` this degenerates to the shipped bug.
     */
    fun washFraction(srcLum: Float, floor: Float): Float = floor + (1f - floor) * srcLum

    /**
     * The 4x5 `ColorMatrix` that folds a source pixel onto [accentR]/[accentG]/[accentB] (each
     * 0..255) with the given [floor].
     *
     * **The offset column is in 0..255 units, not 0..1.** Getting that wrong yields a near-black
     * glyph -- which is to say, it yields exactly the bug this replaced, silently and with no
     * compile error. That is the single most important fact about this function and the reason
     * [washMatrix] is tested rather than trusted.
     */
    fun washMatrix(accentR: Int, accentG: Int, accentB: Int, floor: Float): FloatArray {
        val ar = accentR / 255f
        val ag = accentG / 255f
        val ab = accentB / 255f
        val k = 1f - floor
        return floatArrayOf(
            ar * k * LR, ar * k * LG, ar * k * LB, 0f, ar * floor * 255f,
            ag * k * LR, ag * k * LG, ag * k * LB, 0f, ag * floor * 255f,
            ab * k * LR, ab * k * LG, ab * k * LB, 0f, ab * floor * 255f,
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
     * Contrast of the WORST-CASE washed glyph against its tile: the source pixel of luminance 0,
     * which the wash maps to `accent * floor`. Every other source pixel lands between this and the
     * full accent, and the accent itself is a contrast-safe pair with the tile by Material 3
     * construction -- so this one number bounds the whole icon.
     */
    fun worstCaseGlyphContrast(
        accentR: Int, accentG: Int, accentB: Int,
        tileR: Int, tileG: Int, tileB: Int,
        floor: Float,
    ): Double {
        val m = washMatrix(accentR, accentG, accentB, floor)
        val out = applyMatrix(m, 0, 0, 0)
        return contrastRatio(out[0], out[1], out[2], tileR, tileG, tileB)
    }
}
