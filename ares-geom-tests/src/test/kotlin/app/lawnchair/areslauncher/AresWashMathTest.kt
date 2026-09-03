package app.lawnchair.areslauncher

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Regression cover for the non-adaptive icon wash (owner report, three times).
 *
 * The defect was pure arithmetic. `out = accent * luminance(src)` is a ramp from a FIXED black
 * pole; on a light tile black is the pole furthest from the tile so it was right by accident, and
 * on a dark tile it drove the glyph into its own background. A floor was tried first and only
 * patched the symptom -- it still ramped toward black, so its best case was a *dimmed* accent
 * (3.96:1 on the owner's palette) sitting beside monochrome icons rendered at the accent itself
 * (6.06:1). The owner saw both at once and said it still was not enough.
 *
 * Arithmetic is the one part of this launcher a JVM test can hold still, so this is where the
 * decision is pinned. Every test below is device-agnostic: it either drives the shipped matrix
 * directly, or reads a real shipped palette out of `res/values/colors.xml`.
 */
class AresWashMathTest {

    private val repoRoot = File(System.getProperty("user.dir")).parentFile

    // ---------------------------------------------------------------------------------------
    // Shipped palettes
    // ---------------------------------------------------------------------------------------

    /**
     * A shipped palette colour, read out of the real resource file rather than copied here.
     *
     * `res/values/colors.xml` holds the baked AOSP fallback palette -- the one every device below
     * API 34 actually gets, because `res/values-v34/colors.xml` is what forwards these names on to
     * the framework's wallpaper-derived `@android:color/system_*`. So it is a genuine shipped
     * palette and a genuine device-agnostic case, not a fixture invented for the test.
     */
    private fun bakedColor(name: String): Triple<Int, Int, Int> {
        val xml = File(repoRoot, "res/values/colors.xml").readText()
        val pattern = "<color name=\"" + name + "\">#([0-9A-Fa-f]{6})</color>"
        val hex = Regex(pattern).find(xml)?.groupValues?.get(1)
        checkNotNull(hex) { "res/values/colors.xml no longer defines a literal color named " + name }
        return Triple(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
        )
    }

    /**
     * Every palette this launcher can render the wash in, as (accent, tile) = (primary, onPrimary).
     *
     * The first two are parsed from the shipped resource. The rest were MEASURED off real devices
     * by sampling the modal pixel of a rendered themed icon (a synth monochrome glyph is painted at
     * exactly the accent by `BlendModeColorFilter(accent, SRC_IN)`, so its glyph colour IS
     * `materialColorPrimary` and its tile IS `materialColorOnPrimary`). They are here because they
     * come from the dynamic API-34+ path, which the baked pair cannot reach. The owner's Pixel
     * palette is the one that produced the third report, so it earns a permanent place.
     * See `design/scripts/icon-pixel-contrast.ps1`.
     */
    private fun palettes(): List<Triple<String, Triple<Int, Int, Int>, Triple<Int, Int, Int>>> = listOf(
        Triple("baked AOSP light", bakedColor("system_primary_light"), bakedColor("system_on_primary_light")),
        Triple("baked AOSP dark", bakedColor("system_primary_dark"), bakedColor("system_on_primary_dark")),
        Triple("owner Pixel dark", Triple(0xFF, 0x88, 0xB4), Triple(0x61, 0x00, 0x33)),
        Triple("emulator dark", Triple(0xAF, 0xC7, 0xEB), Triple(0x30, 0x47, 0x65)),
        Triple("emulator light", Triple(0x42, 0x62, 0x8A), Triple(0xEE, 0xF0, 0xF9)),
    )

    /** Contrast of the accent against its own tile -- what the MONOCHROME icon path renders at. */
    private fun accentContrast(accent: Triple<Int, Int, Int>, tile: Triple<Int, Int, Int>) =
        AresWashMath.contrastRatio(
            accent.first, accent.second, accent.third,
            tile.first, tile.second, tile.third,
        )

    private fun worstCase(span: Float) = palettes().map { (name, accent, tile) ->
        name to AresWashMath.worstCaseGlyphContrast(
            accent.first, accent.second, accent.third,
            tile.first, tile.second, tile.third,
            span,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The decision
    // ---------------------------------------------------------------------------------------

    /**
     * THE DECISION, and the reason the floor was abandoned.
     *
     * A washed legacy icon must never be dimmer than a themed one. The band's endpoints are the
     * accent and a colour strictly further from the tile, so the worst pixel in any washed icon has
     * *exactly* the accent's contrast -- the same number the monochrome path renders. Not "clears
     * 3:1": equal to the icons sitting next to it.
     */
    @Test
    fun `the worst washed pixel is exactly as readable as a themed icon`() {
        for ((name, accent, tile) in palettes()) {
            val worst = AresWashMath.worstCaseGlyphContrast(
                accent.first, accent.second, accent.third,
                tile.first, tile.second, tile.third,
                AresWashMath.DEFAULT_WASH_SPAN,
            )
            assertThat(name to worst).isNotNull()
            assertThat(worst).isWithin(1e-9).of(accentContrast(accent, tile))
            assertThat(worst).isAtLeast(AresWashMath.MIN_GLYPH_CONTRAST)
        }
    }

    /** And that holds for every span, because the accent is always one end of the band. */
    @Test
    fun `the guarantee does not depend on the span`() {
        for (span in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            for ((name, accent, tile) in palettes()) {
                val worst = AresWashMath.worstCaseGlyphContrast(
                    accent.first, accent.second, accent.third,
                    tile.first, tile.second, tile.third,
                    span,
                )
                assertThat(name to span).isNotNull()
                assertThat(worst).isWithin(1e-9).of(accentContrast(accent, tile))
            }
        }
    }

    /**
     * THE NEGATIVE CONTROL. Floor and pole are different fixes for different things, and only one
     * of them addresses the report -- so the old formula must still fail here, on a dark tile.
     *
     * Measured: owner Pixel palette 1.12:1, baked AOSP dark 2.02:1. On a LIGHT tile the same
     * formula is fine (~19:1), which is exactly why this went unnoticed for weeks and why the
     * control has to be mode-specific to discriminate at all.
     */
    @Test
    fun `the original formula is unreadable on a dark tile - negative control`() {
        val darkTiles = palettes().filter { (_, _, tile) ->
            AresWashMath.poleIsWhite(tile.first, tile.second, tile.third)
        }
        assertThat(darkTiles).isNotEmpty()
        for ((name, accent, tile) in darkTiles) {
            val legacy = AresWashMath.worstCaseGlyphContrastLegacy(
                accent.first, accent.second, accent.third,
                tile.first, tile.second, tile.third,
            )
            assertThat(name to legacy).isNotNull()
            assertThat(legacy).isLessThan(AresWashMath.MIN_GLYPH_CONTRAST)
        }
    }

    /**
     * THE SECOND NEGATIVE CONTROL: the floor that shipped first is also not good enough, which is
     * the whole reason this file changed. Reproduce it (`accent * (0.8 + 0.2 * lum)`, worst case at
     * lum 0) and require it to fall MEASURABLY short of the accent on a dark tile. Owner Pixel:
     * 3.96:1 against 6.06:1.
     */
    @Test
    fun `the floor approach falls short of the accent on a dark tile - negative control`() {
        var checked = 0
        for ((name, accent, tile) in palettes()) {
            if (!AresWashMath.poleIsWhite(tile.first, tile.second, tile.third)) continue
            val floored = AresWashMath.contrastRatio(
                (accent.first * 0.80f).toInt(), (accent.second * 0.80f).toInt(), (accent.third * 0.80f).toInt(),
                tile.first, tile.second, tile.third,
            )
            assertThat(name to floored).isNotNull()
            assertThat(floored).isLessThan(accentContrast(accent, tile) - 1.0)
            checked++
        }
        assertThat(checked).isGreaterThan(0)
    }

    // ---------------------------------------------------------------------------------------
    // The matrix itself
    // ---------------------------------------------------------------------------------------

    /**
     * The offset column is in 0..255 units, not 0..1. Writing it as 0..1 compiles, runs, and
     * produces a near-black glyph -- it silently reintroduces the original bug. Nothing else in the
     * build would catch that, so it is asserted directly: the matrix's endpoints must be the band's
     * endpoints.
     */
    @Test
    fun `the matrix endpoints are the band endpoints`() {
        for ((name, accent, tile) in palettes()) {
            val band = AresWashMath.washBand(
                accent.first, accent.second, accent.third,
                tile.first, tile.second, tile.third,
                AresWashMath.DEFAULT_WASH_SPAN,
            )
            val m = AresWashMath.washMatrix(
                accent.first, accent.second, accent.third,
                tile.first, tile.second, tile.third,
                AresWashMath.DEFAULT_WASH_SPAN,
            )
            val atBlack = AresWashMath.applyMatrix(m, 0, 0, 0)
            val atWhite = AresWashMath.applyMatrix(m, 255, 255, 255)
            assertThat(name).isNotEmpty()
            for (i in 0..2) {
                assertThat(atBlack[i]).isWithin(1).of(band[i])
                assertThat(atWhite[i]).isWithin(1).of(band[i + 3])
            }
        }
    }

    /**
     * Shape must survive. A plain `SRC_IN` tint is the reflex fix and is wrong here: many legacy
     * icons are fully opaque squares with no alpha shape, and SRC_IN renders them as solid coloured
     * blocks with the artwork thrown away. So the wash has to stay strictly monotonic in source
     * luminance -- and now it is monotonic in BOTH modes, which the floor version was not.
     */
    @Test
    fun `wash stays monotonic in source luminance in every palette`() {
        for ((name, accent, tile) in palettes()) {
            val m = AresWashMath.washMatrix(
                accent.first, accent.second, accent.third,
                tile.first, tile.second, tile.third,
                AresWashMath.DEFAULT_WASH_SPAN,
            )
            var previous = -1.0
            for (grey in 0..255 step 15) {
                val out = AresWashMath.applyMatrix(m, grey, grey, grey)
                val lum = AresWashMath.relativeLuminance(out[0], out[1], out[2])
                assertThat(name to grey).isNotNull()
                assertThat(lum).isGreaterThan(previous)
                previous = lum
            }
        }
    }

    /**
     * The pole is chosen by the TILE, not by the accent and not by a build flag. This is the whole
     * fix in one assertion, and it is the thing a future refactor is most likely to quietly drop.
     */
    @Test
    fun `the pole is chosen away from the tile`() {
        assertThat(AresWashMath.poleIsWhite(0x61, 0x00, 0x33)).isTrue()
        assertThat(AresWashMath.poleIsWhite(0xF9, 0xF8, 0xFF)).isFalse()

        // Same accent, opposite tiles -> opposite ramp directions.
        val onDark = AresWashMath.washBand(0xFF, 0x88, 0xB4, 0x61, 0x00, 0x33, 0.5f)
        val onLight = AresWashMath.washBand(0xFF, 0x88, 0xB4, 0xF9, 0xF8, 0xFF, 0.5f)
        // Dark tile: black artwork sits AT the accent and white artwork goes brighter.
        assertThat(onDark[0]).isEqualTo(0xFF)
        assertThat(onDark[4]).isGreaterThan(0x88)
        // Light tile: white artwork sits AT the accent and black artwork goes darker.
        assertThat(onLight[4]).isEqualTo(0x88)
        assertThat(onLight[1]).isLessThan(0x88)
    }

    /** The two luminance weightings in play are different, and conflating them skews everything. */
    @Test
    fun `WCAG luminance is not the matrix luminance`() {
        // Pure green: NTSC weights it 0.587, WCAG (on linearised channels) 0.7152.
        assertThat(AresWashMath.relativeLuminance(0, 255, 0)).isWithin(1e-4).of(0.7152)
        assertThat(AresWashMath.contrastRatio(255, 255, 255, 0, 0, 0)).isWithin(1e-6).of(21.0)
        assertThat(AresWashMath.contrastRatio(1, 2, 3, 1, 2, 3)).isWithin(1e-9).of(1.0)
    }

    /**
     * A span of 0 collapses the band to a flat accent silhouette -- which is exactly `SRC_IN`, the
     * fix that was rejected because it throws away the artwork of an opaque legacy icon. Asserted
     * so that "just set the span to 0" is recognisably that rejected fix rather than a tuning
     * choice.
     */
    @Test
    fun `span zero degenerates to a flat SRC_IN tint`() {
        val m = AresWashMath.washMatrix(0xFF, 0x88, 0xB4, 0x61, 0x00, 0x33, span = 0f)
        val atBlack = AresWashMath.applyMatrix(m, 0, 0, 0)
        val atWhite = AresWashMath.applyMatrix(m, 255, 255, 255)
        assertThat(atBlack.toList()).isEqualTo(atWhite.toList())
        assertThat(atBlack.toList()).isEqualTo(listOf(0xFF, 0x88, 0xB4))
    }
}
