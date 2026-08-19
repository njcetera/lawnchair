package app.lawnchair.allapps.views

import android.content.Context
import android.util.AttributeSet
import app.lawnchair.areslauncher.AresSearchUiDelegate
import com.android.launcher3.allapps.LauncherAllAppsContainerView

class SearchContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LauncherAllAppsContainerView(context, attrs, defStyleAttr) {

    // AresLauncher §17: bottom-anchored, collapsible search. AresSearchUiDelegate extends
    // LawnchairSearchUiDelegate, so Lawnchair's own search adapter behaviour is retained.
    // This view is launcher-only (inflated from res/layout/all_apps.xml), so the Taskbar and
    // secondary-display all-apps surfaces keep the stock delegate.
    override fun createSearchUiDelegate() = AresSearchUiDelegate(this)
}
