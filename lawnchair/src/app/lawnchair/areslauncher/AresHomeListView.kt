package app.lawnchair.areslauncher

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Vertical, continuously-scrolling list of home-screen items -- the Strategy D
 * replacement for CellLayout's grid inside Workspace's single page. See
 * design/vertical-home-strategies.md and design/component-verification-1.md.
 *
 * Lazily created and attached to the DragLayer by Workspace the first time it
 * needs to place a CONTAINER_DESKTOP item, instead of routing that item into a
 * CellLayout grid cell.
 */
class AresHomeListView(context: Context) : RecyclerView(context) {

    val aresAdapter = AresHomeAdapter()

    init {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = aresAdapter
        clipToPadding = false
    }
}
