package app.lawnchair.nexuslauncher

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import app.lawnchair.FeedBridge
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import com.google.android.libraries.launcherclient.ISerializableScrollCallback
import com.google.android.libraries.launcherclient.LauncherClient
import com.google.android.libraries.launcherclient.LauncherClientCallbacks
import com.google.android.libraries.launcherclient.LauncherClientService
import com.google.android.libraries.launcherclient.StaticInteger

/**
 * Implements [LauncherOverlay] and passes all the corresponding events to [LauncherClient],
 * see [LauncherClientService.setClient].
 *
 * Implements [LauncherClientCallbacks] and sends all the corresponding callbacks to [Launcher].
 */
class OverlayCallbackImpl(private val mLauncher: LawnchairLauncher) :
    LauncherOverlay,
    LauncherClientCallbacks,
    LauncherOverlayManager,
    ISerializableScrollCallback {
    private val mClient: LauncherClient
    private var mFlagsChanged = false
    private var mLauncherOverlayCallbacks: LauncherOverlayCallbacks? = null
    private var mWasOverlayAttached = false
    private var mFlags = 0

    init {
        val prefs = PreferenceManager2.getInstance(mLauncher)
        val enableFeed = prefs.enableFeed.firstCached()
        mClient = LauncherClient(
            mLauncher,
            this,
            StaticInteger((if (enableFeed) 1 else 0) or 2 or 4 or 8),
        )
    }

    fun reconnect() {
        mClient.reconnect()
    }

    fun setEnableFeed(enable: Boolean) {
        mClient.setEnableFeed(enable)
        reconnect()
    }

    override fun onDeviceProvideChanged() {
        mClient.redraw()
    }

    override fun onAttachedToWindow() {
        mClient.onAttachedToWindow()
        ensureFoldSizeWatcher()
    }

    // --- Foldable display-switch handling -------------------------------------------------------
    //
    // On a fold/unfold the launcher (and Google's child overlay window) resize to the new panel, but
    // GSB latches its overlay geometry per attached window and is NOT re-notified when the resize
    // settles -- so the feed renders at the previous display's size (confirmed on a Pixel Fold: with
    // Discover open, folding left the feed laid out for the inner display on the cover screen).
    //
    // NOTE: the wide side margins Discover shows on the INNER display are Google's own large-screen
    // layout, NOT this bug -- verified with the owner 2026-09-01. Do not "fix" them.
    //
    // Re-handing geometry at onDeviceProvideChanged time is unreliable: that fires while the launcher
    // still reports the OLD size, and only when the feed is fully open at that exact instant -- so a
    // fold with the feed closed or mid-scroll (the common case) never corrects, and repeated folds
    // accumulate a stale size on both displays. Instead, watch the drag layer for a settled size
    // change and re-attach the overlay then,
    // whenever GSB is attached (open OR closed -- a closed re-attach is off-screen and invisible, and
    // makes the next open correctly scaled). Debounced so we capture the FINAL size, not an intermediate
    // animation frame (an intermediate re-attach is what previously latched a too-small size).
    private var mFoldSizeWatcherInstalled = false
    private var mLastFoldWidth = 0
    private var mLastFoldHeight = 0
    private var mPendingFoldReattach: Runnable? = null

    private fun ensureFoldSizeWatcher() {
        if (mFoldSizeWatcherInstalled) return
        val dl = mLauncher.dragLayer
        mLastFoldWidth = dl.width
        mLastFoldHeight = dl.height
        dl.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w == mLastFoldWidth && h == mLastFoldHeight) return@addOnLayoutChangeListener
            mLastFoldWidth = w
            mLastFoldHeight = h
            if (!mWasOverlayAttached) return@addOnLayoutChangeListener
            mPendingFoldReattach?.let { dl.removeCallbacks(it) }
            val run = Runnable {
                mPendingFoldReattach = null
                aresLogFoldReattach(dl.width, dl.height)
                mClient.reattach()
            }
            mPendingFoldReattach = run
            // Debounce past the fold/unfold resize animation so we read the final size, not an
            // intermediate animation frame (an intermediate re-attach latches a wrong size).
            dl.postDelayed(run, FOLD_REATTACH_DEBOUNCE_MS)
        }
        mFoldSizeWatcherInstalled = true
    }

    private fun aresLogFoldReattach(width: Int, height: Int) {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        mLauncher.windowManager.defaultDisplay.getRealSize(p)
        android.util.Log.i(
            "AresFold",
            "reattach dragLayer=${width}x$height realSize=${p.x}x${p.y} attached=$mWasOverlayAttached",
        )
    }

    override fun onDetachedFromWindow() {
        mClient.onDetachedFromWindow()
    }

    override fun openOverlay() {
        mClient.showOverlay(true)
    }

    override fun hideOverlay(animate: Boolean) {
        mClient.hideOverlay(animate)
    }

    override fun hideOverlay(duration: Int) {
        mClient.hideOverlay(duration)
    }

    fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit

    override fun onActivityStarted() {
        mClient.onStart()
    }

    override fun onActivityResumed() {
        mClient.onResume()
    }

    override fun onActivityPaused() {
        mClient.onPause()
    }

    override fun onActivityStopped() {
        mClient.onStop()
    }

    fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit

    override fun onActivityDestroyed() {
        mClient.onDestroy()
    }

    override fun onOverlayScrollChanged(progress: Float) {
        mLauncherOverlayCallbacks?.onOverlayScrollChanged(progress)
    }

    override fun onServiceStateChanged(overlayAttached: Boolean, hotwordActive: Boolean) {
        onServiceStateChanged(overlayAttached)
    }

    override fun onServiceStateChanged(overlayAttached: Boolean) {
        if (overlayAttached != mWasOverlayAttached) {
            mWasOverlayAttached = overlayAttached
            mLauncher.setLauncherOverlay(if (overlayAttached) this else null)
        }
    }

    override fun onScrollInteractionBegin() {
        mClient.startScroll()
    }

    override fun onScrollInteractionEnd() {
        mClient.endScroll()
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) {
        mClient.setScroll(progress)
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        mLauncherOverlayCallbacks = callbacks
    }

    override fun setPersistentFlags(flags: Int) {
        val newFlags = flags and (8 or 16)
        if (newFlags != mFlags) {
            mFlagsChanged = true
            mFlags = newFlags
            LauncherPrefs.getDevicePrefs(mLauncher).edit().putInt(PREF_PERSIST_FLAGS, newFlags).apply()
        }
    }

    companion object {
        private const val PREF_PERSIST_FLAGS = "pref_persistent_flags"
        private const val FOLD_REATTACH_DEBOUNCE_MS = 200L

        fun minusOneAvailable(context: Context): Boolean {
            return FeedBridge.useBridge(context) ||
                context.applicationInfo.flags and
                (ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_SYSTEM) != 0
        }
    }
}
