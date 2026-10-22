package play.ott.nativeapp

import play.ott.nativeapp.core.ProviderException

/** Show status-specific guidance without ever rendering provider URLs, bodies or credentials. */
internal fun providerErrorResource(error: Exception): Int = when {
    error is ProviderException && error.statusCode == 401 -> R.string.starlink_login_denied
    error is ProviderException && error.statusCode == 403 && error.reasonCode == "client_blocked" -> R.string.starlink_client_blocked
    error is ProviderException && error.statusCode == 403 && error.reasonCode == "subscription_expired" -> R.string.starlink_subscription_expired
    error is ProviderException && error.statusCode == 403 && error.reasonCode == "client_inactive" -> R.string.starlink_client_inactive
    error is ProviderException && error.statusCode == 403 -> R.string.starlink_access_denied
    error is ProviderException && error.statusCode in 500..599 -> R.string.starlink_server_unavailable
    error is java.net.UnknownHostException -> R.string.message_host_not_found
    error is java.net.SocketTimeoutException -> R.string.message_network_timeout
    else -> R.string.message_operation_failed
}
