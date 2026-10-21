package com.agnish.linkdrop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Small pieces used by every screen, so they all size and color things the same way.

/** Minimum height of every button (Android's minimum tap target). */
val BUTTON_HEIGHT: Dp = 48.dp

/** The main action of a screen or card: filled with the accent color. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = BUTTON_HEIGHT)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Cancel, Decline, Disconnect: red outline, never filled. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val red = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = BUTTON_HEIGHT),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = red),
        border = BorderStroke(1.dp, red),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** What a [StatusLine] says about the state: its dot and text color. */
enum class Tone { Neutral, Active, Good, Warning, Error }

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Active -> MaterialTheme.colorScheme.primary
    Tone.Good -> LinkDropColors.Success
    Tone.Warning -> LinkDropColors.Warning
    Tone.Error -> MaterialTheme.colorScheme.error
}

/** One line of state with a colored dot in front (the dot is decoration; the text says it all). */
@Composable
fun StatusLine(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, color = color)
    }
}

/** A warning the user must not miss (the plaintext development build). */
@Composable
fun WarningBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, LinkDropColors.Warning),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, color = LinkDropColors.Warning, modifier = Modifier.padding(12.dp))
    }
}

/** A small outlined label, e.g. "Paired". Outlined, not filled, so its text keeps full contrast. */
@Composable
fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = Color.Transparent, border = BorderStroke(1.dp, color)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * A card on the page, a step lighter than the background. [raised]: one that waits for the user
 * (an offer, a share), a step lighter again. With [onClick] the whole card is the tap target.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = if (raised) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val colors = CardDefaults.cardColors(containerColor = color, disabledContainerColor = color)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, content = content)
    } else {
        Card(modifier = modifier, colors = colors, content = content)
    }
}

/** The heading of a section of the screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}
