package com.morsecode.app.core.ui

import android.app.Activity
import android.view.View

/**
 * A screen is just a view plus lifecycle hooks. MainActivity keeps one instance per bottom-nav
 * tab and pushes the Transfer/Broadcast screen on top of them.
 */
interface Screen {
    fun view(ctx: Activity): View
    fun onShown() {}
    fun onHidden() {}

    /**
     * Something outside the screen changed the state it renders (a radio came on, a permission
     * was granted) and the screen should redraw. Cheap by contract: it must not re-register
     * observers.
     */
    fun refresh() {}
    /** Return true when the screen consumed the back press. */
    fun onBackPressed(): Boolean = false
}

/** Marker for screens that want to be told an Activity result came back. */
interface ResultAware {
    fun onActivityResultHandled(requestCode: Int, resultCode: Int): Boolean = false
}
