package play.ott.nativeapp.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import play.ott.nativeapp.MainActivity
import play.ott.nativeapp.OttplayApplication
import play.ott.nativeapp.TvActivity
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.UserPreferences
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.playback.PlaybackService
import play.ott.nativeapp.playback.PlaybackTestLifecycle

/** Real Activity, persisted catalogue, ViewModel, service and decoder; no replacement UI/controller. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealActivityPlaybackInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as OttplayApplication).repository
    private val source = NativeRepository.demoSource().copy(
        id = "activity-test-${UUID.randomUUID()}", name = "Activity test",
        nameMessage = null, nameIsUserDefined = true,
    )
    private val entryId get() = "${source.id}:pattern"
    private val isTv get() = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    private var activity: MainActivity? = null
    private var savedPreferences: UserPreferences? = null
    private var menuOpenAttempt = 0

    @Before fun seedOnlyOurSyntheticSource(): Unit = runBlocking {
        PlaybackTestLifecycle.finishPreviousPlayback()
        savedPreferences = repository.preferences.data.first()
        repository.save(source)
        repository.refresh(source)
        repository.preferences.update { it.copy(selectedSourceId = source.id, backgroundPlayback = true) }
    }

    @After fun restoreUserPreferencesAndRemoveOnlyOurSource(): Unit = runBlocking {
        onMain { activity?.finish() }
        PlaybackTestLifecycle.finishPreviousPlayback()
        repository.remove(source.id)
        savedPreferences?.let { previous -> repository.preferences.update { previous } }
    }

    @Test fun coldLaunchSelectsWithRemoteAndNativeControlsChangeActualPlayback() {
        val playerView = launchAndPlayDemo()
        val actualPlayer = requireNotNull(playerView.player)
        if (isTv) {
            // Reveal controls through the remote if startup already outlasted
            // their timer; keep them visible until the explicit auto-hide phase.
            var revealControls = false
            onMain {
                playerView.controllerShowTimeoutMs = 0
                revealControls = !playerView.isControllerFullyVisible
            }
            if (revealControls) key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitViewFocus(androidx.media3.ui.R.id.exo_play_pause)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        } else onMain { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick() }
        awaitPlayback("Native pause must preserve the selected item") { !it.playWhenReady && it.currentMediaItem?.mediaId == entryId }
        if (isTv) {
            // Keep the current remote focus while the resume action settles. The
            // automatic-hide behavior is exercised explicitly in the next phase.
            onMain { playerView.controllerShowTimeoutMs = 0 }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        } else onMain { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick() }
        awaitPlayback("Native play must resume") { it.isPlaying }

        if (isTv) {
            awaitViewFocus(androidx.media3.ui.R.id.exo_play_pause)
            onMain {
                playerView.controllerShowTimeoutMs = 500
                playerView.showController()
            }
            awaitState("Native controls must automatically hide during playback") {
                var hidden = false
                onMain { hidden = !playerView.isControllerFullyVisible }
                hidden
            }
            var retainsRemoteFocus = false
            onMain { retainsRemoteFocus = playerView.hasFocus() }
            if (!retainsRemoteFocus) {
                capturePlaybackDiagnostics("Automatic controller hide lost native remote focus", includeSemantics = true)
            }
            assertTrue("Automatic controller hide must retain remote focus within the native player", retainsRemoteFocus)
            onMain { playerView.controllerShowTimeoutMs = 4000 }
        }

        openOptions()
        compose.onNodeWithTag("player-subtitle-options").assertIsNotEnabled() // Fixture has no subtitle stream.
        activateMenuItem("player-speed-options")
        activateMenuItem("player-speed-1.25")
        awaitPlayback("Speed selection must reach the service") { it.playbackParameters.speed == 1.25f }
        openOptions()
        activateMenuItem("player-scale-${AspectRatioFrameLayout.RESIZE_MODE_ZOOM}")
        awaitActivityWindowReady(requireNotNull(activity), acknowledgeImmersiveTutorial = true)
        onMain { assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, playerView.resizeMode) }

        if (isTv) {
            // Isolate explicit Back behavior from the separately tested auto-hide
            // timer, then let its visibility callback update Compose's BackHandler.
            onMain {
                playerView.controllerShowTimeoutMs = 0
                if (!playerView.isControllerFullyVisible) playerView.showController()
            }
            awaitState("Native controls must be visible before testing explicit Back") {
                var visible = false
                onMain { visible = playerView.isControllerFullyVisible }
                visible
            }
            compose.waitForIdle()
            // The first Back dismisses controls; the second returns to the catalogue and restores focus.
            key(KeyEvent.KEYCODE_BACK)
            onMain { assertTrue("Back must hide native controls", !playerView.isControllerFullyVisible) }
            compose.onNodeWithTag("player-container").assertIsDisplayed()
        }
        key(KeyEvent.KEYCODE_BACK)
        awaitNode("now-playing-bar")
        if (isTv) awaitFocused("catalog-item-$entryId")
        awaitPlaybackWithoutSurface("Leaving fullscreen must keep the service playing", actualPlayer) { it.isPlaying }
    }

    @Test fun homePausesTelevisionVideoAndPreservesPhonePictureInPicture() {
        val actualPlayer = requireNotNull(launchAndPlayDemo().player)
        val supportsPhonePip = !isTv && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        openOptions()
        if (supportsPhonePip) compose.onNodeWithTag("player-pip").performScrollTo().assertIsDisplayed()
        else compose.onNodeWithTag("player-pip").assertDoesNotExist()
        key(KeyEvent.KEYCODE_BACK)
        awaitActivityWindowReady(requireNotNull(activity), acknowledgeImmersiveTutorial = true)

        assertTrue("The system must accept Home", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        awaitState("Home did not complete the Activity transition") {
            var transitioned = false
            onMain {
                val current = requireNotNull(activity)
                transitioned = if (supportsPhonePip) current.isInPictureInPictureMode
                else ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(current) == Stage.STOPPED
            }
            transitioned
        }
        if (isTv) {
            onMain { assertFalse("TV must never enter video PiP", requireNotNull(activity).isInPictureInPictureMode) }
            awaitPlaybackWithoutSurface("Home must pause TV even when background playback is enabled", actualPlayer) {
                !it.playWhenReady && !it.isPlaying && it.currentMediaItem?.mediaId == entryId
            }
            // Model a provider request that completes after onStop: it cannot restart hidden TV video.
            onMain { actualPlayer.play() }
            awaitPlaybackWithoutSurface("A late Play must not restart TV video behind Home", actualPlayer) { !it.playWhenReady }
        } else {
            var position = 0L
            onMain { position = actualPlayer.currentPosition }
            awaitPlaybackWithoutSurface("Phone playback must continue after Home", actualPlayer) {
                it.isPlaying && it.currentMediaItem?.mediaId == entryId && it.currentPosition != position
            }
        }

        // Returning through the launcher keeps the paused TV item available for an explicit resume.
        returnThroughSystemLauncher()
        awaitActivityWindowReady(requireNotNull(activity), acknowledgeImmersiveTutorial = true)
        if (isTv) {
            awaitPlayback("Returning to TV must preserve the paused video") { !it.playWhenReady && it.currentMediaItem?.mediaId == entryId }
            awaitViewFocus(androidx.media3.ui.R.id.exo_play_pause)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlayback("TV playback must resume when the user presses Play") { it.isPlaying }
            val resumedView = awaitPlayerView()
            onMain { resumedView.controllerShowTimeoutMs = 0 }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitState("Right must move focus between native controls after the language/IME handoff") {
                var moved = false
                onMain {
                    moved = resumedView.hasFocus() &&
                        resumedView.findFocus()?.id != androidx.media3.ui.R.id.exo_play_pause
                }
                moved
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitViewFocus(androidx.media3.ui.R.id.exo_play_pause)
            onMain {
                assertTrue("Native TV gear must open the same remote-aware Options menu",
                    resumedView.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick())
            }
            awaitNode("player-speed-options")
            key(KeyEvent.KEYCODE_BACK)
            awaitActivityWindowReady(requireNotNull(activity))
            awaitViewFocus(androidx.media3.ui.R.id.exo_play_pause)
        }
    }

    @Test fun changingAppLanguageRecreatesActivityWithoutPausingOrLosingPlayback() {
        // A phone with background playback disabled must still survive recreation;
        // the existing Home test deliberately enables background playback instead.
        runBlocking { repository.preferences.update { it.copy(backgroundPlayback = false) } }
        launchAndPlayDemo()
        val original = requireNotNull(activity)
        var previousTag = ""
        var previousLanguage = ""
        var targetTag = ""
        onMain {
            previousTag = AppLanguages.currentTag()
            previousLanguage = original.resources.configuration.locales[0].language
            targetTag = if (previousLanguage == "en") "ru" else "en"
        }

        // The Activity releases its controller during recreation. A separate connection
        // observes the same service across that handoff, including transient pauses.
        var connection: ListenableFuture<MediaController>? = null
        var observer: MediaController? = null
        var playerListener: Player.Listener? = null
        var observing = false
        val interruptions = mutableListOf<String>() // All accesses are on the main looper.
        fun recordInterruption(description: String) {
            interruptions += description
            Log.w("OttPlaybackUiTest", "language recreation interruption: $description")
        }
        var originalFailure: Throwable? = null
        try {
            onMain {
                connection = MediaController.Builder(context.applicationContext,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                    .setListener(object : MediaController.Listener {
                        override fun onDisconnected(controller: MediaController) {
                            if (observing) recordInterruption("service controller disconnected")
                        }
                    }).buildAsync()
            }
            val player = requireNotNull(connection).get(10, TimeUnit.SECONDS)
            observer = player
            awaitPlaybackWithoutSurface("Independent controller must observe the playing demo", player) {
                player.isConnected && it.isPlaying && it.currentMediaItem?.mediaId == entryId
            }
            // Rewind the eight-second fixture before changing configuration. Do not
            // issue Play or replace its item after the switch: that would hide a pause.
            onMain { player.seekTo(0) }
            awaitPlaybackWithoutSurface("Rewound demo must be playing before the language switch", player) {
                it.isPlaying && it.currentPosition in 100L..2_000L && it.currentMediaItem?.mediaId == entryId
            }
            onMain {
                playerListener = object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady) recordInterruption("playWhenReady=false, reason=$reason")
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (mediaItem?.mediaId != entryId) {
                            recordInterruption("item=${mediaItem?.mediaId}, reason=$reason")
                        }
                    }
                }.also(player::addListener)
                observing = true
                Log.i("OttPlaybackUiTest", "language switch $previousTag -> $targetTag; " +
                    "item=${player.currentMediaItem?.mediaId}, position=${player.currentPosition}")
                AppLanguages.setLanguage(targetTag)
            }

            awaitState("Language change must resume a new launcher Activity in $targetTag") {
                var replaced = false
                onMain {
                    val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull { it.componentName == original.componentName }
                    if (resumed != null) activity = resumed
                    replaced = resumed != null && resumed !== original &&
                        resumed.resources.configuration.locales[0].language == targetTag &&
                        AppLanguages.currentTag() == targetTag
                }
                replaced
            }
            awaitNode("player-container")
            val replacementView = awaitPlayerView()
            var resumedPosition = 0L
            onMain {
                assertEquals("Recreation must keep the phone/TV launcher", original.javaClass, requireNotNull(activity).javaClass)
                assertEquals("Replacement surface must retain the selected item", entryId, replacementView.player?.currentMediaItem?.mediaId)
                resumedPosition = player.currentPosition
            }
            // Observe beyond the bounded visibility handoff as well, so a delayed
            // expiry that incorrectly pauses the resumed Activity cannot pass.
            val observeUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            awaitPlaybackWithoutSurface("Playback must continue after locale recreation and handoff expiry", player) {
                System.nanoTime() >= observeUntil && player.isConnected && it.isPlaying &&
                    it.currentMediaItem?.mediaId == entryId && it.currentPosition != resumedPosition
            }
            onMain {
                Log.i("OttPlaybackUiTest", "language recreation complete; item=${player.currentMediaItem?.mediaId}, " +
                    "position=${player.currentPosition}, interruptions=$interruptions")
                assertTrue("Language recreation interrupted playback: $interruptions", interruptions.isEmpty())
                assertTrue("Playback must remain requested", player.playWhenReady)
            }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                var changed = false
                var beforeRestore: MainActivity? = null
                onMain {
                    observing = false
                    playerListener?.let { observer?.removeListener(it) }
                    connection?.let(MediaController::releaseFuture)
                    changed = AppLanguages.currentTag() != previousTag
                    beforeRestore = activity
                    AppLanguages.setLanguage(previousTag)
                }
                if (changed) {
                    awaitState("The test must restore the previous app language in a new Activity") {
                        var restored = false
                        onMain {
                            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<MainActivity>().firstOrNull { it.componentName == original.componentName }
                            if (resumed != null) activity = resumed
                            restored = resumed != null && resumed !== beforeRestore &&
                                beforeRestore?.isDestroyed == true &&
                                AppLanguages.currentTag() == previousTag &&
                                resumed.resources.configuration.locales[0].language == previousLanguage
                        }
                        restored
                    }
                    // Resource updates can precede recreation and the IME/window handoff.
                    // Complete that transition before teardown starts the next test.
                    awaitActivityWindowReady(requireNotNull(activity), acknowledgeImmersiveTutorial = true)
                }
            } catch (cleanupFailure: Throwable) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun returnThroughSystemLauncher() {
        // Resolve as the system launcher, independently of the app's package-visibility filters.
        val home = requireNotNull(shellOutput(
            "cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.HOME",
        ).lineSequence().mapNotNull { ComponentName.unflattenFromString(it.trim()) }.lastOrNull())
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var windowsDescription = "not observed"
        try {
            awaitState("Home must have focus before reopening the app") {
                val windows = automation.windows
                windowsDescription = windows.joinToString { window ->
                    "id=${window.id}, type=${window.type}, focused=${window.isFocused}, active=${window.isActive}, package=${window.root?.packageName}"
                }
                // In PiP, the accessibility active window can still be the player.
                // Input focus belongs to the separate Home window, so inspect that window explicitly.
                windows.any { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused &&
                        window.root?.packageName?.toString() == home.packageName
                }
            }
        } catch (failure: AssertionError) {
            val systemFocus = shellOutput("dumpsys window").lineSequence()
                .filter { it.contains("mCurrentFocus=") || it.contains("mFocusedApp=") }.joinToString()
            throw AssertionError("Home focus not observed: expected=${home.flattenToShortString()}; windows=[$windowsDescription]; $systemFocus", failure)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        // The PiP callback precedes the end of Home's animation. Let the system finish
        // that transition before launching again, as a user selecting the launcher would.
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val launch = requireNotNull(PlaybackTestLifecycle.launchIntent().component)
        val component = launch.flattenToString()
        check(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+").matches(component))
        val output = shellOutput("am start -W -n $component")
        assertTrue("System launcher did not open the app: $output", output.lineSequence().any { it.trim() == "Status: ok" })
        awaitState("The launched Activity did not resume") {
            var resumed: MainActivity? = null
            onMain {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it.componentName == launch }
            }
            if (resumed != null) activity = resumed
            resumed != null
        }
    }

    private fun shellOutput(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun launchAndPlayDemo(): PlayerView {
        val launch = PlaybackTestLifecycle.launchIntent()
        activity = instrumentation.startActivitySync(launch) as MainActivity
        val launched = requireNotNull(activity)
        assertEquals("The actual launcher Activity must match the device", isTv, launched is TvActivity)
        assertLauncherPipDeclaration(launched)
        awaitNode("catalog-item-$entryId")
        awaitActivityWindowReady(requireNotNull(activity), hideIme = true)
        if (isTv) {
            // Crucially no RequestFocus or touch injection: launch must create usable remote focus.
            awaitFocused("library-tab-MOVIES")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            // The rail scrolls the lazy target into composition and waits a frame before
            // assigning focus. Observe that real transition without injecting focus.
            val focusStarted = System.nanoTime()
            awaitFocused("catalog-item-$entryId")
            Log.i("OttPlaybackUiTest", "D-pad catalogue focus settled after " +
                "${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - focusStarted)} ms")
            compose.onNodeWithTag("catalog-item-$entryId").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        } else compose.onNodeWithTag("catalog-item-$entryId").performClick()
        awaitNode("player-container")
        val playerView = awaitPlayerView()
        // Keep the eight-second deterministic fixture available while interacting with menus.
        onMain { requireNotNull(playerView.player).repeatMode = Player.REPEAT_MODE_ONE }
        awaitPlayback("Video must actually decode") { it.isPlaying && it.videoSize.width > 0 && it.currentPosition > 100 }
        return playerView
    }

    private fun assertLauncherPipDeclaration(launched: MainActivity) {
        // Read the installed binary manifest; ActivityInfo's PiP flag is not public SDK API.
        val namespace = "http://schemas.android.com/apk/res/android"
        val names = setOf(launched.componentName.className, ".${launched.javaClass.simpleName}")
        var supportsPip: Boolean? = null
        context.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.next() != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "activity" &&
                    manifest.getAttributeValue(namespace, "name") in names) {
                    supportsPip = manifest.getAttributeBooleanValue(namespace, "supportsPictureInPicture", false)
                    break
                }
            }
        }
        assertEquals("Only the phone launcher may declare PiP support in the installed manifest", !isTv, supportsPip)
    }

    private fun openOptions() {
        awaitActivityWindowReady(requireNotNull(activity), acknowledgeImmersiveTutorial = true)
        if (isTv) awaitState("Native player must regain remote focus after the dialog closes") {
            var focused = false
            onMain { focused = activity?.window?.decorView?.findViewWithTag<View>("ott-native-video")?.hasFocus() == true }
            focused
        }
        menuOpenAttempt++
        // A semantics query synchronizes Compose and could hide the focus-handoff race.
        capturePlaybackDiagnostics("openOptions#$menuOpenAttempt before input", includeCompose = false)
        if (isTv) key(KeyEvent.KEYCODE_MENU) else compose.onNodeWithTag("player-options").performClick()
        capturePlaybackDiagnostics("openOptions#$menuOpenAttempt after input")
        awaitNode("player-speed-options")
    }

    private fun activateMenuItem(tag: String) {
        if (isTv) {
            // Follow actual D-pad navigation, without assigning focus to the desired target.
            repeat(10) {
                if (isFocused(tag)) { key(KeyEvent.KEYCODE_DPAD_CENTER); return }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            throw AssertionError("Remote could not reach $tag")
        } else {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            // The menu action updates Compose state; wait for AndroidView.update to apply it.
            compose.waitForIdle()
        }
    }

    private fun isFocused(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun awaitFocused(tag: String) {
        try {
            compose.waitUntil(10_000) { isFocused(tag) }
            compose.onNodeWithTag(tag).assertIsFocused()
        } catch (failure: Throwable) {
            try { capturePlaybackDiagnostics("awaitFocused($tag) failed", includeSemantics = true) }
            catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
    }

    private fun awaitNode(tag: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).assertIsDisplayed()
        } catch (failure: Throwable) {
            // Capture the failed UI before @After removes its Activity and Dialogs.
            // Diagnostics must never replace the original assertion/timeout.
            try {
                capturePlaybackDiagnostics("awaitNode($tag) failed; menuAttempt=$menuOpenAttempt", includeSemantics = true)
            } catch (diagnosticFailure: Throwable) {
                failure.addSuppressed(diagnosticFailure)
            }
            throw failure
        }
    }

    private fun capturePlaybackDiagnostics(stage: String, includeCompose: Boolean = true, includeSemantics: Boolean = false) {
        fun record(label: String, read: () -> String) {
            try {
                read().chunked(3_000).forEachIndexed { index, text ->
                    Log.i("OttPlaybackUiTest", "$stage; $label[$index]: $text")
                }
            } catch (failure: Throwable) {
                Log.w("OttPlaybackUiTest", "$stage; $label unavailable", failure)
            }
        }

        record("windows") {
            var description = ""
            onMain {
                fun describe(view: View?): String {
                    if (view == null) return "null"
                    val id = runCatching { view.resources.getResourceName(view.id) }.getOrDefault(view.id.toString())
                    val playback = if (view is PlayerView) {
                        val button = view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        ", controllerVisible=${view.isControllerFullyVisible}, controllerTimeoutMs=${view.controllerShowTimeoutMs}, " +
                            "playPauseShown=${button?.isShown}, playPauseFocused=${button?.hasFocus()}"
                    } else ""
                    return "${view.javaClass.name}@${System.identityHashCode(view)}(id=$id, tag=${view.tag}, " +
                        "attached=${view.isAttachedToWindow}, windowFocus=${view.hasWindowFocus()}, " +
                        "hasFocus=${view.hasFocus()}, visibility=${view.visibility}$playback)"
                }
                val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
                    else listOfNotNull(activity?.window?.decorView)
                description = "activityFocus=${describe(activity?.currentFocus)}; " + roots.joinToString("\n") { root ->
                    val params = root.layoutParams as? WindowManager.LayoutParams
                    "title=${params?.title}, type=${params?.type}, token=${root.windowToken}, root=${describe(root)}, " +
                        "focusedChild=${describe(root.findFocus())}, nativePlayer=${describe(root.findViewWithTag<View>("ott-native-video"))}"
                }
            }
            description
        }
        if (includeSemantics) {
            record("system input focus") {
                shellOutput("dumpsys input").lineSequence().filter {
                    it.contains("Focused", true) || it.contains("TouchMode", true) || it.contains("filter", true)
                }.joinToString("\n")
            }
            record("system window focus") {
                shellOutput("dumpsys window").lineSequence().filter {
                    it.contains("CurrentFocus") || it.contains("FocusedApp") || it.contains("FocusedWindow")
                }.joinToString("\n")
            }
        }

        if (!includeCompose) return
        record("menu tags") {
            val tags = listOf("player-options", "player-speed-options", "player-speed-0.5", "player-speed-0.75",
                "player-speed-1.0", "player-speed-1.25", "player-speed-1.5", "player-speed-2.0")
            val nodes = compose.onAllNodes(SemanticsMatcher("Playback menu diagnostic tags") {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } in tags
            }, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
            tags.joinToString { tag ->
                val matches = nodes.filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
                "$tag=${matches.size}(focused=${matches.count { it.config.getOrElse(SemanticsProperties.Focused) { false } }})"
            }
        }

        if (includeSemantics) record("all Compose roots, unmerged") {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val count = roots.fetchSemanticsNodes(atLeastOneRootRequired = false).size
            "rootCount=$count\n" + (0 until count).joinToString("\n") { index ->
                "root[$index]:\n${roots[index].printToString()}"
            }
        }
    }

    private fun awaitPlayerView(): PlayerView {
        var view: PlayerView? = null
        awaitState("Activity did not attach its native PlayerView") {
            onMain { view = activity?.window?.decorView?.findViewWithTag("ott-native-video") }
            view?.player != null
        }
        return requireNotNull(view)
    }

    private fun awaitViewFocus(id: Int) = awaitState("Native playback control did not receive TV focus") {
        var focused = false
        onMain { focused = activity?.currentFocus?.id == id }
        focused
    }

    private fun awaitPlayback(message: String, condition: (Player) -> Boolean) {
        val player = awaitPlayerView().player
        awaitPlaybackWithoutSurface(message, player, condition)
    }

    private fun awaitPlaybackWithoutSurface(message: String, player: Player?, condition: (Player) -> Boolean) {
        awaitState(message) {
            var ready = false
            onMain {
                val actual = requireNotNull(player)
                assertEquals("Unexpected decoder error", null, actual.playerError?.errorCodeName)
                ready = condition(actual)
            }
            ready
        }
    }

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        val failure = AssertionError(message)
        try {
            capturePlaybackDiagnostics("awaitState($message) failed; menuAttempt=$menuOpenAttempt", includeSemantics = true)
        } catch (diagnosticFailure: Throwable) {
            failure.addSuppressed(diagnosticFailure)
        }
        throw failure
    }

    private fun key(code: Int) {
        // Exercise Android input routing with a virtual remote and fail if the OS rejects it.
        val now = android.os.SystemClock.uptimeMillis()
        val source = if (isTv) android.view.InputDevice.SOURCE_DPAD else android.view.InputDevice.SOURCE_KEYBOARD
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, android.os.SystemClock.uptimeMillis(), action, code, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, source)
            val injected = instrumentation.uiAutomation.injectInputEvent(event, true)
            assertTrue("Android must accept remote input key=$code action=$action", injected)
        }
        compose.waitForIdle()
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
