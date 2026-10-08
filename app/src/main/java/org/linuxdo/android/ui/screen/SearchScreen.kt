package org.linuxdo.android.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jsoup.Jsoup
import org.linuxdo.android.data.Topic
import org.linuxdo.android.data.SearchOrder
import org.linuxdo.android.ui.SearchUiState
import org.linuxdo.android.ui.design.*

@Composable
fun SearchScreen(state: SearchUiState, onQuery: (String) -> Unit, onLoadMore: () -> Unit,
                 onOpenTopic: (Topic) -> Unit, onOrder: (SearchOrder) -> Unit, active: Boolean = true,
                 scrollToTopRequest: Int = 0, trailing: @Composable () -> Unit = {}) {
    val list = rememberLazyListState()
    ScrollToTopOnRequest(list, scrollToTopRequest)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val fieldColor by animateColorAsState(if (focused) IosTheme.colors.card else IosTheme.colors.fieldBackground, label = "search-focus")
    val nearEnd by remember { derivedStateOf {
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(active, nearEnd, state.results) {
        if (active && nearEnd && state.results.items.isNotEmpty() && state.results.hasMore &&
            !state.results.loadingMore && !state.results.initialLoading && state.results.error == null) onLoadMore()
    }
    var previousQuery by rememberSaveable { mutableStateOf(state.query) }
    var previousOrder by rememberSaveable { mutableStateOf(state.order) }
    LaunchedEffect(state.query, state.order) {
        if (previousQuery != state.query || previousOrder != state.order) {
            previousQuery = state.query
            previousOrder = state.order
            list.scrollToItem(0)
        }
    }
    Column(Modifier.fillMaxSize().background(IosTheme.colors.groupedBackground)) {
        IosNavBar(title = "搜索", trailing = trailing)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = state.query, onValueChange = onQuery, singleLine = true,
                textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                cursorBrush = SolidColor(IosTheme.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
                    .background(fieldColor, RoundedCornerShape(10.dp)).padding(10.dp),
                decorationBox = { field -> Box {
                    if (state.query.isEmpty()) Text("搜索话题", color = IosTheme.colors.secondaryLabel)
                    field()
                } })
            Text("取消", color = IosTheme.colors.accent, modifier = Modifier.clickable {
                onQuery(""); focusManager.clearFocus()
            }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("排序依据", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel,
                modifier = Modifier.weight(1f))
            Text("${state.order.label} ⌄", style = IosTheme.type.subheadline, color = IosTheme.colors.accent,
                modifier = Modifier.background(IosTheme.colors.fieldBackground, RoundedCornerShape(8.dp))
                    .clickable { focusManager.clearFocus(); showSort = true }.padding(horizontal = 12.dp, vertical = 9.dp))
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            if (state.query.isBlank()) item("prompt") {
                Text("输入关键词查找话题", color = IosTheme.colors.secondaryLabel, modifier = Modifier.padding(24.dp))
            }
            if (state.results.initialLoading) item("loading") { IosLoadingBox() }
            state.results.error?.let { message -> item("error") { ErrorNotice(message) { onQuery(state.query) } } }
            if (state.query.isNotBlank() && !state.results.initialLoading && state.results.error == null && state.results.items.isEmpty()) {
                item("empty") { EmptyNotice() }
            }
            items(state.results.items, key = { it.topic.id }) { item ->
                Column(Modifier.fillMaxWidth().background(IosTheme.colors.card)) {
                    TopicRow(item) { focusManager.clearFocus(); onOpenTopic(item.topic) }
                    state.excerpts[item.topic.id]?.let { html ->
                        SearchExcerpt(html, state.query)
                    }
                    IosDivider()
                }
            }
            if (state.results.loadingMore) item("more") { IosLoadingBox() }
        }
    }
    if (showSort) Dialog(onDismissRequest = { showSort = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            IosGroupCard {
                Text("搜索排序", style = IosTheme.type.title3, modifier = Modifier.padding(16.dp))
                SearchOrder.entries.forEach { order ->
                    IosDivider()
                    IosRow(onClick = { onOrder(order); showSort = false }) {
                        Text(order.label, modifier = Modifier.weight(1f))
                        if (state.order == order) Text("✓", color = IosTheme.colors.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchExcerpt(html: String, query: String) {
    val accent = IosTheme.colors.accent
    val text = remember(html, query, accent) {
        val plain = Jsoup.parseBodyFragment(html).text()
        buildAnnotatedString {
            append(plain)
            val needle = query.trim()
            if (needle.isNotEmpty()) {
                var start = plain.indexOf(needle, ignoreCase = true)
                while (start >= 0) {
                    addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), start, start + needle.length)
                    start = plain.indexOf(needle, start + needle.length, ignoreCase = true)
                }
            }
        }
    }
    Text(text, style = IosTheme.type.subheadline, color = IosTheme.colors.secondaryLabel,
        maxLines = 3, modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp))
}
