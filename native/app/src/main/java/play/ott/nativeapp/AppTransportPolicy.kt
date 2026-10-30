package play.ott.nativeapp

import play.ott.nativeapp.core.RemoteTransportPolicy

/** One build-time choice shared by providers, playback, DRM, artwork and source validation. */
internal object AppTransportPolicy {
    val current = RemoteTransportPolicy(allowInsecureHttp = BuildConfig.ALLOW_INSECURE_HTTP)
}
