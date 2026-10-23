package play.ott.nativeapp

import play.ott.nativeapp.core.*
import kotlin.test.*

class CustomerSourcePolicyTest {
    private val account = SourceConfig("account", "Account", SourceKind.XTREAM, StarlinkConfig.XTREAM_BASE_URL, "fixture-user", "fixture-password")
    @Test fun customerAccountsHaveFixedOperatorEndpoints() {
        assertTrue(StarlinkConfig.isManagedSource(account))
        assertFalse(StarlinkConfig.isManagedSource(account.copy(url = "https://example.invalid")))
        assertFalse(StarlinkConfig.isManagedSource(account.copy(headers = mapOf("X-Test" to "test"))))
        assertFalse(StarlinkConfig.isManagedSource(account.copy(epgUrl = "https://example.invalid/epg")))
        assertTrue(StarlinkConfig.isManagedSource(StarlinkConfig.playlistSource()))
        assertFalse(StarlinkConfig.isManagedSource(StarlinkConfig.playlistSource().copy(url = "https://example.invalid/list.m3u")))
    }
    @Test fun legacySourcesAreHiddenWithoutDeletingTheirStoredData() {
        val legacy = account.copy(id = "legacy", url = "https://example.invalid")
        val all = listOf(account, legacy, StarlinkConfig.playlistSource())
        assertEquals(listOf(account, StarlinkConfig.playlistSource()), StarlinkConfig.visibleSources(all, true))
        assertEquals(all, StarlinkConfig.visibleSources(all, false))
        assertEquals(3, all.size)
    }
    @Test fun customersReceiveUsefulMessagesWithoutTechnicalInternals() {
        val reasons = ProviderFailure.entries
        for (reason in reasons) {
            val resource = providerErrorResource(ProviderException("untrusted", failure = reason), true)
            assertTrue(resource in setOf(R.string.customer_auth_error,R.string.customer_access_error,R.string.customer_connection_error,R.string.customer_timeout_error,R.string.customer_generic_error,R.string.customer_service_error))
        }
    }
}
