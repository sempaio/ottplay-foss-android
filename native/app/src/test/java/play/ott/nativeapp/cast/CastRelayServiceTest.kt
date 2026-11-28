package play.ott.nativeapp.cast

import android.app.Notification
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import play.ott.nativeapp.playback.PlaybackTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class CastRelayServiceTest {
    @Before fun resetBefore() = runBlocking {
        CastSessionHost.controller.disconnect(context = null)
    }

    @After fun resetAfter() = runBlocking {
        CastSessionHost.controller.disconnect(context = null)
    }

    @Test fun bridgeModeAcquiresWakeAndWifiLocksAndDirectModeReleasesBoth() {
        val controller = Robolectric.buildService(CastRelayService::class.java).create()
        val service = controller.get()
        try {
            // 1. Bridge Mode (isDirectMode = false) -> MUST acquire PARTIAL_WAKE_LOCK & WifiLock
            val bridgeIntent = Intent(service, CastRelayService::class.java).apply {
                action = CastRelayService.ACTION_START_OR_UPDATE
                putExtra(CastRelayService.EXTRA_TV_NAME, "Samsung QLED")
                putExtra(CastRelayService.EXTRA_MEDIA_TITLE, "Globo HD")
                putExtra(CastRelayService.EXTRA_IS_PLAYING, true)
                putExtra(CastRelayService.EXTRA_DIRECT_MODE, false)
            }
            val bridgeStartResult = service.onStartCommand(bridgeIntent, 0, 1)
            assertEquals(android.app.Service.START_NOT_STICKY, bridgeStartResult)
            assertTrue("Bridge mode must hold PARTIAL_WAKE_LOCK", service.isWakeLockHeldForTest())
            assertTrue("Bridge mode must hold WifiLock", service.isWifiLockHeldForTest())
            assertTrue(CastRelayService.isServiceActive)

            val bridgeNotif = shadowOf(service).lastForegroundNotification
            assertNotNull(bridgeNotif)
            assertEquals(
                "Transmitindo para Samsung QLED",
                bridgeNotif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )

            // 2. Direct Cloud/CDN Mode (isDirectMode = true) -> MUST release BOTH WakeLock & WifiLock!
            val directIntent = Intent(service, CastRelayService::class.java).apply {
                action = CastRelayService.ACTION_START_OR_UPDATE
                putExtra(CastRelayService.EXTRA_TV_NAME, "Chromecast Sala")
                putExtra(CastRelayService.EXTRA_MEDIA_TITLE, "Pluto TV Filmes")
                putExtra(CastRelayService.EXTRA_IS_PLAYING, true)
                putExtra(CastRelayService.EXTRA_DIRECT_MODE, true)
            }
            service.onStartCommand(directIntent, 0, 2)
            assertFalse("Direct mode MUST release PARTIAL_WAKE_LOCK to save battery", service.isWakeLockHeldForTest())
            assertFalse("Direct mode MUST release WifiLock to save battery", service.isWifiLockHeldForTest())

            val directNotif = shadowOf(service).lastForegroundNotification
            assertNotNull(directNotif)
            assertEquals(
                "☁️ Direto em Chromecast Sala",
                directNotif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )

            // 3. Toggle back to Bridge mode (e.g. zapping from Direct Pluto to HTTP Bridge channel) -> re-acquires locks
            service.onStartCommand(bridgeIntent, 0, 3)
            assertTrue("Switching back to Bridge mode must re-acquire PARTIAL_WAKE_LOCK", service.isWakeLockHeldForTest())
            assertTrue("Switching back to Bridge mode must re-acquire WifiLock", service.isWifiLockHeldForTest())

            // 4. Explicit ACTION_STOP_SERVICE releases locks cleanly
            val stopIntent = Intent(service, CastRelayService::class.java).apply {
                action = CastRelayService.ACTION_STOP_SERVICE
            }
            service.onStartCommand(stopIntent, 0, 4)
            assertFalse(service.isWakeLockHeldForTest())
            assertFalse(service.isWifiLockHeldForTest())
            assertFalse(CastRelayService.isServiceActive)
        } finally {
            controller.destroy()
        }
    }

    @Test fun nullIntentRestartWithoutActiveCastSessionAcquiresNoLocksAndStopsCleanly() {
        val controller = Robolectric.buildService(CastRelayService::class.java).create()
        val service = controller.get()
        try {
            assertFalse(CastSessionHost.isCastingActive)
            val result = service.onStartCommand(null, 0, 1)
            assertEquals(android.app.Service.START_NOT_STICKY, result)
            assertFalse("Null-intent restart without active session must NEVER acquire WakeLock", service.isWakeLockHeldForTest())
            assertFalse("Null-intent restart without active session must NEVER acquire WifiLock", service.isWifiLockHeldForTest())
            assertFalse(CastRelayService.isServiceActive)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
