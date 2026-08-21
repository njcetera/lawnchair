package app.lawnchair.areslauncher

import android.content.Context
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Reorderable
import com.android.launcher3.testing.TestInformationHandler

import com.android.launcher3.util.MultiTranslateDelegate.INDEX_REORDER_BOUNCE_OFFSET

/**
 * AresLauncher's own end of the test channel.
 *
 * ## Why this exists rather than `ro.test_harness`
 *
 * Stock opens the `TestInformationProvider` channel on `Utilities.isRunningInTestHarness()`, which
 * reads `ActivityManager.isRunningInTestHarness()`. Two problems, both measured rather than
 * reasoned:
 *
 * 1. That flag is **not** a test-channel switch, it is a global behaviour switch with ~41 product
 *    call sites in this tree, and at least one of them is on a surface this project has open
 *    defects on: `SpringLoadedDragController` picks `ENTER_SPRING_LOAD_HOVER_TIME_IN_TEST` (3000ms)
 *    instead of `ENTER_SPRING_LOAD_HOVER_TIME` (500ms) under it. Turning the channel on that way
 *    changes the thing under test.
 * 2. `Utilities.sIsRunningInTestHarness` is a `static` field initialised at class load, so the
 *    property has to be true before the launcher process starts. A test cannot set it.
 *
 * So the channel is opened by a **debug-build** predicate we own instead. Release builds are
 * unaffected, and no product behaviour moves.
 *
 * Note for anyone measuring on `emulator-5554`: that image already ships `ro.test_harness=1`
 * (`ro.kernel.qemu=1`), so `isRunningInTestHarness()` is *already* true there and the 3000ms dwell
 * is *already* in force. That is independent of this file and true of every measurement taken on
 * that emulator to date.
 */
object AresTestInfo {

    /** True when this build may answer test-channel requests. Debug builds only. */
    @JvmStatic
    fun isTestChannelOpen(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Enables `TestInformationProvider` on a debug build, from inside the launcher's own process.
     *
     * `AndroidManifest-common.xml` declares the provider `android:enabled="false"`, and stock
     * expects the test to switch it on. **An out-of-process test cannot**, measured:
     *
     * ```
     * $ adb shell pm enable app.lawnchair.debug/com.android.launcher3.testing.TestInformationProvider
     * SecurityException: Shell cannot change component state for ComponentInfo{...} to 1
     * ```
     *
     * `PackageManagerService` lets the shell uid change component state only for apps carrying
     * `FLAG_TEST_ONLY`, and a Gradle `assembleDebug` APK does not (`android:debuggable=true` is
     * there, `android:testOnly` is not). TAPL only gets away with it because AOSP's in-process
     * branch calls `setComponentEnabledSetting` as the launcher itself.
     *
     * So the launcher does it as the launcher -- same app, always permitted -- and only when
     * [isTestChannelOpen]. `DONT_KILL_APP` so a debug launch does not restart itself, and it is a
     * no-op once the state is already set. The alternatives were worse: shipping the provider
     * `enabled="true"` in release, or making every debug APK `testOnly` (which then refuses to
     * install without `-t`, on the owner's device as well as the emulator).
     */
    @JvmStatic
    fun enableTestProviderIfDebug(context: Context) {
        if (!isTestChannelOpen(context)) return
        val component = ComponentName(
            context.packageName,
            "com.android.launcher3.testing.TestInformationProvider",
        )
        try {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                Log.i(TAG, "test channel: enabled $component")
            }
        } catch (e: Exception) {
            Log.w(TAG, "test channel: could not enable $component", e)
        }
    }

    private const val TAG = "AresTestInfo"

    // ---------------------------------------------------------------- requests

    /** Titles of the home grid's items, in visual (adapter) order. */
    const val REQUEST_HOME_ORDER = "ares-home-order"

    /** `true` while the masonry home grid is in edit mode. */
    const val REQUEST_EDIT_MODE = "ares-edit-mode"

    /**
     * One line per attached home tile describing **where it is drawn**, not where it is laid out.
     *
     * Format, pipe separated:
     * `pos|title|left,top,right,bottom|containerTx,containerTy|reflowX,reflowY|itemTx,itemTy|drawnLeft,drawnTop`
     *
     * The container and the item view are reported separately on purpose. On the home grid the
     * orbit, the reflow and the follow are written to the **holder container** (a plain
     * `FrameLayout`, so a direct `translationX` write) while the label lift is written to the
     * **item view** through `MultiTranslateDelegate`'s [INDEX_REORDER_BOUNCE_OFFSET]. Reading
     * `getTranslationX()` off either one alone is a partial answer; the drawn position of the icon
     * is the sum.
     */
    const val REQUEST_TILE_METRICS = "ares-tile-metrics"

    /**
     * The W1 metric: `viewGroup|layoutManager|adapter` child counts for the home grid.
     *
     * The three disagree in exactly one interesting way. `RecyclerView.getChildCount()` is plain
     * `ViewGroup`, so it counts **every** attached child including ones `ChildHelper` is hiding;
     * `LayoutManager.getChildCount()` is `getUnhiddenChildCount()`, so it does not. A view that
     * `animateDisappearance` re-attached hidden and never removed shows up as
     * `viewGroup > layoutManager` and is invisible to everything the layout manager can reach —
     * it cannot be laid out, cannot be recycled, and keeps the bounds it had when it was deleted.
     *
     * That is the ghost the owner reported, and this is the number that says whether one is
     * present. It is the same divergence the ledger recorded by hand as "17 attached children
     * against 16 database rows", measured directly rather than parsed out of `dumpsys`.
     */
    const val REQUEST_CHILD_CENSUS = "ares-child-census"

    /**
     * Removes the first home item matching `arg` (`"widget"` or `"icon"`), returning its id or -1.
     *
     * Calls [AresHomeListView.removeFromHome] — the same function the × badge's click listener
     * calls, and the whole point of routing through it rather than poking the adapter. The W1 bug
     * lives downstream of `notifyItemRemoved`, in RecyclerView's disappearance handling, so what
     * matters is that the notify is real; whether the finger or the test asked for it is not part
     * of the mechanism. Driving it from here rather than through a synthesised tap on a badge also
     * keeps the check away from the gesture reliability this harness is separately known to be bad
     * at.
     */
    const val REQUEST_REMOVE_ITEM = "ares-remove-item"

    /**
     * Handles an Ares request, or returns null if [method] is not one of ours.
     *
     * Called from `TestInformationHandler.call`'s `default:` branch, so stock's own switch is
     * untouched and a future Lawnchair merge sees one added line rather than a rewritten method.
     */
    @JvmStatic
    fun handle(method: String, arg: String?): Bundle? = when (method) {
        // Stock's own seam, used rather than reimplemented: `getLauncherUIProperty` submits the
        // lambda to MAIN_EXECUTOR and blocks on the result, so the lambda runs on the UI thread
        // with the LIVE Launcher and can read anything a frame callback could.
        REQUEST_HOME_ORDER -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { launcher -> homeOrder(launcher) },
        )
        REQUEST_EDIT_MODE -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putBoolean(key, value) },
            { launcher -> launcher.workspace?.aresHomeList?.isEditMode() ?: false },
        )
        REQUEST_TILE_METRICS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putStringArray(key, value) },
            { launcher -> tileMetrics(launcher) },
        )
        REQUEST_CHILD_CENSUS -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putString(key, value) },
            { launcher -> childCensus(launcher) },
        )
        REQUEST_REMOVE_ITEM -> TestInformationHandler.getLauncherUIProperty(
            { b, key, value -> b.putInt(key, value) },
            { launcher -> removeFirst(launcher, arg) },
        )
        else -> null
    }

    // ---------------------------------------------------------------- internals

    /**
     * Item identity in visual order, as `id/title`.
     *
     * The database id leads because a **widget's `title` is null** -- an order made of titles alone
     * cannot tell two widgets apart, and telling two widgets apart is the whole of the widget-swap
     * regression check.
     */
    private fun homeOrder(launcher: Launcher): Array<String> {
        val list = launcher.workspace?.aresHomeList ?: return emptyArray()
        val adapter = list.aresAdapter
        return (0 until adapter.itemCount).map { i ->
            val info = adapter.itemAt(i)
            if (info == null) "?" else "${info.id}/${info.title ?: "type${info.itemType}"}"
        }.toTypedArray()
    }

    /** See [REQUEST_CHILD_CENSUS]. `viewGroup|layoutManager|adapter`. */
    private fun childCensus(launcher: Launcher): String {
        val list = launcher.workspace?.aresHomeList ?: return "0|0|0"
        // list.childCount is ViewGroup's, so it counts hidden children too. The layout manager's
        // is getUnhiddenChildCount(). Their difference is the ghost count.
        val viewGroup = list.childCount
        val layoutManager = list.layoutManager?.childCount ?: 0
        val adapter = list.aresAdapter.itemCount
        return "$viewGroup|$layoutManager|$adapter"
    }

    /** See [REQUEST_REMOVE_ITEM]. Returns the removed item's id, or -1. */
    private fun removeFirst(launcher: Launcher, arg: String?): Int {
        val list = launcher.workspace?.aresHomeList ?: return -1
        val adapter = list.aresAdapter
        val wantWidget = arg == "widget"
        for (i in 0 until adapter.itemCount) {
            val info = adapter.itemAt(i) ?: continue
            val isWidget = info.itemType == Favorites.ITEM_TYPE_APPWIDGET ||
                info.itemType == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET
            if (isWidget != wantWidget) continue
            list.removeFromHome(info)
            return info.id
        }
        return -1
    }

    private fun tileMetrics(launcher: Launcher): Array<String> {
        val list = launcher.workspace?.aresHomeList ?: return emptyArray()
        val out = ArrayList<String>()
        val loc = IntArray(2)
        for (i in 0 until list.childCount) {
            val container = list.getChildAt(i) ?: continue
            val pos = list.getChildAdapterPosition(container)
            val info = list.aresAdapter.itemAt(pos)
            val title = if (info == null) "?" else "${info.id}/${info.title ?: "type${info.itemType}"}"
            val item = (container as? ViewGroup)?.getChildAt(0)
            val itemT = item?.let { translationOf(it) } ?: floatArrayOf(0f, 0f)
            container.getLocationOnScreen(loc)
            val containerScreenX = loc[0]
            val containerScreenY = loc[1]
            var itemScreenX = -1
            var itemScreenY = -1
            if (item != null) {
                item.getLocationOnScreen(loc)
                itemScreenX = loc[0]
                itemScreenY = loc[1]
            }
            out.add(
                buildString {
                    append(pos).append('|')
                    append(title).append('|')
                    append(container.left).append(',').append(container.top).append(',')
                    append(container.right).append(',').append(container.bottom).append('|')
                    append(container.translationX).append(',').append(container.translationY)
                        .append('|')
                    append(AresEditMotion.reflowX(container)).append(',')
                    append(AresEditMotion.reflowY(container)).append('|')
                    append(itemT[0]).append(',').append(itemT[1]).append('|')
                    // Ground truth: the full transform chain, scale and all. Compare against
                    // box + translation above -- edit mode scales the list by EDIT_MODE_SCALE, so
                    // the two are NOT the same number and only this one is where the finger has
                    // to land.
                    append(containerScreenX).append(',').append(containerScreenY).append('|')
                    append(itemScreenX).append(',').append(itemScreenY).append('|')
                    append(container.width).append(',').append(container.height).append('|')
                    append(container.scaleX).append('|')
                    // itemType, so a caller can pick out the widgets. ITEM_TYPE_APPWIDGET is 4.
                    append(info?.itemType ?: -1).append('|')
                    append(info?.spanX ?: -1).append(',').append(info?.spanY ?: -1)
                },
            )
        }
        return out.toTypedArray()
    }

    /**
     * A view's live translation, taking the [Reorderable] channel into account.
     *
     * `View.getTranslationX()` on a `Reorderable` is in fact the *sum* every
     * `MultiTranslateDelegate` channel aggregates to -- the delegate writes the aggregate through
     * `VIEW_TRANSLATE_X` -- so it is not wrong for a single view. It is only a partial answer
     * because on this grid two different views carry two different contributions.
     */
    private fun translationOf(view: View): FloatArray {
        val reorderable = view as? Reorderable
        return if (reorderable != null) {
            floatArrayOf(
                reorderable.translateDelegate.getTranslationX(INDEX_REORDER_BOUNCE_OFFSET).value,
                reorderable.translateDelegate.getTranslationY(INDEX_REORDER_BOUNCE_OFFSET).value,
            )
        } else {
            floatArrayOf(view.translationX, view.translationY)
        }
    }
}
