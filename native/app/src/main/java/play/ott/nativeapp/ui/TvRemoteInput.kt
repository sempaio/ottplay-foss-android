package play.ott.nativeapp.ui

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** A hidden TV IME can retain its key handler after an app-language change. */
internal class TvRemoteInput {
    private data class RemoteKey(val deviceId: Int, val keyCode: Int)
    private val ownedKeys = mutableMapOf<RemoteKey, Long>()

    fun clear() = ownedKeys.clear()

    fun dispatch(view: View, event: KeyEvent, dispatchNormally: (KeyEvent) -> Boolean): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val key = RemoteKey(event.deviceId, event.keyCode)
        val owned = ownedKeys[key] == event.downTime
        if (owned) {
            if (event.action == KeyEvent.ACTION_UP) ownedKeys.remove(key)
            dispatchNormally(event)
            return true
        }
        // Never introduce half a press into the app or re-validate an unowned UP in Compose.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        ownedKeys.remove(key)
        if (view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_TELEVISION ||
            !event.hasNoModifiers() || event.keyCode !in remoteKeys ||
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != false) return false
        // Only take ownership if the existing handler accepts the command. Unhandled
        // commands retain Android's regular dispatch and native focus-navigation fallback.
        val handled = dispatchNormally(event)
        if (handled) ownedKeys[key] = event.downTime
        return handled
    }

    private companion object {
        val remoteKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
    }
}

// Resolve the View where this modifier is applied: dialogs own a different window.
internal fun Modifier.tvRemoteInput(): Modifier = composed {
    val view = LocalView.current
    val input = remember(view) { TvRemoteInput() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(view, windowFocused) {
        if (!windowFocused) input.clear()
        onDispose { input.clear() }
    }
    onPreInterceptKeyBeforeSoftKeyboard { input.dispatch(view, it.nativeKeyEvent, view::dispatchKeyEvent) }
}
