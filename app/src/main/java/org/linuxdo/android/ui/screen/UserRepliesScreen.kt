package org.linuxdo.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.linuxdo.android.ui.format.RelativeTime
import org.linuxdo.android.data.UserRepliesUiState
import org.linuxdo.android.data.UserReplyItem
import org.linuxdo.android.data.AvatarCache
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.data.isAnimatedAvatarPath
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.linuxdo.android.data.UserSummaryData
import org.linuxdo.android.data.SummaryTopicItem
import org.linuxdo.android.data.SummaryReplyItem
import org.linuxdo.android.data.SummaryUserItem
import org.linuxdo.android.data.SummaryCategoryItem
import org.linuxdo.android.data.SummaryBadgeItem
import org.linuxdo.android.data.SummaryLinkItem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.launch
import org.linuxdo.android.App
import org.linuxdo.android.data.UserCardData
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosLoadingBox
import org.linuxdo.android.ui.design.IosLargeTitleScaffold
import org.linuxdo.android.ui.design.IosTheme

@Composable
fun UserRepliesScreen(
    title: String,
    state: UserRepliesUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenUser: ((String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= listState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(nearEnd, state.hasMore, state.loading) {
        if (nearEnd && state.hasMore && !state.loading && state.items.isNotEmpty()) {
            onLoadMore()
        }
    }

    IosLargeTitleScaffold(
        title = title,
        showLargeTitle = false,
        listState = listState,
        onBack = onBack,
        backLabel = "返回",
    ) {
        when {
            state.initialLoading && state.items.isEmpty() -> item("loading") {
                IosLoadingBox()
            }
            state.error != null && state.items.isEmpty() -> item("error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = state.error,
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                    )
                    Text(
                        text = "点击重试",
                        style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = IosTheme.colors.accent,
                        modifier = Modifier.clickable(onClick = onRefresh),
                    )
                }
            }
            state.items.isEmpty() -> item("empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无回复记录",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
            }
            else -> {
                items(state.items, key = { it.id }) { item ->
                    UserReplyCard(
                        item = item,
                        onClick = { onOpenTopic(item.topicId, item.title, item.postNumber) },
                        onOpenUser = onOpenUser,
                    )
                }
                if (state.loading) {
                    item("loading-more") {
                        IosLoadingBox()
                    }
                } else if (!state.hasMore && state.items.isNotEmpty()) {
                    item("footer-no-more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "已加载全部回复",
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.tertiaryLabel,
                            )
                        }
                    }
                item("bottom-spacer") { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UserReplyCard(
    item: UserReplyItem,
    onClick: () -> Unit,
    onOpenUser: ((String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 顶部行：头像 + 用户名 + 分类 + 相对时间
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.avatarUrl,
                contentDescription = item.username,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(IosTheme.colors.fieldBackground)
                    .clickable(enabled = onOpenUser != null) { onOpenUser?.invoke(item.username) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.username,
                style = IosTheme.type.footnote.copy(fontWeight = FontWeight.Medium),
                color = IosTheme.colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            item.categoryName?.let { catName ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.categoryColor?.let { hex ->
                        val dotColor = runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrDefault(IosTheme.colors.accent)
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = catName,
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            item.createdAt?.let { timeStr ->
                Text(
                    text = RelativeTime.format(timeStr),
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.tertiaryLabel,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 主题标题
        Text(
            text = item.title,
            style = IosTheme.type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            color = IosTheme.colors.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // 我的评论内容（一行多了省略号，参照 PC 样式）
        val cleaned = cleanExcerpt(item.commentExcerpt)
        if (!cleaned.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = cleaned,
                style = IosTheme.type.footnote.copy(fontSize = 14.sp),
                color = IosTheme.colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    IosDivider(startIndent = 16.dp)
}

enum class UserProfileTab(val label: String, val filter: String?) {
    Summary("总结", null),
    Activity("动态", null),
    Topics("话题", "4"),
    Replies("回复", "5"),
    Likes("赞", "1"),
    Reactions("回应", null),
    Boost("Boost", null),
    Votes("投票", null),
    Solutions("已解决", null);
}

@Composable
fun UserProfileScreen(
    username: String,
    initialAvatarUrl: String? = null,
    initialStaticAvatarUrl: String? = null,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenUser: ((String) -> Unit)? = null,
    onChat: ((String) -> Unit)? = null,
    onComposePrivateMessage: ((String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val repo = (context.applicationContext as? App)?.container?.topicRepository
    var userCard by remember(username) { mutableStateOf<UserCardData?>(repo?.getCachedUserCard(username)) }
    var selectedTab by rememberSaveable(username) { mutableIntStateOf(0) }
    val tabEntries = remember { UserProfileTab.entries }

    var summaryData by remember(username) { mutableStateOf<UserSummaryData?>(null) }
    var summaryLoading by remember(username) { mutableStateOf(false) }
    var summaryError by remember(username) { mutableStateOf<String?>(null) }

    var beforeBoostId by remember(username, selectedTab) { mutableStateOf<Long?>(null) }
    var beforeReactionId by remember(username, selectedTab) { mutableStateOf<Long?>(null) }
    var retryTrigger by remember(username, selectedTab) { mutableIntStateOf(0) }
    var loadMoreJob by remember(username, selectedTab) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var items by remember(username, selectedTab) { mutableStateOf<List<UserReplyItem>>(emptyList()) }
    var loading by remember(username, selectedTab) { mutableStateOf(false) }
    var error by remember(username, selectedTab) { mutableStateOf<String?>(null) }
    var cursor by remember(username, selectedTab) { mutableIntStateOf(0) }
    var hasMore by remember(username, selectedTab) { mutableStateOf(true) }

    LaunchedEffect(username) {
        if (repo == null) return@LaunchedEffect
        try {
            val card = repo.fetchUserCard(username)
            userCard = card
            if (card.animatedAvatar.isNullOrBlank() || !isAnimatedAvatarPath(card.animatedAvatar)) {
                userCard = repo.enrichUserCardAvatar(username, card)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // 保留 card.json 的首屏和现有错误兜底；完整资料补全失败不影响资料页可用性。
        }
    }

    LaunchedEffect(username, selectedTab, retryTrigger) {
        val currentTab = tabEntries[selectedTab]
        if (repo == null) return@LaunchedEffect
        loadMoreJob?.cancel()
        if (currentTab == UserProfileTab.Summary) {
            if (summaryData == null || retryTrigger > 0) {
                summaryLoading = true
                summaryError = null
                try {
                    summaryData = repo.fetchUserSummary(username)
                } catch (e: Exception) {
                    summaryError = e.message ?: "加载总结失败"
                } finally {
                    summaryLoading = false
                }
            }
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            when (currentTab) {
                UserProfileTab.Boost -> {
                    val (newItems, nextBeforeId) = repo.fetchUserBoosts(username, null)
                    items = newItems
                    beforeBoostId = nextBeforeId
                    hasMore = nextBeforeId != null
                }
                UserProfileTab.Votes -> {
                    val (newItems, more) = repo.fetchUserVotedTopics(username, 0)
                    items = newItems
                    hasMore = more
                    cursor = 1
                }
                UserProfileTab.Solutions -> {
                    val (newItems, more) = repo.fetchUserSolvedPosts(username, 0)
                    items = newItems
                    hasMore = more
                    cursor = 1
                }
                UserProfileTab.Reactions -> {
                    val (newItems, nextBeforeId) = repo.fetchUserReactions(username, null)
                    items = newItems
                    beforeReactionId = nextBeforeId
                    hasMore = nextBeforeId != null
                }
                else -> {
                    val (newItems, more) = repo.fetchUserActionItems(username, currentTab.filter, 0)
                    items = newItems
                    hasMore = more
                    cursor = 1
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "加载数据失败"
        } finally {
            loading = false
        }
    }

    fun loadMore() {
        val currentTab = tabEntries[selectedTab]
        if (loading || !hasMore || repo == null || currentTab == UserProfileTab.Summary) return
        loadMoreJob?.cancel()
        loading = true
        loadMoreJob = coroutineScope.launch {
            try {
                when (currentTab) {
                    UserProfileTab.Boost -> {
                        val (newItems, nextBeforeId) = repo.fetchUserBoosts(username, beforeBoostId)
                        items = (items + newItems).distinctBy { it.id }
                        beforeBoostId = nextBeforeId
                        hasMore = nextBeforeId != null
                    }
                    UserProfileTab.Votes -> {
                        val (newItems, more) = repo.fetchUserVotedTopics(username, cursor)
                        items = (items + newItems).distinctBy { it.topicId }
                        hasMore = more
                        cursor += 1
                    }
                    UserProfileTab.Solutions -> {
                        val (newItems, more) = repo.fetchUserSolvedPosts(username, cursor * 30)
                        items = (items + newItems).distinctBy { it.id }
                        hasMore = more
                        cursor += 1
                    }
                    UserProfileTab.Reactions -> {
                        val (newItems, nextBeforeId) = repo.fetchUserReactions(username, beforeReactionId)
                        items = (items + newItems).distinctBy { it.id }
                        beforeReactionId = nextBeforeId
                        hasMore = nextBeforeId != null
                    }
                    else -> {
                        val (newItems, more) = repo.fetchUserActionItems(username, currentTab.filter, cursor)
                        items = (items + newItems).distinctBy { it.id }
                        hasMore = more
                        cursor += 1
                    }
                }
            } catch (e: Exception) {
                // 不清除已加载的数据
            } finally {
                loading = false
            }
        }
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && hasMore && !loading) {
            loadMore()
        }
    }

    val isDark = IosTheme.colors.isDark

    Column(
        Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF0F0F10) else Color(0xFFF7F8FA))
    ) {
        // 顶部黑色星空微光头部卡片 (Header)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141416))
        ) {
            val bgUrl = userCard?.backgroundUrl
            if (bgUrl != null) {
                val bgRequest = org.linuxdo.android.ui.design.rememberAnimatedImageRequest(bgUrl)
                AsyncImage(
                    model = bgRequest ?: bgUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF141416).copy(alpha = 0.45f),
                                    Color(0xFF141416).copy(alpha = 0.95f),
                                ),
                            ),
                        ),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onComposePrivateMessage != null) {
                        IconButton(
                            onClick = { onComposePrivateMessage(username) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Email, contentDescription = "私信", tint = Color.White)
                        }
                    }
                }

                val card = userCard
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(80.dp)) {
                        val animCandidate = card?.animatedAvatar ?: AvatarCache.get(username) ?: initialAvatarUrl?.takeIf(::isAnimatedAvatarPath)
                        val animUrl = avatarUrlOf(
                            template = card?.avatarTemplate,
                            animatedAvatar = animCandidate,
                            size = 120,
                            username = username,
                        )
                        val staticUrl = avatarUrlOf(template = card?.avatarTemplate, size = 120) ?: initialStaticAvatarUrl
                        val avatar = if (isAnimatedAvatarPath(animCandidate)) {
                            animUrl
                        } else {
                            animUrl ?: initialAvatarUrl ?: staticUrl
                        }
                        val avatarShape = org.linuxdo.android.ui.design.avatarShape(username, avatar, 12.dp)
                        org.linuxdo.android.ui.design.UserAvatar(
                            avatarUrl = avatar,
                            fallbackUrl = staticUrl,
                            username = username,
                            modifier = Modifier
                                .size(76.dp)
                                .border(2.5.dp, Color.White, avatarShape),
                            cornerRadius = 12.dp,
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(Color(0xFF262628)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🔥", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = card?.cleanDisplayName ?: username,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "@$username",
                            fontSize = 14.sp,
                            color = Color(0xFFA1A1AA),
                            maxLines = 1,
                        )
                        LevelBadge(card?.trustLevel ?: 2)
                    }
                }
            }

            // 个性签名药丸卡片
            val bio = card?.bioExcerpt?.let {
                org.jsoup.Jsoup.parse(it).text()
            }?.takeIf { it.isNotBlank() } ?: "这个人很懒，什么都没写"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF222226))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bio,
                    fontSize = 13.5.sp,
                    color = Color(0xFFD4D4D8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("›", fontSize = 16.sp, color = Color(0xFF71717A))
            }

            // 获赞、访问、话题、回复指标行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatCount(summaryData?.likesReceived ?: card?.likesReceived ?: 0), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                    Text("获赞", color = Color(0xFFA1A1AA), fontSize = 13.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val days = summaryData?.daysVisited
                    Text(if (days != null) formatCount(days) else "-", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                    Text("访问", color = Color(0xFFA1A1AA), fontSize = 13.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatCount(summaryData?.topicCount ?: card?.topicCount ?: 0), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                    Text("话题", color = Color(0xFFA1A1AA), fontSize = 13.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatCount(summaryData?.postCount ?: card?.postCount ?: 0), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                    Text("回复", color = Color(0xFFA1A1AA), fontSize = 13.5.sp)
                }
            }

            // 最后活跃状态小标
            Box(
                modifier = Modifier
                    .padding(start = 18.dp, top = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF26262A))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("📍", fontSize = 10.sp)
                    Text("活跃用户", fontSize = 11.5.sp, color = Color(0xFFA1A1AA))
                }
            }
        }
    }

        // ── 下半部分: 横向滑动 TabRow (总结、动态、话题、回复、赞、回应、Boost、投票、已解决) ──
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = if (isDark) Color(0xFF18181A) else Color.White,
            contentColor = IosTheme.colors.accent,
            edgePadding = 12.dp,
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = IosTheme.colors.accent,
                        height = 2.5.dp,
                    )
                }
            },
            divider = { IosDivider() },
        ) {
            tabEntries.forEachIndexed { index, tab ->
                val selected = selectedTab == index
                Tab(
                    selected = selected,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = tab.label,
                            fontSize = 14.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) IosTheme.colors.accent else IosTheme.colors.secondaryLabel,
                        )
                    },
                )
            }
        }

        // 列表区
        val currentTab = tabEntries[selectedTab]
        if (currentTab == UserProfileTab.Summary) {
            when {
                summaryLoading && summaryData == null -> {
                    IosLoadingBox(Modifier.fillMaxSize())
                }
                summaryError != null && summaryData == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(summaryError ?: "加载失败", color = IosTheme.colors.secondaryLabel)
                            TextButton(onClick = { retryTrigger++ }) {
                                Text("重试", color = IosTheme.colors.accent)
                            }
                        }
                    }
                }
                summaryData != null -> {
                    UserSummaryView(
                        summary = summaryData!!,
                        userCard = userCard,
                        username = username,
                        onOpenTopic = onOpenTopic,
                        onOpenUser = { u -> onOpenUser?.invoke(u) },
                    )
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无总结记录", color = IosTheme.colors.secondaryLabel)
                    }
                }
            }
        } else {
            if (loading && items.isEmpty()) {
                IosLoadingBox(Modifier.fillMaxSize())
            } else if (error != null && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error ?: "加载失败", color = IosTheme.colors.secondaryLabel)
                }
            } else if (!loading && items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("暂无${currentTab.label}记录", color = IosTheme.colors.secondaryLabel, style = IosTheme.type.body)
                        if (currentTab == UserProfileTab.Votes) {
                            Text(
                                text = "注：此处记录仅统计功能建议等板块的话题投票支持（Topic Voting），帖子内参与的问卷调查（Poll）不归入此项。",
                                color = IosTheme.colors.tertiaryLabel,
                                style = IosTheme.type.footnote,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        UserReplyCard(
                            item = item,
                            onClick = { onOpenTopic(item.topicId, item.title, item.postNumber) },
                            onOpenUser = onOpenUser,
                        )
                    }
                    if (loading && items.isNotEmpty()) {
                        item("loading-more") { IosLoadingBox() }
                    }
                }
            }
        }
    }
}


private fun formatTimeRead(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分钟"
        else -> "${seconds}秒"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserSummaryView(
    summary: UserSummaryData,
    userCard: UserCardData?,
    username: String,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val context = LocalContext.current
    val isDark = IosTheme.colors.isDark
    val cardBg = if (isDark) Color(0xFF18181A) else Color.White
    val cardBorder = if (isDark) Color(0xFF262628) else Color(0xFFE5E7EB)
    val textPrimary = if (isDark) Color.White else Color(0xFF111827)
    val textSecondary = if (isDark) Color(0xFFA1A1AA) else Color(0xFF6B7280)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. 核心指标统计 (8 宫格)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "📊 数据统计",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary,
            )

            val stats = listOf(
                Triple("访问天数", formatCount(summary.daysVisited), "📅"),
                Triple("阅读时长", formatTimeRead(summary.timeRead), "⏱️"),
                Triple("已读帖子", formatCount(summary.postsReadCount), "📖"),
                Triple("浏览话题", formatCount(summary.topicsEntered), "👀"),
                Triple("建立话题", formatCount(summary.topicCount), "📝"),
                Triple("发表回复", formatCount(summary.postCount), "💬"),
                Triple("获得点赞", formatCount(summary.likesReceived), "❤️"),
                Triple("送出点赞", formatCount(summary.likesGiven), "👍"),
            )

            for (row in stats.chunked(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for ((label, value, icon) in row) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0xFF222226) else Color(0xFFF9FAFB))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(icon, fontSize = 16.sp)
                            Column {
                                Text(label, fontSize = 11.5.sp, color = textSecondary)
                                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }
                    }
                }
            }
        }

        // 2. 热门话题
        if (summary.topTopics.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "🔥 热门话题",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )
                summary.topTopics.take(5).forEachIndexed { idx, topic ->
                    if (idx > 0) IosDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTopic(topic.id, topic.title, null) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = topic.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (topic.likeCount > 0) {
                                    Text("❤️ ${topic.likeCount}", fontSize = 11.5.sp, color = Color(0xFFFF453A))
                                }
                                topic.createdAt?.let {
                                    Text(RelativeTime.format(it), fontSize = 11.5.sp, color = textSecondary)
                                }
                            }
                        }
                        Text("›", fontSize = 18.sp, color = textSecondary, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        // 3. 热门回复
        if (summary.topReplies.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "💬 热门回复",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )
                summary.topReplies.take(5).forEachIndexed { idx, reply ->
                    if (idx > 0) IosDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTopic(reply.topicId, reply.title, reply.postNumber) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = reply.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("#${reply.postNumber} 楼", fontSize = 11.5.sp, color = IosTheme.colors.accent)
                                if (reply.likeCount > 0) {
                                    Text("❤️ ${reply.likeCount}", fontSize = 11.5.sp, color = Color(0xFFFF453A))
                                }
                                reply.createdAt?.let {
                                    Text(RelativeTime.format(it), fontSize = 11.5.sp, color = textSecondary)
                                }
                            }
                        }
                        Text("›", fontSize = 18.sp, color = textSecondary, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        // 4. 热门链接
        if (summary.topLinks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "🔗 热门链接",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )
                summary.topLinks.take(5).forEachIndexed { idx, link ->
                    if (idx > 0) IosDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (link.topicId != null) {
                                    onOpenTopic(link.topicId, link.topicTitle ?: link.title ?: "", link.postNumber)
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(link.url)
                                            )
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = link.title?.takeIf { it.isNotBlank() } ?: link.url,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("${link.clicks} 次点击", fontSize = 11.5.sp, color = IosTheme.colors.accent)
                                link.topicTitle?.let {
                                    Text("来自: $it", fontSize = 11.5.sp, color = textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Text("›", fontSize = 18.sp, color = textSecondary, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        // 5. 用户互动
        val hasInteractions = summary.mostRepliedToUsers.isNotEmpty() ||
                summary.mostLikedByUsers.isNotEmpty() ||
                summary.mostLikedUsers.isNotEmpty()
        if (hasInteractions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "🤝 用户互动",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )

                if (summary.mostRepliedToUsers.isNotEmpty()) {
                    InteractionUserSection(
                        title = "最常回复的人",
                        users = summary.mostRepliedToUsers.take(6),
                        onOpenUser = onOpenUser,
                        isDark = isDark,
                        textSecondary = textSecondary,
                    )
                }

                if (summary.mostLikedByUsers.isNotEmpty()) {
                    InteractionUserSection(
                        title = "被谁点赞最多",
                        users = summary.mostLikedByUsers.take(6),
                        onOpenUser = onOpenUser,
                        isDark = isDark,
                        textSecondary = textSecondary,
                    )
                }

                if (summary.mostLikedUsers.isNotEmpty()) {
                    InteractionUserSection(
                        title = "给谁点赞最多",
                        users = summary.mostLikedUsers.take(6),
                        onOpenUser = onOpenUser,
                        isDark = isDark,
                        textSecondary = textSecondary,
                    )
                }
            }
        }

        // 6. 活跃类别
        if (summary.topCategories.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "📁 活跃类别",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )
                summary.topCategories.forEachIndexed { idx, cat ->
                    if (idx > 0) IosDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val catColor = cat.color?.let {
                                runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull()
                            } ?: IosTheme.colors.accent
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(catColor)
                            )
                            Text(
                                text = cat.name,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                            )
                        }
                        Text(
                            text = "${cat.topicCount} 话题 · ${cat.postCount} 回复",
                            fontSize = 12.5.sp,
                            color = textSecondary,
                        )
                    }
                }
            }
        }

        // 7. 徽章展示
        if (summary.badges.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "🏅 获得徽章 (${summary.badges.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.badges.forEach { badge ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDark) Color(0xFF222226) else Color(0xFFF3F4F6))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("🎖️", fontSize = 13.sp)
                            Text(
                                text = badge.name,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InteractionUserSection(
    title: String,
    users: List<SummaryUserItem>,
    onOpenUser: (String) -> Unit,
    isDark: Boolean,
    textSecondary: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.5.sp, color = textSecondary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            users.forEach { user ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isDark) Color(0xFF222226) else Color(0xFFF3F4F6))
                        .clickable { onOpenUser(user.username) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val avatarUrl = user.avatarTemplate?.let { avatarUrlOf(it, 48) }
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = user.username,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Gray.copy(alpha = 0.3f)),
                    )
                    Text(
                        text = "@${user.username}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color.White else Color(0xFF111827),
                    )
                    if (user.count > 0) {
                        Text(
                            text = "${user.count}",
                            fontSize = 11.sp,
                            color = IosTheme.colors.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
