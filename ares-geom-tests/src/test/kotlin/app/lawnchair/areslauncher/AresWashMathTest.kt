package app.lawnchair.areslauncher

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Regression cover for the non-adaptive icon wash (owner report, twice: "the glyph is a bit dark").
 *
 * The defect was pure arithmetic -- `out = accent * luminance(src)`, a ceiling with no floor, so a
 * dark legacy icon washed to near-black and was then drawn on the dark themed tile at 1.44:1. It
 * survived for weeks because the only fixture anyone checked it against happened to be light
 * artwork, and even that one was readable only in its very brightest pixels.
 *
 * Arithmetic is the one part of this launcher a JVM test can hold still, so this is where the
 * decision is pinned. Every test below is device-agnostic: it either drives the shipped matrix
 * directly, or reads a real shipped palette out of `res/values/colors.xml`.
 */
class AresWashMathTest {

    private val repoRoot = File(System.getProperty("user.dir")).parentFile

    // ---------------------------------------------------------------------------------------
    // The matrix itself
    // ---------------------------------------------------------------------------------------

    /**
     * The offset column is in 0..255 units, not 0..1. Writing it as 0..1 compiles, runs, and
     * produces a near-black glyph -- it silently reintroduces the exact bug. Nothing else in the
     * build would catch that, so it is asserted first and directly: a BLACK source pixel must come
     * out at `accent * floor`.
     */
    @Test
    fun `black source washes to accent times floor`() {
        val floor = 0.80f
        val m = AresWashMath.washMatrix(0xFF, 0x88, 0xB4, floor)
        val out = AresWashMath.applyMatrix(m, 0, 0, 0)

        assertThat(out[0]).isEqualTo((0xFF * floor).toInt())
        assertThat(out[1]).isEqualTo((0x88 * floor).toInt())
        assertThat(out[2]).isEqualTo((0xB4 * floor).toInt())
    }

    /**
     * A WHITE source pixel must come out at the FULL accent, at any floor. This is what keeps the
     * fix from being "just tint everything": the top of the range still reaches the accent exactly,
     * so a light legacy icon renders as it always did.
     */
    @Test
    fun `white source washes to the full accent at every floor`() {
        for (floor in listOf(0.0f, 0.55f, 0.80f, 1.0f)) {
            val m = AresWashMath.washMatrix(0xFF, 0x88, 0xB4, floor)
            val out = AresWashMath.applyMatrix(m, 255, 255, 255)
            assertThat(out[0]).isWithin(1).of(0xFF)
            assertThat(out[1]).isWithin(1).of(0x88)
            assertThat(out[2]).isWithin(1).of(0xB4)
        }
    }

    /**
     * Shape must survive. A plain `SRC_IN` tint is the obvious fix and is wrong here: many legacy
     * icons are fully opaque squares with no alpha shape, and SRC_IN renders them as solid coloured
     * blocks with the artwork thrown away. So the wash has to stay strictly monotonic in source
     * luminance -- darker artwork stays darker -- rather than collapsing to one colour.
     */
    @Test
    fun `wash stays monotonic in source luminance`() {
        val m = AresWashMath.washMatrix(0xAF, 0xC7, 0xEB, AresWashMath.DEFAULT_WASH_FLOOR)
        var previous = -1.0
        for (grey in 0..255 step 15) {
            val out = AresWashMath.applyMatrix(m, grey, grey, grey)
            val lum = AresWashMath.relativeLuminance(out[0], out[1], out[2])
            assertThat(lum).isGreaterThan(previous)
            previous = lum
        }
    }

    /** The two luminance weightings in play are different, and conflating them skews everything. */
    @Test
    fun `WCAG luminance is not the matrix luminance`() {
        // Pure green: NTSC weights it 0.587, WCAG (on linearised channels) 0.7152.
        assertThat(AresWashMath.relativeLuminance(0, 255, 0)).isWithin(1e-4).of(0.7152)
        assertThat(AresWashMath.LG).isWithin(1e-6f).of(0.587f)

        // Anchors for the contrast formula.
        assertThat(AresWashMath.contrastRatio(255, 255, 255, 0, 0, 0)).isWithin(1e-6).of(21.0)
        assertThat(AresWashMath.contrastRatio(1, 2, 3, 1, 2, 3)).isWithin(1e-9).of(1.0)
    }

    // ---------------------------------------------------------------------------------------
    // The decision: the shipped floor clears the readability bar, and floor 0 does not
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
     * The first two are parsed from the shipped resource. The last two were MEASURED off
     * emulator-5554 on 2026-09-02 by sampling the modal pixel of a rendered themed icon (a synth
     * monochrome glyph is painted at exactly the accent by `BlendModeColorFilter(accent, SRC_IN)`,
     * so its glyph colour IS `materialColorPrimary` and its tile IS `materialColorOnPrimary`).
     * They are here because they come from the dynamic API-34+ path, which the baked pair cannot
     * reach -- see `design/scripts/icon-pixel-contrast.ps1`.
     */
    private fun palettes(): List<Triple<String, Triple<Int, Int, Int>, Triple<Int, Int, Int>>> = listOf(
        Triple("baked AOSP light", bakedColor("system_primary_light"), bakedColor("system_on_primary_light")),
        Triple("baked AOSP dark", bakedColor("system_primary_dark"), bakedColor("system_on_primary_dark")),
        Triple("measured dynamic dark", Triple(0xAF, 0xC7, 0xEB), Triple(0x30, 0x47, 0x65)),
        Triple("measured dynamic light", Triple(0x42, 0x62, 0x8A), Triple(0xEE, 0xF0, 0xF9)),
    )

    private fun worstCase(floor: Float): List<Pair<String, Double>> = palettes().map { (name, accent, tile) ->
        name to AresWashMath.worstCaseGlyphContrast(
            accent.first, accent.second, accent.third,
            tile.first, tile.second, tile.third,
            floor,
        )
    }

    /**
     * The palettes whose TILE is dark, which is the only place the defect ever bit.
     *
     * This distinction is the thing the first draft of these tests got wrong, and it is worth
     * spelling out. Floor 0 drives the glyph toward BLACK. Against the light tile of a light-mode
     * palette that is a contrast *increase* -- ~20:1, gorgeous, and completely useless as evidence.
     * The bug lived entirely in dark mode, where black-on-#323F60 is 2.02:1. A negative control
     * that averages the two modes together, or asserts over both, does not discriminate.
     */
    private fun darkTilePalettes() = palettes().filter { (_, _, tile) ->
        AresWashMath.relativeLuminance(tile.first, tile.second, tile.third) < 0.5
    }

    private fun worstCaseOnDarkTiles(floor: Float): List<Pair<String, Double>> =
        darkTilePalettes().map { (name, accent, tile) ->
            name to AresWashMath.worstCaseGlyphContrast(
                accent.first, accent.second, accent.third,
                tile.first, tile.second, tile.third,
                floor,
            )
        }

    /**
     * THE DECISION. At the shipped floor, the darkest pixel a legacy icon can contain still clears
     * the WCAG 3:1 bar for non-text graphics against its own tile, in every palette we ship.
     *
     * The worst case is the black source pixel, because the wash is monotonic (asserted above) and
     * its top end is the accent, which Material 3 already guarantees is a contrast-safe pair with
     * onPrimary. So this single number bounds the whole icon.
     */
    @Test
    fun `shipped floor keeps the darkest glyph pixel readable in every shipped palette`() {
        for ((name, contrast) in worstCase(AresWashMath.DEFAULT_WASH_FLOOR)) {
            assertThat(name to contrast).isNotNull()
            assertThat(contrast).isAtLeast(AresWashMath.MIN_GLYPH_CONTRAST)
        }
    }

    /**
     * THE NEGATIVE CONTROL, and the reason the test above is worth anything.
     *
     * An assertion is not coverage until it has been made to fail on the broken path. Floor 0 IS
     * the broken path -- byte for byte the formula that shipped -- so on a dark tile the same check
     * must report these palettes as unreadable. If this ever passes, the check above has stopped
     * discriminating and is green for the wrong reason.
     *
     * Measured values at floor 0: baked AOSP dark 2.02:1, measured dynamic dark 2.21:1.
     */
    @Test
    fun `floor zero is unreadable on a dark tile - negative control`() {
        val cases = worstCaseOnDarkTiles(0f)
        assertThat(cases).isNotEmpty()
        for ((name, contrast) in cases) {
            assertThat(name to contrast).isNotNull()
            assertThat(contrast).isLessThan(AresWashMath.MIN_GLYPH_CONTRAST)
        }
    }

    /**
     * 0.80 is claimed to be the LOWEST floor that clears the bar everywhere, and a claim like that
     * rots quietly: someone drops it to 0.70 for a softer look, the light palettes still pass with
     * room to spare, and the dark ones quietly go back under the bar.
     *
     * Measured on a dark tile: 0.70 gives 3.07:1 for the baked palette but only **2.73:1** for the
     * dynamic palette measured on device -- under the bar. 0.80 gives 3.91 and 3.52.
     */
    @Test
    fun `the floor sits at the boundary, not comfortably above it`() {
        assertThat(worstCase(AresWashMath.DEFAULT_WASH_FLOOR).minOf { it.second })
            .isAtLeast(AresWashMath.MIN_GLYPH_CONTRAST)
        assertThat(worstCaseOnDarkTiles(0.70f).minOf { it.second })
            .isLessThan(AresWashMath.MIN_GLYPH_CONTRAST)
    }

    /**
     * The trap that makes "just compromise on a middle value" actively wrong.
     *
     * Contrast against a dark tile is NOT monotonic in the floor. Raising it from 0 walks the glyph
     * UP through the tile's own luminance before it comes out the other side, so a half-measure is
     * worse than doing nothing: on the palette measured on device, floor 0 gives 2.21:1 and floor
     * 0.55 gives **1.81:1**. Anyone who softens the floor a little and eyeballs it will conclude the
     * fix did not work -- and will be looking at a real regression while thinking they compromised.
     */
    @Test
    fun `a middle floor is worse than no floor on a dark tile`() {
        val atZero = worstCaseOnDarkTiles(0f).toMap()
        val atHalf = worstCaseOnDarkTiles(0.55f).toMap()
        val regressed = atZero.keys.filter { atHalf.getValue(it) < atZero.getValue(it) }
        assertThat(regressed).isNotEmpty()
    }

    /**
     * The device agreed with the model. On 2026-09-02 the dark-artwork probe rendered #8FA3C0 on
     * tile #304765 with accent #AFC7EB -- the device drew `accent * 0.817`, and the model says a
     * source luminance of 0.085 at floor 0.80 gives `0.80 + 0.20 * 0.085 = 0.817`. Pinning that
     * here means a future change to the formula has to explain the discrepancy against a real
     * measurement rather than just rebuilding.
     */
    @Test
    fun `model reproduces the fraction measured on device`() {
        val fraction = AresWashMath.washFraction(srcLum = 0.085f, floor = AresWashMath.DEFAULT_WASH_FLOOR)
        assertThat(fraction).isWithin(0.002f).of(0.817f)

        // ... and the matrix, driven at that source luminance, lands on the pixel the device drew.
        val m = AresWashMath.washMatrix(0xAF, 0xC7, 0xEB, AresWashMath.DEFAULT_WASH_FLOOR)
        val grey = (0.085f * 255).toInt()
        val out = AresWashMath.applyMatrix(m, grey, grey, grey)
        assertThat(out[0]).isWithin(2).of(0x8F)
        assertThat(out[1]).isWithin(2).of(0xA3)
        assertThat(out[2]).isWithin(2).of(0xC0)
    }
}
