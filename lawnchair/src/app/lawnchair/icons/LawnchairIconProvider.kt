package app.lawnchair.icons

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_DATE_CHANGED
import android.content.Intent.ACTION_PACKAGE_ADDED
import android.content.Intent.ACTION_PACKAGE_CHANGED
import android.content.Intent.ACTION_PACKAGE_REMOVED
import android.content.Intent.ACTION_TIMEZONE_CHANGED
import android.content.Intent.ACTION_TIME_CHANGED
import android.content.Intent.ACTION_TIME_TICK
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageItemInfo
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import app.lawnchair.areslauncher.AresIconTint
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.icons.iconpack.IconPack
import app.lawnchair.icons.iconpack.IconPackProvider
import app.lawnchair.icons.picker.IconEntry
import app.lawnchair.icons.picker.IconType
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.MultiSafeCloseable
import app.lawnchair.util.isPackageInstalled
import app.lawnchair.util.requireSystemService
import app.lawnchair.icons.ExtendedBitmapDrawable.Companion.isFromIconPack
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.ClockDrawableWrapper
import com.android.launcher3.icons.IconNormalizer
import com.android.launcher3.icons.LauncherIconProvider
import com.android.launcher3.icons.mono.ThemedIconDrawable
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.SafeCloseable
import javax.inject.Inject
import org.xmlpull.v1.XmlPullParser

@LauncherAppSingleton
class LawnchairIconProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    val themeManager: ThemeManager,
) : LauncherIconProvider(
    context,
    themeManager,
) {
    private val prefs = PreferenceManager.getInstance(context)
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val themedIconsEnabled get() = prefs.themedIcons.get()

    private val iconPackPref = prefs.iconPackPackage
    private val themedIconSourcePref = prefs.themedIconPackPackage

    private val iconPackProvider = IconPackProvider.INSTANCE.get(context)
    private val overrideRepo = IconOverrideRepository.INSTANCE.get(context)

    private val iconPack
        get() = iconPackProvider.getIconPack(iconPackPref.get())?.apply { loadBlocking() }
    private val themedIconSource
        get() = iconPackProvider.getIconPack(themedIconSourcePref.get())?.apply { loadBlocking() }

    private var themeMapName: String = ""
    private var _themeMap: Map<String, ThemeData>? = null
    // Tracks which theming state [_themeMap] was built for, so it reloads when the Ares tint flips
    // the effective theming on/off (hybrid B needs the real map even when themed icons are off).
    private var themeMapThemed: Boolean? = null

    val themeMap: Map<String, ThemeData>
        get() {
            // Hybrid B: the Ares icon tint renders native themed icons for themeable apps, so it
            // needs the real themed-icon map even when the separate themed-icons pref is off.
            val effectiveThemed = themedIconsEnabled || AresIconTint.isActive(prefs2)
            if (themeMapThemed != effectiveThemed) {
                themeMapThemed = effectiveThemed
                _themeMap = if (effectiveThemed) getThemedIconMap() else DISABLED_MAP
            }
            if (_themeMap == null) {
                _themeMap = getThemedIconMap()
            }
            if (themedIconSource != null && themeMapName == "") {
                _themeMap = super.getThemedIconMap()
            }
            if (themedIconSource != null && themeMapName != themedIconSource!!.packPackageName) {
                themeMapName = themedIconSource!!.packPackageName
                _themeMap = getThemedIconMap()
            }
            return _themeMap!!
        }

    val systemIconState = themeManager.iconState

    private fun resolveIconEntry(componentName: ComponentName, user: UserHandle): IconEntry? {
        val componentKey = ComponentKey(componentName, user)
        // first look for user-overridden icon
        val overrideItem = overrideRepo.overridesMap[componentKey]
        if (overrideItem != null) {
            return overrideItem.toIconEntry()
        }

        val iconPack = iconPack ?: return null
        // then look for dynamic calendar
        val calendarEntry = iconPack.getCalendar(componentName)
        if (calendarEntry != null) {
            return calendarEntry
        }
        // finally, look for normal icon
        return iconPack.getIcon(componentName)
    }

    /**
     * Resolves the launch component for icon-pack lookup.
     *
     * Avoid [android.content.pm.PackageManager.getLaunchIntentForPackage], which only sees the
     * current user — work/private profile apps would otherwise fall back to the system icon.
     * Prefer the real component from [ComponentInfo], then [LauncherApps] for the app's user.
     */
    private fun resolveComponentName(
        info: PackageItemInfo,
        appInfo: ApplicationInfo,
        user: UserHandle,
    ): ComponentName? {
        if (info is ComponentInfo && !info.name.isNullOrEmpty()) {
            return ComponentName(info.packageName ?: appInfo.packageName, info.name)
        }
        return resolveLaunchComponent(appInfo.packageName, user)
    }

    private fun resolveLaunchComponent(packageName: String, user: UserHandle): ComponentName? {
        return try {
            val launcherApps: LauncherApps = context.requireSystemService()
            launcherApps.getActivityList(packageName, user).firstOrNull()?.componentName
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve launch component for $packageName user=$user", t)
            null
        }
    }

    override fun getIcon(
        info: PackageItemInfo,
        appInfo: ApplicationInfo,
        iconDpi: Int,
    ): Drawable {
        val packageName = appInfo.packageName
        val user = UserHandle.getUserHandleForUid(appInfo.uid)
        val componentName = resolveComponentName(info, appInfo, user)

        var iconEntry: IconEntry? = null
        if (componentName != null) {
            iconEntry = resolveIconEntry(componentName, user)
        }

        var iconPackEntry = iconEntry

        val themeData = getThemeDataForPackage(packageName)
        var themedIcon: Drawable? = null

        val themedColors = ThemedIconDrawable.getColors(context)

        // Ares icon tint, option B / hybrid (owner 2026-08-27): an app that ships a themed
        // (monochrome) layer follows Material You theming naturally, so when the tint is on we render
        // its NATIVE themed icon -- even if the separate themed-icons pref is off -- and only apps
        // without one get the wash (below). So the tint also enables the themed path here.
        val wantThemed = themedIconsEnabled || AresIconTint.isActive(prefs2)

        if (iconEntry != null) {
            val clock = iconPackProvider.getClockMetadata(iconEntry)

            if (iconEntry.type == IconType.Calendar) {
                iconPackEntry = iconEntry.resolveDynamicCalendar(getDay())
            }

            when {
                !wantThemed -> {
                    // theming is disabled and the tint is off, don't populate theme data
                    themedIcon = null
                }

                clock != null -> {
                    // the icon supports dynamic clock, use dynamic themed clock
                    themedIcon =
                        ClockDrawableWrapper.forPackage(mContext, mClock.packageName, iconDpi)
                            ?.getMonochrome()
                }

                packageName == mClock.packageName -> {
                    // is clock app but icon might not be adaptive, fallback to static themed clock
                    val clockThemedData =
                        ThemeData(context.resources, R.drawable.themed_icon_static_clock)
                    themedIcon = CustomAdaptiveIconDrawable(
                        themedColors[0].toDrawable(),
                        clockThemedData.loadPaddedDrawable().apply { setTint(themedColors[1]) },
                    )
                }

                packageName == mCalendar.packageName -> {
                    // calendar app, apply the dynamic calendar icon
                    themedIcon = loadCalendarDrawable(iconDpi, themeData)
                }

                else -> {
                    // regular icon
                    themedIcon = if (themeData != null) {
                        CustomAdaptiveIconDrawable(
                            themedColors[0].toDrawable(),
                            themeData.loadPaddedDrawable().apply { setTint(themedColors[1]) },
                        )
                    } else {
                        null
                    }
                }
            }
        }

        val iconPackIcon = iconPackEntry?.let { iconPackProvider.getDrawable(it, iconDpi, user) }

        // Hybrid B (owner 2026-08-27): a themed icon already follows Material You theming; keep it.
        if (themedIcon != null) return themedIcon
        val result = iconPackIcon ?: super.getIcon(info, appInfo, iconDpi)
        // Full theming (owner 2026-08-27): when theming is on, render EVERY app as an accent
        // monochrome -- done HERE, outside the icon-pack gate above, so it applies regardless of
        // whether an icon pack is set. Prefer the app's own monochrome layer (its native Android 13+
        // themed icon); else an icon pack's themed layer; else synthesize a monochrome from the
        // regular adaptive icon (like Android 16's auto-theming). Only a non-adaptive icon, which has
        // no layers to monochrome, falls through to the accent wash.
        if (AresIconTint.isActive(prefs2)) {
            // Ares theming uses its OWN vibrant colour pair (M3 primary bg + light on-primary glyph),
            // not `themedColors` (the pale stock light-mode scheme) -- owner 2026-08-27.
            val ares = AresIconTint.themedColors(context)
            val adaptive = result as? AdaptiveIconDrawable
            val nativeMono: Drawable? =
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    adaptive?.monochrome
                } else {
                    null
                }) ?: componentName?.let { ThemedIconCompat.getThemedIcon(context, it) }
            if (nativeMono != null) {
                return CustomAdaptiveIconDrawable(
                    ares[0].toDrawable(),
                    nativeMono.mutate().apply { setTint(ares[1]) },
                )
            }
            if (adaptive != null) {
                val synth = AresIconTint.generateMono(context, adaptive, ares[1])
                if (synth != null) {
                    return CustomAdaptiveIconDrawable(ares[0].toDrawable(), synth)
                }
            }
            // NON-ADAPTIVE (legacy) icon. It has no layers to monochrome, but that does NOT mean it
            // cannot BE monochromed: MonochromeIconFactory never reads the source alpha, it derives
            // the mask from luminance and auto-detects polarity. So a legacy icon can go through
            // the very same generator as everything above, which makes it match by construction
            // rather than by tuning a colour ramp to approximate the match.
            //
            // That is the owner's call, 2026-09-02: "I feel like we just gotta try to get the glyph
            // one color and the background another? then it'll match the monochromatic icons."
            // Two previous revisions tried to reach that with a duotone wash -- first aimed at the
            // wrong colour, then ramping toward a fixed black pole -- and both landed short of the
            // adaptive icons sitting beside them. See AresIconTint.generateMonoFromLegacy for why
            // the earlier objection to this ("opaque icons would flatten into a colour block") was
            // wrong, and why the icon must fill the layer rect for the polarity check to work.
            //
            // THE WRAP GATE COMES FIRST, and the mono lives INSIDE it. Take over ONLY the wrap that
            // BaseIconFactory's legacy branch would have done: a non-adaptive icon that did NOT come
            // from an icon pack (normalizeAndWrapToAdaptiveIcon shrinks only when `!isFromIconPack
            // && shouldWrapAdaptive(context)`, and early-returns for an AdaptiveIconDrawable).
            // Wrapping anything else DOUBLE wraps -- an already-adaptive icon reaches here whenever
            // generateMono() returns null (below API 33, or on any failure) and would get a second
            // background painted across a 1.5x layer rect, and a pack icon, which that branch
            // deliberately never wrapped, would gain a background it never had. Adversarial review
            // 2026-09-01, and the reason the mono is not simply returned above: it would have
            // reintroduced exactly that.
            if (result is AdaptiveIconDrawable || result.isFromIconPack ||
                !shouldWrapAdaptive(context)
            ) {
                return AresIconTint.wash(result, prefs2, ares[1], ares[0])
            }
            // Reproduce that branch's GEOMETRY, not just its background colour. It wraps the
            // icon in a FixedScaleDrawable at IconNormalizer's scale -- and setScale() itself
            // multiplies by LEGACY_ICON_SCALE (~0.467). Handing a full-bleed drawable straight in
            // as an adaptive foreground instead let CustomAdaptiveIconDrawable stretch it to the
            // 1.5x layer rect: ~2.1x too large, corners cut off by the icon mask. The original
            // fix here changed the background colour and silently changed the size with it;
            // sampling pixel COLOURS could not see that. Adversarial review 2026-09-01.
            //
            // Both the mono mask and the wash fallback go through this same scale, so switching
            // between them changes the COLOUR treatment and nothing about the geometry.
            val legacyMono =
                if (AresIconTint.legacyMonoEnabled) {
                    AresIconTint.generateMonoFromLegacy(context, result, ares[1])
                } else {
                    null
                }
            val foreground = FixedScaleDrawable().apply {
                setDrawable(legacyMono ?: AresIconTint.wash(result, prefs2, ares[1], ares[0]))
                setScale(IconNormalizer(LauncherAppState.getIDP(context).iconBitmapSize).getScale(result))
            }
            return CustomAdaptiveIconDrawable(ares[0].toDrawable(), foreground)
        }
        return result
    }

    override fun getStateForApp(info: ApplicationInfo?): String {
        val base = super.getStateForApp(info)
        val overrideState = if (info != null) {
            val user = UserHandle.getUserHandleForUid(info.uid)
            overrideRepo.getPackageOverrideState(info.packageName, user)
        } else {
            ""
        }
        return "$base|lc:" +
            "ip=${iconPackPref.get()}," +
            "tip=${themedIconSourcePref.get()}," +
            "ti=${prefs.themedIcons.get()}," +
            "dti=${prefs.drawerThemedIcons.get()}," +
            "fm=${prefs.forceIconMonochrome.get()}," +
            "tb=${prefs.tintIconPackBackgrounds.get()}," +
            "${AresIconTint.stateFragment(context, prefs2)}," +
            "ov=$overrideState"
    }

    override fun getThemeDataForPackage(packageName: String?): ThemeData? {
        return themeMap[packageName]
    }

    override fun getThemedIconMap(): MutableMap<String, ThemeData> {
        val themedIconMap = ArrayMap<String, ThemeData>()

        fun ArrayMap<String, ThemeData>.updateFromResources(
            resources: Resources,
            packageName: String,
        ) {
            try {
                @SuppressLint("DiscouragedApi")
                val xmlId = resources.getIdentifier("grayscale_icon_map", "xml", packageName)
                if (xmlId != 0) {
                    val parser = resources.getXml(xmlId)
                    val depth = parser.depth
                    var type: Int
                    while (
                        (
                            parser.next()
                                .also { type = it } != XmlPullParser.END_TAG || parser.depth > depth
                            ) &&
                        type != XmlPullParser.END_DOCUMENT
                    ) {
                        if (type != XmlPullParser.START_TAG) continue
                        if (TAG_ICON == parser.name) {
                            val pkg = parser.getAttributeValue(null, ATTR_PACKAGE)
                            val iconId = parser.getAttributeResourceValue(null, ATTR_DRAWABLE, 0)
                            if (iconId != 0 && pkg.isNotEmpty()) {
                                this[pkg] = ThemeData(resources, iconId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to parse icon map.", e)
            }
        }

        // first, get Lawnchair's internal grayscale icon map
        themedIconMap.updateFromResources(
            resources = context.resources,
            packageName = context.packageName,
        )

        if (context.packageManager.isPackageInstalled(packageName = themeMapName)) {
            // get the grayscale icon map of the supported icon pack
            themedIconMap.updateFromResources(
                resources = context.packageManager.getResourcesForApplication(themeMapName),
                packageName = themeMapName,
            )
        }

        return themedIconMap
    }

    override fun registerIconChangeListener(
        callback: IconChangeListener,
        handler: Handler,
    ): SafeCloseable {
        return MultiSafeCloseable().apply {
            add(super.registerIconChangeListener(callback, handler))
            add(IconPackChangeReceiver(context, handler, callback))
            add(LawniconsChangeReceiver(context, handler))
        }
    }

    private inner class IconPackChangeReceiver(
        private val context: Context,
        private val handler: Handler,
        private val callback: IconChangeListener,
    ) : SafeCloseable {

        private var calendarAndClockChangeReceiver: CalendarAndClockChangeReceiver? = null
            set(value) {
                field?.close()
                field = value
            }
        private var iconState = themeManager.iconState
        private val iconPackPref = PreferenceManager.getInstance(context).iconPackPackage
        private val themedIconPackPref = PreferenceManager.getInstance(context).themedIconPackPackage

        private val subscription = iconPackPref.subscribeChanges {
            val newState = themeManager.iconState
            if (iconState != newState) {
                iconState = newState
                updateSystemState()
                recreateCalendarAndClockChangeReceiver()
            }
        }
        private val themedIconSubscription = themedIconPackPref.subscribeChanges {
            val newState = themeManager.iconState
            if (iconState != newState) {
                iconState = newState
                updateSystemState()
                recreateCalendarAndClockChangeReceiver()
            }
        }

        init {
            recreateCalendarAndClockChangeReceiver()
        }

        private fun recreateCalendarAndClockChangeReceiver() {
            val iconPack = IconPackProvider.INSTANCE.get(context).getIconPack(iconPackPref.get())
            calendarAndClockChangeReceiver = if (iconPack != null) {
                CalendarAndClockChangeReceiver(context, handler, iconPack, callback)
            } else {
                null
            }
        }

        override fun close() {
            calendarAndClockChangeReceiver = null
            subscription.close()
            themedIconSubscription.close()
        }
    }

    private class CalendarAndClockChangeReceiver(
        private val context: Context,
        handler: Handler,
        private val iconPack: IconPack,
        private val callback: IconChangeListener,
    ) : BroadcastReceiver(),
        SafeCloseable {

        init {
            val filter = IntentFilter(ACTION_TIMEZONE_CHANGED)
            filter.addAction(ACTION_TIME_TICK)
            filter.addAction(ACTION_TIME_CHANGED)
            filter.addAction(ACTION_DATE_CHANGED)
            context.registerReceiver(this, filter, null, handler)
        }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TIMEZONE_CHANGED, ACTION_TIME_CHANGED, ACTION_TIME_TICK -> {
                    context.getSystemService<UserManager>()?.userProfiles?.forEach { user ->
                        iconPack.getClocks().forEach { componentName ->
                            callback.onAppIconChanged(
                                componentName.packageName,
                                user,
                            )
                        }
                    }
                }

                ACTION_DATE_CHANGED -> {
                    context.getSystemService<UserManager>()?.userProfiles?.forEach { user ->
                        iconPack.getCalendars().forEach { componentName ->
                            callback.onAppIconChanged(componentName.packageName, user)
                        }
                    }
                }
            }
        }

        override fun close() {
            context.unregisterReceiver(this)
        }
    }

    private inner class LawniconsChangeReceiver(
        private val context: Context,
        handler: Handler,
    ) : BroadcastReceiver(),
        SafeCloseable {

        init {
            val filter = IntentFilter(ACTION_PACKAGE_ADDED)
            filter.addAction(ACTION_PACKAGE_CHANGED)
            filter.addAction(ACTION_PACKAGE_REMOVED)
            filter.addDataScheme("package")
            filter.addDataSchemeSpecificPart(themeMapName, 0)
            context.registerReceiver(this, filter, null, handler)
        }

        override fun onReceive(context: Context, intent: Intent) {
            updateSystemState()
        }

        override fun close() {
            context.unregisterReceiver(this)
        }
    }

    companion object {
        const val TAG = "LawnchairIconProvider"
    }
}
