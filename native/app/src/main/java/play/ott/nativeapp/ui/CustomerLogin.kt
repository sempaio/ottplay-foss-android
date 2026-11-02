package play.ott.nativeapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import play.ott.nativeapp.R
import play.ott.nativeapp.StarlinkConfig
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import java.util.UUID

/** Managed customer entry: no server/type/header/import controls exist in this screen. */
@Composable
internal fun CustomerLoginScreen(original: SourceConfig? = null, busy: Boolean, onSubmit: (SourceConfig) -> Unit, onDismiss: (() -> Unit)? = null) {
    val id = remember(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    var username by rememberSaveable(id) { mutableStateOf(original?.username.orEmpty()) }
    var password by remember(id) { mutableStateOf(original?.password.orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = Color.Transparent)
    Surface(Modifier.tvRemoteInput().fillMaxSize().testTag("customer-login"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Image(painterResource(R.drawable.starlink_logo), stringResource(R.string.library_brand_name), Modifier.size(132.dp).testTag("customer-brand"))
                Text(stringResource(R.string.customer_login_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(username, { username = it; missing = false }, enabled = !busy,
                    placeholder = { Text(stringResource(R.string.customer_username)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Text),
                    shape = RoundedCornerShape(28.dp), colors = colors, modifier = Modifier.fillMaxWidth().testTag("customer-username"))
                OutlinedTextField(password, { password = it; missing = false }, enabled = !busy,
                    placeholder = { Text(stringResource(R.string.customer_password)) }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = { TextButton({ visible = !visible }) { Text(stringResource(if (visible) R.string.dialog_hide else R.string.dialog_show)) } },
                    shape = RoundedCornerShape(28.dp), colors = colors, modifier = Modifier.fillMaxWidth().testTag("customer-password"))
                if (missing) Text(stringResource(R.string.customer_missing_credentials), color = MaterialTheme.colorScheme.error)
                ActionButton(stringResource(if (busy) R.string.customer_signing_in else R.string.customer_sign_in), {
                    visible = false; keyboard?.hide()
                    missing = username.isBlank() || password.isBlank()
                    if (!missing) onSubmit(SourceConfig(id, "STARLINK IPTV", SourceKind.XTREAM, StarlinkConfig.XTREAM_BASE_URL, username.trim(), password))
                }, Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(28.dp)).testTag("customer-sign-in"), selected = true, enabled = !busy)
                CustomerSupportLink()
                onDismiss?.let { TextButton(it) { Text(stringResource(R.string.dialog_cancel)) } }
            }
        }
    }
}

@Composable
private fun CustomerSupportLink() {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val tv = androidx.compose.ui.platform.LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    if (tv) Text("(21) 98142-8144", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else TextButton({ runCatching { uri.openUri("https://wa.me/5521981428144") } }) { Text(stringResource(R.string.customer_help)) }
}

@Composable
internal fun CustomerErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.tvRemoteInput(),
        title = { Text(stringResource(R.string.customer_error_title)) }, text = { Text(message) },
        confirmButton = { ActionButton(stringResource(R.string.library_understood), onDismiss, selected = true) })
}
