package app.lawnchair.areslauncher

/**
 * The geometry constants and relationships that must hold on **any** Android device, not just the
 * one this launcher was tuned on.
 *
 * ## Why this file exists
 *
 * Every number here used to live somewhere that only a real device could evaluate: raw pixel
 * constants in [AresFolderDrop], `<dimen>` entries in `res/values/ares_dimens.xml`. That made them
 * untestable off-device, and it hid a class of defect nobody was looking for -- constants tuned by
 * feel on a Pixel Fold at density 2.4375, applied unconditionally to every device.
 *
 * ## The hard rule for this file
 *
 * **No Android imports. Ever.** It is compiled into the app *and* into a plain-JVM test module
 * (`:ares-geom-tests`) that has no Android runtime at all. An `import android.*` here breaks that
 * module, which is the only thing in the project that can check these relationships across a
 * device matrix in under a second.
 *
 * ## And the rule that makes it worth anything
 *
 * Production code must **consume** these, never restate them. A test that asserts on a copy of a
 * constant proves nothing about the constant the device actually uses -- that is the
 * "structurally blind assertion" failure this whole effort exists to end. If you add a number here,
 * delete the original.
 */
object AresGeometry {

    // ---------------------------------------------------------------- drag / dwell thresholds

    /**
     * Dwell drift tolerance, in multiples of the platform's own `scaledTouchSlop`.
     *
     * Was a raw `18f`. `scaledTouchSlop` is 8dp, which is 19.5px at the Fold's density 2.4375 --
     * so on that one device the raw constant was approximately right, and it was wrong everywhere
     * else. At density 4.0 the platform slop is 32px, so an 18px tolerance sat *below* the
     * threshold Android itself uses to decide a finger has moved at all: the dwell timer would
     * restart on jitter the system classifies as stationary, and **dwell-to-create-folder becomes
     * unreachable**. At density 1.0 it was 2.25x too permissive.
     *
     * 1.0 slops keeps the Fold at 19px against the old 18px -- a fractionally more permissive
     * dwell on the one device that was already tuned, and correct behaviour on every other.
     */
    const val DWELL_SLOP_SLOPS = 1.0f

    /**
     * A same-tile jump past this many touch slops in ONE frame is a layout reframe of the
     * reporting view, not a finger move. Fingers are continuous; frames are not.
     *
     * Was a raw `60f`, calibrated against a measured 180px reframe on the Fold. A reframe is a cell
     * displacement, so it scales with density *and* shrinks with the 3..6 column override -- at 6
     * columns on a small phone the real reframe can fall below a fixed 60px, at which point
     * reframes are misread as finger moves and the dwell restarts forever.
     *
     * 3.0 slops is 58px at the Fold's density against the old 60 -- within a pixel of the tuned
     * value, and it tracks density everywhere else.
     */
    const val REFRAME_JUMP_SLOPS = 3.0f

    /** Dwell drift tolerance in px. [touchSlopPx] is `ViewConfiguration.getScaledTouchSlop()`. */
    fun dwellSlopPx(touchSlopPx: Int): Float = touchSlopPx * DWELL_SLOP_SLOPS

    /** Reframe-detection bound in px. [touchSlopPx] is `ViewConfiguration.getScaledTouchSlop()`. */
    fun reframeJumpPx(touchSlopPx: Int): Float = touchSlopPx * REFRAME_JUMP_SLOPS

    // ---------------------------------------------------------------- list ergonomics
    //
    // NOTE what is deliberately NOT here: the dp values themselves. `ares_list_ergo_top_padding`
    // and friends live in `res/values*/ares_dimens.xml` and nowhere else, because Android resource
    // qualifiers are what actually select them per window size -- and because a constant mirrored
    // here would be a COPY, so a test asserting on it could pass while the shipped resource was
    // wrong. `:ares-geom-tests` parses the XML instead. Only policy lives here.

    /**
     * The fraction of the window that fixed ergonomic padding may consume before the list stops
     * being a list.
     *
     * 247dp of the three paddings above is 28% of the Fold's inner panel -- the shape it was tuned
     * for -- but **63.8% of a landscape phone** (1080px at 420dpi minus gesture inset) and 57% of a
     * split-screen half. `AresMasonryLayoutManager` clamps the viewport at `coerceAtLeast(0)`, so
     * the failure mode is not a crash: it is a surface that quietly has almost no room, and nothing
     * logs.
     */
    const val MAX_ERGO_FRACTION = 0.35f

    /**
     * What fraction of a window of [availableHeightPx] at [density] is consumed by [totalPaddingDp]
     * of fixed padding. Must stay under [MAX_ERGO_FRACTION].
     *
     * The padding total is a PARAMETER, not a constant, so the caller has to go and read the real
     * resource for the bucket it is testing.
     */
    fun ergoFractionOf(availableHeightPx: Int, density: Float, totalPaddingDp: Int): Float =
        (totalPaddingDp * density) / availableHeightPx

    // ---------------------------------------------------------------- app-list row

    /**
     * Android 14+ non-linear font scaling reaches 2.0x, and Display Size (largest) multiplies
     * density by a further ~1.3x. Both are ordinary Settings toggles, neither needs special
     * hardware, and a fixed-height row holding `sp` text has no way to absorb either.
     */
    const val MAX_FONT_SCALE = 2.0f

    /**
     * Minimum height a row needs to hold its label at [fontScale], in dp.
     *
     * 1.3 is the line-box multiplier over the type size (ascender + descender + leading); the
     * padding term is the layout's own vertical breathing room. Deliberately an estimate -- the
     * assertion it feeds is "does the fixed height leave any room at all", not a pixel claim.
     */
    fun minRowHeightDp(textSp: Int, fontScale: Float, verticalPaddingDp: Int = 4): Float =
        textSp * fontScale * 1.3f + 2 * verticalPaddingDp
}
