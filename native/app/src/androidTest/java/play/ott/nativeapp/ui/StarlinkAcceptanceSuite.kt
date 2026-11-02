package play.ott.nativeapp.ui

import org.junit.runner.RunWith
import org.junit.runners.Suite
import play.ott.nativeapp.playback.HlsPlaybackInstrumentedTest
import play.ott.nativeapp.playback.NativePlaybackInstrumentedTest

/** One runner class prevents comma-separated Gradle filters from silently selecting only the first class. */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    StarlinkUiInstrumentedTest::class,
    CustomerUiInstrumentedTest::class,
    NeonLayoutInstrumentedTest::class,
    StarlinkStartupInstrumentedTest::class,
    NativeUiInstrumentedTest::class,
    HlsPlaybackInstrumentedTest::class,
    NativePlaybackInstrumentedTest::class,
)
class StarlinkAcceptanceSuite
