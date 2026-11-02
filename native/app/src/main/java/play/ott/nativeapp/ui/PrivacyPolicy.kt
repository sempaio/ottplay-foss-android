package play.ott.nativeapp.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import play.ott.nativeapp.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import play.ott.nativeapp.BuildConfig

/** Offline copy: reading the policy never opens a provider or requires an installed browser. */
@Composable
internal fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 180.dp.toPx() }
    val readerFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var readerFocused by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.tvRemoteInput().widthIn(max = 920.dp).fillMaxWidth(.94f).fillMaxHeight(.92f).testTag("privacy-policy"),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dialog_privacy_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.dialog_privacy_updated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isTv) Text(stringResource(R.string.dialog_privacy_remote_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("privacy-policy-text")
                        .focusRequester(readerFocus)
                        .onPreviewKeyEvent { event ->
                            if (!isTv || event.type != KeyEventType.KeyDown) false
                            else when (event.key) {
                                Key.DirectionDown -> {
                                    if (scroll.value < scroll.maxValue) scope.launch { scroll.scrollTo((scroll.value + step.toInt()).coerceAtMost(scroll.maxValue)) }
                                    else closeFocus.requestFocus()
                                    true
                                }
                                Key.DirectionUp -> {
                                    scope.launch { scroll.scrollTo((scroll.value - step.toInt()).coerceAtLeast(0)) }
                                    true
                                }
                                Key.DirectionRight -> { closeFocus.requestFocus(); true }
                                else -> false
                            }
                        }
                        .onFocusChanged { readerFocused = it.isFocused }
                        .border(BorderStroke(2.dp, if (readerFocused) MaterialTheme.colorScheme.primary else Color.Transparent), RoundedCornerShape(8.dp))
                        .focusable(isTv).verticalScroll(scroll).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    privacyPolicySections().forEach { section ->
                        Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(section.bodyRes, *section.formatArgs.toTypedArray()), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ActionButton(stringResource(R.string.dialog_close), onDismiss, Modifier.fillMaxWidth().testTag("privacy-policy-close")
                    .focusRequester(closeFocus).focusProperties { up = readerFocus; left = readerFocus }, selected = true)
            }
        }
        LaunchedEffect(isTv) {
            if (isTv) { withFrameNanos { }; readerFocus.requestFocus() }
        }
    }
}

internal data class PrivacyPolicySection(
    @param:androidx.annotation.StringRes val titleRes: Int,
    @param:androidx.annotation.StringRes val bodyRes: Int,
    val formatArgs: List<String> = emptyList(),
)

/** Publisher identity is configuration, shared by every translation. */
internal data class PrivacyPublisher(
    val name: String = "alvit",
    val email: String = "alvit.work@gmail.com",
)

/** Pure resource model: transport choice and publisher arguments are testable without a Context. */
internal fun privacyPolicySections(
    allowInsecureHttp: Boolean = BuildConfig.ALLOW_INSECURE_HTTP,
    publisher: PrivacyPublisher = PrivacyPublisher(),
): List<PrivacyPolicySection> = listOf(
    PrivacyPolicySection(R.string.dialog_privacy_publisher_title,
        R.string.dialog_privacy_publisher_body, listOf(publisher.name, publisher.email)),
    PrivacyPolicySection(R.string.dialog_privacy_purpose_title,
        R.string.dialog_privacy_purpose_body),
    PrivacyPolicySection(R.string.dialog_privacy_input_title,
        R.string.dialog_privacy_input_body),
    PrivacyPolicySection(R.string.dialog_privacy_network_title,
        R.string.dialog_privacy_network_body),
    PrivacyPolicySection(R.string.dialog_privacy_transport_title,
        if (allowInsecureHttp) R.string.dialog_privacy_transport_http else R.string.dialog_privacy_transport_https),
    PrivacyPolicySection(R.string.dialog_privacy_guide_title,
        R.string.dialog_privacy_guide_body),
    PrivacyPolicySection(R.string.dialog_privacy_drm_title,
        R.string.dialog_privacy_drm_body),
    PrivacyPolicySection(R.string.dialog_privacy_storage_title,
        R.string.dialog_privacy_storage_body),
    PrivacyPolicySection(R.string.dialog_privacy_export_title,
        R.string.dialog_privacy_export_body),
    PrivacyPolicySection(R.string.dialog_privacy_delete_title,
        R.string.dialog_privacy_delete_body),
    PrivacyPolicySection(R.string.dialog_privacy_analytics_title,
        R.string.dialog_privacy_analytics_body),
    PrivacyPolicySection(R.string.dialog_privacy_android_title,
        R.string.dialog_privacy_android_body),
)
