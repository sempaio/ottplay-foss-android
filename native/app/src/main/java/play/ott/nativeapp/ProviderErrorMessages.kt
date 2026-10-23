package play.ott.nativeapp

import play.ott.nativeapp.core.ProviderException

/** Show status-specific guidance without ever rendering provider URLs, bodies or credentials. */
internal fun providerErrorResource(error: Exception, customerMode: Boolean = BuildConfig.CUSTOMER_MODE): Int = if (customerMode) customerErrorResource(error) else when {
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.DNS -> R.string.message_host_not_found
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.TIMEOUT -> R.string.message_network_timeout
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.TLS -> R.string.starlink_tls_failed
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.CONNECTION -> R.string.starlink_connection_failed
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.AUTHENTICATION -> R.string.starlink_credentials_rejected
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.ACCOUNT_INACTIVE -> R.string.starlink_account_inactive
    error is ProviderException && error.failure == play.ott.nativeapp.core.ProviderFailure.INVALID_RESPONSE -> R.string.starlink_invalid_response
    error is ProviderException && error.statusCode == 401 -> R.string.starlink_login_denied
    error is ProviderException && error.statusCode == 403 -> R.string.starlink_access_denied
    error is ProviderException && error.statusCode in 500..599 -> R.string.starlink_server_unavailable
    error is java.net.UnknownHostException -> R.string.message_host_not_found
    error is java.net.SocketTimeoutException -> R.string.message_network_timeout
    else -> R.string.message_operation_failed
}

private fun customerErrorResource(error: Exception): Int {
    val provider = error as? ProviderException
    return when {
        provider?.failure == play.ott.nativeapp.core.ProviderFailure.AUTHENTICATION || provider?.statusCode == 401 -> R.string.customer_auth_error
        provider?.failure == play.ott.nativeapp.core.ProviderFailure.ACCOUNT_INACTIVE || provider?.statusCode == 403 -> R.string.customer_access_error
        provider?.failure == play.ott.nativeapp.core.ProviderFailure.TIMEOUT || error is java.net.SocketTimeoutException -> R.string.customer_timeout_error
        provider?.failure in setOf(play.ott.nativeapp.core.ProviderFailure.DNS, play.ott.nativeapp.core.ProviderFailure.CONNECTION, play.ott.nativeapp.core.ProviderFailure.TLS) || error is java.net.UnknownHostException -> R.string.customer_connection_error
        provider?.statusCode in 500..599 || provider?.failure == play.ott.nativeapp.core.ProviderFailure.INVALID_RESPONSE -> R.string.customer_service_error
        else -> R.string.customer_generic_error
    }
}
