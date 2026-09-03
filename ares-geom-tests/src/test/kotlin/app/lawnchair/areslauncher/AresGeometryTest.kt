package app.lawnchair.areslauncher

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Device-agnostic geometry checks. No Android runtime, no emulator, no device — this runs on the
 * JVM in about a second, which is the only reason it can run on every commit.
 *
 * ## What it is for
 *
 * This launcher is developed on exactly two devices: a Pixel Fold and one AVD that mimics the same
 * Fold. The stated goal is that it run on any newer Android device. Nothing else in the project can
 * see a second device class at all — an `adb shell wm size` sweep cannot, because `mIsFoldable` is a
 * `PackageManager` hinge feature the AVD always reports, so `isTwoPanels = isTablet &&
 * isMultiDisplay` is unreachable-false there and every "tablet" configuration is a foldable in a
 * costume. This file is the cheapest instrument that can see past that.
 *
 * ## It reads the real resources, not copies of them
 *
 * The ergonomic paddings and the row height are parsed out of the `ares_dimens.xml` in every
 * `res/values` bucket, and out of `res/layout/ares_all_apps_icon.xml` — the actual files the APK is
 * built from, resource qualifiers and all. That is deliberate and it is the whole point: this
 * project's recorded failure mode is
 * assertions that pass on a broken build, and a test asserting on a mirrored constant is exactly
 * that. If somebody edits a dimen, this test sees the edit.
 *
 * The device specs are not invented either. The first four are AOSP's own fixture table from
 * `tests/multivalentTests/src/com/android/launcher3/AbstractDeviceProfileTest.kt:92-127`.
 */
class AresGeometryTest {

    /** A window the launcher has to lay out in. Sizes are px; density is `densityDpi / 160`. */
    data class Spec(val name: String, val widthPx: Int, val heightPx: Int, val densityDpi: Int) {
        val density: Float get() = densityDpi / 160f
        /** What Android's `h<N>dp` resource qualifier matches against. */
        val heightDp: Int get() = (heightPx / density).toInt()
        override fun toString() = "$name (${widthPx}x$heightPx @ ${densityDpi}dpi, ${heightDp}dp tall)"
    }

    private val specs = listOf(
        // AOSP's fixture table -- the rows Google themselves parameterise DeviceProfile over.
        Spec("aosp-phone", 1080, 2400, 420),
        Spec("aosp-tablet", 2560, 1600, 320),
        Spec("aosp-twopanel-phone", 1080, 2092, 420),
        Spec("aosp-twopanel-tablet", 2208, 1840, 420),
        // The two devices this project actually develops on.
        Spec("pixel-fold-inner", 2076, 2152, 390),
        Spec("pixel-fold-outer", 1080, 2364, 420),
        // Configurations it has never been run on. Each is an ordinary device or an ordinary
        // Settings toggle, not an exotic case.
        Spec("compact-phone", 720, 1280, 320),
        Spec("high-density-phone", 1440, 3120, 560),
        Spec("low-density-phone", 480, 800, 160),
        Spec("phone-landscape", 2400, 1080, 420),
        Spec("high-density-landscape", 3120, 1440, 560),
        Spec("large-tablet", 2560, 1600, 240),
    )

    // ------------------------------------------------------------------ resource reading

    private val repoRoot: File by lazy {
        var d = File(System.getProperty("user.dir")!!).absoluteFile
        while (!File(d, "res/values/ares_dimens.xml").exists()) {
            d = d.parentFile ?: error("could not locate the repo root from ${System.getProperty("user.dir")}")
        }
        d
    }

    /** Reads a `<dimen>` in dp from one resource bucket, or null if that bucket does not define it. */
    private fun dimenDp(bucket: String, name: String): Int? {
        val f = File(repoRoot, "res/$bucket/ares_dimens.xml")
        if (!f.exists()) return null
        val m = Regex("""<dimen name="$name">\s*([0-9.]+)(dp|sp)\s*</dimen>""").find(f.readText())
        return m?.groupValues?.get(1)?.toFloat()?.toInt()
    }

    /**
     * Resolves a dimen the way Android would for a window [heightDp] tall: the most specific
     * `values-h<N>dp` bucket whose N the window satisfies, falling back to plain `values`.
     */
    private fun resolveDp(heightDp: Int, name: String): Int {
        val buckets = (File(repoRoot, "res").listFiles() ?: emptyArray())
            .mapNotNull { Regex("""^values-h(\d+)dp$""").find(it.name)?.groupValues?.get(1)?.toInt() }
            .filter { it <= heightDp }
            .sortedDescending()
        for (n in buckets) dimenDp("values-h${n}dp", name)?.let { return it }
        return dimenDp("values", name) ?: error("dimen $name not defined in res/values")
    }

    private fun totalErgoDp(heightDp: Int): Int =
        resolveDp(heightDp, "ares_list_ergo_top_padding") +
            resolveDp(heightDp, "ares_list_ergo_bottom_padding") +
            resolveDp(heightDp, "ares_home_list_top_padding")

    private val appRowLayout: String by lazy {
        File(repoRoot, "res/layout/ares_all_apps_icon.xml").readText()
    }

    // ------------------------------------------------------------------ resource well-formedness

    /**
     * Every resource this suite reads must be well-formed XML.
     *
     * Added after the regex readers below cheerfully parsed a file that `aapt2` would have
     * rejected: a comment I wrote contained `--`, which the XML spec forbids inside a comment, and
     * another had prose sitting outside its `-->`. Both files still matched the dimen regexes, so
     * every other test in this class passed while the resource was unbuildable.
     *
     * That is this project's signature failure in miniature - a check that cannot see the damage -
     * so the fix is not "be careful with comments", it is to parse the file properly at least once.
     */
    @Test
    fun `parsed resources are well-formed xml`() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val files = buildList {
            add(File(repoRoot, "res/layout/ares_all_apps_icon.xml"))
            (File(repoRoot, "res").listFiles() ?: emptyArray())
                .filter { it.isDirectory && it.name.startsWith("values") }
                .forEach { d -> File(d, "ares_dimens.xml").takeIf(File::exists)?.let(::add) }
        }
        assertThat(files).isNotEmpty()
        val bad = files.mapNotNull { f ->
            try { factory.newDocumentBuilder().parse(f); null } catch (e: Exception) {
                "${f.relativeTo(repoRoot)}: ${e.message}"
            }
        }
        assertThat(bad).isEmpty()
    }

    // ------------------------------------------------------------------ drag thresholds

    /**
     * `ViewConfiguration`'s touch slop is 8dp, scaled and rounded by the platform. Reproduced here
     * because the real one needs a `Context`; the arithmetic is stable and it is what matters.
     */
    private fun touchSlopPx(density: Float): Int = (8 * density + 0.5f).toInt()

    /**
     * The dwell tolerance must never fall below the platform's own touch slop.
     *
     * This is why the module exists. `DWELL_SLOP_PX` was a raw `18f`: right at the Fold's density
     * 2.4375 (slop 19.5) and broken at density 4.0 (slop 32), where the dwell timer restarted on
     * jitter Android itself classifies as stationary — making dwell-to-create-folder
     * **unreachable**, on the surface whose open defects are all dwell timing. Nothing on the
     * emulator could have caught it: that AVD is 390dpi.
     */
    @Test
    fun `dwell tolerance is never below the platform touch slop`() {
        for (s in specs) {
            val slop = touchSlopPx(s.density)
            assertThat(AresGeometry.dwellSlopPx(slop)).isAtLeast(slop.toFloat())
        }
    }

    /** A layout reframe must stay distinguishable from a finger move, at every density. */
    @Test
    fun `reframe bound stays clear of the dwell tolerance`() {
        for (s in specs) {
            val slop = touchSlopPx(s.density)
            assertThat(AresGeometry.reframeJumpPx(slop))
                .isGreaterThan(AresGeometry.dwellSlopPx(slop))
        }
    }

    /** Both thresholds must scale with the screen, or they are tuned for exactly one device. */
    @Test
    fun `drag thresholds scale with density`() {
        val low = AresGeometry.dwellSlopPx(touchSlopPx(1.0f))
        val high = AresGeometry.dwellSlopPx(touchSlopPx(4.0f))
        assertThat(high).isGreaterThan(low * 3)
    }

    // ------------------------------------------------------------------ list ergonomics

    /**
     * Fixed ergonomic padding must leave a usable list on every window.
     *
     * 247dp of padding is 28% of the Fold's inner panel, which is what it was tuned against — and
     * measured here at 35% of AOSP's twopanel-tablet, 39% of a compact phone and 60% of this same
     * phone in landscape before the `values-h600dp` / `values-h720dp` buckets existed.
     * `AresMasonryLayoutManager` clamps the viewport at `coerceAtLeast(0)`, so the failure mode is
     * not a crash but a surface with almost no room and nothing in the log.
     */
    @Test
    fun `fixed ergonomic padding leaves a usable list on every window`() {
        val bad = specs.mapNotNull { s ->
            val frac = AresGeometry.ergoFractionOf(s.heightPx, s.density, totalErgoDp(s.heightDp))
            if (frac >= AresGeometry.MAX_ERGO_FRACTION) "$s -> ${"%.0f%%".format(frac * 100)}" else null
        }
        assertThat(bad).isEmpty()
    }

    /** The Fold must keep the exact values it was tuned with -- the buckets must not disturb it. */
    @Test
    fun `the tuned Fold values are unchanged`() {
        val fold = specs.first { it.name == "pixel-fold-inner" }
        assertThat(resolveDp(fold.heightDp, "ares_list_ergo_top_padding")).isEqualTo(100)
        assertThat(resolveDp(fold.heightDp, "ares_list_ergo_bottom_padding")).isEqualTo(140)
    }

    // ------------------------------------------------------------------ app-list row

    /**
     * The app-list row must be able to grow for its own label.
     *
     * The label is `sp`-sized, so it tracks the user's font scale; Android 14+ non-linear font
     * scaling reaches 2.0x and Display Size (largest) adds ~1.3x on top. A hard `layout_height` of
     * 52dp cannot absorb either, and 52dp is less than the ~52.2dp a 17sp label needs at 2.0x —
     * before the 32dp icon is considered. So the height must be a floor, not a cap.
     */
    @Test
    fun `app row height is a floor, not a fixed height`() {
        assertThat(appRowLayout).contains("""android:layout_height="wrap_content"""")
        assertThat(appRowLayout).contains("""android:minHeight="@dimen/ares_app_row_height"""")
    }

    /** And the floor itself must still clear the icon and the label at the default font scale. */
    @Test
    fun `app row floor clears its icon and its label at default font scale`() {
        val h = dimenDp("values", "ares_app_row_height")!!
        val icon = dimenDp("values", "ares_app_row_icon_size")!!
        val textSp = dimenDp("values", "ares_app_row_text_size")!!
        assertThat(h).isAtLeast(icon + 8)
        assertThat(h.toFloat()).isAtLeast(AresGeometry.minRowHeightDp(textSp, 1.0f))
    }
}
