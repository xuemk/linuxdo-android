package org.linuxdo.android.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.compactCategoryGroups
import org.linuxdo.android.ui.CategoriesUiState
import org.linuxdo.android.ui.design.*

@Composable
fun CategoryListScreen(state: CategoriesUiState, onRetry: () -> Unit, onOpen: (Category) -> Unit,
    scrollToTopRequest: Int = 0, trailing: @Composable () -> Unit = {}) {
    IosLargeTitleScaffold(title = "分类", compactTop = true, scrollToTopRequest = scrollToTopRequest, trailing = trailing) {
        if (state.loading) item("loading") { IosLoadingBox() }
        state.error?.let { message -> item("error") { ErrorNotice(message, onRetry) } }
        if (state.loaded && state.items.isEmpty()) item("empty") { EmptyNotice() }
        items(compactCategoryGroups(state.items), key = { it.first.id }) { (parent, levels) ->
            IosGroupCard(Modifier.padding(vertical = 8.dp)) {
                IosRow(onClick = { onOpen(parent) }) {
                    Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                        CategoryTag(parent, prominent = true)
                        Text("${parent.topicCount} 个话题", style = IosTheme.type.caption,
                            color = IosTheme.colors.secondaryLabel, modifier = Modifier.padding(top = 5.dp))
                    }
                    Row(Modifier.widthIn(max = 180.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        levels.forEach { category ->
                            val label = Regex("Lv\\s*\\d+", RegexOption.IGNORE_CASE).find(category.name)?.value?.replace(" ", "") ?: category.name
                            Text(label, style = IosTheme.type.footnote, color = IosTheme.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(IosTheme.colors.fieldBackground)
                                    .clickable { onOpen(category) }.padding(horizontal = 9.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
