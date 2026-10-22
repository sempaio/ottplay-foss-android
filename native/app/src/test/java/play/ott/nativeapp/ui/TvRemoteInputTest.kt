package play.ott.nativeapp.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.playback.PlaybackTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = PlaybackTestApplication::class)
class TvRemoteInputTest {
    private var controller: ActivityController<Activity>? = null
    private val normalEvents = mutableListOf<KeyEvent>()
    private val input = TvRemoteInput()

    @After fun closeWindow() { controller?.pause()?.stop()?.destroy() }

    @Test fun `phone buttons remain on the ordinary Android input path`() {
        val button = window(isTv = false, button = true) as Button
        var clicks = 0
        button.setOnClickListener { clicks++ }
        var earlyDeliveries = 0
        val earlyDispatch: (KeyEvent) -> Boolean = { earlyDeliveries++; button.dispatchKeyEvent(it) }
        for (event in press(KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertFalse(input.dispatch(button, event, earlyDispatch))
            // The caller's normal Android path remains available after pre-IME declines.
            assertTrue(button.dispatchKeyEvent(event))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, earlyDeliveries)
        assertEquals(1, clicks)
    }

    @Test fun `visible TV keyboard retains every remote direction and center`() {
        val view = window()
        setImeVisible(view, true)
        for (key in remoteDirections) for (event in press(key)) {
            assertFalse(input.dispatch(view, event, view::dispatchKeyEvent))
        }
        assertTrue("No app handler may run before a visible IME", normalEvents.isEmpty())
    }

    @Test fun `an unattached TV view with unknown insets does not bypass the keyboard`() {
        val attached = window()
        val unattached = View(attached.context)
        assertFalse(unattached.isAttachedToWindow)
        assertNull(ViewCompat.getRootWindowInsets(unattached))
        var earlyDeliveries = 0
        for (event in press(KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertFalse(input.dispatch(unattached, event) { earlyDeliveries++; true })
        }
        assertEquals(0, earlyDeliveries)
    }

    @Test fun `printable activation media and modified keys stay on their existing path`() {
        val view = window()
        val ordinaryKeys = listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        for (key in ordinaryKeys) for (event in press(key)) {
            assertFalse(input.dispatch(view, event, view::dispatchKeyEvent))
        }
        for (modifier in listOf(KeyEvent.META_SHIFT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_ALT_ON)) {
            for (key in remoteDirections) for (event in press(key, modifiers = modifier)) {
                assertFalse(input.dispatch(view, event, view::dispatchKeyEvent))
            }
        }
        assertTrue(normalEvents.isEmpty())
    }

    @Test fun `caps and num lock do not disable an otherwise unmodified remote press`() {
        val view = window { it.action == KeyEvent.ACTION_DOWN }
        val events = press(KeyEvent.KEYCODE_DPAD_RIGHT, modifiers = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON)
        assertTrue(events.first().hasNoModifiers())
        events.forEach { assertTrue(input.dispatch(view, it, view::dispatchKeyEvent)) }
        assertEquals(events, normalEvents)
    }

    @Test fun `hidden keyboard remote center uses native button handlers exactly once`() {
        val button = window(button = true) as Button
        var clicks = 0
        button.setOnClickListener { clicks++ }
        val events = press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(input.dispatch(button, events[0], button::dispatchKeyEvent))
        assertTrue(button.isPressed)
        // A keyboard becoming visible between down and up must not strand this owned press.
        setImeVisible(button, true)
        assertTrue(input.dispatch(button, events[1], button::dispatchKeyEvent))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(button.isPressed)
        assertEquals(1, clicks)
        assertEquals(events, normalEvents)
    }

    @Test fun `an unhandled press stays unowned so repeat and release reach the IME normally`() {
        val view = window()
        val down = event(KeyEvent.ACTION_DOWN)
        assertFalse(input.dispatch(view, down, view::dispatchKeyEvent))
        assertFalse(input.dispatch(view, event(KeyEvent.ACTION_DOWN, repeat = 1), view::dispatchKeyEvent))
        assertFalse(input.dispatch(view, event(KeyEvent.ACTION_UP), view::dispatchKeyEvent))
        assertEquals(listOf(down), normalEvents)
    }

    // The removed event kind remains an intentional negative compatibility input.
    @Suppress("DEPRECATION")
    @Test fun `orphan releases repeated downs and multiple actions never activate pre IME handlers`() {
        val view = window { true }
        for (event in listOf(event(KeyEvent.ACTION_UP), event(KeyEvent.ACTION_DOWN, repeat = 1),
            event(KeyEvent.ACTION_MULTIPLE, repeat = 2))) {
            assertFalse(input.dispatch(view, event, view::dispatchKeyEvent))
        }
        assertTrue(normalEvents.isEmpty())
    }

    @Test fun `only the owning device key and down time may complete a handled press`() {
        val view = window { it.action == KeyEvent.ACTION_DOWN }
        val down = event(KeyEvent.ACTION_DOWN)
        assertTrue(input.dispatch(view, down, view::dispatchKeyEvent))
        for (foreign in listOf(
            event(KeyEvent.ACTION_UP, device = 8),
            event(KeyEvent.ACTION_UP, downTime = 101),
            event(KeyEvent.ACTION_UP, key = KeyEvent.KEYCODE_DPAD_LEFT),
            event(KeyEvent.ACTION_DOWN, repeat = 1, device = 8),
            event(KeyEvent.ACTION_DOWN, repeat = 1, downTime = 101),
        )) assertFalse(input.dispatch(view, foreign, view::dispatchKeyEvent))
        assertEquals(listOf(down), normalEvents)
        val repeat = event(KeyEvent.ACTION_DOWN, repeat = 1)
        assertTrue(input.dispatch(view, repeat, view::dispatchKeyEvent))
        val up = event(KeyEvent.ACTION_UP)
        // The normal handler declines UP; the owned release must still be consumed once.
        assertTrue(input.dispatch(view, up, view::dispatchKeyEvent))
        assertFalse(input.dispatch(view, up, view::dispatchKeyEvent))
        assertEquals(listOf(down, repeat, up), normalEvents)
    }

    @Test fun `window loss clears ownership before late repeat and release`() {
        val view = window { it.action == KeyEvent.ACTION_DOWN }
        val down = event(KeyEvent.ACTION_DOWN)
        assertTrue(input.dispatch(view, down, view::dispatchKeyEvent))
        input.clear()
        assertFalse(input.dispatch(view, event(KeyEvent.ACTION_DOWN, repeat = 1), view::dispatchKeyEvent))
        assertFalse(input.dispatch(view, event(KeyEvent.ACTION_UP), view::dispatchKeyEvent))
        assertEquals(listOf(down), normalEvents)
        val nextDown = event(KeyEvent.ACTION_DOWN, downTime = 200)
        assertTrue(input.dispatch(view, nextDown, view::dispatchKeyEvent))
        assertTrue(input.dispatch(view, event(KeyEvent.ACTION_UP, downTime = 200), view::dispatchKeyEvent))
        assertEquals(3, normalEvents.size)
    }

    private fun window(isTv: Boolean = true, button: Boolean = false, handleNormally: (KeyEvent) -> Boolean = { false }): View {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val mode = if (isTv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        @Suppress("DEPRECATION")
        application.resources.updateConfiguration(Configuration(application.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or mode
        }, application.resources.displayMetrics)
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup().visible()
        controller = lifecycle
        val activity = lifecycle.get()
        val view = (if (button) Button(activity) else View(activity)).apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, key -> normalEvents += key; handleNormally(key) }
        }
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(240, 120)) })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.isAttachedToWindow)
        assertEquals(mode, view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        assertTrue(view.requestFocus())
        setImeVisible(view, false)
        return view
    }

    private fun setImeVisible(view: View, visible: Boolean) {
        val bottom = if (visible) 320 else 0
        val insets = if (Build.VERSION.SDK_INT >= 30) WindowInsets.Builder()
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsets.Type.ime(), visible)
            .build()
        else {
            // API 28 exposes this constructor at runtime, but the compile SDK hides it.
            ReflectionHelpers.callConstructor(WindowInsets::class.java,
                ReflectionHelpers.ClassParameter.from(Rect::class.java, Rect(0, 0, 0, bottom)))
        }
        // Robolectric has no IME window/server. Populate Android's real attached
        // ViewRoot inset cache, without replacing the production guard or View APIs.
        val root = ReflectionHelpers.callInstanceMethod<Any>(view, "getViewRootImpl")
        assertNotNull(root)
        ReflectionHelpers.setField(root, "mLastWindowInsets", insets)
        assertEquals(visible, requireNotNull(ViewCompat.getRootWindowInsets(view)).isVisible(WindowInsetsCompat.Type.ime()))
    }

    private fun press(key: Int, modifiers: Int = 0): List<KeyEvent> = listOf(
        event(KeyEvent.ACTION_DOWN, key = key, modifiers = modifiers),
        event(KeyEvent.ACTION_UP, key = key, modifiers = modifiers),
    )

    private fun event(action: Int, key: Int = KeyEvent.KEYCODE_DPAD_RIGHT, repeat: Int = 0,
                      modifiers: Int = 0, device: Int = 7, downTime: Long = 100): KeyEvent =
        KeyEvent(downTime, downTime + 10 + repeat, action, key, repeat, modifiers, device, 0, 0, InputDevice.SOURCE_DPAD)

    private companion object {
        val remoteDirections = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
    }
}
