package play.ott.nativeapp.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit

/** Compose idle does not include the OS handoff from an IME or a dismissed Dialog. */
internal fun awaitActivityWindowReady(
    activity: Activity,
    hideIme: Boolean = false,
    acknowledgeImmersiveTutorial: Boolean = false,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    if (hideIme) instrumentation.runOnMainSync {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
    }
    var diagnostics = ""
    val automation = if (acknowledgeImmersiveTutorial) instrumentation.uiAutomation else null
    val originalFlags = automation?.serviceInfo?.flags
    if (automation != null) automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var tutorialAcknowledged = false
    try {
        while (System.nanoTime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                val imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                ready = decor.isAttachedToWindow && decor.hasWindowFocus() && !imeVisible
                diagnostics = "windowFocus=${decor.hasWindowFocus()}, attached=${decor.isAttachedToWindow}, " +
                    "ime=$imeVisible, focus=${activity.currentFocus?.javaClass?.simpleName}:${activity.currentFocus?.id}"
            }
            if (ready) return
            if (automation != null && !tutorialAcknowledged) {
                tutorialAcknowledged = acknowledgeKnownImmersiveTutorial(automation)
                if (tutorialAcknowledged) {
                    // Accessibility may block until Android's first-fullscreen overlay
                    // responds. Its confirmed dismissal starts a separate focus handoff;
                    // always observe that handoff even if the first deadline just expired.
                    // This extends the wait only once, after the exact tutorial accepted
                    // its click. Other system dialogs must still fail the readiness check.
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                }
            }
            Thread.sleep(50)
        }
        val windows = automation?.windows?.joinToString { window ->
            val root = window.root
            "type=${window.type}, title=${window.title}, focused=${window.isFocused}, package=${root?.packageName}, " +
                "tutorialTitle=${root?.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title")?.size}, " +
                "tutorialDescription=${root?.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_description")?.size}, " +
                "ok=${root?.findAccessibilityNodeInfosByViewId("android:id/ok")?.size}"
        }
        throw AssertionError(
            "Activity window did not become ready for a hardware key: $diagnostics; " +
                "tutorialAcknowledged=$tutorialAcknowledged; windows=$windows",
        )
    } finally {
        if (automation != null && originalFlags != null) {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}

private fun acknowledgeKnownImmersiveTutorial(automation: UiAutomation): Boolean {
    // AOSP's first-fullscreen tutorial is a system window, not an app dialog.
    // Android 15 exposes no accessibility window title for this overlay. Require
    // its focused system window, system package and exact framework layout IDs;
    // never accept a generic "OK" button or dismiss unrelated permission/error dialogs.
    val window = automation.windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused
    } ?: return false
    val root = window.root ?: return false
    if (root.packageName?.toString() !in setOf("android", "com.android.systemui")) return false
    if (root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title").none { it.isVisibleToUser } ||
        root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_description").none { it.isVisibleToUser }) return false
    val confirm = root.findAccessibilityNodeInfosByViewId("android:id/ok").singleOrNull {
        it.isVisibleToUser && it.isEnabled && it.isClickable && it.className?.toString() == "android.widget.Button"
    } ?: return false
    check(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        "Android immersive tutorial confirmation did not accept its click"
    }
    Log.i("OttUiWindowReadiness", "Acknowledged Android first-fullscreen tutorial")
    return true
}
