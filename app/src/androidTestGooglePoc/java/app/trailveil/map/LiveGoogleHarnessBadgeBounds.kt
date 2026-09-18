package app.trailveil.map

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/** Unexpected permission/recording cards invalidate a map-only pixel fixture; none are excluded. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun liveMapAuditNotices(mapView: View): Set<String> {
    check(Looper.myLooper() == Looper.getMainLooper())
    val noticeTags = setOf("recording_entry_location_notice", "recording_entry_notification_notice",
        "recording_entry_start_notice", "recording_entry_recording_state", "recording_entry_background_start_notice")
    val found = mutableSetOf<String>()
    fun nodes(node: SemanticsNode) {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        if (tag in noticeTags && !node.boundsInWindow.isEmpty) found += requireNotNull(tag)
        node.children.forEach(::nodes)
    }
    fun views(view: View) {
        if (view is ViewRootForTest) nodes(view.semanticsOwner.unmergedRootSemanticsNode)
        if (view is ViewGroup) for (index in 0 until view.childCount) views(view.getChildAt(index))
    }
    views(mapView.rootView)
    return found
}

/** Acknowledge an existing terminal result through its real UI; active states have no such action. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun acknowledgeMapAuditTerminalNotice(mapView: View): Boolean {
    check(Looper.myLooper() == Looper.getMainLooper())
    val actions = mutableListOf<() -> Boolean>()
    fun nodes(node: SemanticsNode) {
        if (node.config.getOrNull(SemanticsProperties.TestTag) == "recording_entry_recording_state_dismiss" &&
            !node.boundsInWindow.isEmpty) {
            actions += checkNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        }
        node.children.forEach(::nodes)
    }
    fun views(view: View) {
        if (view is ViewRootForTest) nodes(view.semanticsOwner.unmergedRootSemanticsNode)
        if (view is ViewGroup) for (index in 0 until view.childCount) views(view.getChildAt(index))
    }
    views(mapView.rootView)
    check(actions.size <= 1) { "Ambiguous terminal notice dismissal" }
    val action = actions.singleOrNull() ?: return false
    check(action()) { "Terminal notice acknowledgement was rejected" }
    return true
}
