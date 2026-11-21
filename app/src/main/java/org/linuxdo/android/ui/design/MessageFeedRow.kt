package org.linuxdo.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MessageFeedRow(
    name: String,
    preview: String,
    date: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    badge: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(IosTheme.colors.card).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            avatar()
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = IosTheme.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    color = IosTheme.colors.label)
                badge()
            }
            if (date.isNotBlank()) Text(date, style = IosTheme.type.caption, color = IosTheme.colors.secondaryLabel)
        }
        Spacer(Modifier.height(7.5.dp))
        Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis, color = IosTheme.colors.label,
            style = IosTheme.type.body.copy(fontSize = 14.6.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 20.8.sp, letterSpacing = 0.1.sp))
    }
}
