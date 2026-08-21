package app.lawnchair.areslauncher

/**
 * Build fingerprint for the INDEPENDENT VERIFICATION pass of W1 and S12 (2026-08-21).
 *
 * Nothing calls this. It exists so `dexdump` can prove which source state the installed APK was
 * built from: `versionName` comes from `git describe` and is identical across the fixed and the
 * deliberately-broken builds, and the shared Gradle cache has repackaged another tree's classes
 * while reporting "up-to-date". The method name is rewritten by
 * `design-verify/set-state.sh` for every build state, so a `dexdump` grep is unambiguous.
 *
 * Delete along with AresVerifyProbe once verification is reported.
 */
internal object AresVerifyMarker {
    fun vrfyStateFIXED() = Unit
}
