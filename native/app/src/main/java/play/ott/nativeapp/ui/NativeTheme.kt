package play.ott.nativeapp.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import androidx.tv.material3.Text as TvText

internal val OttColors = darkColorScheme(
    primary = Color(0xFFE50914),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF70090E),
    onPrimaryContainer = Color(0xFFFFDADC),
    secondary = Color(0xFFFF7D85),
    onSecondary = Color(0xFF400006),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFF0F3EC),
    surface = Color(0xFF141418),
    onSurface = Color(0xFFF0F3EC),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFB5C3C5),
    outline = Color(0xFF4A5B5F),
    error = Color(0xFFFFB4A9),
)

@Composable
internal fun OttTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OttColors, content = content)
}

/** Native focus remains in Compose; a visible outline also works with physical remotes. */
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    if (isTv) {
        TvButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = if (compact) 44.dp else 50.dp),
            colors = TvButtonDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            TvText(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        modifier = modifier
            .heightIn(min = if (compact) 44.dp else 50.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                BorderStroke(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent),
                shape,
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
