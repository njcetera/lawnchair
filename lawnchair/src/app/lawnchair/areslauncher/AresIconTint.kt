package app.lawnchair.areslauncher

import com.android.launcher3.Launcher

/**
 * Applies the edit-mode **icon tint** personalization (owner 2026-08-26): a unified-intensity
 * Material You tint that cross-fades every icon from its normal look (0%) toward its tinted look
 * (100%) -- Android themed (monochrome-accent) icon where an app ships one, a Material You colour
 * wash otherwise -- across the home grid, the app list, and folder contents. Strength 0..100.
 *
 * **Phase 1 (current): stub.** The tint pill's toggle and strength stepper persist the prefs
 * (`aresIconTintEnabled` / `aresIconTintStrength`) and call [apply] live, but the rendering is not
 * wired yet. Phase 2 spikes Lawnchair's themed-icon path, then re-tints the three surfaces in place.
 * Kept as a single entry point so the pill never has to know how the tint is realised.
 */
object AresIconTint {

    /**
     * Re-render the launcher's icons for the given tint state. No-op until Phase 2 wires the icon
     * draw/cache path. Safe to call on every stepper change (it will become an in-place re-tint,
     * never a recreate/reload -- a pref-driven recreate mid-edit is exactly the bug we must avoid).
     */
    @Suppress("UNUSED_PARAMETER")
    fun apply(launcher: Launcher, enabled: Boolean, strength: Int) {
        // Phase 2: resolve Material You accent, build the tint ColorFilter/blend, and invalidate the
        // icon caches / redraw BubbleTextViews on home, app list and folder surfaces.
    }
}
