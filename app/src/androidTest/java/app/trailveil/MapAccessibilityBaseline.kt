package app.trailveil

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * `V02-005` stage 9: the accessibility shape both map providers must present, stated once and
 * asserted by two tests — the MapLibre debug baseline and the Google googlePoc parity twin.
 *
 * Parity is defined by construction rather than by a recorded dump: both variants assert the same
 * three properties against the same live window, so a divergence fails one of them.
 *
 *  1. Exactly one node in the active window is important for accessibility and announces the
 *     localized map description. That is the one TalkBack target a map contributes.
 *  2. Beneath that node only the provider's declared SDK controls announce, each exactly once, and
 *     nothing else. On Google nothing does: `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` on
 *     the SDK's child views hides its map surface and marker nodes. On MapLibre the attribution
 *     control announces and fog/track overlays contribute zero nodes, as measured on device.
 *  3. No important, described or texted node anywhere in the window belongs to a map-SDK class. A
 *     Google "marker" virtual node or a MapLibre native view leaking a name would fail here even
 *     if it sat outside the map node.
 *
 * Nodes are judged by `AccessibilityNodeInfo.isImportantForAccessibility`, the property a screen
 * reader consults, because a UiAutomation fetch can include views TalkBack never receives.
 *
 * The window is read through `rootInActiveWindow`, which is a synchronous binder call into the
 * window's process; P4-049 traced a shard hang to an unbounded call of exactly this kind, so it is
 * taken on a worker thread with a deadline and reported as a failure, never a hang.
 */
internal object MapAccessibilityBaseline {
    data class NodeSummary(
        val depth: Int,
        val important: Boolean,
        val className: String,
        val description: String,
        val text: String,
        val viewId: String,
        val bounds: Rect,
    ) {
        /** Carries a description or text, important or not; the failure listings use this. */
        val named: Boolean get() = description.isNotEmpty() || text.isNotEmpty()

        /** A screen-reader target: important for accessibility and named. */
        val announces: Boolean get() = important && named
        val isMapSdkClass: Boolean
            get() = SDK_CLASS_MARKERS.any { marker -> className.contains(marker, ignoreCase = true) }
    }

    /**
     * Pre-order dump of the active window, or a failure if the binder call does not return.
     *
     * The active window is transiently nobody's (`rootInActiveWindow` is null while a transition
     * or the shell holds focus) or another package's, and a freshly connected UiAutomation can
     * answer with a tree in which nothing announces yet. Each attempt stays bounded; the dump is
     * retried until the target package's window carries at least one announcing node, or the
     * overall deadline passes and the failure names what was active instead.
     */
    fun dumpActiveWindow(
        timeoutMillis: Long = ROOT_TIMEOUT_MILLIS,
        deadlineMillis: Long = DUMP_DEADLINE_MILLIS,
    ): List<NodeSummary> {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val deadline = SystemClock.uptimeMillis() + deadlineMillis
        var last = dumpOnce(timeoutMillis)
        while (last.packageName != targetPackage || last.nodes.none(NodeSummary::named)) {
            if (SystemClock.uptimeMillis() >= deadline) {
                throw AssertionError(
                    "no announcing node in an active window of $targetPackage within " +
                        "$deadlineMillis ms; last root package=${last.packageName} " +
                        "nodes=${last.nodes.size} classes=" +
                        last.nodes.take(DUMP_CLASS_SAMPLE)
                            .joinToString { it.className.substringAfterLast('.') },
                )
            }
            SystemClock.sleep(RETRY_MILLIS)
            last = dumpOnce(timeoutMillis)
        }
        return last.nodes
    }

    private class WindowDump(val packageName: String?, val nodes: List<NodeSummary>)

    /** Whether the last fetch had to clear `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`; evidence only. */
    @Volatile
    var lastFetchClearedUnimportantFlag: Boolean = false
        private set

    /** One bounded read of `rootInActiveWindow` on a worker thread. */
    private fun dumpOnce(timeoutMillis: Long): WindowDump {
        val result = AtomicReference<WindowDump?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val root = withoutUnimportantViews(automation) { automation.rootInActiveWindow }
                result.set(
                    WindowDump(
                        packageName = root?.packageName?.toString(),
                        nodes = root?.let { node -> collect(node, 0) } ?: emptyList(),
                    ),
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMillis)
        failure.get()?.let { throw AssertionError("accessibility dump failed", it) }
        return checkNotNull(result.get()) {
            "rootInActiveWindow did not return within $timeoutMillis ms; not retried, by design"
        }
    }

    /**
     * [expectedDescendants]: the SDK controls a provider legitimately exposes beneath its map node,
     * each labelled and matched by a predicate. Every one must announce exactly once and nothing
     * else beneath the map node may announce. MapLibre lists its attribution control; Google lists
     * nothing (its attribution is a watermark inside the map, not a control).
     */
    fun assertMapContributesExactlyOneTarget(
        nodes: List<NodeSummary>,
        expectedDescription: String,
        screen: String,
        expectedDescendants: List<Pair<String, (NodeSummary) -> Boolean>> = emptyList(),
    ) {
        val mapNodes = nodes.withIndex().filter { (_, node) ->
            node.important && node.description == expectedDescription
        }
        assertEquals(
            "$screen: the map must announce exactly one localized description; window=" +
                nodes.filter(NodeSummary::named).joinToString { summarize(it) } +
                " unimportantFlagCleared=$lastFetchClearedUnimportantFlag",
            1,
            mapNodes.size,
        )
        val (mapIndex, mapNode) = mapNodes.single()
        val describedDescendants = nodes.drop(mapIndex + 1)
            .takeWhile { node -> node.depth > mapNode.depth }
            .filter(NodeSummary::announces)
        expectedDescendants.forEach { (label, matches) ->
            assertEquals(
                "$screen: expected exactly one $label beneath the map node; found " +
                    describedDescendants.joinToString { summarize(it) },
                1,
                describedDescendants.count(matches),
            )
        }
        val unexpected = describedDescendants.filter { node ->
            expectedDescendants.none { (_, matches) -> matches(node) }
        }
        assertTrue(
            "$screen: the map node must contribute no further targets; found " +
                unexpected.joinToString { summarize(it) },
            unexpected.isEmpty(),
        )
        val sdkLeaks = nodes.filter { node -> node.announces && node.isMapSdkClass }
        assertTrue(
            "$screen: a map-SDK class exposed an accessibility name: " +
                sdkLeaks.joinToString { summarize(it) },
            sdkLeaks.isEmpty(),
        )
    }

    /**
     * UiAutomation connects with `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`, which makes every fetch return
     * what `importantForAccessibility` hides - the opposite of what a screen reader is handed. The
     * flag is cleared for one fetch and put back afterwards, so the dump is the tree TalkBack would
     * receive and the rest of the run keeps the automation it connected with.
     */
    private fun <T> withoutUnimportantViews(automation: UiAutomation, block: () -> T): T {
        val info = automation.serviceInfo
        val flag = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        lastFetchClearedUnimportantFlag = info != null && info.flags and flag != 0
        if (info == null || !lastFetchClearedUnimportantFlag) return block()
        info.flags = info.flags and flag.inv()
        automation.serviceInfo = info
        try {
            return block()
        } finally {
            info.flags = info.flags or flag
            automation.serviceInfo = info
        }
    }

    private fun collect(node: AccessibilityNodeInfo, depth: Int): List<NodeSummary> {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val self = NodeSummary(
            depth = depth,
            important = node.isImportantForAccessibility,
            className = node.className?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            bounds = bounds,
        )
        val children = (0 until node.childCount).flatMap { index ->
            val child = node.getChild(index) ?: return@flatMap emptyList()
            try {
                collect(child, depth + 1)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return listOf(self) + children
    }

    /** Class, id and a bounded, non-positional summary — never a coordinate. */
    private fun summarize(node: NodeSummary): String =
        "${node.className.substringAfterLast('.')}(${node.viewId.substringAfterLast('/')}: " +
            "imp=${node.important} desc=${node.description.take(24)} text=${node.text.take(24)})"

    private const val ROOT_TIMEOUT_MILLIS = 5_000L
    private const val DUMP_DEADLINE_MILLIS = 15_000L
    private const val RETRY_MILLIS = 250L
    private const val DUMP_CLASS_SAMPLE = 12
    private val SDK_CLASS_MARKERS = listOf("com.google.android.gms", "org.maplibre")
}
