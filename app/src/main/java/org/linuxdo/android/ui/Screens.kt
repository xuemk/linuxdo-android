package org.linuxdo.android.ui

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import org.linuxdo.android.net.gecko.CfDiagnosticMode
import org.linuxdo.android.net.gecko.CfDiagnosticSession
import org.linuxdo.android.net.gecko.CfDiagnosticStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import org.linuxdo.android.data.UserBadgeItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import org.linuxdo.android.Config
import org.linuxdo.android.BuildConfig
import org.linuxdo.android.service.NotificationHelper
import org.linuxdo.android.service.ApkUpdateInstaller
import org.linuxdo.android.data.DiscourseTagInfo
import org.linuxdo.android.data.SessionStore
import org.linuxdo.android.data.AutoRefreshInterval
import org.linuxdo.android.data.NotificationPollInterval
import org.linuxdo.android.data.NotificationScope
import org.linuxdo.android.data.AttachedMedia
import org.linuxdo.android.net.NetworkPath
import org.linuxdo.android.net.DohServer
import org.linuxdo.android.net.DohPresets
import org.linuxdo.android.ui.design.IosDivider
import org.linuxdo.android.ui.design.IosGroupCard
import org.linuxdo.android.ui.design.avatarShape
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosLargeTitleScaffold
import org.linuxdo.android.ui.design.IosMetrics
import org.linuxdo.android.ui.design.IosRow
import org.linuxdo.android.ui.design.IosTheme
import org.linuxdo.android.ui.design.IosLoadingBox
import org.linuxdo.android.ui.design.IosSwitch
import org.linuxdo.android.ui.design.UserAvatar
import org.linuxdo.android.ui.nav.SettingsPage
import org.linuxdo.android.data.ProfileSection
import org.linuxdo.android.data.NotificationFilter
import org.linuxdo.android.ui.AccountUiState
import org.linuxdo.android.ui.screen.AccountSwitchBottomSheet
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import org.linuxdo.android.ui.screen.ErrorNotice
import org.linuxdo.android.ui.screen.EmptyNotice
import android.widget.Toast
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import java.util.Locale
import java.io.File
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.EditText
import android.text.style.ReplacementSpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.Editable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path as AndroidPath
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.Gravity
import android.text.InputType
import coil.request.ImageRequest
import coil.imageLoader
import coil.size.Precision
import coil.size.Scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import org.linuxdo.android.data.Category
import org.linuxdo.android.data.FloatingAction
import org.linuxdo.android.data.DraftItem
import org.linuxdo.android.data.avatarUrlOf
import org.linuxdo.android.data.AvatarCache
import org.linuxdo.android.data.isAnimatedAvatarPath
import org.linuxdo.android.data.compactCategoryGroups
import org.linuxdo.android.data.fixedTagsForCategory
import org.linuxdo.android.data.flattenCategories
import org.linuxdo.android.ui.design.IosNavBar
import org.linuxdo.android.ui.format.RelativeTime
import org.linuxdo.android.ui.screen.ComposerAttachmentIcon
import org.linuxdo.android.ui.screen.ComposerEmojiIcon
import org.linuxdo.android.ui.screen.ComposerImageIcon
import org.linuxdo.android.ui.screen.ComposerBoldIcon
import org.linuxdo.android.ui.screen.ComposerTagIcon
import org.linuxdo.android.ui.screen.ComposerLockIcon
import org.linuxdo.android.ui.screen.PostAction
import org.linuxdo.android.ui.screen.PostActionIcon
import org.linuxdo.android.ui.screen.QuickEmojiPicker
import org.linuxdo.android.ui.screen.LevelBadge
import org.linuxdo.android.ui.screen.categoryBaseNameOf
import org.linuxdo.android.ui.screen.categoryLevelOf
import org.linuxdo.android.ui.screen.levelPillColors
import org.linuxdo.android.ui.screen.parseCategoryColor

/** 列表攒够这么多条才允许继续自动翻页;不足则改成手动按钮。 */
private const val AUTO_LOAD_THRESHOLD = 20

/**
 * 角标实测下发后,桌面保持人造数字的时长。
 *
 * 未读每 10 秒轮询一次,不冻结的话实测数字按不到 Home 键就被真实未读覆盖了。
 */
/**
 * 精美蓝盾认证徽章 (科技蓝渐变盾牌 + 纯白对勾 + 外发光晕)
 */
@Composable
fun ProfileVerifiedBadge(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(17.dp)) {
        val w = size.width
        val h = size.height

        // 外层柔和微光辉光
        drawCircle(
            color = Color(0x3538BDF8),
            radius = w * 0.55f,
            center = Offset(w * 0.5f, h * 0.5f)
        )

        // 盾牌几何路径
        val shieldPath = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            cubicTo(w * 0.85f, h * 0.08f, w * 0.95f, h * 0.22f, w * 0.92f, h * 0.52f)
            cubicTo(w * 0.88f, h * 0.78f, w * 0.55f, h * 0.96f, w * 0.5f, h * 0.98f)
            cubicTo(w * 0.45f, h * 0.96f, w * 0.12f, h * 0.78f, w * 0.08f, h * 0.52f)
            cubicTo(w * 0.05f, h * 0.22f, w * 0.15f, h * 0.08f, w * 0.5f, h * 0.08f)
            close()
        }

        // 渐变填充 (天蓝 -> 宝石蓝)
        drawPath(
            path = shieldPath,
            brush = Brush.verticalGradient(
                listOf(Color(0xFF38BDF8), Color(0xFF0284C7))
            )
        )

        // 细微高光内描边
        drawPath(
            path = shieldPath,
            color = Color(0x90BAE6FD),
            style = Stroke(width = 1.dp.toPx())
        )

        // 中间对勾 (Checkmark)
        val checkPath = Path().apply {
            moveTo(w * 0.32f, h * 0.52f)
            lineTo(w * 0.46f, h * 0.67f)
            lineTo(w * 0.70f, h * 0.38f)
        }
        drawPath(
            path = checkPath,
            color = Color.White,
            style = Stroke(
                width = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

data class ProfileBannerTheme(
    val bgColors: List<Color>,
    val borderColor: Color,
    val textColor: Color,
    val btnColors: List<Color>,
    val shieldPrimary: Color,
    val shieldAccent: Color,
)

/**
 * 信任等级横幅左侧突出的立体微光盾牌
 * (立体微发光光晕 + 盾牌双层描边 + 内部星芒 + 尊荣等级文字)
 * 严格联动用户等级设计规范：Lv.2 温润琥珀金(绝无绿)，Lv.3 华贵深金皇冠
 */
@Composable
fun ProfileShieldBadge(
    level: Int,
    primaryColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(width = 44.dp, height = 50.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. 底层大范围微光晕 (Glow)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = w * 0.68f
                )
            )

            // 2. 盾牌几何路径
            val shieldPath = Path().apply {
                moveTo(w * 0.5f, h * 0.06f)
                cubicTo(w * 0.88f, h * 0.06f, w * 0.98f, h * 0.20f, w * 0.94f, h * 0.55f)
                cubicTo(w * 0.90f, h * 0.80f, w * 0.56f, h * 0.97f, w * 0.5f, h * 0.99f)
                cubicTo(w * 0.44f, h * 0.97f, w * 0.10f, h * 0.80f, w * 0.06f, h * 0.55f)
                cubicTo(w * 0.02f, h * 0.20f, w * 0.12f, h * 0.06f, w * 0.5f, h * 0.06f)
                close()
            }

            // 3. 盾牌半透明立体填充
            drawPath(
                path = shieldPath,
                brush = Brush.verticalGradient(
                    listOf(
                        primaryColor.copy(alpha = 0.90f),
                        accentColor.copy(alpha = 0.95f)
                    )
                )
            )

            // 4. 金属发光高光描边
            drawPath(
                path = shieldPath,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.75f), accentColor)
                ),
                style = Stroke(width = 1.6.dp.toPx())
            )

            // 5. 顶部微小星芒 ✨ (3 颗闪烁点)
            val starPoints = listOf(
                Offset(w * 0.36f, h * 0.23f) to 1.1.dp.toPx(),
                Offset(w * 0.50f, h * 0.18f) to 1.6.dp.toPx(),
                Offset(w * 0.64f, h * 0.23f) to 1.1.dp.toPx(),
            )
            for ((center, radius) in starPoints) {
                drawCircle(color = Color.White.copy(alpha = 0.92f), radius = radius, center = center)
            }
        }

        // 中部尊荣等级文字 Lv.X
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                text = "Lv.$level",
                style = IosTheme.type.caption.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.2).sp
                ),
                color = Color.White,
            )
        }
    }
}

/**
 * 拟态微光玻璃球金刚位图标
 * (立体渐变球底 + 专属主题色内发光晕 + 弧形环境高光 + 细微立体描边)
 */
@Composable
fun SphereIconButton(
    title: String,
    iconGlyph: String,
    glowColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = IosTheme.colors.isDark
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // 1. 底层立体玻璃球底座
                val sphereBrush = Brush.radialGradient(
                    colors = if (isDark) {
                        listOf(Color(0xFF2C3342), Color(0xFF141720))
                    } else {
                        listOf(Color(0xFFFFFFFF), Color(0xFFE2E8F0))
                    },
                    center = Offset(w * 0.4f, h * 0.32f),
                    radius = w * 0.62f
                )
                drawCircle(brush = sphereBrush)

                // 2. 内部专属主题色微光晕
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = if (isDark) 0.38f else 0.28f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = w * 0.46f
                    )
                )

                // 3. 顶部微弱弧形高光反射条
                val highlightPath = Path().apply {
                    moveTo(w * 0.26f, h * 0.18f)
                    quadraticTo(w * 0.5f, h * 0.12f, w * 0.74f, h * 0.18f)
                }
                drawPath(
                    path = highlightPath,
                    color = Color.White.copy(alpha = if (isDark) 0.35f else 0.70f),
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                )

                // 4. 外圆圈立体微边框
                drawCircle(
                    color = if (isDark) Color(0x33FFFFFF) else Color(0x1F000000),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // 中央图标
            Text(
                text = iconGlyph,
                fontSize = 20.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = IosTheme.type.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal),
            color = IosTheme.colors.label,
            maxLines = 1
        )
    }
}

private const val BADGE_TEST_HOLD_MILLIS = 3 * 60 * 1000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    username: String?,
    states: Map<ProfileSection, ProfileUiState>,
    onLoad: (ProfileSection, Boolean, Boolean) -> Unit,
    onOpenTopic: (Long, String, Int?) -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit = {},
    active: Boolean,
    notificationOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
    onReadNotification: (Long) -> Unit = {},
    notificationFilter: NotificationFilter = NotificationFilter.All,
    onNotificationFilter: (NotificationFilter) -> Unit = {},
    unreadFilters: Set<NotificationFilter> = emptySet(),
    onOpenUserTopics: ((username: String, title: String, isBookmark: Boolean) -> Unit)? = null,
    draftCount: String = "0",
    onOpenDrafts: (() -> Unit)? = null,
    accountState: AccountUiState = AccountUiState(),
    onRefreshAccounts: () -> Unit = {},
    onSwitchAccount: (Long) -> Unit = {},
    onRemoveAccount: (Long) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onOpenConnect: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenFollowing: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenBlacklist: () -> Unit = {},
    onOpenUser: (username: String, primaryAvatar: String?, staticAvatar: String?) -> Unit = { _, _, _ -> },
    onComposePrivateMessage: (String) -> Unit = {},
    onOpenUserReplies: ((String) -> Unit)? = null,
    onOpenUserActions: ((username: String, title: String, filter: String) -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    onMarkAllNotificationsRead: (() -> Unit)? = null,
    userBadges: List<UserBadgeItem>? = null,
    isUserBadgesLoading: Boolean = false,
    onRefreshUserBadges: (() -> Unit)? = null,
    cdkScore: Int? = null,
    onLoadCdkScore: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    var selectedBadge by remember { mutableStateOf<UserBadgeItem?>(null) }
    var section by rememberSaveable { mutableStateOf(if (notificationOnly) ProfileSection.Notifications else ProfileSection.Summary) }
    var previousSection by rememberSaveable { mutableStateOf(section) }
    val list = rememberLazyListState()
    val state = states[section] ?: ProfileUiState()
    val summaryState = states[ProfileSection.Summary] ?: ProfileUiState()
    val summaryFields = summaryState.content.fields

    val visibleEntries = state.content.entries
    val nearEnd by remember { derivedStateOf {
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(section, active) {
        if (active) {
            onLoad(section, section == ProfileSection.Notifications, false)
            if (section != ProfileSection.Summary && summaryFields.isEmpty()) {
                onLoad(ProfileSection.Summary, false, false)
            }
            if (!notificationOnly && userBadges == null && !isUserBadgesLoading) {
                onRefreshUserBadges?.invoke()
            }
            if (!notificationOnly) onLoadCdkScore()
        }
        if (previousSection != section) {
            previousSection = section
            list.scrollToItem(0)
        }
    }
    LaunchedEffect(active, nearEnd, state, notificationFilter) {
        if (active && nearEnd && state.loaded && !state.loading && state.error == null &&
            state.content.hasMore && visibleEntries.size >= AUTO_LOAD_THRESHOLD) {
            onLoad(section, false, true)
        }
    }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showMarkAllReadConfirmDialog by remember { mutableStateOf(false) }
    var showAccountSwitchSheet by remember { mutableStateOf(false) }
    val activeAccount = accountState.accounts.firstOrNull { it.userId == accountState.activeUserId }

    val daysVisited = remember(summaryFields) { findMetric(summaryFields, "访问天数") }
    val timeRead = remember(summaryFields) { findMetric(summaryFields, "阅读时长") }
    val topicsEntered = remember(summaryFields) { findMetric(summaryFields, "浏览话题") }

    val postsRead = remember(summaryFields) { findMetric(summaryFields, "已读帖子") }
    val likesReceived = remember(summaryFields) { findMetric(summaryFields, "收到赞") }
    val likesGiven = remember(summaryFields) { findMetric(summaryFields, "送出赞") }
    val postCount = remember(summaryFields) { findMetric(summaryFields, "回复数量") }
    val topicCount = remember(summaryFields) { findMetric(summaryFields, "创建话题", findMetric(summaryFields, "发表话题")) }
    val context = LocalContext.current
    val bookmarkCount = remember(summaryFields) { findMetric(summaryFields, "我的收藏") }

    IosLargeTitleScaffold(
        title = if (notificationOnly) "通知" else "我的",
        listState = list,
        onBack = if (notificationOnly) null else onBack,
        showLargeTitle = false,
        backLabel = if (notificationOnly) "" else "返回",
        scrollToTopRequest = scrollToTopRequest,
        trailing = {
            if (!notificationOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailing()
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onSettings),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileSettingsGearIcon(tint = IosTheme.colors.secondaryLabel)
                    }
                }
            } else {
                if (onMarkAllNotificationsRead != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable {
                                showMarkAllReadConfirmDialog = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BroomGlyph(tint = IosTheme.colors.label)
                    }
                }
            }
        },
    ) {
        if (!notificationOnly) {
            // ── 贴吧风格全新现代化个人主页 (顶部身份 + 晋级卡片 + 4列仪表盘 + 5宫格金刚位) ──
            item("tieba-profile-header") {
                val animCandidate = activeAccount?.animatedAvatar ?: AvatarCache.get(username)
                val avatarUrl = avatarUrlOf(
                    activeAccount?.avatarTemplate,
                    animCandidate,
                    size = 120,
                    username = username,
                )
                val staticAvatarUrl = avatarUrlOf(activeAccount?.avatarTemplate, size = 120)
                val level = activeAccount?.trustLevel ?: 0

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isDark = IosTheme.colors.isDark

                    // 1. 用户身份主卡片 (顶部大圆角，支持点击头像查看资料)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    if (isDark) {
                                        listOf(Color(0xFF1E2430), Color(0xFF151922))
                                    } else {
                                        listOf(Color(0xFFF8FAFC), Color(0xFFFFFFFF))
                                    }
                                )
                            )
                            .border(
                                1.dp,
                                if (isDark) Color(0xFF2B3342) else Color(0xFFE2E8F0),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 大头像 (外圈光环)
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .border(
                                        2.dp,
                                        if (isDark) Color(0xFF0284C7) else Color(0xFFBAE6FD),
                                        CircleShape
                                    )
                                    .clickable {
                                        if (!username.isNullOrBlank()) {
                                            val animPath = animCandidate?.takeIf(::isAnimatedAvatarPath)
                                            val primaryToSend = if (animPath != null) avatarUrl else null
                                            onOpenUser(username, primaryToSend, staticAvatarUrl)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                UserAvatar(
                                    avatarUrl = avatarUrl,
                                    fallbackUrl = staticAvatarUrl,
                                    username = username,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 29.dp,
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            // 用户信息
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = username ?: "尚未确认登录",
                                        style = IosTheme.type.headline.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                                        color = IosTheme.colors.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    ProfileVerifiedBadge()
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 社区分胶囊
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                if (isDark) Color(0x332B3950) else Color(0x181890FF)
                                            )
                                            .border(
                                                1.dp,
                                                if (isDark) Color(0x5538BDF8) else Color(0x351890FF),
                                                RoundedCornerShape(50)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "社区分 ${cdkScore ?: "-"}",
                                            style = IosTheme.type.caption.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isDark) Color(0xFF7DD3FC) else Color(0xFF096DD9),
                                            ),
                                        )
                                    }

                                    // 获赞胶囊
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                if (isDark) Color(0x20334155) else Color(0x1264748B)
                                            )
                                            .border(
                                                1.dp,
                                                if (isDark) Color(0x30475569) else Color(0x20CBD5E1),
                                                RoundedCornerShape(50)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "获赞 $likesReceived",
                                            style = IosTheme.type.caption.copy(
                                                fontSize = 11.sp,
                                                color = IosTheme.colors.secondaryLabel
                                            ),
                                        )
                                    }
                                }
                            }

                            // 切换账号胶囊 (参考图右侧圆角胶囊按钮)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isDark) Color(0x30334155) else Color(0xFFF1F5F9))
                                    .border(
                                        1.dp,
                                        if (isDark) Color(0x50475569) else Color(0xFFE2E8F0),
                                        RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        onRefreshAccounts()
                                        showAccountSwitchSheet = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "切换 ⇄",
                                    style = IosTheme.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }
                        }
                    }

                    // 2. 贴吧式「信任等级进阶微横幅」 (立体发光微光盾牌 + 严格联动用户等级设计规范配色)
                    val bannerTheme = when (level) {
                        1 -> ProfileBannerTheme(
                            // Lv.1 活跃成员：科技冰蓝
                            bgColors = if (isDark) listOf(Color(0xFF092032), Color(0xFF133854)) else listOf(Color(0xFFEBF5FF), Color(0xFFD0E8FF)),
                            borderColor = if (isDark) Color(0xFF0369A1) else Color(0xFFBAE6FD),
                            textColor = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1),
                            btnColors = listOf(Color(0xFF0284C7), Color(0xFF0369A1)),
                            shieldPrimary = Color(0xFF38BDF8),
                            shieldAccent = Color(0xFF0284C7)
                        )
                        2 -> ProfileBannerTheme(
                            // Lv.2 进阶成员：温润琥珀流金 (严格杜绝绿色！)
                            bgColors = if (isDark) listOf(Color(0xFF2E1F0A), Color(0xFF463012)) else listOf(Color(0xFFFFF7E6), Color(0xFFFFE8BA)),
                            borderColor = if (isDark) Color(0xFF784F12) else Color(0xFFFFD591),
                            textColor = if (isDark) Color(0xFFFFD166) else Color(0xFF874D00),
                            btnColors = listOf(Color(0xFFF59E0B), Color(0xFFD97706)),
                            shieldPrimary = Color(0xFFFBBF24),
                            shieldAccent = Color(0xFFD97706)
                        )
                        3 -> ProfileBannerTheme(
                            // Lv.3 资深核心：华贵深鎏金皇冠
                            bgColors = if (isDark) listOf(Color(0xFF382305), Color(0xFF523508)) else listOf(Color(0xFFFFF2E8), Color(0xFFFFDBB5)),
                            borderColor = if (isDark) Color(0xFF92400E) else Color(0xFFFFBB96),
                            textColor = if (isDark) Color(0xFFFFC069) else Color(0xFF9A3412),
                            btnColors = listOf(Color(0xFFEA580C), Color(0xFFC2410C)),
                            shieldPrimary = Color(0xFFFCD34D),
                            shieldAccent = Color(0xFFD97706)
                        )
                        4 -> ProfileBannerTheme(
                            // Lv.4 领袖成员：尊贵紫晶
                            bgColors = if (isDark) listOf(Color(0xFF2B1038), Color(0xFF3F1952)) else listOf(Color(0xFFFAF5FF), Color(0xFFE9D5FF)),
                            borderColor = if (isDark) Color(0xFF6B21A8) else Color(0xFFD8B4FE),
                            textColor = if (isDark) Color(0xFFD8B4FE) else Color(0xFF6B21A8),
                            btnColors = listOf(Color(0xFF9333EA), Color(0xFF7E22CE)),
                            shieldPrimary = Color(0xFFD8B4FE),
                            shieldAccent = Color(0xFF9333EA)
                        )
                        else -> ProfileBannerTheme(
                            // Lv.0 探索者：钛金银灰
                            bgColors = if (isDark) listOf(Color(0xFF1E2128), Color(0xFF2B2F38)) else listOf(Color(0xFFF1F3F5), Color(0xFFE2E8F0)),
                            borderColor = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1),
                            textColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                            btnColors = listOf(Color(0xFF64748B), Color(0xFF475569)),
                            shieldPrimary = Color(0xFFCBD5E1),
                            shieldAccent = Color(0xFF64748B)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.horizontalGradient(bannerTheme.bgColors))
                            .border(
                                1.dp,
                                bannerTheme.borderColor,
                                RoundedCornerShape(16.dp)
                            )
                            .clickable(onClick = onOpenConnect)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 左侧立体微光盾牌
                                ProfileShieldBadge(
                                    level = level,
                                    primaryColor = bannerTheme.shieldPrimary,
                                    accentColor = bannerTheme.shieldAccent
                                )
                                val levelStatusText = if (level >= 3) "继续保持" else "继续加油"
                                Text(
                                    text = "信任等级 Lv.$level · $levelStatusText",
                                    style = IosTheme.type.caption.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                                    color = bannerTheme.textColor,
                                )
                            }
                            // 右侧发光胶囊按钮
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.horizontalGradient(bannerTheme.btnColors))
                                    .border(1.dp, Color(0x60FFFFFF), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "等级积分",
                                    style = IosTheme.type.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    // 3. 4 列核心大数字横排仪表盘
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(IosTheme.colors.card)
                            .border(
                                1.dp,
                                if (isDark) Color(0xFF2C2D30) else Color(0xFFEFF1F4),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            // 我的发帖
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (!username.isNullOrBlank()) onOpenUserTopics?.invoke(username, "创建的话题", false)
                                    }
                            ) {
                                Text(
                                    text = topicCount,
                                    style = IosTheme.type.headline.copy(fontSize = 17.5.sp, fontWeight = FontWeight.Bold),
                                    color = IosTheme.colors.label,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("发帖", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.secondaryLabel)
                            }
                            // 我的回帖
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (!username.isNullOrBlank()) onOpenUserReplies?.invoke(username)
                                    }
                            ) {
                                Text(
                                    text = postCount,
                                    style = IosTheme.type.headline.copy(fontSize = 17.5.sp, fontWeight = FontWeight.Bold),
                                    color = IosTheme.colors.label,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("我的回帖", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.secondaryLabel)
                            }
                            // 阅读时长
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                                Text(
                                    text = timeRead,
                                    style = IosTheme.type.headline.copy(fontSize = 17.5.sp, fontWeight = FontWeight.Bold),
                                    color = IosTheme.colors.label,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("阅读时长", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.secondaryLabel)
                            }
                            // 访问天数
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = daysVisited,
                                    style = IosTheme.type.headline.copy(fontSize = 17.5.sp, fontWeight = FontWeight.Bold),
                                    color = IosTheme.colors.label,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("访问天数", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.secondaryLabel)
                            }
                        }
                    }

                    // 4. 5 联微光玻璃球拟态金刚位 (参考图通透悬浮排列)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 1: 我的收藏 (金色星芒)
                        SphereIconButton(
                            title = "我的收藏",
                            iconGlyph = "⭐",
                            glowColor = Color(0xFFFAAD14),
                            onClick = {
                                if (!username.isNullOrBlank()) onOpenUserTopics?.invoke(username, "我的收藏", true)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        // 2: 我的点赞 (珊瑚暖橙赞)
                        SphereIconButton(
                            title = "我的点赞",
                            iconGlyph = "👍",
                            glowColor = Color(0xFFFF5722),
                            onClick = {
                                if (!username.isNullOrBlank() && onOpenUserActions != null) {
                                    onOpenUserActions(username, "我的点赞", "1")
                                } else {
                                    section = ProfileSection.Summary
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        // 3: 浏览历史 (科技冰蓝钟)
                        SphereIconButton(
                            title = "浏览历史",
                            iconGlyph = "🕒",
                            glowColor = Color(0xFF1890FF),
                            onClick = onOpenHistory,
                            modifier = Modifier.weight(1f)
                        )
                        // 4: 我的关注 (天蓝双人群组)
                        SphereIconButton(
                            title = "我的关注",
                            iconGlyph = "👥",
                            glowColor = Color(0xFF2F54EB),
                            onClick = onOpenFollowing,
                            modifier = Modifier.weight(1f)
                        )
                        // 5: 草稿箱 (翡翠薄荷铅笔)
                        SphereIconButton(
                            title = "草稿箱",
                            iconGlyph = "✏️",
                            glowColor = Color(0xFF52C41A),
                            onClick = { onOpenDrafts?.invoke() },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 5. 常用快捷卡片组 (实时聊天 与 本地拉黑用户)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 卡片 1: 实时聊天
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(IosTheme.colors.card)
                                .border(
                                    1.dp,
                                    if (isDark) Color(0xFF2C2D30) else Color(0xFFEFF1F4),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable(onClick = onOpenChat)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("💬", fontSize = 16.sp)
                                        Text("实时聊天频道", style = IosTheme.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                                    }
                                    Text("与佬友在线互动畅聊", style = IosTheme.type.caption.copy(fontSize = 11.sp), color = IosTheme.colors.secondaryLabel)
                                }
                                Text("›", fontSize = 18.sp, color = IosTheme.colors.secondaryLabel.copy(alpha = 0.6f))
                            }
                        }
                        // 卡片 2: 本地黑名单 (严格对齐文案规范与单行排版)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(IosTheme.colors.card)
                                .border(
                                    1.dp,
                                    if (isDark) Color(0xFF2C2D30) else Color(0xFFEFF1F4),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable(onClick = onOpenBlacklist)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("🚫", fontSize = 16.sp)
                                        Text("本地黑名单", style = IosTheme.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                                    }
                                    Text(
                                        text = "本机屏蔽话题、评论",
                                        style = IosTheme.type.caption.copy(fontSize = 11.sp),
                                        color = IosTheme.colors.secondaryLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text("›", fontSize = 18.sp, color = IosTheme.colors.secondaryLabel.copy(alpha = 0.6f))
                            }
                        }
                    }

                    // 6. 徽章展示模块 (玻璃质感大卡片，优先展示已获得，后排未获得，与上方卡片保持统一的 8dp 间距)
                    UserBadgesSectionCard(
                        badges = userBadges,
                        isLoading = isUserBadgesLoading,
                        onRefresh = onRefreshUserBadges,
                        onBadgeClick = { selectedBadge = it },
                    )
                }
            }
        }

        // 通知分类 Tab (全部、回复、点赞、私信)
        if (section == ProfileSection.Notifications) item("notification-filters") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NotificationFilter.entries.forEach { filter ->
                    val selected = notificationFilter == filter
                    val color = if (selected) IosTheme.colors.accent else IosTheme.colors.secondaryLabel
                    val hasUnread = filter != NotificationFilter.All && (
                        filter in unreadFilters || visibleEntries.any { it.unread && filter.includes(it.notificationType) }
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) IosTheme.colors.accent.copy(alpha = 0.12f) else IosTheme.colors.card)
                            .clickable { onNotificationFilter(filter) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasUnread) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF3B30)),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        NotificationTabIcon(filter, color)
                        Text(
                            text = filter.label,
                            style = IosTheme.type.subheadline,
                            color = color,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
        }


        if (state.loading && !state.loaded) item("loading") { IosLoadingBox() }
        state.error?.let { message -> item("error") { ErrorNotice(message) { onLoad(section, true, false) } } }

        if (section != ProfileSection.Summary) {
            items(visibleEntries, key = { it.id }) { entry ->
                val notification = section == ProfileSection.Notifications
                IosGroupCard(Modifier.padding(bottom = if (notification) 4.dp else 8.dp)) {
                    IosRow(onClick = {
                        if (section == ProfileSection.Notifications && entry.unread) entry.id.toLongOrNull()?.let(onReadNotification)
                        entry.topicId?.let { onOpenTopic(it, entry.title, entry.postNumber) }
                    }) {
                        if (entry.unread) Text("•", color = IosTheme.colors.accent, modifier = Modifier.padding(end = 6.dp))
                        if (notification) {
                            NotificationAvatarWithBadge(
                                avatarUrl = entry.avatarUrl,
                                actor = entry.actor,
                                notificationType = entry.notificationType,
                                unread = entry.unread,
                                onClick = { entry.actor?.let { onOpenUser(it, entry.avatarUrl, null) } },
                            )
                            Spacer(Modifier.width(10.dp))
                        } else {
                            entry.avatarUrl?.let { url ->
                                AsyncImage(
                                    url,
                                    entry.actor,
                                    modifier = Modifier.size(32.dp).clip(avatarShape(entry.actor, url, 4.dp))
                                        .clickable(enabled = !entry.actor.isNullOrBlank()) { entry.actor?.let { onOpenUser(it, url, null) } },
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(vertical = if (notification) 0.dp else 4.dp)) {
                            if (notification) {
                                val typeTag = notificationTypeTag(entry.notificationType)
                                val dateStr = entry.subtitle.substringAfterLast(" · ").takeLast(5)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (!entry.actor.isNullOrBlank()) {
                                            Text(
                                                text = entry.actor,
                                                style = IosTheme.type.footnote,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(notificationTypeTint(entry.notificationType).copy(alpha = 0.13f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = typeTag,
                                                style = IosTheme.type.caption.copy(fontSize = 11.sp),
                                                fontWeight = FontWeight.Medium,
                                                color = notificationTypeTint(entry.notificationType),
                                            )
                                        }
                                    }
                                    if (dateStr.isNotBlank()) {
                                        Text(
                                            text = dateStr,
                                            style = IosTheme.type.caption,
                                            color = IosTheme.colors.secondaryLabel,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            } else {
                                entry.actor?.let { actor ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(actor, style = IosTheme.type.footnote, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            Text(
                                text = entry.title,
                                style = if (notification) IosTheme.type.subheadline else IosTheme.type.body,
                                maxLines = if (notification) 2 else 3,
                                modifier = Modifier.padding(top = if (notification) 2.dp else 0.dp),
                            )
                            if (!notification && entry.subtitle.isNotBlank()) Text(entry.subtitle, style = IosTheme.type.footnote,
                                color = IosTheme.colors.secondaryLabel, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        // 还有更多但不足一屏: 给手动加载入口
        if (state.loaded && !state.loading && state.error == null && state.content.hasMore &&
            visibleEntries.size < AUTO_LOAD_THRESHOLD && state.content.fields.isEmpty()) {
            item("load-more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    IosTextAction(if (visibleEntries.isEmpty()) "这一页没有更多，继续往前找" else "加载更早的记录") {
                        onLoad(section, false, true)
                    }
                }
            }
        }
        if (state.loaded && visibleEntries.isEmpty() && state.content.fields.isEmpty() &&
            state.error == null && !state.content.hasMore) {
            item("empty") { EmptyNotice() }
        }
        if (state.loading && state.loaded) item("more") { IosLoadingBox() }
    }

    if (showAccountSwitchSheet) {
        AccountSwitchBottomSheet(
            state = accountState,
            onDismiss = { showAccountSwitchSheet = false },
            onSwitch = onSwitchAccount,
            onRemoveAccount = onRemoveAccount,
            onAddAccount = {
                showAccountSwitchSheet = false
                onAddAccount()
            },
        )
    }

    // 退出登录确认对话框（防止误触，对齐评论管理弹框风格）
    if (showLogoutDialog) {
        LogoutConfirmDialog(
            onDismiss = { showLogoutDialog = false },
            onConfirm = {
                showLogoutDialog = false
                onLogout()
            },
        )
    }

    // 标记全部已读确认对话框 (参考图高保真对齐: 居中加粗标题 + 左灰右红双胶囊按键)
    if (showMarkAllReadConfirmDialog) {
        Dialog(
            onDismissRequest = { showMarkAllReadConfirmDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(IosTheme.colors.card)
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "确定将所有消息标记为已读吗？",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosTheme.colors.label,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 取消按钮
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (IosTheme.colors.isDark) Color(0xFF2C2C2E) else Color(0xFFF4F5F7))
                                .clickable { showMarkAllReadConfirmDialog = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "取消",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = IosTheme.colors.label,
                            )
                        }
                        // 确定按钮
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFF3B30))
                                .clickable {
                                    showMarkAllReadConfirmDialog = false
                                    onMarkAllNotificationsRead?.invoke()
                                    Toast.makeText(context, "已全部标记为已读", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "确定",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }

    selectedBadge?.let { badge ->
        BadgeDetailDialog(
            badge = badge,
            onDismiss = { selectedBadge = null },
        )
    }
}

private fun findMetric(fields: List<Pair<String, String>>, key: String, fallback: String = "0"): String {
    return fields.firstOrNull { it.first == key }?.second?.ifBlank { fallback } ?: fallback
}

@Composable
private fun ProfileSettingsGearIcon(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val cx = 12f
            val cy = 12f
            val r = 9.2f
            val cornerRatio = 0.22f
            val vertices = Array(6) { i ->
                val rad = (i * 60f) * (kotlin.math.PI / 180.0)
                Offset(
                    x = cx + (r * kotlin.math.cos(rad)).toFloat(),
                    y = cy + (r * kotlin.math.sin(rad)).toFloat(),
                )
            }
            val hexPath = Path().apply {
                for (i in 0 until 6) {
                    val prev = vertices[(i + 5) % 6]
                    val curr = vertices[i]
                    val next = vertices[(i + 1) % 6]
                    val pStart = Offset(
                        x = curr.x + (prev.x - curr.x) * cornerRatio,
                        y = curr.y + (prev.y - curr.y) * cornerRatio,
                    )
                    val pEnd = Offset(
                        x = curr.x + (next.x - curr.x) * cornerRatio,
                        y = curr.y + (next.y - curr.y) * cornerRatio,
                    )
                    if (i == 0) moveTo(pStart.x, pStart.y) else lineTo(pStart.x, pStart.y)
                    quadraticTo(curr.x, curr.y, pEnd.x, pEnd.y)
                }
                close()
            }
            val stroke = Stroke(
                width = 1.7f,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            drawPath(hexPath, color = tint, style = stroke)
            drawCircle(color = tint, radius = 3.0f, center = Offset(cx, cy), style = stroke)
        }
    }
}

@Composable
private fun BroomGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            // 扫把长手柄 (从右上斜伸向中间)
            drawLine(tint, Offset(19f, 4f), Offset(12f, 11f), strokeWidth = 2.0f, cap = StrokeCap.Round)
            // 扫把束带箍
            drawLine(tint, Offset(10.5f, 10f), Offset(14f, 13.5f), strokeWidth = 2.4f, cap = StrokeCap.Round)
            // 扫把扇形刷头
            val broom = Path().apply {
                moveTo(11.5f, 11.5f)
                lineTo(5f, 17f)
                quadraticBezierTo(7.5f, 20.5f, 12f, 19.5f)
                lineTo(13.5f, 13.5f)
                close()
            }
            drawPath(broom, color = tint, style = stroke)
            // 刷毛纹理
            drawLine(tint, Offset(10.5f, 13.5f), Offset(8f, 18.5f), strokeWidth = 1.2f, cap = StrokeCap.Round)
            // 打扫微光/清洁星芒
            drawCircle(tint, 1.2f, Offset(4f, 10.5f))
            drawCircle(tint, 0.9f, Offset(7f, 7.5f))
        }
    }
}

enum class ProfileNavIconType {
    Summary, History, Following, Chat
}

@Composable
private fun ProfileNavCard(
    title: String,
    icon: ProfileNavIconType,
    selected: Boolean,
    bgColor: Color = IosTheme.colors.card,
    tintColor: Color = IosTheme.colors.accent,
    modifier: Modifier = Modifier,
    hasBadge: Boolean = false,
    onClick: () -> Unit,
) {
    val isDark = IosTheme.colors.isDark
    val cardBg = if (isDark) tintColor.copy(alpha = 0.16f) else bgColor
    val borderColor = if (isDark) tintColor.copy(alpha = 0.38f) else tintColor.copy(alpha = 0.25f)
    val contentColor = tintColor

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                        when (icon) {
                            ProfileNavIconType.Summary -> {
                                val doc = Path().apply {
                                    moveTo(5f, 3f)
                                    lineTo(15f, 3f)
                                    lineTo(19f, 7f)
                                    lineTo(19f, 21f)
                                    lineTo(5f, 21f)
                                    close()
                                }
                                drawPath(doc, contentColor, style = Stroke(1.8f))
                                drawLine(contentColor, Offset(8f, 10f), Offset(16f, 10f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                                drawLine(contentColor, Offset(8f, 14f), Offset(16f, 14f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                                drawLine(contentColor, Offset(8f, 18f), Offset(13f, 18f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                            }
                            ProfileNavIconType.History -> {
                                val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                drawArc(
                                    color = contentColor,
                                    startAngle = 40f,
                                    sweepAngle = 280f,
                                    useCenter = false,
                                    topLeft = Offset(4f, 4f),
                                    size = Size(16f, 16f),
                                    style = stroke,
                                )
                                val arrow = Path().apply {
                                    moveTo(9.5f, 2.5f)
                                    lineTo(4f, 4.5f)
                                    lineTo(5.5f, 10f)
                                }
                                drawPath(arrow, contentColor, style = stroke)
                                drawLine(contentColor, Offset(12f, 12f), Offset(12f, 7.5f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                                drawLine(contentColor, Offset(12f, 12f), Offset(15.5f, 12f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                            }
                            ProfileNavIconType.Following -> {
                                val star = Path().apply {
                                    moveTo(12f, 3.5f)
                                    lineTo(14.6f, 8.8f)
                                    lineTo(20.5f, 9.6f)
                                    lineTo(16.2f, 13.8f)
                                    lineTo(17.2f, 19.6f)
                                    lineTo(12f, 16.9f)
                                    lineTo(6.8f, 19.6f)
                                    lineTo(7.8f, 13.8f)
                                    lineTo(3.5f, 9.6f)
                                    lineTo(9.4f, 8.8f)
                                    close()
                                }
                                drawPath(star, contentColor, style = Stroke(1.8f, join = StrokeJoin.Round))
                            }
                            ProfileNavIconType.Chat -> {
                                val chat = Path().apply {
                                    moveTo(4.5f, 6f)
                                    cubicTo(4.5f, 4.5f, 5.5f, 4f, 7f, 4f)
                                    lineTo(17f, 4f)
                                    cubicTo(18.5f, 4f, 19.5f, 4.5f, 19.5f, 6f)
                                    lineTo(19.5f, 14f)
                                    cubicTo(19.5f, 15.5f, 18.5f, 16f, 17f, 16f)
                                    lineTo(9.5f, 16f)
                                    lineTo(5.5f, 19.5f)
                                    lineTo(5.5f, 16f)
                                    lineTo(4.5f, 16f)
                                    close()
                                }
                                drawPath(chat, contentColor, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawCircle(contentColor, 1.1f, Offset(8.5f, 10f))
                                drawCircle(contentColor, 1.1f, Offset(12f, 10f))
                                drawCircle(contentColor, 1.1f, Offset(15.5f, 10f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = IosTheme.type.caption.copy(fontSize = 13.sp),
                color = IosTheme.colors.label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        if (hasBadge) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B30))
                    .align(Alignment.TopEnd)
                    .offset(x = (-5).dp, y = (5).dp)
            )
        }
    }
}

enum class MetricCardIconType {
    Book, Heart, Thumb, Rocket, Reply, Bookmark, Draft
}

@Composable
private fun ProfileMetricCard(
    label: String,
    value: String,
    iconType: MetricCardIconType,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(IosTheme.colors.card)
            .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(32.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                when (iconType) {
                    MetricCardIconType.Book -> {
                        val leftPage = Path().apply {
                            moveTo(12f, 6f)
                            cubicTo(9f, 4.5f, 4f, 4.5f, 3f, 5f)
                            lineTo(3f, 18f)
                            cubicTo(4f, 17.5f, 9f, 17.5f, 12f, 19f)
                            close()
                        }
                        val rightPage = Path().apply {
                            moveTo(12f, 6f)
                            cubicTo(15f, 4.5f, 20f, 4.5f, 21f, 5f)
                            lineTo(21f, 18f)
                            cubicTo(20f, 17.5f, 15f, 17.5f, 12f, 19f)
                            close()
                        }
                        drawPath(leftPage, Color(0xFF67C23A))
                        drawPath(rightPage, Color(0xFF409EFF))
                        drawLine(Color.White, Offset(12f, 5.5f), Offset(12f, 19f), strokeWidth = 1.2f)
                    }
                    MetricCardIconType.Heart -> {
                        val heart = Path().apply {
                            moveTo(12f, 21f); cubicTo(10f, 19f, 2.5f, 13f, 2.5f, 8f)
                            cubicTo(2.5f, 2.5f, 9.5f, 1.5f, 12f, 6.2f)
                            cubicTo(14.5f, 1.5f, 21.5f, 2.5f, 21.5f, 8f)
                            cubicTo(21.5f, 13f, 14f, 19f, 12f, 21f); close()
                        }
                        drawPath(heart, Color(0xFFFF4D6A))
                    }
                    MetricCardIconType.Thumb -> {
                        val thumb = Path().apply {
                            moveTo(8f, 19f); lineTo(4f, 19f); lineTo(4f, 9f); lineTo(8f, 9f); close()
                            moveTo(9f, 9f); lineTo(13f, 2f); cubicTo(14.5f, 2f, 15.5f, 3.5f, 15f, 5f)
                            lineTo(14f, 9f); lineTo(20f, 9f); cubicTo(21.5f, 9f, 22f, 10.5f, 21.5f, 12f)
                            lineTo(19.5f, 17.5f); cubicTo(19f, 18.5f, 18f, 19f, 17f, 19f); lineTo(9f, 19f); close()
                        }
                        drawPath(thumb, Color(0xFFFA8C16))
                    }
                    MetricCardIconType.Rocket -> {
                        val rocket = Path().apply {
                            moveTo(20f, 4f)
                            cubicTo(15f, 4f, 10.5f, 7.5f, 8.5f, 12f)
                            lineTo(5f, 13f); lineTo(7.5f, 15.5f)
                            lineTo(8.5f, 15.5f); lineTo(11f, 19f)
                            lineTo(12f, 15.5f)
                            cubicTo(16.5f, 13.5f, 20f, 9f, 20f, 4f)
                            close()
                        }
                        drawPath(rocket, Color(0xFF8560F4))
                        drawCircle(Color.White, radius = 2f, center = Offset(14.5f, 9.5f))
                    }
                    MetricCardIconType.Reply -> {
                        val bubble = Path().apply {
                            moveTo(4f, 12f)
                            cubicTo(4f, 6.5f, 7.5f, 3f, 12f, 3f)
                            cubicTo(16.5f, 3f, 20f, 6.5f, 20f, 12f)
                            cubicTo(20f, 17.5f, 16.5f, 21f, 12f, 21f)
                            cubicTo(10f, 21f, 8.5f, 20.5f, 7f, 19.5f)
                            lineTo(4f, 20.5f)
                            lineTo(4.8f, 17.5f)
                            cubicTo(4.3f, 16f, 4f, 14f, 4f, 12f)
                            close()
                        }
                        drawPath(bubble, Color(0xFF1890FF))
                        drawCircle(Color.White, 1.2f, Offset(9f, 12f))
                        drawCircle(Color.White, 1.2f, Offset(12f, 12f))
                        drawCircle(Color.White, 1.2f, Offset(15f, 12f))
                    }
                    MetricCardIconType.Bookmark -> {
                        val bookmark = Path().apply {
                            moveTo(6f, 3.5f)
                            lineTo(18f, 3.5f)
                            cubicTo(18.5f, 3.5f, 19f, 4f, 19f, 4.5f)
                            lineTo(19f, 20.5f)
                            lineTo(12f, 16f)
                            lineTo(5f, 20.5f)
                            lineTo(5f, 4.5f)
                            cubicTo(5f, 4f, 5.5f, 3.5f, 6f, 3.5f)
                            close()
                        }
                        drawPath(bookmark, Color(0xFFFFB800))
                    }
                    MetricCardIconType.Draft -> {
                        val doc = Path().apply {
                            moveTo(5f, 3f)
                            lineTo(14.5f, 3f)
                            lineTo(19f, 7.5f)
                            lineTo(19f, 20.5f)
                            lineTo(5f, 20.5f)
                            close()
                        }
                        drawPath(doc, Color(0xFFFA8C16))
                        drawLine(Color.White, Offset(8f, 10f), Offset(16f, 10f), strokeWidth = 1.6f, cap = StrokeCap.Round)
                        drawLine(Color.White, Offset(8f, 14f), Offset(15f, 14f), strokeWidth = 1.6f, cap = StrokeCap.Round)
                        drawLine(Color.White, Offset(8f, 17.5f), Offset(12.5f, 17.5f), strokeWidth = 1.6f, cap = StrokeCap.Round)
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = IosTheme.type.caption.copy(fontSize = 12.sp),
                color = IosTheme.colors.secondaryLabel,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = IosTheme.colors.label,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun notificationTypeTint(type: Int): Color = when (type) {
    5, 19 -> Color(0xFFFF3B30)
    25 -> Color(0xFFFF9500)
    14 -> Color(0xFF30B85B)
    12 -> Color(0xFFAF52DE)
    43 -> Color(0xFF5856D6)
    800, 801, 802 -> Color(0xFF0084FF)
    else -> IosTheme.colors.accent
}

private fun notificationTypeTag(type: Int): String = when (type) {
    1, 15 -> "提及"
    2 -> "回复"
    3 -> "引用"
    4 -> "编辑"
    5, 19 -> "赞了你"
    6, 7, 16 -> "私信"
    8 -> "接受邀请"
    9, 17 -> "新帖"
    10 -> "移动"
    11, 38 -> "链接"
    12 -> "徽章"
    13 -> "话题邀请"
    14 -> "解决方案"
    25 -> "表情回应"
    27 -> "活动提醒"
    28 -> "活动邀请"
    29, 32 -> "聊天提及"
    30 -> "聊天消息"
    31 -> "聊天邀请"
    33 -> "聊天引用"
    34 -> "指派"
    43 -> "简评"
    800 -> "关注动态"
    801 -> "关注发帖"
    802 -> "关注回复"
    else -> "通知"
}

/**
 * 参照 Discourse Web 通知列表样式：在圆形头像右上角叠加带白/底色描边的类型角标图标，
 * 一眼区分回复(弯箭头)、点赞(爱心)、表情回应(笑脸点赞)、采纳解答(勾选框)、徽章(星章)、简评(小火箭)、私信(信封)、关注动态(蓝底小人加号/铃铛)。
 */
@Composable
private fun NotificationAvatarWithBadge(
    avatarUrl: String?,
    actor: String?,
    notificationType: Int,
    unread: Boolean,
    onClick: () -> Unit = {},
) {
    val cardColor = IosTheme.colors.card
    val isFollowType = notificationType in setOf(800, 801, 802)
    val badgeBgColor = if (isFollowType) Color(0xFF0084FF) else cardColor
    val badgeIconColor = when {
        isFollowType -> Color.White
        unread -> IosTheme.colors.accent
        IosTheme.colors.isDark -> Color(0xFFD1D1D6)
        else -> Color(0xFF3A3A3C)
    }
    Box(Modifier.size(38.dp).clickable(enabled = !actor.isNullOrBlank(), onClick = onClick)) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = actor,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(34.dp)
                    .clip(avatarShape(actor, avatarUrl, 4.dp)),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(34.dp)
                    .clip(avatarShape(actor, null, 4.dp))
                    .background(IosTheme.colors.fieldBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actor?.firstOrNull()?.uppercase() ?: "★",
                    style = IosTheme.type.footnote,
                    fontWeight = FontWeight.Bold,
                    color = IosTheme.colors.secondaryLabel,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(badgeBgColor)
                .then(
                    if (isFollowType) {
                        Modifier.border(1.5.dp, cardColor, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) {
                scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                    val shape = Path()
                    when (notificationType) {
                        // 关注动态：800 关注人/成为粉丝（蓝底白图 👤+ 小人加号）
                        800 -> {
                            drawCircle(Color.White, radius = 3.6f, center = Offset(8f, 7.5f))
                            val body = Path().apply {
                                moveTo(3f, 18f)
                                cubicTo(3f, 13.5f, 5.5f, 13f, 8f, 13f)
                                cubicTo(10.5f, 13f, 13f, 13.5f, 13f, 18f)
                                close()
                            }
                            drawPath(body, Color.White)
                            drawLine(Color.White, start = Offset(15f, 10f), end = Offset(21f, 10f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                            drawLine(Color.White, start = Offset(18f, 7f), end = Offset(18f, 13f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                        }
                        // 关注动态：801 关注人发帖、802 关注人回复（蓝底白图 👤🔔 小人铃铛）
                        801, 802 -> {
                            drawCircle(Color.White, radius = 3.4f, center = Offset(7.5f, 7.5f))
                            val body = Path().apply {
                                moveTo(2.5f, 18f)
                                cubicTo(2.5f, 13.5f, 5f, 13f, 7.5f, 13f)
                                cubicTo(10f, 13f, 12.5f, 13.5f, 12.5f, 18f)
                                close()
                            }
                            drawPath(body, Color.White)
                            val bell = Path().apply {
                                moveTo(15f, 14f)
                                lineTo(16f, 11f)
                                cubicTo(16f, 8.5f, 20f, 8.5f, 20f, 11f)
                                lineTo(21f, 14f)
                                close()
                            }
                            drawPath(bell, Color.White)
                            drawCircle(Color.White, radius = 1.3f, center = Offset(18f, 15.5f))
                        }
                        // 回复 / 提及 / 引用 / 聊天引用：左弯箭头 (↩)
                        1, 2, 3, 9, 15, 33 -> {
                            shape.moveTo(10f, 5f); shape.lineTo(3f, 11f); shape.lineTo(10f, 17f)
                            shape.lineTo(10f, 13f); shape.cubicTo(17f, 12f, 19f, 15f, 21f, 20f)
                            shape.cubicTo(21f, 10f, 17f, 8f, 10f, 9f); shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 点赞 / 合并点赞：实心爱心 (❤)
                        5, 19 -> {
                            shape.moveTo(12f, 21f); shape.cubicTo(10f, 19f, 2.5f, 13f, 2.5f, 8f)
                            shape.cubicTo(2.5f, 2.5f, 9.5f, 1.5f, 12f, 6.2f)
                            shape.cubicTo(14.5f, 1.5f, 21.5f, 2.5f, 21.5f, 8f)
                            shape.cubicTo(21.5f, 13f, 14f, 19f, 12f, 21f); shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 表情回应：圆脸 + 微笑弧线 + 右侧小拇指
                        25 -> {
                            drawCircle(badgeIconColor, radius = 7.5f, center = Offset(9.5f, 12f), style = Stroke(2.2f))
                            drawCircle(badgeIconColor, radius = 1.2f, center = Offset(7f, 10.2f))
                            drawCircle(badgeIconColor, radius = 1.2f, center = Offset(12f, 10.2f))
                            drawArc(
                                color = badgeIconColor,
                                startAngle = 25f,
                                sweepAngle = 130f,
                                useCenter = false,
                                topLeft = Offset(6.5f, 11f),
                                size = androidx.compose.ui.geometry.Size(6f, 4.5f),
                                style = Stroke(2f),
                            )
                            shape.moveTo(18f, 11f); shape.lineTo(19.5f, 7f); shape.lineTo(21.5f, 7.8f)
                            shape.lineTo(20.5f, 11f); shape.lineTo(23f, 11f); shape.lineTo(22.2f, 16.5f)
                            shape.lineTo(18f, 16.5f); shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 采纳解决方案：圆角方框 + 对勾 (☑)
                        14 -> {
                            if (unread) {
                                drawRoundRect(
                                    color = badgeIconColor,
                                    topLeft = Offset(3f, 3f),
                                    size = androidx.compose.ui.geometry.Size(18f, 18f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                                    style = Stroke(2.4f),
                                )
                                shape.moveTo(7.5f, 12.2f); shape.lineTo(10.6f, 15.3f); shape.lineTo(16.8f, 9f)
                                drawPath(shape, badgeIconColor, style = Stroke(2.4f))
                            } else {
                                drawRoundRect(
                                    color = badgeIconColor,
                                    topLeft = Offset(3f, 3f),
                                    size = androidx.compose.ui.geometry.Size(18f, 18f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                                )
                                shape.moveTo(7.5f, 12.2f); shape.lineTo(10.6f, 15.3f); shape.lineTo(16.8f, 9f)
                                drawPath(shape, cardColor, style = Stroke(2.4f))
                            }
                        }
                        // 获赠徽章：多角星章 (☀)
                        12 -> {
                            val points = 12
                            val outerR = 9.5f
                            val innerR = 7.2f
                            for (i in 0 until points * 2) {
                                val r = if (i % 2 == 0) outerR else innerR
                                val angle = i * Math.PI / points
                                val x = 12f + (r * kotlin.math.cos(angle)).toFloat()
                                val y = 12f + (r * kotlin.math.sin(angle)).toFloat()
                                if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                            }
                            shape.close()
                            drawPath(shape, badgeIconColor)
                        }
                        // 简评 (Boost)：小火箭 (🚀)
                        43 -> {
                            shape.moveTo(20f, 4f)
                            shape.cubicTo(15f, 4f, 10.5f, 7.5f, 8.5f, 12f)
                            shape.lineTo(5f, 13f); shape.lineTo(7.5f, 15.5f)
                            shape.lineTo(8.5f, 15.5f); shape.lineTo(11f, 19f)
                            shape.lineTo(12f, 15.5f)
                            shape.cubicTo(16.5f, 13.5f, 20f, 9f, 20f, 4f)
                            shape.close()
                            drawPath(shape, badgeIconColor)
                            drawCircle(cardColor, radius = 1.6f, center = Offset(14.5f, 9.5f))
                        }
                        // 私信：信封 (✉)
                        6, 7, 16 -> {
                            shape.moveTo(3f, 6f); shape.lineTo(21f, 6f); shape.lineTo(21f, 18f)
                            shape.lineTo(3f, 18f); shape.close()
                            shape.moveTo(3f, 7f); shape.lineTo(12f, 13.5f); shape.lineTo(21f, 7f)
                            drawPath(shape, badgeIconColor, style = Stroke(2.2f))
                        }
                        // 其他通知：小铃铛
                        else -> {
                            shape.moveTo(5f, 17f); shape.lineTo(7f, 14f); shape.lineTo(7f, 9f)
                            shape.cubicTo(7f, 2.5f, 17f, 2.5f, 17f, 9f)
                            shape.lineTo(17f, 14f); shape.lineTo(19f, 17f); shape.close()
                            drawPath(shape, badgeIconColor)
                            drawCircle(badgeIconColor, 1.8f, Offset(12f, 20f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationTabIcon(filter: NotificationFilter, color: Color) {
    Canvas(Modifier.size(17.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val shape = Path()
            when (filter) {
                NotificationFilter.All -> {
                    shape.moveTo(5f, 17f); shape.lineTo(7f, 14f); shape.lineTo(7f, 9f)
                    shape.cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                    shape.lineTo(17f, 14f); shape.lineTo(19f, 17f); shape.close()
                    drawPath(shape, color, style = Stroke(1.7f))
                    drawCircle(color, 1.5f, Offset(12f, 20f))
                }
                NotificationFilter.Replies -> {
                    shape.moveTo(10f, 5f); shape.lineTo(3f, 11f); shape.lineTo(10f, 17f)
                    shape.lineTo(10f, 13f); shape.cubicTo(17f, 12f, 19f, 15f, 21f, 20f)
                    shape.cubicTo(21f, 10f, 17f, 8f, 10f, 9f); shape.close()
                    drawPath(shape, color)
                }
                NotificationFilter.Messages -> {
                    shape.moveTo(3f, 5f); shape.lineTo(21f, 5f); shape.lineTo(21f, 19f)
                    shape.lineTo(3f, 19f); shape.close()
                    shape.moveTo(3f, 6f); shape.lineTo(12f, 13f); shape.lineTo(21f, 6f)
                    drawPath(shape, color, style = Stroke(1.7f))
                }
                NotificationFilter.Likes -> {
                    shape.moveTo(12f, 21f); shape.cubicTo(10f, 19f, 3f, 13f, 3f, 8f)
                    shape.cubicTo(3f, 2f, 10f, 1f, 12f, 6f); shape.cubicTo(14f, 1f, 21f, 2f, 21f, 8f)
                    shape.cubicTo(21f, 13f, 14f, 19f, 12f, 21f); shape.close(); drawPath(shape, color)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    treeView: Boolean,
    onTreeView: (Boolean) -> Unit,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    page: SettingsPage? = null,
    onOpenPage: (SettingsPage) -> Unit = {},
    floatingBallEnabled: Boolean = true,
    onFloatingBallEnabled: (Boolean) -> Unit = {},
    floatingBallActions: List<FloatingAction> = FloatingAction.DEFAULT_ACTIONS,
    onFloatingBallActions: (List<FloatingAction>) -> Unit = {},
    updateState: UpdateUiState = UpdateUiState(),
    onCheckUpdate: () -> Unit = {},
    syncReadProgress: Boolean = true,
    onSyncReadProgress: (Boolean) -> Unit = {},
    showUnreadDot: Boolean = true,
    onShowUnreadDot: (Boolean) -> Unit = {},
    unreadDotPosition: UnreadDotPosition = UnreadDotPosition.Avatar,
    onUnreadDotPosition: (UnreadDotPosition) -> Unit = {},
    showTopicUnreadDot: Boolean = true,
    onShowTopicUnreadDot: (Boolean) -> Unit = {},
    topicUnreadDotPosition: TopicUnreadDotPosition = TopicUnreadDotPosition.Avatar,
    onTopicUnreadDotPosition: (TopicUnreadDotPosition) -> Unit = {},
    onDiagnostics: () -> Unit = {},
    readingStatus: String? = null,
    canRetryReading: Boolean = false,
    onRetryReading: () -> Unit = {},
    onLogout: () -> Unit = {},
    autoRefreshIncoming: Boolean = true,
    onAutoRefreshIncoming: (Boolean) -> Unit = {},
    autoRefreshInterval: AutoRefreshInterval = AutoRefreshInterval.SEC_20,
    onAutoRefreshInterval: (AutoRefreshInterval) -> Unit = {},
    autoReadClipboard: Boolean = true,
    onAutoReadClipboard: (Boolean) -> Unit = {},
    backgroundNotification: Boolean = false,
    onBackgroundNotification: (Boolean) -> Unit = {},
    notificationPollInterval: NotificationPollInterval = NotificationPollInterval.OFF,
    onNotificationPollInterval: (NotificationPollInterval) -> Unit = {},
    customRealtimeSeconds: Int = 15,
    onCustomRealtimeSeconds: (Int) -> Unit = {},
    notificationScope: NotificationScope = NotificationScope.INTERACTION_ONLY,
    onNotificationScope: (NotificationScope) -> Unit = {},
    keepAliveService: Boolean = false,
    onKeepAliveService: (Boolean) -> Unit = {},
    onTestNotification: () -> Unit = {},
    // ── DoH 安全直连参数 ────────────────────────────────────────────────────────
    dohEnabled: Boolean = false,
    onDohEnabled: (Boolean) -> Unit = {},
    dohServers: List<DohServer> = DohPresets.ALL,
    selectedDohServerUrl: String = DohPresets.DEFAULT.url,
    onDohServerSelected: (DohServer) -> Unit = {},
    dohAutoFailover: Boolean = true,
    onDohAutoFailover: (Boolean) -> Unit = {},
    dohAutoOptimize: Boolean = true,
    onDohAutoOptimize: (Boolean) -> Unit = {},
    dohForceOverProxy: Boolean = false,
    onDohForceOverProxy: (Boolean) -> Unit = {},
    dohLatencies: Map<String, Long?> = emptyMap(),
    dohSummaries: Map<String, String> = emptyMap(),
    isTestingAllDoh: Boolean = false,
    onTestDohServer: (DohServer) -> Unit = {},
    onTestAllDohServers: () -> Unit = {},
    smartRouteMode: org.linuxdo.android.net.NetworkRouteMode = org.linuxdo.android.net.NetworkRouteMode.DIRECT,
    smartDirectLatency: Long? = null,
    smartDohLatency: Long? = null,
    smartRouteSummary: String = "",
    isSmartEvaluating: Boolean = false,
    onEvaluateSmartRoute: () -> Unit = {},
    onAddCustomDohServer: (url: String, name: String) -> Unit = { _, _ -> },
    onUpdateDohServer: (oldServer: DohServer, url: String, name: String) -> Unit = { _, _, _ -> },
    onDeleteDohServer: (DohServer) -> Unit = {},
    onResetDohServers: () -> Unit = {},
    feedGraphicMode: Boolean = true,
    onFeedGraphicMode: (Boolean) -> Unit = {},
    feedDisplayMode: String = "no_excerpt",
    onFeedDisplayMode: (String) -> Unit = {},
    skinPreset: org.linuxdo.android.ui.design.SkinPreset = org.linuxdo.android.ui.design.SkinPreset.CLASSIC,
    onSkinPreset: (org.linuxdo.android.ui.design.SkinPreset) -> Unit = {},
    isSelfHealing: Boolean = false,
    onSelfHealNetwork: () -> Unit = {},
) {
    val context = LocalContext.current
    var showSyncConfirmDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var showAutoRefreshIntervalDialog by remember { mutableStateOf(false) }
    var showNotificationScopeDialog by remember { mutableStateOf(false) }
    var showTestPushLabDialog by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("display_preferences", Context.MODE_PRIVATE) }
    var devOptionsUnlocked by remember {
        mutableStateOf(if (prefs.contains("dev_options_enabled")) prefs.getBoolean("dev_options_enabled", false) else BuildConfig.DEBUG)
    }
    var devClickCount by remember { mutableIntStateOf(0) }
    var lastDevClickTime by remember { mutableStateOf(0L) }

    var isBatteryOptimizedIgnored by remember {
        mutableStateOf(NotificationHelper.isIgnoringBatteryOptimizations(context))
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isBatteryOptimizedIgnored = NotificationHelper.isIgnoringBatteryOptimizations(context)
                devOptionsUnlocked = if (prefs.contains("dev_options_enabled")) {
                    prefs.getBoolean("dev_options_enabled", false)
                } else {
                    BuildConfig.DEBUG
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，默认拒绝。
    // 没授权时 NotificationManager.notify() 会被系统静默丢弃 —— 用户只会觉得"推送不工作"，
    // 不会收到任何提示，所以必须在用户开启通知相关开关的那一刻顺手要权限。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不阻断设置本身，用户仍可在系统设置里补开 */ }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationHelper.openNotificationSettings(context)
        }
    }

    // 动态计算本地缓存大小
    fun calcCacheSize(): String {
        return runCatching {
            var totalBytes = 0L
            listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir).forEach { dir ->
                if (dir.exists()) {
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        totalBytes += file.length()
                    }
                }
            }
            val mb = totalBytes / (1024.0 * 1024.0)
            if (mb < 0.1) "0.0 MB" else String.format(Locale.US, "%.1f MB", mb)
        }.getOrDefault("0.0 MB")
    }

    val handleSyncToggle: (Boolean) -> Unit = { targetState ->
        if (targetState) {
            showSyncConfirmDialog = true
        } else {
            onSyncReadProgress(false)
        }
    }

    val settingsListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // ── 悬浮球设置专用状态与拖拽重排逻辑 (参照 CategoryListScreen 分类拖拽方式) ──
    val maxFloatingActions = FloatingAction.MAX_ACTIONS
    val enabledFloatingActions = remember { mutableStateListOf<FloatingAction>() }
    var draggedFloatingAction by remember { mutableStateOf<FloatingAction?>(null) }
    var floatingBallDragOrigin by remember { mutableFloatStateOf(0f) }
    var floatingBallDragDistance by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(floatingBallActions) {
        if (draggedFloatingAction == null) {
            enabledFloatingActions.clear()
            enabledFloatingActions.addAll(floatingBallActions.take(maxFloatingActions))
        }
    }

    fun moveFloatingBallItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in enabledFloatingActions.indices || toIndex !in enabledFloatingActions.indices) return
        enabledFloatingActions.add(toIndex, enabledFloatingActions.removeAt(fromIndex))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun moveDraggedFloatingBallItem() {
        val active = draggedFloatingAction ?: return
        val visible = settingsListState.layoutInfo.visibleItemsInfo
        val activeItem = visible.firstOrNull { it.key == "enabled-${active.name}" } ?: return
        val fromIndex = enabledFloatingActions.indexOf(active)
        if (fromIndex < 0) return
        val center = floatingBallDragOrigin + floatingBallDragDistance + activeItem.size / 2f
        val target = visible.firstOrNull { item ->
            val targetAction = enabledFloatingActions.firstOrNull { "enabled-${it.name}" == item.key }
            val targetIndex = if (targetAction != null) enabledFloatingActions.indexOf(targetAction) else -1
            targetIndex >= 0 && item.key != "enabled-${active.name}" &&
                center in item.offset.toFloat()..(item.offset + item.size).toFloat() &&
                ((targetIndex > fromIndex && center > item.offset + item.size / 2f) ||
                    (targetIndex < fromIndex && center < item.offset + item.size / 2f))
        }
        val targetIndex = if (target != null) {
            val targetAction = enabledFloatingActions.firstOrNull { "enabled-${it.name}" == target.key }
            if (targetAction != null) enabledFloatingActions.indexOf(targetAction) else -1
        } else {
            // 首尾越界兜底：拖拽中心若已越过首项顶部或末项底部，直接吸附到首位或末位
            val actionItems = visible.filter { v -> enabledFloatingActions.any { "enabled-${it.name}" == v.key } }
            val firstCat = actionItems.firstOrNull()
            val lastCat = actionItems.lastOrNull()
            when {
                firstCat != null && center < firstCat.offset && fromIndex > 0 -> 0
                lastCat != null && center > lastCat.offset + lastCat.size && fromIndex < enabledFloatingActions.lastIndex -> enabledFloatingActions.lastIndex
                else -> -1
            }
        }
        if (targetIndex in enabledFloatingActions.indices && targetIndex != fromIndex) {
            moveFloatingBallItem(fromIndex, targetIndex)
        }
    }

    IosLargeTitleScaffold(
        title = page?.title ?: "设置",
        onBack = onBack,
        showLargeTitle = false,
        backLabel = "",
        listState = settingsListState,
    ) {
        // ── 首页只列品目入口，具体设置项点进子页再展开；版本信息与退出账号在列表末尾 ──
        if (page == null) item("entries-general") {
            Spacer(Modifier.height(20.dp))
            IosGroupCard {
                SettingsEntryRow(
                    title = SettingsPage.Appearance.title,
                    badgeColor = Color(0xFFEEEDFC),
                    value = themeMode.label,
                    onClick = { onOpenPage(SettingsPage.Appearance) },
                ) { SettingAppearanceIcon(tint = Color(0xFF5856D6)) }
                IosDivider()
                SettingsEntryRow(
                    title = SettingsPage.Reading.title,
                    badgeColor = Color(0xFFFFF4E5),
                    value = when (feedDisplayMode) {
                        "graphic" -> "图文模式"
                        "no_excerpt" -> "无正文模式"
                        "text_only" -> "纯文模式"
                        else -> "小图模式"
                    },
                    onClick = { onOpenPage(SettingsPage.Reading) },
                ) { SettingBookIcon(tint = Color(0xFFFF9500)) }
                IosDivider()
                SettingsEntryRow(
                    title = SettingsPage.FloatingBall.title,
                    badgeColor = Color(0xFFF3E5F5),
                    value = if (floatingBallEnabled) "已开启" else "已关闭",
                    onClick = { onOpenPage(SettingsPage.FloatingBall) },
                ) { SettingFloatingBallIcon(tint = Color(0xFFAB47BC)) }
            }
        }

        if (page == null) item("entries-system") {
            Spacer(Modifier.height(20.dp))
            IosGroupCard {
                SettingsEntryRow(
                    title = SettingsPage.Notifications.title,
                    badgeColor = Color(0xFFFFF0F5),
                    value = if (backgroundNotification) "已开启" else "已关闭",
                    onClick = { onOpenPage(SettingsPage.Notifications) },
                ) { SettingBellIcon(tint = Color(0xFFFF2D55)) }
                IosDivider()
                SettingsEntryRow(
                    title = SettingsPage.Network.title,
                    badgeColor = Color(0xFFE3F5E8),
                    value = if (dohEnabled) "DoH 已开启" else "DoH 已关闭",
                    onClick = { onOpenPage(SettingsPage.Network) },
                ) { SettingDohIcon(tint = Color(0xFF34C759)) }
            }
        }

        // ── 1. 外观 ──
        if (page == SettingsPage.Appearance) item("group-appearance") {
            Spacer(Modifier.height(12.dp))
            ThemeStudioContent(
                skinPreset = skinPreset,
                onSkinPreset = onSkinPreset,
                themeMode = themeMode,
                onThemeMode = onThemeMode,
            )
        }

        // ── 2. 阅读与浏览 ──
        if (page == SettingsPage.Reading) item("group-reading") {
            SectionTitle("列表与阅读偏好")
            IosGroupCard {
                // 2.0 展现模式分段选择器（图文模式 / 小图模式 / 纯文模式）
                // [临时屏蔽] 强制使用无正文模式展示以避免爬取正文风险，保留代码供后续优化
                if (false) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingIconBadge(bgColor = if (IosTheme.colors.isDark) Color(0xFF2C2D30) else Color(0xFFF2F4F7)) {
                            SettingPictureIcon(tint = if (IosTheme.colors.isDark) Color.White else Color(0xFF181818))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("信息流展现样式", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Medium)
                            val desc = when (feedDisplayMode) {
                                "graphic" -> "图文模式：微博风格大卡片，大图与三栏互动"
                                "no_excerpt" -> "无正文模式：微博式上下排版，聚焦标题与缩略图"
                                "text_only" -> "纯文模式：极客纯文字排版，无图超省流"
                                else -> "小图模式：经典双列排版，右侧小图，紧凑舒适"
                            }
                            Text(
                                text = desc,
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 四胶囊分段选择器（素雅黑白高亮，包含无正文模式）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (IosTheme.colors.isDark) Color(0xFF2C2D30) else Color(0xFFF0F2F5))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "no_excerpt" to "无正文模式",
                            "graphic" to "图文模式",
                            "small_image" to "小图模式",
                            "text_only" to "纯文模式",
                        ).forEach { (modeKey, label) ->
                            val isSelected = feedDisplayMode == modeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) {
                                            if (IosTheme.colors.isDark) Color(0xFFFFFFFF) else Color(0xFF181818)
                                        } else Color.Transparent
                                    )
                                    .clickable {
                                        onFeedDisplayMode(modeKey)
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isSelected) {
                                        if (IosTheme.colors.isDark) Color(0xFF181818) else Color.White
                                    } else IosTheme.colors.label,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                IosDivider()
                }

                // 2.1 树形查看话题
                IosRow(onClick = { onTreeView(!treeView) }) {
                    SettingIconBadge(bgColor = Color(0xFFE8F3FF)) {
                        SettingTreeIcon(tint = Color(0xFF007AFF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("树形查看话题", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "按回复树状排列，关闭后恢复顺序楼层",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(checked = treeView, onCheckedChange = onTreeView)
                }

                IosDivider()

                // 2.14 显示话题未读蓝点
                IosRow(onClick = { onShowTopicUnreadDot(!showTopicUnreadDot) }) {
                    SettingIconBadge(bgColor = Color(0xFFE5F6FF)) {
                        SettingUnreadDotIcon(tint = Color(0xFF007AFF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("显示话题未读蓝点", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "列表未读话题阅读后自动淡出",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(checked = showTopicUnreadDot, onCheckedChange = onShowTopicUnreadDot)
                }

                if (showTopicUnreadDot) {
                    IosDivider(startIndent = 48.dp)
                    // 子选项 1：显示在图像右上角 (与评论选项1「显示在头像上」对齐)
                    IosRow(
                        modifier = Modifier.padding(start = 24.dp),
                        onClick = { onTopicUnreadDotPosition(TopicUnreadDotPosition.Avatar) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "显示在图像右上角",
                                style = IosTheme.type.body.copy(fontSize = 14.sp),
                                fontWeight = if (topicUnreadDotPosition == TopicUnreadDotPosition.Avatar) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "在作者头像右上角显示角标，与评论风格对齐",
                                style = IosTheme.type.footnote.copy(fontSize = 11.5.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (topicUnreadDotPosition == TopicUnreadDotPosition.Avatar) {
                            Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }

                    IosDivider(startIndent = 48.dp)
                    // 子选项 2：显示在话题最开始左侧 (老版本蓝点显示方式)
                    IosRow(
                        modifier = Modifier.padding(start = 24.dp),
                        onClick = { onTopicUnreadDotPosition(TopicUnreadDotPosition.TitleLeft) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "显示在话题最开始左侧",
                                style = IosTheme.type.body.copy(fontSize = 14.sp),
                                fontWeight = if (topicUnreadDotPosition == TopicUnreadDotPosition.TitleLeft) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "老版本显示方式，在话题标题最左侧显示未读蓝点",
                                style = IosTheme.type.footnote.copy(fontSize = 11.5.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (topicUnreadDotPosition == TopicUnreadDotPosition.TitleLeft) {
                            Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                IosDivider()

                // 2.15 显示评论未读蓝点
                IosRow(onClick = { onShowUnreadDot(!showUnreadDot) }) {
                    SettingIconBadge(bgColor = Color(0xFFE5F6FF)) {
                        SettingUnreadDotIcon(tint = Color(0xFF007AFF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("显示评论未读蓝点", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "未读评论阅读后自动淡出",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(checked = showUnreadDot, onCheckedChange = onShowUnreadDot)
                }

                if (showUnreadDot) {
                    IosDivider(startIndent = 48.dp)
                    // 子选项 1：显示在图像上 (默认选项；与2互斥)
                    IosRow(
                        modifier = Modifier.padding(start = 24.dp),
                        onClick = { onUnreadDotPosition(UnreadDotPosition.Avatar) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "显示在头像上",
                                style = IosTheme.type.body.copy(fontSize = 14.sp),
                                fontWeight = if (unreadDotPosition == UnreadDotPosition.Avatar) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "在用户头像右上角显示角标，与列表风格一致",
                                style = IosTheme.type.footnote.copy(fontSize = 11.5.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (unreadDotPosition == UnreadDotPosition.Avatar) {
                            Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }

                    IosDivider(startIndent = 48.dp)
                    // 子选项 2：显示在楼层右边 (现有显示模式；打开后1自动关闭)
                    IosRow(
                        modifier = Modifier.padding(start = 24.dp),
                        onClick = { onUnreadDotPosition(UnreadDotPosition.FloorRight) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "显示在楼层右边",
                                style = IosTheme.type.body.copy(fontSize = 14.sp),
                                fontWeight = if (unreadDotPosition == UnreadDotPosition.FloorRight) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                "在评论楼层号右侧显示未读小蓝点",
                                style = IosTheme.type.footnote.copy(fontSize = 11.5.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (unreadDotPosition == UnreadDotPosition.FloorRight) {
                            Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                IosDivider()

                // 2.2 同步楼层与阅读时长
                IosRow(onClick = { handleSyncToggle(!syncReadProgress) }) {
                    SettingIconBadge(bgColor = Color(0xFFF0EDFF)) {
                        SettingClockIcon(tint = Color(0xFF6C5CE7))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("同步楼层与阅读时长", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "在后台实时记录阅读位置与社区足迹",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(checked = syncReadProgress, onCheckedChange = handleSyncToggle)
                }

                IosDivider()

                // 2.3 自动感知新话题频率
                IosRow(onClick = { showAutoRefreshIntervalDialog = true }) {
                    SettingIconBadge(bgColor = Color(0xFFE5F9E7)) {
                        SettingBroomIcon(tint = Color(0xFF34C759))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动感知新话题", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "停留在列表时静默探测并提示新话题",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text(
                        "${autoRefreshInterval.label} ›",
                        style = IosTheme.type.subheadline.copy(fontSize = 13.sp),
                        color = if (autoRefreshInterval != AutoRefreshInterval.OFF) IosTheme.colors.accent else IosTheme.colors.secondaryLabel,
                    )
                }

                IosDivider()

                // 2.35 ?????????
                IosRow(onClick = { onAutoReadClipboard(!autoReadClipboard) }) {
                    SettingIconBadge(bgColor = Color(0xFFFFF3E0)) {
                        SettingClipboardIcon(tint = Color(0xFFFF9800))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("读取剪贴板话题链接", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "切回应用时检测剪贴板中的 linux.do 链接并提示直达",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(checked = autoReadClipboard, onCheckedChange = onAutoReadClipboard)
                }

                if (syncReadProgress && readingStatus != null) {
                    IosDivider()
                    IosRow(onClick = { if (canRetryReading) onRetryReading() }) {
                        Text(readingStatus, style = IosTheme.type.footnote, modifier = Modifier.weight(1f))
                        if (canRetryReading) Text("继续", color = IosTheme.colors.accent)
                    }
                }
            }
        }

        // ── 2.5 辅助悬浮按钮设置 ──
        if (page == SettingsPage.FloatingBall) {
            item("floating-ball-section-title") {
                SectionTitle("辅助功能")
            }

            item("floating-ball-toggle") {
                IosGroupCard {
                    IosRow(onClick = { onFloatingBallEnabled(!floatingBallEnabled) }) {
                        SettingIconBadge(bgColor = Color(0xFFF3E5F5)) {
                            SettingFloatingBallIcon(tint = Color(0xFFAB47BC))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("开启辅助悬浮球", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                            Text(
                                "单功能直接点击直达，多功能伞型展开；可拖动吸附贴边并记录坐标",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        IosSwitch(
                            checked = floatingBallEnabled,
                            onCheckedChange = onFloatingBallEnabled,
                        )
                    }
                }
            }

            if (floatingBallEnabled) {
                // 1. 已启用功能标题栏
                item("floating-ball-enabled-header") {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已启用功能 (${enabledFloatingActions.size}/$maxFloatingActions)",
                            style = IosTheme.type.headline.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            color = IosTheme.colors.label,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "长按 ☰ 拖动可自定义排序",
                            style = IosTheme.type.caption.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                        )
                        if (enabledFloatingActions.toList() != FloatingAction.DEFAULT_ACTIONS) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "恢复默认",
                                style = IosTheme.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                color = IosTheme.colors.accent,
                                modifier = Modifier.clickable {
                                    enabledFloatingActions.clear()
                                    enabledFloatingActions.addAll(FloatingAction.DEFAULT_ACTIONS)
                                    onFloatingBallActions(FloatingAction.DEFAULT_ACTIONS)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // 2. 已启用功能各项 (参照 CategoryListScreen 分类卡片拖拽方式: 独立动画条目 + graphicsLayer 平滑位移 + 相交交换)
                itemsIndexed(
                    items = enabledFloatingActions,
                    key = { _, action -> "enabled-${action.name}" }
                ) { index, action ->
                    val dragging = draggedFloatingAction == action
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = IosMetrics.screenPadding, vertical = 2.5.dp)
                            .animateItem(placementSpec = if (dragging) null else tween(180))
                            .zIndex(if (dragging) 2f else 0f)
                            .graphicsLayer {
                                val currentOffset = settingsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "enabled-${action.name}" }?.offset
                                translationY = if (dragging && currentOffset != null) floatingBallDragOrigin + floatingBallDragDistance - currentOffset else 0f
                                scaleX = if (dragging) 1.025f else 1f
                                scaleY = if (dragging) 1.025f else 1f
                                shadowElevation = if (dragging) 10.dp.toPx() else 0f
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dragging) IosTheme.colors.card.copy(alpha = 0.96f) else IosTheme.colors.card)
                            .pointerInput(action.name) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val item = settingsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "enabled-${action.name}" }
                                        if (item != null) {
                                            floatingBallDragOrigin = item.offset.toFloat()
                                            floatingBallDragDistance = 0f
                                            draggedFloatingAction = action
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        if (draggedFloatingAction == action) {
                                            change.consume()
                                            floatingBallDragDistance += amount.y
                                            moveDraggedFloatingBallItem()
                                        }
                                    },
                                    onDragEnd = {
                                        if (draggedFloatingAction != null) {
                                            draggedFloatingAction = null
                                            onFloatingBallActions(enabledFloatingActions.toList())
                                        }
                                    },
                                    onDragCancel = {
                                        draggedFloatingAction = null
                                        enabledFloatingActions.clear()
                                        enabledFloatingActions.addAll(floatingBallActions.take(maxFloatingActions))
                                    }
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 红色减号按钮 ⊖
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935))
                                    .clickable {
                                        if (enabledFloatingActions.size > 1) {
                                            enabledFloatingActions.remove(action)
                                            onFloatingBallActions(enabledFloatingActions.toList())
                                        } else {
                                            Toast.makeText(context, "悬浮球至少需要保留 1 项功能", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 10.dp, height = 2.5.dp)
                                        .background(Color.White, RoundedCornerShape(1.dp))
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            // 功能专属矢量图标徽章
                            FloatingActionSettingBadge(action = action)

                            Spacer(Modifier.width(12.dp))

                            // 标题与副标题
                            Column(Modifier.weight(1f)) {
                                Text(
                                    action.title,
                                    style = IosTheme.type.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                                    color = IosTheme.colors.label,
                                )
                                Text(
                                    action.description,
                                    style = IosTheme.type.caption.copy(fontSize = 12.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }

                            // 拖拽手柄 ☰ (三条杠)
                            Box(
                                modifier = Modifier.size(width = 34.dp, height = 36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                DragHandleIcon(tint = if (dragging) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel)
                            }
                        }
                    }
                }

                // 3. 未启用功能标题栏
                item("floating-ball-disabled-header") {
                    val isFull = enabledFloatingActions.size >= maxFloatingActions
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "未启用功能 (点击 ⊕ 添加)",
                            style = IosTheme.type.headline.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            color = IosTheme.colors.label,
                        )
                        Spacer(Modifier.weight(1f))
                        if (isFull) {
                            Text(
                                "已达到上限 $maxFloatingActions/$maxFloatingActions",
                                style = IosTheme.type.caption.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                            )
                        } else {
                            Text(
                                "还可添加 ${maxFloatingActions - enabledFloatingActions.size} 项",
                                style = IosTheme.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                color = Color(0xFF43A047),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // 4. 未启用功能卡片
                item("floating-ball-disabled-card") {
                    val disabledActions = FloatingAction.entries.filter { it !in enabledFloatingActions }
                    val isFull = enabledFloatingActions.size >= maxFloatingActions
                    IosGroupCard {
                        disabledActions.forEachIndexed { index, action ->
                            if (index > 0) {
                                IosDivider(startIndent = 58.dp)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!isFull) {
                                            enabledFloatingActions.add(action)
                                            onFloatingBallActions(enabledFloatingActions.toList())
                                        } else {
                                            Toast.makeText(context, "已达到上限 $maxFloatingActions/$maxFloatingActions，请先移除上方功能", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 绿色加号按钮 ⊕ (达到上限置灰)
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (!isFull) Color(0xFF4CAF50).copy(alpha = 0.12f) else Color(0xFFEEEEEE)
                                        )
                                        .border(
                                            width = 1.2.dp,
                                            color = if (!isFull) Color(0xFF4CAF50).copy(alpha = 0.6f) else Color(0xFFBDBDBD),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val plusColor = if (!isFull) Color(0xFF43A047) else Color(0xFFBDBDBD)
                                    Box(
                                        modifier = Modifier
                                            .size(width = 10.dp, height = 2.dp)
                                            .background(plusColor, RoundedCornerShape(1.dp))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(width = 2.dp, height = 10.dp)
                                            .background(plusColor, RoundedCornerShape(1.dp))
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                // 功能专属矢量图标徽章
                                FloatingActionSettingBadge(action = action)

                                Spacer(Modifier.width(12.dp))

                                // 标题与副标题
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        action.title,
                                        style = IosTheme.type.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                                        color = IosTheme.colors.label,
                                    )
                                    Text(
                                        action.description,
                                        style = IosTheme.type.caption.copy(fontSize = 12.sp),
                                        color = IosTheme.colors.secondaryLabel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 3. 通知与后台保活 ──
        if (page == SettingsPage.Notifications) item("group-notifications") {
            SectionTitle("通知")
            IosGroupCard {
                // 3.0 后台通知总开关
                IosRow(onClick = {
                    val next = !backgroundNotification
                    if (next) {
                        ensureNotificationPermission()
                        NotificationHelper.promptIgnoreBatteryOptimizationsIfNeeded(context)
                    }
                    onBackgroundNotification(next)
                }) {
                    SettingIconBadge(bgColor = Color(0xFFFFF0F5)) {
                        SettingInfoIcon(tint = Color(0xFFFF2D55))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("允许后台通知", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                        Text(
                            "亮屏实时推送，息屏智能切换系统低功耗唤醒",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(
                        checked = backgroundNotification,
                        onCheckedChange = { next ->
                            if (next) {
                                ensureNotificationPermission()
                                NotificationHelper.promptIgnoreBatteryOptimizationsIfNeeded(context)
                            }
                            onBackgroundNotification(next)
                        },
                    )
                }

                if (backgroundNotification) {
                    IosDivider()

                    // 系统通知权限与横幅直达
                    val isSystemNotificationEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                    if (!isSystemNotificationEnabled) {
                        IosRow(onClick = { NotificationHelper.openNotificationSettings(context) }) {
                            SettingIconBadge(bgColor = Color(0xFFFFEBEE)) {
                                SettingInfoIcon(tint = Color(0xFFD32F2F))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("系统通知权限未开启", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal, color = Color(0xFFD32F2F))
                                Text(
                                    "手机系统屏蔽了此应用通知，点击前往开启通知总开关与横幅提醒",
                                    style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            Text("去开启 ›", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = Color(0xFFD32F2F))
                        }
                    } else {
                        IosRow(onClick = { NotificationHelper.openNotificationSettings(context) }) {
                            SettingIconBadge(bgColor = Color(0xFFE8F5E9)) {
                                SettingTreeIcon(tint = Color(0xFF4CAF50))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("系统通知与横幅设置", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                                Text(
                                    "管理顶部横幅浮窗、锁屏显示与铃声震动，也可关闭常驻静音图标",
                                    style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            Text("已开启 ›", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = IosTheme.colors.secondaryLabel)
                        }
                    }

                    IosDivider()

                    // 通知推送范围
                    IosRow(onClick = { showNotificationScopeDialog = true }) {
                        SettingIconBadge(bgColor = Color(0xFFE8EAF6)) {
                            SettingTreeIcon(tint = Color(0xFF3F51B5))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("通知推送范围", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                            Text(
                                notificationScope.description,
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Text(
                            "${notificationScope.label} ›",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.accent,
                        )
                    }

                    IosDivider()

                    // 测试通知推送（全场景实测实验室）
                    IosRow(onClick = {
                        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                            Toast.makeText(context, "系统通知权限尚未开启，请在设置中允许通知", Toast.LENGTH_LONG).show()
                            NotificationHelper.openNotificationSettings(context)
                        } else {
                            showTestPushLabDialog = true
                        }
                    }) {
                        SettingIconBadge(bgColor = Color(0xFFE1F5FE)) {
                            SettingInfoIcon(tint = Color(0xFF03A9F4))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("测试通知推送 (全场景实测)", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                            Text(
                                "验证息屏浅睡、Doze 闹钟硬件唤醒、亮屏抢跑、单帖正文拉取与桌面角标增减",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Text("实测实验室 ›", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = IosTheme.colors.accent)
                    }
                }
            }

            if (backgroundNotification) {
                // ── 3.1 专门的休眠状态推送保活整体区块（带左上角小标识） ──
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "休眠推送保活",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF9500).copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "休眠状态推送建议开启",
                            style = IosTheme.type.caption.copy(fontSize = 11.sp),
                            color = Color(0xFFFF9500),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                IosGroupCard {
                    // 1. 电池优化直达配置
                    IosRow(onClick = {
                        NotificationHelper.requestIgnoreBatteryOptimizations(context)
                    }) {
                        SettingIconBadge(bgColor = Color(0xFFFFF8E1)) {
                            SettingBatteryIcon(tint = Color(0xFFFFB300))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("电池优化 (设为不允许)", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                            Text(
                                if (isBatteryOptimizedIgnored) "已加入电池优化白名单，息屏不被系统睡眠冻结" else "设为“不允许 / 无限制”，防止息屏被系统休眠挂起",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        if (isBatteryOptimizedIgnored) {
                            Text("已允许 ✓", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = Color(0xFF34C759), fontWeight = FontWeight.Medium)
                        } else {
                            Text("去设置 ›", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = Color(0xFFFF9500), fontWeight = FontWeight.Normal)
                        }
                    }

                    IosDivider()

                    // 2. 应用启动管理直达配置
                    IosRow(onClick = {
                        Toast.makeText(context, "请在系统中将本应用设为“手动管理”，允许自启动与后台活动", Toast.LENGTH_LONG).show()
                        NotificationHelper.openAutoStartSettings(context)
                    }) {
                        SettingIconBadge(bgColor = Color(0xFFF3E5F5)) {
                            SettingRocketIcon(tint = Color(0xFF9C27B0))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("应用启动管理 (后台自启)", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                            Text(
                                "设为手动管理：允许自启动与后台活动，防止灭屏被杀",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Text("去配置 ›", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = IosTheme.colors.accent)
                    }

                    IosDivider()

                    // 3. 休眠网络与电池设置
                    IosRow(onClick = {
                        Toast.makeText(context, "请检查是否开启“休眠时始终保持网络连接”，避免息屏断网", Toast.LENGTH_LONG).show()
                        NotificationHelper.openPowerSettings(context)
                    }) {
                        SettingIconBadge(bgColor = Color(0xFFE0F7FA)) {
                            SettingClockIcon(tint = Color(0xFF00ACC1))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("休眠保持网络连接", style = IosTheme.type.body.copy(fontSize = 14.5.sp), fontWeight = FontWeight.Normal)
                            Text(
                                "在系统电池高级设置中开启“休眠时始终保持网络连接”，避免断网",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Text("去检查 ›", style = IosTheme.type.subheadline.copy(fontSize = 13.sp), color = IosTheme.colors.secondaryLabel)
                    }
                }

                Text(
                    text = "💡 提示：若仅需亮屏使用时接收通知，上述三项可保持系统默认以最大化省电；若需手机息屏深睡下稳定接收，建议全部配置并在多任务后台锁定本应用。",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }

        // ── 4.5 DoH 安全直连 ──
        if (page == SettingsPage.Network) item("group-doh") {
            var showAddServerDialog by remember { mutableStateOf(false) }
            var editingServer by remember { mutableStateOf<DohServer?>(null) }
            var showResetConfirmDialog by remember { mutableStateOf(false) }

            val isVpnActive = remember(context) {
                runCatching {
                    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    cm?.allNetworks?.any { network ->
                        cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                    } ?: false
                }.getOrDefault(false)
            }

            SectionTitle("DoH 安全直连")
            IosGroupCard {
                // VPN 开启提示
                if (isVpnActive) {
                    IosRow(onClick = {}) {
                        SettingIconBadge(bgColor = if (dohForceOverProxy) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)) {
                            SettingInfoIcon(tint = if (dohForceOverProxy) Color(0xFF4CAF50) else Color(0xFFFF9500))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (dohForceOverProxy) "已检测到 VPN (已强制保持 DoH)" else "已检测到 VPN / 系统代理",
                                style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = if (dohForceOverProxy) Color(0xFF4CAF50) else Color(0xFFFF9500),
                            )
                            Text(
                                if (dohForceOverProxy) "已保持原生 DoH + ECH 隧道，可防止代理分流规则设为直连时的明文 SNI 阻断"
                                else "若代理分流规则将 L 站设为了直连，可能导致打不开；建议开启下方「代理环境保持 DoH」",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    IosDivider()
                }

                // 开关行
                IosRow(onClick = {}) {
                    SettingIconBadge(bgColor = Color(0xFFE3F5E8)) {
                        SettingDohIcon(tint = Color(0xFF34C759))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "DNS over HTTPS",
                            style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (dohEnabled) "已开启 · 智能双轨调度 (Anycast直连/DoH解析+ECH)" else "关闭 · 强制本地直连 (Anycast) + 内置ECH",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    IosSwitch(checked = dohEnabled, onCheckedChange = onDohEnabled)
                }

                if (dohEnabled) {
                    IosDivider()

                    // 自动故障切换
                    IosRow(onClick = {}) {
                        Text(
                            "自动故障切换",
                            style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(checked = dohAutoFailover, onCheckedChange = onDohAutoFailover)
                    }

                    IosDivider()

                    // 智能定时测速切优
                    IosRow(onClick = {}) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "智能定时测速切优",
                                style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            )
                            Text(
                                "每 10 分钟自动监测各节点延迟，智能切换至最快健康节点",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        IosSwitch(checked = dohAutoOptimize, onCheckedChange = onDohAutoOptimize)
                    }

                    IosDivider()

                    // 代理环境保持 DoH
                    IosRow(onClick = {}) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "代理环境保持 DoH",
                                style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            )
                            Text(
                                "开启 VPN/代理且分流规则直连 L 站时强制保持 DoH，防止明文 SNI 阻断",
                                style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        IosSwitch(checked = dohForceOverProxy, onCheckedChange = onDohForceOverProxy)
                    }

                    IosDivider()

                    // 智能双轨调度状态卡片
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "智能双轨调度",
                                        style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = IosTheme.colors.label,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val isDirect = smartRouteMode == org.linuxdo.android.net.NetworkRouteMode.DIRECT
                                    val badgeBg = if (isDirect) Color(0xFFE8F5E9) else Color(0xFFE3F2FD)
                                    val badgeTextColor = if (isDirect) Color(0xFF2E7D32) else Color(0xFF1565C0)
                                    Box(
                                        modifier = Modifier
                                            .background(badgeBg, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = smartRouteMode.shortLabel,
                                            style = IosTheme.type.footnote.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                            color = badgeTextColor,
                                        )
                                    }
                                }
                                Text(
                                    "真实 TLS+ECH 握手延迟智能仲裁，防频繁切换风控",
                                    style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .clickable(enabled = !isSmartEvaluating, onClick = onEvaluateSmartRoute)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isSmartEvaluating) {
                                    IosActivityIndicator(diameter = 14.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    if (isSmartEvaluating) "探测中..." else "测速评估",
                                    style = IosTheme.type.footnote.copy(fontSize = 13.sp),
                                    color = if (isSmartEvaluating) IosTheme.colors.secondaryLabel else IosTheme.colors.accent,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(IosTheme.colors.fieldBackground, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "本地直连 (Anycast)",
                                    style = IosTheme.type.footnote.copy(fontSize = 11.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                                Text(
                                    text = if (smartDirectLatency != null) "${smartDirectLatency} ms" else if (isSmartEvaluating) "测速中..." else "--",
                                    style = IosTheme.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (smartDirectLatency != null && smartDirectLatency < 100) Color(0xFF2E7D32) else IosTheme.colors.label,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(IosTheme.colors.separator)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "DoH 动态解析",
                                    style = IosTheme.type.footnote.copy(fontSize = 11.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                                Text(
                                    text = if (smartDohLatency != null) "${smartDohLatency} ms" else if (isSmartEvaluating) "测速中..." else "--",
                                    style = IosTheme.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (smartDohLatency != null && smartDohLatency < 150) Color(0xFF1565C0) else IosTheme.colors.label,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }

                        if (smartRouteSummary.isNotBlank()) {
                            Text(
                                text = "仲裁状态：$smartRouteSummary",
                                style = IosTheme.type.footnote.copy(fontSize = 11.5.sp),
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                            )
                        }
                    }

                    IosDivider()

                    // 节点列表标题与一键测速栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "节点列表",
                            style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = IosTheme.colors.label,
                        )
                        Row(
                            modifier = Modifier
                                .clickable(onClick = onTestAllDohServers)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isTestingAllDoh) {
                                IosActivityIndicator(diameter = 14.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                "⚡ 一键测速所有节点",
                                style = IosTheme.type.subheadline.copy(fontSize = 13.sp),
                                color = IosTheme.colors.accent,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    IosDivider()

                    // 节点列表
                    dohServers.forEachIndexed { idx, server ->
                        if (idx > 0) IosDivider(startIndent = 42.dp)
                        val latencyMs = dohLatencies[server.id]
                        val isSelected = server.url == selectedDohServerUrl

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = { onDohServerSelected(server) })
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 选中对勾或空白占位
                            Box(
                                modifier = Modifier.width(26.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (isSelected) {
                                    Text(
                                        "✓",
                                        style = IosTheme.type.body,
                                        color = Color(0xFF34C759),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                    )
                                }
                            }

                            // 节点名称与 URL
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            ) {
                                Text(
                                    server.name,
                                    style = IosTheme.type.body.copy(fontSize = 13.5.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = IosTheme.colors.label,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    server.url,
                                    style = IosTheme.type.caption.copy(fontSize = 11.5.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }

                            // 右侧：延迟胶囊 + 编辑按钮 + 删除按钮
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // 延迟胶囊
                                if (latencyMs != null) {
                                    val (bgColor, textColor, text) = when {
                                        latencyMs < 0L -> Triple(IosTheme.colors.pressedHighlight, IosTheme.colors.secondaryLabel, "超时")
                                        latencyMs < 100L -> Triple(Color(0x3330D158), Color(0xFF30D158), "${latencyMs}ms")
                                        else -> Triple(IosTheme.colors.pressedHighlight, IosTheme.colors.label, "${latencyMs}ms")
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(bgColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text,
                                            style = IosTheme.type.caption,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textColor,
                                        )
                                    }
                                } else if (isTestingAllDoh || dohLatencies.containsKey(server.id)) {
                                    IosActivityIndicator(diameter = 14.dp)
                                }

                                // 编辑按钮（小齿轮）
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(IosTheme.colors.pressedHighlight, RoundedCornerShape(8.dp))
                                        .clickable { editingServer = server },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    DohGearIcon(tint = IosTheme.colors.accent)
                                }

                                // 删除按钮（小垃圾桶）
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(IosTheme.colors.pressedHighlight, RoundedCornerShape(8.dp))
                                        .clickable { onDeleteDohServer(server) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    DohTrashIcon(tint = IosTheme.colors.destructive)
                                }
                            }
                        }
                    }

                    IosDivider()

                    // 添加自定义节点与恢复默认
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "+ 添加自定义节点",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.accent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { showAddServerDialog = true },
                        )
                        Text(
                            "恢复默认预设",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.clickable { showResetConfirmDialog = true },
                        )
                    }
                }
            }

            // 添加自定义节点对话框
            if (showAddServerDialog) {
                var newServerUrl by remember { mutableStateOf("") }
                var newServerName by remember { mutableStateOf("") }
                Dialog(onDismissRequest = { showAddServerDialog = false }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(IosTheme.colors.card, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("添加自定义 DoH 节点", style = IosTheme.type.headline, fontWeight = FontWeight.SemiBold)
                        Text("服务器 URL（必须以 https:// 开头）", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                        BasicTextField(
                            value = newServerUrl,
                            onValueChange = { newServerUrl = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(IosTheme.colors.fieldBackground, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            singleLine = true,
                            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            cursorBrush = SolidColor(IosTheme.colors.label),
                        )
                        Text("节点名称（可选）", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                        BasicTextField(
                            value = newServerName,
                            onValueChange = { newServerName = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(IosTheme.colors.fieldBackground, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            singleLine = true,
                            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                            cursorBrush = SolidColor(IosTheme.colors.label),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.TextButton(
                                onClick = { showAddServerDialog = false },
                                modifier = Modifier.weight(1f),
                            ) { Text("取消", color = IosTheme.colors.secondaryLabel) }
                            androidx.compose.material3.Button(
                                onClick = {
                                    val url = newServerUrl.trim()
                                    if (url.startsWith("https://")) {
                                        onAddCustomDohServer(url, newServerName.trim().ifBlank { url })
                                        showAddServerDialog = false
                                    } else {
                                        Toast.makeText(context, "URL 必须以 https:// 开头", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("添加") }
                        }
                    }
                }
            }

            // 编辑节点对话框
            editingServer?.let { server ->
                var editUrl by remember(server.id) { mutableStateOf(server.url) }
                var editName by remember(server.id) { mutableStateOf(server.name) }
                Dialog(onDismissRequest = { editingServer = null }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(IosTheme.colors.card, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("编辑 DoH 节点", style = IosTheme.type.headline, fontWeight = FontWeight.SemiBold)
                        Text("服务器 URL（必须以 https:// 开头）", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                        BasicTextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(IosTheme.colors.fieldBackground, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            singleLine = true,
                            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            cursorBrush = SolidColor(IosTheme.colors.label),
                        )
                        Text("节点名称", style = IosTheme.type.footnote, color = IosTheme.colors.secondaryLabel)
                        BasicTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(IosTheme.colors.fieldBackground, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            singleLine = true,
                            textStyle = IosTheme.type.body.copy(color = IosTheme.colors.label),
                            cursorBrush = SolidColor(IosTheme.colors.label),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.TextButton(
                                onClick = { editingServer = null },
                                modifier = Modifier.weight(1f),
                            ) { Text("取消", color = IosTheme.colors.secondaryLabel) }
                            androidx.compose.material3.Button(
                                onClick = {
                                    val url = editUrl.trim()
                                    if (url.startsWith("https://")) {
                                        onUpdateDohServer(server, url, editName.trim().ifBlank { url })
                                        editingServer = null
                                    } else {
                                        Toast.makeText(context, "URL 必须以 https:// 开头", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("保存") }
                        }
                    }
                }
            }

            // 恢复默认预设确认对话框
            if (showResetConfirmDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showResetConfirmDialog = false },
                    title = { Text("恢复默认预设") },
                    text = { Text("确定要将 DoH 节点列表恢复为官方默认预设（始皇DoH、社区优选DoH）吗？此操作将清除自定义节点与修改。") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                onResetDohServers()
                                showResetConfirmDialog = false
                            }
                        ) {
                            Text("恢复默认", color = IosTheme.colors.accent)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showResetConfirmDialog = false }
                        ) {
                            Text("取消")
                        }
                    },
                )
            }
        }

        // ── 4. 网络与缓存 ──
        if (page == SettingsPage.Network) item("group-network") {
            // 遍历缓存目录有 I/O 开销，放在卡片内计算，避免每个设置子页都扫一遍。
            var cacheSizeText by remember { mutableStateOf(calcCacheSize()) }
            SectionTitle("缓存与自愈")
            IosGroupCard {
                // 3.1 清理本地缓存
                IosRow(onClick = {
                    runCatching {
                        listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir).forEach { dir ->
                            dir.deleteRecursively()
                        }
                    }
                    cacheSizeText = calcCacheSize()
                    Toast.makeText(context, "已清理图片与网络临时缓存，账号与已读状态不受影响", Toast.LENGTH_SHORT).show()
                }) {
                    SettingIconBadge(bgColor = Color(0xFFFFF4E5)) {
                        SettingBroomIcon(tint = Color(0xFFFF9500))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "清理本地缓存",
                            style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            fontWeight = FontWeight.Normal,
                        )
                        Text(
                            "仅清理图片与网络临时资源，不影响账号和已读状态",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text(
                        "$cacheSizeText ›",
                        style = IosTheme.type.subheadline.copy(fontSize = 13.sp),
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
                IosDivider()
                // 3.2 一键修复网络与会话
                IosRow(onClick = {
                    if (!isSelfHealing) {
                        onSelfHealNetwork()
                        cacheSizeText = calcCacheSize()
                    }
                }) {
                    SettingIconBadge(bgColor = Color(0xFFE8F5E9)) {
                        SettingSyncIcon(tint = Color(0xFF34C759))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "一键修复网络与会话",
                            style = IosTheme.type.body.copy(fontSize = 14.5.sp),
                            fontWeight = FontWeight.Normal,
                        )
                        Text(
                            "清理过期盾凭据、重置连接池并对齐会话，不影响登录与已读状态",
                            style = IosTheme.type.footnote.copy(fontSize = 12.sp),
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (isSelfHealing) {
                        IosActivityIndicator(diameter = 16.dp)
                    } else {
                        Text(
                            "自愈 ›",
                            style = IosTheme.type.subheadline.copy(fontSize = 13.sp),
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        // ── 3. 关于与维护 ──
        if (page == null) item("group-about") {
            Spacer(Modifier.height(20.dp))
            IosGroupCard {
                // 3.1 版本信息与检查更新 (合并)
                IosRow(onClick = {
                    onCheckUpdate()
                    val now = System.currentTimeMillis()
                    if (now - lastDevClickTime > 2000L) {
                        devClickCount = 1
                    } else {
                        devClickCount++
                    }
                    lastDevClickTime = now
                    if (devClickCount >= 7) {
                        devOptionsUnlocked = !devOptionsUnlocked
                        prefs.edit().putBoolean("dev_options_enabled", devOptionsUnlocked).apply()
                        val msg = if (devOptionsUnlocked) "开发者选项已开启" else "开发者选项已隐藏"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        devClickCount = 0
                    } else if (devClickCount >= 4) {
                        val remaining = 7 - devClickCount
                        Toast.makeText(context, "再点击 $remaining 次开启开发者选项", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    SettingIconBadge(bgColor = Color(0xFFE8F3FF)) {
                        SettingInfoIcon(tint = Color(0xFF007AFF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "版本信息",
                        style = IosTheme.type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(IosTheme.colors.fieldBackground)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "v${updateState.currentVersion}",
                                    style = IosTheme.type.caption.copy(fontSize = 12.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            if (updateState.updateInfo != null) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-3).dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF3B30)),
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        if (updateState.manualChecking) {
                            IosActivityIndicator(diameter = 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "检查中...",
                                style = IosTheme.type.subheadline.copy(fontSize = 13.5.sp),
                                color = IosTheme.colors.secondaryLabel,
                            )
                        } else {
                            Text(
                                if (updateState.updateInfo != null) "可更新" else "已是最新",
                                style = IosTheme.type.subheadline.copy(fontSize = 13.5.sp),
                                color = if (updateState.updateInfo != null) Color(0xFFFF3B30) else IosTheme.colors.secondaryLabel,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("›", color = IosTheme.colors.tertiaryLabel, style = IosTheme.type.title3)
                    }
                }

                if (devOptionsUnlocked) {
                    IosDivider()

                    // 3.3 开发者选项与诊断
                    SettingsEntryRow(
                        title = "开发者选项与诊断",
                        badgeColor = Color(0xFFF0F0F2),
                        onClick = onDiagnostics,
                    ) { SettingTerminalIcon(tint = Color(0xFF3A3A3C)) }
                }
            }
        }

        // ── 4. 底部独立退出账号卡片 ──
        if (page == null) item("logout-card") {
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IosMetrics.screenPadding)
                    .clip(RoundedCornerShape(14.dp))
                    .background(IosTheme.colors.card)
                    .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .clickable { showLogoutConfirmDialog = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    SettingLogoutIcon(tint = Color(0xFFFF3B30))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "退出当前账号",
                        style = IosTheme.type.body,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF3B30),
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }

    if (showSyncConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSyncConfirmDialog = false },
            title = { Text("开启阅读进度同步") },
            text = {
                Text(
                    "开启后将向服务端上报各楼层阅读进度与阅读时长（消除网页端未读小蓝点）。\n\n" +
                        "需要站点安全验证时会显示验证页，确认上报成功后回到当前话题。取消验证会暂停同步，可在设置中继续。\n\n" +
                        "话题访问记录不受此开关控制。"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showSyncConfirmDialog = false
                        onSyncReadProgress(true)
                    }
                ) {
                    Text("确认开启", color = IosTheme.colors.accent)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showSyncConfirmDialog = false }
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (showLogoutConfirmDialog) {
        LogoutConfirmDialog(
            onDismiss = { showLogoutConfirmDialog = false },
            onConfirm = {
                showLogoutConfirmDialog = false
                onLogout()
            },
        )
    }

    if (showAutoRefreshIntervalDialog) {
        Dialog(onDismissRequest = { showAutoRefreshIntervalDialog = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 340.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(IosTheme.colors.card)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("新话题感知频率", style = IosTheme.type.headline, fontWeight = FontWeight.Bold)
                Text(
                    "停留在话题列表时只读 GET /latest.json 探测是否有新话题并在顶部提示。频率越高越及时，可按需测试 Cloudflare 影响。",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.secondaryLabel,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AutoRefreshInterval.entries.forEach { option ->
                        val isSelected = option == autoRefreshInterval
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) IosTheme.colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    onAutoRefreshInterval(option)
                                    showAutoRefreshIntervalDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                style = IosTheme.type.body,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) IosTheme.colors.accent else IosTheme.colors.label,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotificationScopeDialog) {
        Dialog(onDismissRequest = { showNotificationScopeDialog = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 340.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(IosTheme.colors.card)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("通知推送范围", style = IosTheme.type.headline, fontWeight = FontWeight.Bold)
                Text(
                    "选择推送到系统状态栏的消息类型：",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.secondaryLabel,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NotificationScope.entries.forEach { option ->
                        val isSelected = option == notificationScope
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) IosTheme.colors.accent.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    onNotificationScope(option)
                                    showNotificationScopeDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    style = IosTheme.type.body,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) IosTheme.colors.accent else IosTheme.colors.label,
                                )
                                Text(
                                    option.description,
                                    style = IosTheme.type.caption,
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }
                            if (isSelected) {
                                Text("✓", color = IosTheme.colors.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTestPushLabDialog) {
        Dialog(onDismissRequest = { showTestPushLabDialog = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 290.dp, max = 350.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(IosTheme.colors.card)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("🧪 推送与角标全场景实测", style = IosTheme.type.headline, fontWeight = FontWeight.Bold)
                Text(
                    "无需等待他人回复，直接重放账号最近一条真实通知，走完整后台网络拉取 + 单帖正文补全 + 桌面角标联动：",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.secondaryLabel,
                )

                // 场景 1：息屏浅睡实测 (20秒)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(IosTheme.colors.accent.copy(alpha = 0.10f))
                        .clickable {
                            showTestPushLabDialog = false
                            org.linuxdo.android.service.NotificationKeepAliveService.startShallowSleepTest(context)
                            Toast.makeText(
                                context,
                                "⚡ 已启动！请立即按电源键锁屏，20秒后将拉取真实通知并弹窗 (角标+1)",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "1. 息屏浅睡实测 (20秒后触发)",
                        style = IosTheme.type.subheadline,
                        fontWeight = FontWeight.SemiBold,
                        color = IosTheme.colors.accent,
                    )
                    Text(
                        "点此按钮后请立即锁屏黑屏，验证浅睡期后台网络拉取、单帖正文展示与锁屏弹窗（角标 +1）",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }

                // 场景 2：Doze 闹钟穿透唤醒实测 (60秒)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF9500).copy(alpha = 0.12f))
                        .clickable {
                            showTestPushLabDialog = false
                            org.linuxdo.android.service.NotificationKeepAliveService.startDozeAlarmTest(context)
                            Toast.makeText(
                                context,
                                "⏰ 已切断长连接模拟深睡！请立即锁屏等待60秒，验证 Doze 闹钟穿透唤醒 (角标+1)",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "2. Doze 闹钟穿透唤醒 (60秒后唤醒)",
                        style = IosTheme.type.subheadline,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE67E22),
                    )
                    Text(
                        "立即断开长连接模拟深睡，60秒后由 AlarmManager + BroadcastReceiver 硬件唤醒拉取（角标 +1）",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }

                // 场景 3：亮屏抢跑实测
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF34C759).copy(alpha = 0.12f))
                        .clickable {
                            showTestPushLabDialog = false
                            org.linuxdo.android.service.NotificationKeepAliveService.startScreenOnRushTest(context)
                            Toast.makeText(
                                context,
                                "📱 已就绪！请锁屏等待5秒以上，再按电源键点亮锁屏界面（无需进App）观察秒弹",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "3. 亮屏抢跑实测 (锁屏后点亮即弹)",
                        style = IosTheme.type.subheadline,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF28A745),
                    )
                    Text(
                        "锁屏黑屏 5 秒以上后按电源键点亮屏幕（无需解锁打开 App），验证亮屏广播秒级抢跑弹窗（角标 +1）",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }

                // 场景 4：退回桌面后台实测 (15秒)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF007AFF).copy(alpha = 0.12f))
                        .clickable {
                            showTestPushLabDialog = false
                            org.linuxdo.android.service.NotificationKeepAliveService.startBackgroundMinimizeTest(context)
                            Toast.makeText(
                                context,
                                "📱 已启动！请立即按 Home 键退回桌面，15秒后将自动弹窗 (角标+1)",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "4. 退回桌面后台实测 (15秒后弹窗)",
                        style = IosTheme.type.subheadline,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF007AFF),
                    )
                    Text(
                        "点此按钮后按 Home 键将 App 最小化退回桌面（屏幕保持亮着），验证最小化后台轮询与弹窗（角标 +1）",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }

                // 场景 4：桌面角标实测操作行。
                // 只驱动角标真相源 lastBadgeCount 与厂商桌面接口，**不写模拟未读数**——
                // 否则人造数字会被 refreshUnread 当成真实未读累加进铃铛，且只能一条条点掉。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IosTheme.colors.accent.copy(alpha = 0.12f))
                            .clickable {
                                val store = org.linuxdo.android.data.SessionStore(context)
                                store.setTestSimulatedUnreadCount(0)
                                val nextBadge = store.incrementLastBadgeCount().coerceAtLeast(1)
                                store.setBadgeTestHoldUntil(System.currentTimeMillis() + BADGE_TEST_HOLD_MILLIS)
                                NotificationHelper.showNotification(
                                    context = context,
                                    notificationId = System.currentTimeMillis() % 1_000_000L,
                                    title = "[角标实测] 桌面未读角标 +1",
                                    content = "已请求将角标更新为 $nextBadge，请返回桌面确认显示结果；诊断详情见开发者选项日志",
                                    badgeCount = nextBadge,
                                )
                                NotificationHelper.updateBadgeCount(context, nextBadge, source = "实测+1", force = true)
                                NotificationHelper.recordDiagnosticSnapshot(context)
                                Toast.makeText(context, "已请求角标 $nextBadge，3 分钟内桌面保持该数字；日志已记录", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("角标 +1", style = IosTheme.type.caption, fontWeight = FontWeight.SemiBold, color = IosTheme.colors.accent)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IosTheme.colors.fieldBackground)
                            .clickable {
                                val store = org.linuxdo.android.data.SessionStore(context)
                                store.setTestSimulatedUnreadCount(0)
                                val nextBadge = (store.getLastBadgeCount() - 1).coerceAtLeast(0)
                                store.setLastBadgeCount(nextBadge, force = true)
                                store.setBadgeTestHoldUntil(System.currentTimeMillis() + BADGE_TEST_HOLD_MILLIS)
                                NotificationHelper.updateBadgeCount(context, nextBadge, source = "实测已读-1", force = true)
                                NotificationHelper.recordDiagnosticSnapshot(context)
                                Toast.makeText(context, "已请求更新角标为 $nextBadge，日志已记录", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("已读 -1", style = IosTheme.type.caption, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFF2D55).copy(alpha = 0.12f))
                            .clickable {
                                val store = org.linuxdo.android.data.SessionStore(context)
                                store.setTestSimulatedUnreadCount(0)
                                store.setLastBadgeCount(99, force = true)
                                store.setBadgeTestHoldUntil(System.currentTimeMillis() + BADGE_TEST_HOLD_MILLIS)
                                NotificationHelper.showNotification(
                                    context = context,
                                    notificationId = System.currentTimeMillis() % 1_000_000L,
                                    title = "[角标实测] 桌面角标强制设为 99",
                                    content = "已向荣耀/华为桌面提交角标 99，请按 Home 键返回桌面确认；如未显示请检查荣耀系统【数字角标】设置",
                                    badgeCount = 99,
                                )
                                NotificationHelper.updateBadgeCount(context, 99, source = "实测强设99", force = true)
                                NotificationHelper.recordDiagnosticSnapshot(context)
                                Toast.makeText(context, "已向桌面提交角标 99，请按 Home 键查看；3 分钟后自动恢复真实未读", Toast.LENGTH_LONG).show()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("设为 99", style = IosTheme.type.caption, fontWeight = FontWeight.Bold, color = Color(0xFFFF2D55))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IosTheme.colors.fieldBackground)
                            .clickable {
                                val store = org.linuxdo.android.data.SessionStore(context)
                                // 解除锁定并清掉模拟未读，让下一轮 refreshUnread 立刻校准回真实未读。
                                store.setBadgeTestHoldUntil(0L)
                                store.setTestSimulatedUnreadCount(0)
                                store.setLastBadgeCount(0, force = true)
                                NotificationHelper.updateBadgeCount(context, 0, source = "实测归零", force = true)
                                NotificationHelper.recordDiagnosticSnapshot(context)
                                Toast.makeText(context, "已清除角标与实测锁定，未读数将恢复为真实值", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("角标归零", style = IosTheme.type.caption, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingIconBadge(
    bgColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 设置首页的品目行：只放图标、名称与当前状态，点击进入子页。 */
@Composable
private fun SettingsEntryRow(
    title: String,
    badgeColor: Color,
    onClick: () -> Unit,
    value: String? = null,
    icon: @Composable () -> Unit,
) {
    IosRow(onClick = onClick) {
        SettingIconBadge(bgColor = badgeColor, content = icon)
        Spacer(Modifier.width(12.dp))
        Text(title, style = IosTheme.type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = IosTheme.type.subheadline.copy(fontSize = 13.5.sp), color = IosTheme.colors.secondaryLabel)
            Spacer(Modifier.width(8.dp))
        }
        Text("›", color = IosTheme.colors.tertiaryLabel, style = IosTheme.type.title3)
    }
}

@Composable
private fun SettingAppearanceIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            // 半实心圆：浅色 / 深色主题
            drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = Stroke(2f))
            drawArc(tint, startAngle = -90f, sweepAngle = 180f, useCenter = true, topLeft = Offset(3.5f, 3.5f), size = Size(17f, 17f))
        }
    }
}

@Composable
private fun SettingBookIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val book = Path().apply {
                moveTo(12f, 6.5f)
                cubicTo(10f, 5f, 6.5f, 4.5f, 3f, 5f)
                lineTo(3f, 18.5f)
                cubicTo(6.5f, 18f, 10f, 18.5f, 12f, 20f)
                cubicTo(14f, 18.5f, 17.5f, 18f, 21f, 18.5f)
                lineTo(21f, 5f)
                cubicTo(17.5f, 4.5f, 14f, 5f, 12f, 6.5f)
                close()
            }
            drawPath(book, tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(12f, 6.5f), Offset(12f, 20f), strokeWidth = 2f)
        }
    }
}

@Composable
private fun SettingPictureIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(3f, 4f),
                size = Size(18f, 16f),
                cornerRadius = CornerRadius(3f, 3f),
                style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawCircle(tint, radius = 1.5f, center = Offset(8f, 9f))
            val mountain = Path().apply {
                moveTo(3f, 17f)
                lineTo(9f, 11f)
                lineTo(14f, 16f)
                lineTo(17f, 13f)
                lineTo(21f, 17f)
            }
            drawPath(mountain, tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun SettingBellIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val bell = Path().apply {
                moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f)
                cubicTo(7f, 2.5f, 17f, 2.5f, 17f, 9f)
                lineTo(17f, 14f); lineTo(19f, 17f); close()
            }
            drawPath(bell, tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(tint, radius = 1.8f, center = Offset(12f, 20f))
        }
    }
}

@Composable
private fun SettingFloatingBallIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = Stroke(2.2f))
            drawCircle(tint, radius = 3.5f, center = Offset(12f, 12f))
        }
    }
}

@Composable
private fun SettingUnreadDotIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(3f, 5f),
                size = androidx.compose.ui.geometry.Size(14f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                style = Stroke(2f),
            )
            drawLine(tint, Offset(6.5f, 9.5f), Offset(13.5f, 9.5f), strokeWidth = 1.8f, cap = StrokeCap.Round)
            drawLine(tint, Offset(6.5f, 13f), Offset(11f, 13f), strokeWidth = 1.8f, cap = StrokeCap.Round)
            drawCircle(tint, radius = 3.8f, center = Offset(18.5f, 6.5f))
        }
    }
}

@Composable
private fun SettingTreeIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            // 组织架构树/分支树
            drawRoundRect(tint, Offset(9.5f, 3f), androidx.compose.ui.geometry.Size(5f, 5f), androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f))
            drawLine(tint, Offset(12f, 8f), Offset(12f, 13f), strokeWidth = 2f)
            drawLine(tint, Offset(6f, 13f), Offset(18f, 13f), strokeWidth = 2f)
            drawLine(tint, Offset(6f, 13f), Offset(6f, 16f), strokeWidth = 2f)
            drawLine(tint, Offset(18f, 13f), Offset(18f, 16f), strokeWidth = 2f)
            drawRoundRect(tint, Offset(3.5f, 16f), androidx.compose.ui.geometry.Size(5f, 5f), androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f))
            drawRoundRect(tint, Offset(15.5f, 16f), androidx.compose.ui.geometry.Size(5f, 5f), androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f))
        }
    }
}

@Composable
private fun SettingBatteryIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawRoundRect(tint, Offset(3f, 7f), androidx.compose.ui.geometry.Size(15f, 10f), androidx.compose.ui.geometry.CornerRadius(2f, 2f), style = Stroke(2f))
            drawRoundRect(tint, Offset(18f, 10f), androidx.compose.ui.geometry.Size(2.5f, 4f), androidx.compose.ui.geometry.CornerRadius(1f, 1f))
            val bolt = Path().apply {
                moveTo(11.5f, 9f); lineTo(8.5f, 12f); lineTo(11f, 12f); lineTo(9.5f, 15f); lineTo(13.5f, 11.5f); lineTo(11f, 11.5f); close()
            }
            drawPath(bolt, tint)
        }
    }
}

@Composable
private fun SettingRocketIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val rocket = Path().apply {
                moveTo(14.5f, 2.5f)
                cubicTo(11f, 3.5f, 7.5f, 7f, 6.5f, 10.5f)
                lineTo(4f, 13f); lineTo(7.5f, 14f); lineTo(8.5f, 17.5f)
                lineTo(11f, 15f)
                cubicTo(14.5f, 14f, 18f, 10.5f, 19f, 7f)
                lineTo(14.5f, 2.5f); close()
            }
            drawPath(rocket, tint)
            drawCircle(Color.White, radius = 1.8f, center = Offset(13.5f, 8f))
        }
    }
}

@Composable
private fun SettingClockIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = Stroke(2f))
            drawLine(tint, Offset(12f, 7.5f), Offset(12f, 12f), strokeWidth = 2f, cap = StrokeCap.Round)
            drawLine(tint, Offset(12f, 12f), Offset(15.5f, 12f), strokeWidth = 2f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingBroomIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val brush = Path().apply {
                moveTo(16f, 3f); lineTo(20f, 7f); lineTo(14f, 13f); lineTo(10f, 9f); close()
                moveTo(9f, 10f); lineTo(13f, 14f); lineTo(9f, 19f); lineTo(4f, 19f); lineTo(4f, 14f); close()
            }
            drawPath(brush, tint)
        }
    }
}

@Composable
private fun SettingInfoIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawCircle(tint, radius = 8.5f, center = Offset(12f, 12f), style = Stroke(2f))
            drawCircle(tint, radius = 1.2f, center = Offset(12f, 8f))
            drawLine(tint, Offset(12f, 11f), Offset(12f, 16f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingDohIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            // 盾牌形状：代表 DNS 安全保护
            val shield = Path().apply {
                moveTo(12f, 3f)
                lineTo(4f, 6.5f)
                lineTo(4f, 12f)
                cubicTo(4f, 16.5f, 7.5f, 20f, 12f, 21f)
                cubicTo(16.5f, 20f, 20f, 16.5f, 20f, 12f)
                lineTo(20f, 6.5f)
                close()
            }
            drawPath(shield, tint, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // 内部勾：表示已验证
            val check = Path().apply {
                moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f)
            }
            drawPath(check, tint, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun DohGearIcon(tint: Color = Color(0xFF007AFF), modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.width * 0.46f
        val innerR = size.width * 0.35f
        val holeR = size.width * 0.16f
        val teeth = 6
        val path = Path()
        for (i in 0 until teeth * 2) {
            val angle = (i * 360f / (teeth * 2)) * (Math.PI / 180.0)
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color = tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color = tint, radius = holeR, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()))
    }
}

@Composable
private fun DohTrashIcon(tint: Color = Color(0xFFFF3B30), modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()

        // 盖子横线
        val lidY = h * 0.28f
        drawLine(color = tint, start = Offset(w * 0.16f, lidY), end = Offset(w * 0.84f, lidY), strokeWidth = stroke, cap = StrokeCap.Round)
        // 提手
        val handlePath = Path().apply {
            moveTo(w * 0.38f, lidY)
            lineTo(w * 0.38f, h * 0.14f)
            lineTo(w * 0.62f, h * 0.14f)
            lineTo(w * 0.62f, lidY)
        }
        drawPath(handlePath, color = tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 桶身外框（微倒梯形）
        val bodyPath = Path().apply {
            moveTo(w * 0.24f, lidY + stroke * 0.8f)
            lineTo(w * 0.28f, h * 0.88f)
            lineTo(w * 0.72f, h * 0.88f)
            lineTo(w * 0.76f, lidY + stroke * 0.8f)
        }
        drawPath(bodyPath, color = tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 桶身内 2 条竖纹
        drawLine(color = tint, start = Offset(w * 0.42f, lidY + h * 0.18f), end = Offset(w * 0.42f, h * 0.76f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.58f, lidY + h * 0.18f), end = Offset(w * 0.58f, h * 0.76f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
    }
}

@Composable
private fun SettingSyncIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawArc(tint, startAngle = 30f, sweepAngle = 200f, useCenter = false, topLeft = Offset(4f, 4f), size = androidx.compose.ui.geometry.Size(16f, 16f), style = Stroke(2.2f, cap = StrokeCap.Round))
            drawArc(tint, startAngle = 210f, sweepAngle = 200f, useCenter = false, topLeft = Offset(4f, 4f), size = androidx.compose.ui.geometry.Size(16f, 16f), style = Stroke(2.2f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun SettingTerminalIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val chevron = Path().apply {
                moveTo(6f, 7f); lineTo(11f, 12f); lineTo(6f, 17f)
            }
            drawPath(chevron, tint, style = Stroke(2.4f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(tint, Offset(13f, 17f), Offset(19f, 17f), strokeWidth = 2.4f, cap = StrokeCap.Round)
        }
    }
}

/** 退出账号确认对话框：与帖子/评论管理弹框风格完全一致（卡片居中、18dp圆角、加粗标题、双胶囊按钮） */
@Composable
private fun LogoutConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String = "确认退出当前账号？",
    subtitle: String = "退出后需要重新输入账号密码登录",
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(IosTheme.colors.card)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = IosTheme.type.headline,
                    fontWeight = FontWeight.Bold,
                    color = IosTheme.colors.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (IosTheme.colors.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "取消",
                        style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = IosTheme.colors.label,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF3B30))
                        .clickable {
                            onDismiss()
                            onConfirm()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SettingLogoutIcon(tint = Color.White)
                        Text(
                            text = "退出",
                            style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingClipboardIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            drawRoundRect(
                color = tint,
                topLeft = Offset(5f, 4f),
                size = androidx.compose.ui.geometry.Size(14f, 17f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                style = stroke
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(9f, 2.5f),
                size = androidx.compose.ui.geometry.Size(6f, 3.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f),
                style = stroke
            )
            drawLine(tint, Offset(8f, 10f), Offset(16f, 10f), strokeWidth = 1.6f, cap = StrokeCap.Round)
            drawLine(tint, Offset(8f, 13.5f), Offset(16f, 13.5f), strokeWidth = 1.6f, cap = StrokeCap.Round)
            drawLine(tint, Offset(8f, 17f), Offset(13f, 17f), strokeWidth = 1.6f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingLogoutIcon(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            // 登出图标: 左边方框开口 + 向右箭头
            val door = Path().apply {
                moveTo(13f, 4f)
                lineTo(5f, 4f)
                cubicTo(4f, 4f, 3f, 5f, 3f, 6f)
                lineTo(3f, 18f)
                cubicTo(3f, 19f, 4f, 20f, 5f, 20f)
                lineTo(13f, 20f)
            }
            drawPath(door, tint, style = Stroke(2.2f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(tint, Offset(9f, 12f), Offset(20f, 12f), strokeWidth = 2.2f, cap = StrokeCap.Round)
            val arrow = Path().apply {
                moveTo(16f, 8f); lineTo(20f, 12f); lineTo(16f, 16f)
            }
            drawPath(arrow, tint, style = Stroke(2.2f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
fun UpdateDialog(
    currentVersion: String,
    info: UpdateInfo,
    stage: ApkUpdateStage,
    downloadedBytes: Long,
    totalBytes: Long,
    errorMessage: String?,
    onStartDownload: () -> Unit,
    onRetryAfterPermission: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: (ignoreThisVersion: Boolean) -> Unit,
) {
    val context = LocalContext.current
    // 下载或安装进行中就不让点外部/返回键关掉，避免把进度丢掉。
    val busy = stage == ApkUpdateStage.Downloading ||
        stage == ApkUpdateStage.Verifying ||
        stage == ApkUpdateStage.Installing
    val closable = !info.mandatory && !busy
    Dialog(
        onDismissRequest = { if (closable) onDismiss(false) },
        properties = DialogProperties(
            dismissOnBackPress = closable,
            dismissOnClickOutside = closable,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(IosTheme.colors.card)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (info.mandatory) {
                                IosTheme.colors.destructive.copy(alpha = 0.14f)
                            } else {
                                IosTheme.colors.accent.copy(alpha = 0.14f)
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = if (info.mandatory) "必升版本" else "发现新版本",
                        style = IosTheme.type.footnote,
                        color = if (info.mandatory) IosTheme.colors.destructive else IosTheme.colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "v$currentVersion → v${info.latestVersion}",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = info.title,
                style = IosTheme.type.title3,
                color = IosTheme.colors.label,
            )

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp, max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(IosTheme.colors.fieldBackground)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = info.changelog,
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.label,
                    lineHeight = 19.sp,
                )
            }

            if (info.mandatory) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "当前版本包含关键协议或安全更新，请升级至最新版本后继续使用。",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.destructive,
                )
            }

            Spacer(Modifier.height(16.dp))

            // 授权页返回后自动续下，不用用户再点一次升级。
            val installPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { onRetryAfterPermission() }

            when (stage) {
                ApkUpdateStage.Downloading, ApkUpdateStage.Verifying -> {
                    val fraction = if (totalBytes > 0L) {
                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Text(
                        text = when {
                            stage == ApkUpdateStage.Verifying -> "正在校验安装包…"
                            totalBytes > 0L ->
                                "正在下载 ${(fraction * 100).toInt()}%　${formatUpdateBytes(downloadedBytes)} / ${formatUpdateBytes(totalBytes)}"
                            else -> "正在下载　${formatUpdateBytes(downloadedBytes)}"
                        },
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(IosTheme.colors.fieldBackground),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(IosTheme.colors.accent),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCancelDownload() }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "取消下载",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                }

                ApkUpdateStage.Installing -> {
                    Text(
                        text = "已唤起系统安装程序，请在系统弹窗中确认安装。",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                    )
                    Spacer(Modifier.height(12.dp))
                    UpdateSecondaryAction(text = "改用浏览器下载") {
                        openInDefaultBrowser(context, info.downloadUrl)
                    }
                }

                ApkUpdateStage.AwaitingPermission -> {
                    Text(
                        text = "系统需要你允许本应用「安装未知应用」。开启后会自动继续下载并安装。",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                    )
                    Spacer(Modifier.height(12.dp))
                    UpdatePrimaryAction(text = "前往开启授权") {
                        val intent = ApkUpdateInstaller.unknownSourceSettingsIntent(context)
                        if (intent != null) {
                            runCatching { installPermissionLauncher.launch(intent) }
                                .onFailure { NotificationHelper.openAppDetailsSettings(context) }
                        } else {
                            NotificationHelper.openAppDetailsSettings(context)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    UpdateSecondaryAction(text = "改用浏览器下载") {
                        openInDefaultBrowser(context, info.downloadUrl)
                    }
                }

                else -> {
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.destructive,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    UpdatePrimaryAction(
                        text = if (stage == ApkUpdateStage.Failed) {
                            "重试下载 (v${info.latestVersion})"
                        } else {
                            "立即升级 (v${info.latestVersion})"
                        },
                        onClick = onStartDownload,
                    )
                    if (stage == ApkUpdateStage.Failed) {
                        Spacer(Modifier.height(10.dp))
                        UpdateSecondaryAction(text = "改用浏览器下载") {
                            openInDefaultBrowser(context, info.downloadUrl)
                        }
                    }
                }
            }

            if (closable) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss(true) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "忽略此版本",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss(false) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "稍后提醒",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.accent,
                        )
                    }
                }
            }
        }
    }
}

/** 升级对话框的主操作按钮。 */
@Composable
private fun UpdatePrimaryAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(IosTheme.colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = IosTheme.type.body,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 升级对话框的次要操作，用于浏览器兜底这类退路。 */
@Composable
private fun UpdateSecondaryAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = IosTheme.type.subheadline,
            color = IosTheme.colors.accent,
        )
    }
}

/** 下载进度用的字节展示：不足 1 MB 时用 KB，避免一直停在 0.0 MB。 */
private fun formatUpdateBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1.0) {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/**
 * 优先调用系统设置中的“默认浏览器”打开升级链接，避免被装有特定下载接管过滤器的第三方应用（如某些下载器）误拦截。
 */
fun openInDefaultBrowser(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    // 1. 构造一个纯网页意图，向系统 PackageManager 查询系统设置中指定的真正“默认浏览器”
    val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://linux.do")).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val defaultBrowserPkg = runCatching {
        context.packageManager
            .resolveActivity(probeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != "com.google.android.packageinstaller" }
    }.getOrNull()

    val targetIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!defaultBrowserPkg.isNullOrBlank()) {
            setPackage(defaultBrowserPkg)
        }
    }
    val launched = runCatching {
        context.startActivity(targetIntent)
        true
    }.getOrDefault(false)

    if (!launched) {
        // 兜底：若指定包名启动失败，移除包名并使用通用选择器唤起
        runCatching {
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

@Composable
fun UnreadBell(count: Int, onClick: () -> Unit) {
    val iconColor = IosTheme.colors.secondaryLabel
    Box(Modifier.size(44.dp).semantics { contentDescription = "通知，${count}条回复或私信未读" }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val bell = Path().apply {
                    moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f)
                    cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                    lineTo(17f, 14f); lineTo(19f, 17f); close()
                }
                drawPath(bell, iconColor, style = Stroke(1.7f))
                drawArc(iconColor, 0f, 180f, false, Offset(10f, 18f), androidx.compose.ui.geometry.Size(4f, 3f), style = Stroke(1.7f))
            }
        }
        if (count > 0) Text(if (count > 99) "99+" else count.toString(), color = Color.White,
            fontSize = 10.sp, lineHeight = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 2.dp)
                .background(Color(0xFFFF3B30), RoundedCornerShape(20.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/** 登录/验证页顶部的说明条;WebView 本体由 AppShell 抬到它下方显示。 */
@Composable
fun WebTopBar(screen: Screen, onCancel: () -> Unit = {}, onRetry: () -> Unit = {}) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(IosMetrics.navBarHeight)
            .background(IosTheme.colors.card),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (screen == Screen.Login) "登录 linux.do" else "安全验证",
            style = IosTheme.type.navTitle,
            color = IosTheme.colors.label,
        )
        androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) { Text("返回") }
        androidx.compose.material3.TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterEnd)) { Text("重试") }
    }
}

/**
 * 开发者选项。承接原型界面的全部调试能力:双路径切换、自动刷新、会话诊断与日志。
 * 这些是排查 Cloudflare 与会话保持问题的主要手段,所以界面改版时只换位置、不削功能。
 */
@Composable
fun DiagnosticsScreen(state: UiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val stats by viewModel.diagnostics.stats.collectAsStateWithLifecycle()
    val lines by viewModel.diagnostics.lines.collectAsStateWithLifecycle()
    val savedAt = if (state.cookieSavedAt == 0L) {
        "-"
    } else {
        DateFormat.getDateTimeInstance().format(Date(state.cookieSavedAt))
    }
    var zeroDohPocReport by remember { mutableStateOf<String?>(null) }
    var zeroDohPocRunning by remember { mutableStateOf(false) }
    var cfDiagnosticMode by remember { mutableStateOf<CfDiagnosticMode?>(null) }
    var cfDiagnosticReports by remember { mutableStateOf<Map<CfDiagnosticMode, String>>(emptyMap()) }
    val zeroDohPocScope = androidx.compose.runtime.rememberCoroutineScope()
    val crashReport = remember { viewModel.diagnostics.readCrashLog() }
    var showCrashDialog by remember { mutableStateOf(false) }
    var crashDismissed by remember { mutableStateOf(false) }

    IosLargeTitleScaffold(title = "开发者选项", onBack = onBack) {
        if (!crashReport.isNullOrBlank() && !crashDismissed) {
            item(key = "crash-report-card") {
                SectionTitle("异常记录")
                IosGroupCard {
                    IosRow {
                        SettingIconBadge(bgColor = Color(0xFFFFE5E5)) {
                            Text("💥", fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("检测到崩溃日志记录", style = IosTheme.type.body, fontWeight = FontWeight.SemiBold, color = Color(0xFFD32F2F))
                            Text(
                                "已将未捕获异常完整堆栈落盘至 crash.log",
                                style = IosTheme.type.footnote,
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = "查看",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showCrashDialog = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        item(key = "developer-switch") {
            SectionTitle("开发者模式")
            IosGroupCard {
                IosRow {
                    SettingIconBadge(bgColor = Color(0xFFF0F0F2)) {
                        SettingTerminalIcon(tint = Color(0xFF3A3A3C))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("开发者模式开关", style = IosTheme.type.body, fontWeight = FontWeight.Normal)
                        Text(
                            "关闭后该入口将从设置中隐藏，需重新点击版本号7次激活",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    val devPrefs = remember { context.getSharedPreferences("display_preferences", Context.MODE_PRIVATE) }
                    var devModeChecked by remember {
                        mutableStateOf(if (devPrefs.contains("dev_options_enabled")) devPrefs.getBoolean("dev_options_enabled", false) else BuildConfig.DEBUG)
                    }
                    IosSwitch(
                        checked = devModeChecked,
                        onCheckedChange = { checked ->
                            devModeChecked = checked
                            devPrefs.edit().putBoolean("dev_options_enabled", checked).apply()
                            if (!checked) {
                                Toast.makeText(context, "开发者选项已关闭并隐藏", Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        }
                    )
                }
            }
        }

        item(key = "paths") {
            SectionTitle("网络路径")
            IosGroupCard {
                NetworkPath.entries.forEachIndexed { index, path ->
                    IosRow(onClick = { viewModel.setPath(path) }) {
                        Text(
                            text = path.label,
                            style = IosTheme.type.body,
                            color = IosTheme.colors.label,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.path == path) {
                            Text("使用中", style = IosTheme.type.subheadline, color = IosTheme.colors.accent)
                        }
                    }
                    if (index < NetworkPath.entries.lastIndex) IosDivider()
                }
            }
        }

        item(key = "cf-diagnostics") {
            SectionTitle("Cloudflare 验证测试")
            IosGroupCard {
                CfDiagnosticMode.entries.forEachIndexed { index, mode ->
                    IosRow(onClick = { cfDiagnosticMode = mode }) {
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, style = IosTheme.type.body, color = IosTheme.colors.accent)
                            Text(
                                cfDiagnosticReports[mode] ?: if (mode == CfDiagnosticMode.FLOW) {
                                    "本地模拟遇盾、点击、失败和重试"
                                } else {
                                    "加载官方交互测试控件；请先关闭手机和宿主代理"
                                },
                                style = IosTheme.type.footnote,
                                color = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text("测试 ›", style = IosTheme.type.subheadline, color = IosTheme.colors.accent)
                    }
                    if (index == 0) IosDivider()
                }
            }
            Text(
                "测试使用独立会话。流程模拟通过、测试控件通过，均不等于 linux.do 真实站点认证通过。详细结果记录在下方日志。",
                style = IosTheme.type.caption,
                color = IosTheme.colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item(key = "zero-doh-lab") {
            SectionTitle("网络直连实验")
            IosGroupCard {
                IosRow(onClick = {
                    if (zeroDohPocRunning) {
                        Toast.makeText(context, "正在验证中，请稍候…", Toast.LENGTH_SHORT).show()
                    } else {
                        zeroDohPocRunning = true
                        zeroDohPocScope.launch {
                            zeroDohPocReport = "未集成实验模块"
                            zeroDohPocRunning = false
                        }
                    }
                }) {
                    SettingIconBadge(bgColor = Color(0xFFE8F5E9)) {
                        SettingInfoIcon(tint = Color(0xFF43A047))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("零 DoH 直连可行性验证", style = IosTheme.type.body, fontWeight = FontWeight.Normal)
                        Text(
                            "验证 Anycast 零 DNS 直连、ECH 存活性、WebView 代理导流与 ClientHello 分片",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text(
                        if (zeroDohPocRunning) "验证中…" else "开始验证 ›",
                        style = IosTheme.type.subheadline,
                        color = IosTheme.colors.accent,
                    )
                }
            }
        }

        item(key = "actions") {
            SectionTitle("操作")
            IosGroupCard {
                IosRow {
                    Text(
                        text = "自动刷新(${Config.AUTO_REFRESH_INTERVAL_MS / 60_000} 分钟)",
                        style = IosTheme.type.body,
                        color = IosTheme.colors.label,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.autoRefresh,
                        onCheckedChange = { viewModel.toggleAutoRefresh() },
                        colors = SwitchDefaults.colors(
                            // iOS 的开关轨道是 systemGreen。
                            checkedTrackColor = Color(0xFF34C759),
                            checkedThumbColor = Color.White,
                        ),
                    )
                }
                IosDivider()
                IosRow(onClick = viewModel::retryLatest) {
                    Text("立即刷新列表", style = IosTheme.type.body, color = IosTheme.colors.accent)
                }
                IosDivider()
                IosRow(onClick = {
                    NotificationHelper.recordDiagnosticSnapshot(context)
                    Toast.makeText(context, "推送与角标状态已记录到下方日志", Toast.LENGTH_SHORT).show()
                }) {
                    Text("记录推送与角标状态", style = IosTheme.type.body, color = IosTheme.colors.accent)
                }
                IosDivider()
                IosRow(onClick = {
                    // 与屏幕一致按新→旧排列：粘贴目标（笔记、聊天）截断长文本时丢掉的是最旧的部分，
                    // 按文件顺序复制会把最新几分钟截没。
                    val newestFirst = viewModel.diagnostics.readLatestLines(300).asReversed()
                    val range = if (newestFirst.isEmpty()) "" else
                        "LinuxDo 诊断日志（新→旧，${newestFirst.first().take(14)} ~ ${newestFirst.last().take(14)}，共 ${newestFirst.size} 行）\n"
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("LinuxDo 诊断日志", range + newestFirst.joinToString("\n")))
                    Toast.makeText(context, "已复制最新${newestFirst.size}行日志（新→旧）", Toast.LENGTH_SHORT).show()
                }) {
                    Text("复制当前日志", style = IosTheme.type.body, color = IosTheme.colors.accent)
                }
                IosDivider()
                IosRow(onClick = {
                    viewModel.clearDiagnostics()
                    Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                }) {
                    Text("清空诊断日志", style = IosTheme.type.body, color = IosTheme.colors.destructive)
                }
                IosDivider()
                IosRow(onClick = viewModel::logout) {
                    Text("登出并清除 Cookie", style = IosTheme.type.body, color = IosTheme.colors.destructive)
                }
            }
        }

        item(key = "session") {
            SectionTitle("会话诊断")
            IosGroupCard {
                InfoRow("当前用户", state.username ?: "未确认")
                IosDivider()
                InfoRow("最近路径", stats.lastPath)
                IosDivider()
                InfoRow("最近状态码", stats.lastStatus?.toString() ?: "-")
                IosDivider()
                InfoRow("静默重验次数", stats.silentRevalidations.toString())
                IosDivider()
                InfoRow("需交互验证次数", stats.interactiveRequired.toString())
                IosDivider()
                InfoRow("Cookie", state.cookieNames.joinToString(", ").ifEmpty { "无" })
                IosDivider()
                InfoRow("Cookie 更新于", savedAt)
                IosDivider()
                InfoRow("User-Agent", state.userAgent)
            }
        }

        item(key = "log-title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = IosMetrics.screenPadding + 16.dp,
                        end = IosMetrics.screenPadding + 16.dp,
                        top = 24.dp,
                        bottom = 6.dp,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (lines.isEmpty()) "日志" else "日志 (${lines.size}条)",
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.secondaryLabel,
                )
                if (lines.isNotEmpty()) {
                    Text(
                        text = "一键清理",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.destructive,
                        modifier = Modifier
                            .clickable {
                                viewModel.clearDiagnostics()
                                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (lines.isEmpty()) {
            item(key = "log-empty") {
                IosGroupCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无诊断日志",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        } else {
            // 日志文本可能重复,不能拿内容当 key。
            items(lines.asReversed()) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = IosTheme.colors.secondaryLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = IosMetrics.screenPadding, vertical = 2.dp),
                )
            }
        }
        item(key = "log-tail") { Spacer(Modifier.height(32.dp)) }
    }

    cfDiagnosticMode?.let { mode ->
        CfDiagnosticDialog(
            mode = mode,
            onClose = { cfDiagnosticMode = null },
            onStatus = { update ->
                cfDiagnosticReports = cfDiagnosticReports + (mode to update.message)
                viewModel.diagnostics.log("[${mode.title}] ${update.message.replace('\n', ' ')}")
            },
        )
    }

    if (showCrashDialog && crashReport != null) {
        Dialog(onDismissRequest = { showCrashDialog = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 290.dp, max = 360.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(IosTheme.colors.card)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("崩溃日志详情", style = IosTheme.type.headline, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                Text(
                    crashReport,
                    style = IosTheme.type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "清空日志",
                        style = IosTheme.type.footnote,
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier
                            .clickable {
                                viewModel.diagnostics.clearCrashLog()
                                crashDismissed = true
                                showCrashDialog = false
                                Toast.makeText(context, "崩溃日志已清空", Toast.LENGTH_SHORT).show()
                            }
                            .padding(4.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "复制全部",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Crash Report", crashReport))
                                    Toast.makeText(context, "崩溃堆栈已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                }
                                .padding(4.dp)
                        )
                        Text(
                            "关闭",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.label,
                            modifier = Modifier
                                .clickable { showCrashDialog = false }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }

    zeroDohPocReport?.let { report ->
        Dialog(onDismissRequest = { zeroDohPocReport = null }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 290.dp, max = 350.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(IosTheme.colors.card)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("零 DoH 可行性验证报告", style = IosTheme.type.headline, fontWeight = FontWeight.Bold)
                Text(
                    "完整报告同时写入 logcat，可用 adb logcat -s ZeroDohPoc:I 取回",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.secondaryLabel,
                )
                Text(
                    report,
                    style = IosTheme.type.caption,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Text(
                    "关闭",
                    style = IosTheme.type.subheadline,
                    color = IosTheme.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable { zeroDohPocReport = null }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CfDiagnosticDialog(
    mode: CfDiagnosticMode,
    onClose: () -> Unit,
    onStatus: (CfDiagnosticStatus) -> Unit,
) {
    val context = LocalContext.current
    val dark = IosTheme.colors.isDark
    val latestClose = androidx.compose.runtime.rememberUpdatedState(onClose)
    val latestStatus = androidx.compose.runtime.rememberUpdatedState(onStatus)
    DisposableEffect(context, mode, dark) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        val surface = if (dark) android.graphics.Color.rgb(28, 28, 30) else android.graphics.Color.WHITE
        val label = if (dark) android.graphics.Color.rgb(245, 245, 247) else android.graphics.Color.rgb(28, 28, 30)
        val dialog = android.app.Dialog(context)
        var disposed = false
        var running: CfDiagnosticSession? = null
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        val title = android.widget.TextView(context).apply {
            text = mode.title
            textSize = 18f
            setTextColor(label)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        val status = android.widget.TextView(context).apply {
            textSize = 14f
            setTextColor(label)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val controls = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val retry = android.widget.Button(context).apply { text = "重试" }
        val close = android.widget.Button(context).apply { text = "关闭" }
        controls.addView(retry, android.widget.LinearLayout.LayoutParams(0, wrap, 1f))
        controls.addView(close, android.widget.LinearLayout.LayoutParams(0, wrap, 1f))
        val host = android.widget.FrameLayout(context)
        root.addView(title, android.widget.LinearLayout.LayoutParams(match, wrap))
        root.addView(status, android.widget.LinearLayout.LayoutParams(match, wrap))
        root.addView(controls, android.widget.LinearLayout.LayoutParams(match, wrap))
        root.addView(host, android.widget.LinearLayout.LayoutParams(match, 0, 1f))

        fun startTest() {
            running?.close(reportCancellation = false)
            host.removeAllViews()
            val test = CfDiagnosticSession(context, mode, dark) { update ->
                if (!disposed) {
                    status.text = update.message
                    status.setTextColor(if (update.success) android.graphics.Color.rgb(48, 166, 86) else label)
                    latestStatus.value(update)
                }
            }
            running = test
            host.addView(test.view, android.widget.FrameLayout.LayoutParams(match, match))
            test.start()
        }

        retry.setOnClickListener { startTest() }
        close.setOnClickListener { dialog.dismiss() }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        dialog.setOnDismissListener {
            running?.close()
            if (!disposed) latestClose.value()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
            setLayout(match, (context.resources.displayMetrics.heightPixels * 0.92f).toInt())
            setGravity(android.view.Gravity.BOTTOM)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        startTest()
        onDispose {
            disposed = true
            dialog.setOnDismissListener(null)
            running?.close(reportCancellation = false)
            host.removeAllViews()
            dialog.dismiss()
        }
    }
}

/**
 * 沉浸式超级调色盘 / 主题风格工坊
 * 1. 顶部：实时拟态交互沙盘（Mini UI Mockup），动态反映当前主题色彩、圆角、边框与硬件加速柔光；
 * 2. 中部：6 大精选主题质感卡片陈列馆，具备拾色球与金句；
 * 3. 底部：明暗模式三段胶囊控制器。
 */
@Composable
private fun ThemeStudioContent(
    skinPreset: org.linuxdo.android.ui.design.SkinPreset,
    onSkinPreset: (org.linuxdo.android.ui.design.SkinPreset) -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val currentSkin = IosTheme.skin
    val isDark = currentSkin.colors.isDark

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IosMetrics.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 1. 显示模式（深浅色胶囊控制器置顶） ──
        Text(
            text = "深浅显示模式",
            style = IosTheme.type.footnote,
            color = currentSkin.colors.secondaryLabel,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )

        val modeShape = RoundedCornerShape(currentSkin.cardCorner)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(modeShape)
                .background(currentSkin.colors.card)
                .then(
                    if (currentSkin.cardBorderWidth > 0.dp) {
                        Modifier.border(currentSkin.cardBorderWidth, currentSkin.cardBorderColor, modeShape)
                    } else Modifier
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = themeMode == mode
                val segmentShape = RoundedCornerShape(currentSkin.innerCorner)
                val icon = when (mode) {
                    ThemeMode.System -> "⚙️"
                    ThemeMode.Light -> "☀️"
                    ThemeMode.Dark -> "🌙"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(segmentShape)
                        .then(
                            if (isSelected) {
                                Modifier
                                    .background(currentSkin.colors.accent.copy(alpha = 0.15f))
                                    .border(1.dp, currentSkin.colors.accent, segmentShape)
                            } else {
                                Modifier.clickable { onThemeMode(mode) }
                            }
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(icon, fontSize = 14.sp)
                        Text(
                            text = mode.label,
                            style = IosTheme.type.subheadline.copy(fontSize = 13.5.sp),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) currentSkin.colors.accent else currentSkin.colors.label,
                        )
                    }
                }
            }
        }

        // ── 2. 精选主题陈列馆 ──
        Text(
            text = "精选主题风格（点击即刻原位生效）",
            style = IosTheme.type.footnote,
            color = currentSkin.colors.secondaryLabel,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )

        // ── 3. 主题卡片网格列表（2 列排布） ──
        val presets = org.linuxdo.android.ui.design.SkinPreset.entries
        val pairs = presets.chunked(2)
        pairs.forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowPresets.forEach { preset ->
                    val isSelected = preset == skinPreset
                    val itemSkin = org.linuxdo.android.ui.design.resolveAppSkin(preset, isDark)
                    val cardShape = RoundedCornerShape(itemSkin.cardCorner)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, itemSkin.colors.accent, cardShape)
                                } else if (itemSkin.cardBorderWidth > 0.dp) {
                                    Modifier.border(itemSkin.cardBorderWidth, itemSkin.cardBorderColor, cardShape)
                                } else {
                                    Modifier.border(1.dp, currentSkin.colors.separator.copy(alpha = 0.5f), cardShape)
                                }
                            )
                            .clip(cardShape)
                            .background(itemSkin.colors.card)
                            .clickable { onSkinPreset(preset) }
                            .padding(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 顶栏：Emoji 与调色拾色阶圆点
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(preset.iconEmoji, fontSize = 20.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    itemSkin.palettePreview.forEach { color ->
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                                .border(0.5.dp, itemSkin.colors.separator.copy(alpha = 0.4f), CircleShape)
                                        )
                                    }
                                }
                            }

                            // 标题与分类
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = preset.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = itemSkin.colors.label,
                                )
                                Text(
                                    text = preset.subtitle,
                                    fontSize = 12.sp,
                                    color = itemSkin.colors.secondaryLabel,
                                    maxLines = 1,
                                )
                            }

                            // 选中徽标或未选提示
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(itemSkin.tagCorner))
                                            .background(itemSkin.colors.accent)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "✓ 已生效",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = preset.category,
                                        fontSize = 11.5.sp,
                                        color = itemSkin.colors.tertiaryLabel,
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowPresets.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = IosTheme.type.footnote,
        color = IosTheme.colors.secondaryLabel,
        modifier = Modifier.padding(
            start = IosMetrics.screenPadding + 16.dp,
            end = IosMetrics.screenPadding,
            top = 24.dp,
            bottom = 6.dp,
        ),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    IosRow {
        Text(
            text = label,
            style = IosTheme.type.body,
            color = IosTheme.colors.label,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = IosTheme.type.subheadline,
            color = IosTheme.colors.secondaryLabel,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 文字按钮,iOS 里强调操作就是一段蓝色文字。 */
@Composable
fun IosTextAction(text: String, color: Color = IosTheme.colors.accent,
    textStyle: androidx.compose.ui.text.TextStyle = IosTheme.type.body, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = text, style = textStyle, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DraftsScreen(
    state: DraftsUiState,
    categories: List<Category>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEditDraft: (DraftItem) -> Unit,
    onDeleteDraft: (DraftItem) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val categoryById = remember(categories) { categories.associateBy { it.id.toInt() } }
    var draftPendingDelete by remember { mutableStateOf<DraftItem?>(null) }
    val pullState = rememberPullToRefreshState()

    Column(
        Modifier
            .fillMaxSize()
            .background(IosTheme.colors.groupedBackground),
    ) {
        IosNavBar(
            title = "我的草稿",
            onBack = onBack,
            trailing = trailing,
        )

        PullToRefreshBox(
            isRefreshing = state.loading && state.loaded,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            indicator = {
                val fraction = pullState.distanceFraction
                if ((state.loading && state.loaded) || fraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .graphicsLayer {
                                alpha = if (state.loading && state.loaded) 1f else min(1f, fraction * 1.4f)
                            },
                    ) {
                        IosActivityIndicator(diameter = 22.dp)
                    }
                }
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.loading && !state.loaded) {
                    item("loading") { IosLoadingBox() }
                }

                state.error?.let { message ->
                    item("error") {
                        ErrorNotice(message, onRefresh)
                    }
                }

                if (state.loaded && state.items.isEmpty() && state.error == null) {
                    item("empty") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 72.dp, bottom = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "暂无保存的草稿",
                                    style = IosTheme.type.headline,
                                    color = IosTheme.colors.secondaryLabel,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "在发帖界面点击“保存”即可将草稿存放在这里",
                                    style = IosTheme.type.footnote,
                                    color = IosTheme.colors.tertiaryLabel,
                                )
                            }
                        }
                    }
                }

                items(state.items, key = { it.draftKey }) { draft ->
                    val category = draft.categoryId?.let { categoryById[it] }
                    val isDeleting = draft.draftKey in state.deletingKeys
                    DraftCard(
                        draft = draft,
                        category = category,
                        isDeleting = isDeleting,
                        onEdit = { onEditDraft(draft) },
                        onDelete = { draftPendingDelete = draft },
                    )
                }

                item("bottom-spacer") {
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    draftPendingDelete?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { draftPendingDelete = null },
            title = { Text("删除草稿") },
            text = {
                val displayTitle = target.title.ifBlank { "无标题草稿" }
                Text("确认要删除草稿「$displayTitle」吗？删除后无法恢复。")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val toDelete = target
                        draftPendingDelete = null
                        onDeleteDraft(toDelete)
                    },
                ) {
                    Text("删除", color = IosTheme.colors.destructive)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { draftPendingDelete = null },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(
    draft: DraftItem,
    category: Category?,
    isDeleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // DraftItem.avatarUrl 已由 TopicRepository 用 avatarUrlOf(avatar_template) 转成绝对地址
    val avatarUrl = draft.avatarUrl?.takeIf { it.isNotBlank() }
    val displayTitle = remember(draft.title, draft.action, draft.topicId) {
        when {
            draft.title.isNotBlank() -> draft.title
            draft.action != "createTopic" && draft.topicId != null -> "回复话题 #${draft.topicId}"
            else -> "（无标题草稿）"
        }
    }
    val summaryText = remember(draft.excerpt, draft.raw) {
        draft.excerpt
            .ifBlank { draft.raw }
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    val timeText = remember(draft.createdAt) {
        RelativeTime.format(draft.createdAt)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(IosTheme.colors.card)
            .border(0.5.dp, IosTheme.colors.separator.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(enabled = !isDeleting, onClick = onEdit)
            .padding(14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = draft.username,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(avatarShape(draft.username, avatarUrl, 10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosTheme.colors.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = draft.username?.firstOrNull()?.uppercase() ?: "草",
                            style = IosTheme.type.headline,
                            color = IosTheme.colors.accent,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayTitle,
                            style = IosTheme.type.headline.copy(fontSize = 16.sp),
                            color = IosTheme.colors.label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (timeText.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = timeText,
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.secondaryLabel,
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (draft.action != "createTopic") {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(IosTheme.colors.accent.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = if (draft.replyToPostNumber != null) "回复 #${draft.replyToPostNumber}" else "回复草稿",
                                    style = IosTheme.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                    color = IosTheme.colors.accent,
                                )
                            }
                        } else if (category != null) {
                            val baseName = categoryBaseNameOf(category.name)
                            val level = categoryLevelOf(category)
                            val dotColor = parseCategoryColor(category.color) ?: IosTheme.colors.accent
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 1.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(dotColor),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = baseName,
                                    style = IosTheme.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                                if (level > 0) {
                                    Spacer(Modifier.width(5.dp))
                                    val (pillBg, _, pillFg) = levelPillColors(level, IosTheme.colors.isDark)
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(pillBg)
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = "Lv.$level",
                                            style = IosTheme.type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                            color = pillFg,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "未选择分类",
                                style = IosTheme.type.caption.copy(fontSize = 12.sp),
                                color = IosTheme.colors.tertiaryLabel,
                            )
                        }

                        draft.tags.forEach { tag ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(IosTheme.colors.fieldBackground)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = tag,
                                    style = IosTheme.type.caption.copy(fontSize = 11.sp),
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }
                        }
                    }
                }
            }

            if (summaryText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = summaryText,
                    style = IosTheme.type.subheadline.copy(lineHeight = 20.sp),
                    color = IosTheme.colors.secondaryLabel,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(IosTheme.colors.fieldBackground)
                        .clickable(enabled = !isDeleting, onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    PostActionIcon(PostAction.Edit, selected = false, tint = IosTheme.colors.secondaryLabel)
                }

                Spacer(Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF4D4F).copy(alpha = 0.14f))
                        .clickable(enabled = !isDeleting, onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDeleting) {
                        IosActivityIndicator(diameter = 14.dp, color = Color(0xFFFF4D4F))
                    } else {
                        PostActionIcon(PostAction.Delete, selected = false, tint = Color(0xFFFF4D4F))
                    }
                }
            }
        }
    }
}

private const val OBJECT_REPLACEMENT_CHAR = "\uFFFC"

internal class CreatorMediaSpan(
    val media: AttachedMedia,
    private val context: Context,
    private val view: EditText,
    private val onRemove: (AttachedMedia) -> Unit,
    private val onPreview: (AttachedMedia) -> Unit,
) : ReplacementSpan() {

    private var cachedBitmap: Bitmap? = null
    private var isBitmapLoading = false
    private var loadFailed = false
    private var retryCount = 0
    private val density = context.resources.displayMetrics.density

    val cardHeight = if (media.isImage) 180f * density else 56f * density
    val paddingVertical = 4f * density
    val cornerRadius = 12f * density
    val closeBtnSize = 32f * density

    val lastDrawBounds = RectF()
    val closeBtnBounds = RectF()

    init {
        loadBitmapAsync()
    }

    private fun loadBitmapAsync(force: Boolean = false) {
        if (!media.isImage || cachedBitmap != null) return
        if (isBitmapLoading && !force) return
        if (loadFailed && !force && retryCount >= 2) return

        isBitmapLoading = true
        loadFailed = false

        val cardW = ((view.width - view.paddingLeft - view.paddingRight)
            .takeIf { it > 100 } ?: context.resources.displayMetrics.widthPixels)
            .coerceIn(100, 1080)
        val cardH = cardHeight.toInt().coerceIn(100, 720)

        // 优先使用 previewUrl，若失败且有远端上传 URL 则自动降级重试远端
        val targetUrl = if (retryCount > 0 && !media.uploaded.url.isNullOrBlank()) {
            val u = media.uploaded.url
            if (u.startsWith("http")) u else org.linuxdo.android.Config.BASE_URL + "/" + u.removePrefix("/")
        } else {
            media.previewUrl
        }

        val request = ImageRequest.Builder(context)
            .data(targetUrl)
            .size(cardW, cardH)
            .precision(Precision.INEXACT)
            .scale(Scale.FILL)
            .allowHardware(false)
            .target(
                onSuccess = { drawable ->
                    cachedBitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                        val w = drawable.intrinsicWidth.coerceAtLeast(1)
                        val h = drawable.intrinsicHeight.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(c)
                        bmp
                    }
                    isBitmapLoading = false
                    loadFailed = false
                    view.postInvalidate()
                },
                onError = {
                    isBitmapLoading = false
                    loadFailed = true
                    retryCount++
                    // 若首次本地 URI 失败且远端有 URL，自动触发一次降级远端拉取
                    val remoteUrl = media.uploaded.url
                    if (retryCount == 1 && !remoteUrl.isNullOrBlank() && targetUrl != remoteUrl) {
                        view.post { loadBitmapAsync(force = true) }
                    } else {
                        view.postInvalidate()
                    }
                }
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun getAvailableWidth(): Float {
        val w = view.width - view.paddingLeft - view.paddingRight
        if (w > 100) return w.toFloat()
        val screenW = context.resources.displayMetrics.widthPixels
        val pad = view.paddingLeft + view.paddingRight
        val fallback = screenW - (if (pad > 0) pad else (32 * density).toInt())
        return fallback.coerceAtLeast(100).toFloat()
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val totalH = cardHeight + paddingVertical * 2
        if (fm != null) {
            val totalInt = totalH.toInt()
            fm.ascent = -totalInt
            fm.descent = 0
            fm.top = fm.ascent
            fm.bottom = 0
        }
        return getAvailableWidth().toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val availWidth = getAvailableWidth()
        val lineH = (bottom - top).toFloat()
        val drawLeft = x
        val drawTop = top + (lineH - cardHeight) / 2f
        val drawRight = x + availWidth
        val drawBottom = drawTop + cardHeight

        lastDrawBounds.set(drawLeft, drawTop, drawRight, drawBottom)
        closeBtnBounds.set(
            drawRight - closeBtnSize - 6f * density,
            drawTop + 6f * density,
            drawRight - 6f * density,
            drawTop + closeBtnSize + 6f * density
        )

        val cardPath = AndroidPath().apply {
            addRoundRect(lastDrawBounds, cornerRadius, cornerRadius, AndroidPath.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(cardPath)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (media.isImage) android.graphics.Color.argb(24, 0, 0, 0) else android.graphics.Color.argb(16, 0, 0, 0)
        }
        canvas.drawRect(lastDrawBounds, bgPaint)

        if (media.isImage) {
            val bmp = cachedBitmap
            if (bmp != null && !bmp.isRecycled) {
                val srcW = bmp.width.toFloat()
                val srcH = bmp.height.toFloat()
                val dstW = lastDrawBounds.width()
                val dstH = lastDrawBounds.height()
                val scale = maxOf(dstW / srcW, dstH / srcH)
                val scaledW = srcW * scale
                val scaledH = srcH * scale
                val dx = lastDrawBounds.left + (dstW - scaledW) / 2f
                val dy = lastDrawBounds.top + (dstH - scaledH) / 2f

                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(dx, dy)
                }
                val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bmp, matrix, bmpPaint)
            } else {
                val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.GRAY
                    textSize = 14f * density
                    textAlign = Paint.Align.CENTER
                }
                val tipText = when {
                    isBitmapLoading -> "🖼️ 正在载入图片…"
                    loadFailed -> "🖼️ ${media.name}（点击放大）"
                    else -> "🖼️ ${media.name}"
                }
                canvas.drawText(tipText, lastDrawBounds.centerX(), lastDrawBounds.centerY() + 5f * density, tipPaint)
                if (!isBitmapLoading && !loadFailed) {
                    loadBitmapAsync()
                }
            }

            val barHeight = 28f * density
            val barRect = RectF(drawLeft, drawBottom - barHeight, drawRight, drawBottom)
            val barPaint = Paint().apply {
                color = android.graphics.Color.argb(130, 0, 0, 0)
            }
            canvas.drawRect(barRect, barPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 11.5f * density
            }
            canvas.drawText("🖼️ ${media.name}（点击放大）", drawLeft + 10f * density, drawBottom - 9f * density, textPaint)
        } else {
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 20f * density
            }
            canvas.drawText("📎", drawLeft + 12f * density, lastDrawBounds.centerY() + 7f * density, iconPaint)

            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.DKGRAY
                textSize = 14f * density
                isFakeBoldText = true
            }
            canvas.drawText(media.name, drawLeft + 42f * density, lastDrawBounds.centerY() - 2f * density, namePaint)

            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.GRAY
                textSize = 11f * density
            }
            canvas.drawText("附件已关联", drawLeft + 42f * density, lastDrawBounds.centerY() + 16f * density, subPaint)
        }

        canvas.restore()

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.argb(30, 0, 0, 0)
            strokeWidth = 1f * density
        }
        canvas.drawRoundRect(lastDrawBounds, cornerRadius, cornerRadius, borderPaint)

        val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(160, 0, 0, 0)
        }
        canvas.drawCircle(closeBtnBounds.centerX(), closeBtnBounds.centerY(), closeBtnBounds.width() / 2f, closeBgPaint)

        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 12f * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("✕", closeBtnBounds.centerX(), closeBtnBounds.centerY() + 4f * density, xPaint)
    }

    fun handleTouch(eventX: Float, eventY: Float): Boolean {
        if (closeBtnBounds.contains(eventX, eventY)) {
            onRemove(media)
            return true
        } else if (lastDrawBounds.contains(eventX, eventY)) {
            if (media.isImage) {
                if (cachedBitmap == null && loadFailed) {
                    loadBitmapAsync(force = true)
                }
                onPreview(media)
            }
            return true
        }
        return false
    }
}

internal class RichMarkdownEditorView(context: Context) : EditText(context) {

    private var onMarkdownChange: ((String) -> Unit)? = null
    private var onRemoveAttachment: ((String) -> Unit)? = null
    private var onPreviewImage: ((String) -> Unit)? = null

    private var isInternalUpdating = false
    private val density = resources.displayMetrics.density

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        textSize = 16f
        setLineSpacing(4f * density, 1.0f)
        hint = "请文明发帖，支持Markdown..."
        val padH = (16 * density).toInt()
        val padV = (8 * density).toInt()
        setPadding(padH, padV, padH, (24 * density).toInt())
        isVerticalScrollBarEnabled = true
        isSingleLine = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setHorizontallyScrolling(false)

        addTextChangedListener(object : TextWatcher {
            private var beforeSpans: Array<CreatorMediaSpan> = emptyArray()

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isInternalUpdating) return
                val sp = s as? Spanned ?: return
                beforeSpans = sp.getSpans(start, start + count, CreatorMediaSpan::class.java)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isInternalUpdating) return
                if (beforeSpans.isNotEmpty() && s != null) {
                    val currentSpans = s.getSpans(0, s.length, CreatorMediaSpan::class.java).toSet()
                    for (oldSpan in beforeSpans) {
                        if (oldSpan !in currentSpans) {
                            onRemoveAttachment?.invoke(oldSpan.media.id)
                        }
                    }
                }
                onMarkdownChange?.invoke(exportToMarkdown())
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val ed = text
            if (ed != null) {
                val touchX = event.x - totalPaddingLeft + scrollX
                val touchY = event.y - totalPaddingTop + scrollY
                val spans = ed.getSpans(0, ed.length, CreatorMediaSpan::class.java)
                for (span in spans) {
                    if (span.handleTouch(touchX, touchY)) {
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    fun setOnMarkdownChangeListener(listener: (String) -> Unit) {
        onMarkdownChange = listener
    }

    fun setOnRemoveAttachmentListener(listener: (String) -> Unit) {
        onRemoveAttachment = listener
    }

    fun setOnPreviewImageListener(listener: (String) -> Unit) {
        onPreviewImage = listener
    }

    fun updateThemeColors(textColor: Int, hintColor: Int) {
        setTextColor(textColor)
        setHintTextColor(hintColor)
    }

    fun requestInputFocus() {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        text?.let { ed ->
            setSelection(ed.length)
        }
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun insertToken(token: String) {
        val ed = text ?: return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        val min = minOf(start, end)
        val max = maxOf(start, end)
        ed.replace(min, max, token)
        setSelection((min + token.length).coerceIn(0, ed.length))
        requestFocus()
    }

    fun insertMedia(media: AttachedMedia) {
        val ed = text ?: return
        val cursor = selectionStart.coerceAtLeast(0)
        val sb = SpannableStringBuilder()
        val isStartOfLine = cursor == 0 || (cursor > 0 && ed[cursor - 1] == '\n')
        if (!isStartOfLine) sb.append("\n")
        val spanStart = sb.length
        sb.append(OBJECT_REPLACEMENT_CHAR)
        sb.setSpan(
            createSpan(media),
            spanStart,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 避免重复叠加换行：若光标后已有换行，则无需再追加 \n
        val hasTrailingNewline = cursor < ed.length && ed[cursor] == '\n'
        if (!hasTrailingNewline) {
            sb.append("\n")
        }

        val end = selectionEnd.coerceAtLeast(cursor)
        ed.replace(cursor, end, sb)
        val newCaret = (cursor + sb.length).coerceIn(0, ed.length)
        setSelection(newCaret)
        requestFocus()
    }

    fun syncAttachments(attachments: List<AttachedMedia>) {
        val ed = text ?: return
        val currentSpans = ed.getSpans(0, ed.length, CreatorMediaSpan::class.java)
        val currentIds = currentSpans.map { it.media.id }.toSet()
        val newAttachments = attachments.filter { it.id !in currentIds }
        if (newAttachments.isNotEmpty()) {
            for (newMedia in newAttachments) {
                insertMedia(newMedia)
            }
        }
    }

    fun removeMediaSpan(mediaId: String) {
        val ed = text ?: return
        val spans = ed.getSpans(0, ed.length, CreatorMediaSpan::class.java)
        for (span in spans) {
            if (span.media.id == mediaId) {
                val start = ed.getSpanStart(span)
                val end = ed.getSpanEnd(span)
                if (start >= 0 && end >= start) {
                    isInternalUpdating = true
                    val deleteStart = if (start > 0 && ed[start - 1] == '\n') start - 1 else start
                    val deleteEnd = if (end < ed.length && ed[end] == '\n') end + 1 else end
                    ed.delete(deleteStart, deleteEnd)
                    isInternalUpdating = false
                    onMarkdownChange?.invoke(exportToMarkdown())
                }
                break
            }
        }
    }

    fun setInitialMarkdown(raw: String, attachments: List<AttachedMedia>) {
        isInternalUpdating = true
        if (attachments.isEmpty()) {
            setText(raw)
            setSelection(raw.length)
            isInternalUpdating = false
            return
        }

        data class Match(val range: IntRange, val media: AttachedMedia)
        val matches = mutableListOf<Match>()
        for (media in attachments) {
            val shortUrl = media.uploaded.shortUrl
            if (shortUrl.isBlank()) continue
            val pattern = Regex("""(!?\[[^\]]*\]\(${Regex.escape(shortUrl)}\))""")
            val m = pattern.find(raw)
            if (m != null) {
                matches.add(Match(m.range, media))
            }
        }

        val sb = SpannableStringBuilder()
        if (matches.isEmpty()) {
            sb.append(raw.trimEnd())
            for (media in attachments) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                val spanStart = sb.length
                sb.append(OBJECT_REPLACEMENT_CHAR)
                sb.setSpan(createSpan(media), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
            }
        } else {
            matches.sortBy { it.range.first }
            var cursor = 0
            for (match in matches) {
                if (match.range.first > cursor) {
                    val textBefore = raw.substring(cursor, match.range.first)
                    // 卡片自身独占高度，若前序段落末尾有多余空行（如 \n\n），收拢为单换行避免留白过大
                    val trimmed = if (textBefore.endsWith("\n\n")) textBefore.trimEnd('\n') + "\n" else textBefore
                    sb.append(trimmed)
                }
                if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                    sb.append("\n")
                }
                val spanStart = sb.length
                sb.append(OBJECT_REPLACEMENT_CHAR)
                sb.setSpan(createSpan(match.media), spanStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
                cursor = match.range.last + 1
                // 跳过紧随卡片后面的连续空行换行符（如 Markdown 常用的 \n\n）
                while (cursor < raw.length && raw[cursor] == '\n') {
                    cursor++
                }
            }
            if (cursor < raw.length) {
                sb.append(raw.substring(cursor))
            }
        }

        setText(sb)
        setSelection(sb.length)
        isInternalUpdating = false
    }

    fun exportToMarkdown(): String {
        val ed = text ?: return ""
        val spans = ed.getSpans(0, ed.length, CreatorMediaSpan::class.java)
        if (spans.isEmpty()) return ed.toString().trimEnd()

        val sorted = spans.map { span ->
            Triple(ed.getSpanStart(span), ed.getSpanEnd(span), span)
        }.filter { it.first >= 0 && it.second >= it.first }.sortedBy { it.first }

        val sb = StringBuilder()
        var cursor = 0
        for ((start, end, span) in sorted) {
            if (start > cursor) {
                val seg = ed.subSequence(cursor, start).toString()
                sb.append(seg.trimEnd('\n'))
            }
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append(span.media.toMarkdown())
            sb.append("\n\n")
            cursor = end
        }
        if (cursor < ed.length) {
            val tail = ed.subSequence(cursor, ed.length).toString()
            sb.append(tail.trimStart('\n'))
        }
        return sb.toString().trimEnd()
    }

    private fun createSpan(media: AttachedMedia): CreatorMediaSpan {
        return CreatorMediaSpan(
            media = media,
            context = context,
            view = this,
            onRemove = { m ->
                removeMediaSpan(m.id)
                onRemoveAttachment?.invoke(m.id)
            },
            onPreview = { m ->
                onPreviewImage?.invoke(m.previewUrl)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicCreatorSheet(
    state: TopicCreatorUiState,
    categories: List<Category>,
    onTitleChange: (String) -> Unit,
    onRawChange: (String) -> Unit,
    onCategoryChange: (Int?) -> Unit,
    onTagsChange: (List<String>) -> Unit,
    onSearchTags: (String) -> Unit = {},
    onAttach: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit = {},
    onSaveDraft: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onClearNotice: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showCloseConfirmDialog by remember { mutableStateOf(false) }
    var previewingImage by remember { mutableStateOf<String?>(null) }

    var editorView by remember { mutableStateOf<RichMarkdownEditorView?>(null) }

    LaunchedEffect(state.attachments) {
        editorView?.syncAttachments(state.attachments)
    }

    val appendTextToken = { token: String ->
        editorView?.insertToken(token)
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) onAttach(uri) },
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) onAttach(uri) },
    )

    val attemptClose = {
        if (state.savingDraft || state.submitting) {
            Unit
        } else if (state.hasUnsavedChanges) {
            showCloseConfirmDialog = true
        } else {
            onClose()
        }
    }

    BackHandler {
        when {
            showEmojiPicker -> showEmojiPicker = false
            else -> attemptClose()
        }
    }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2200)
            onClearNotice()
        }
    }

    val flattenedCategories = remember(categories) {
        flattenCategories(categories)
    }
    val categoryGroups = remember(flattenedCategories) {
        compactCategoryGroups(flattenedCategories)
    }
    val selectedCategory = remember(flattenedCategories, state.categoryId) {
        state.categoryId?.let { id -> flattenedCategories.firstOrNull { it.id.toInt() == id } }
    }
    val selectedGroup = remember(categoryGroups, state.categoryId) {
        val targetId = state.categoryId ?: return@remember null
        categoryGroups.firstOrNull { (parent, levels) ->
            parent.id == targetId || levels.any { it.id == targetId }
        }
    }
    val selectedGroupVariants = remember(selectedGroup) {
        if (selectedGroup == null) {
            emptyList()
        } else {
            (listOf(selectedGroup.first) + selectedGroup.second.sortedBy { categoryLevelOf(it) })
                .distinctBy { it.id }
                .filter { categoryLevelOf(it) in 0..3 }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosTheme.colors.card)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // 顶部操作栏：关闭 ✕ + 标题 + 保存 + 发布
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(IosTheme.colors.fieldBackground)
                    .clickable(enabled = !state.savingDraft && !state.submitting) { attemptClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = IosTheme.colors.secondaryLabel,
                )
            }

            Spacer(Modifier.width(10.dp))

            Text(
                text = if (state.isReplyDraft) "编辑回复草稿" else "创建新话题",
                style = IosTheme.type.headline.copy(fontSize = 17.sp),
                color = IosTheme.colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // 保存草稿按钮
            val canSave = state.canSaveDraft
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (canSave) IosTheme.colors.accent.copy(alpha = 0.12f)
                        else IosTheme.colors.fieldBackground,
                    )
                    .clickable(enabled = canSave) {
                        editorView?.exportToMarkdown()?.let { onRawChange(it) }
                        onSaveDraft(false)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.savingDraft) "保存中…" else "保存",
                    style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = if (canSave) IosTheme.colors.accent else IosTheme.colors.tertiaryLabel,
                )
            }

            Spacer(Modifier.width(8.dp))

            // 发布按钮
            val canPublish = state.canSubmit
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (canPublish) IosTheme.colors.accent
                        else IosTheme.colors.accent.copy(alpha = 0.35f),
                    )
                    .clickable(enabled = canPublish) {
                        editorView?.exportToMarkdown()?.let { onRawChange(it) }
                        onSubmit()
                    }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.submitting) "发布中…" else if (state.isReplyDraft) "发送" else "发布",
                    style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }

        IosDivider()

        // 错误或保存成功轻提示
        state.error?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IosTheme.colors.destructive.copy(alpha = 0.10f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = err,
                    style = IosTheme.type.footnote,
                    color = IosTheme.colors.destructive,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        state.notice?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF52C41A).copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "✓ $msg",
                    style = IosTheme.type.footnote.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF389E0D),
                )
            }
        }

        if (!state.isReplyDraft) {
            // 分类与标签在同一行，高 38.dp，RoundedCornerShape(50) 胶囊形态，样式高度一致，无黄色底色
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧分类胶囊：宽度充裕，未选择时也具有良好开阔度
                val dotColor = selectedCategory?.let { parseCategoryColor(it.color) } ?: IosTheme.colors.accent
                val baseName = selectedCategory?.let { categoryBaseNameOf(it.name) }
                val level = selectedCategory?.let { categoryLevelOf(it) } ?: 0
                val isRestricted = (level > 0) || (selectedCategory?.readRestricted == true)

                Row(
                    modifier = Modifier
                        .weight(1.15f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IosTheme.colors.card)
                        .border(1.dp, IosTheme.colors.separator.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .clickable { showCategoryDialog = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedCategory != null) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(6.dp))
                        if (isRestricted) {
                            ComposerLockIcon(
                                tint = IosTheme.colors.secondaryLabel,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (level > 0) "$baseName Lv.$level" else baseName.orEmpty(),
                            style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Medium),
                            color = IosTheme.colors.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else {
                        Text(
                            text = "选择分类…",
                            style = IosTheme.type.subheadline,
                            color = IosTheme.colors.secondaryLabel,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "▾",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }

                // 右侧标签胶囊：与分类胶囊大小样式保持完全一致，根据当前分类下拉选择
                Box(modifier = Modifier.weight(1f)) {
                    var showTagDropdown by remember { mutableStateOf(false) }
                    var tagSearchQuery by remember { mutableStateOf("") }
                    val candidateTags: List<DiscourseTagInfo> = remember(state.availableTags, selectedCategory) {
                        if (state.availableTags.isNotEmpty()) state.availableTags
                        else fixedTagsForCategory(selectedCategory).map { DiscourseTagInfo(name = it, text = it, count = 0) }
                    }
                    val filteredCandidateTags = remember(candidateTags, tagSearchQuery) {
                        if (tagSearchQuery.isBlank()) candidateTags
                        else candidateTags.filter {
                            it.name.contains(tagSearchQuery.trim(), ignoreCase = true) ||
                            it.text.contains(tagSearchQuery.trim(), ignoreCase = true)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(50))
                            .background(IosTheme.colors.card)
                            .border(1.dp, IosTheme.colors.separator.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .clickable { showTagDropdown = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ComposerTagIcon(
                            tint = if (state.tags.isNotEmpty()) IosTheme.colors.accent else IosTheme.colors.secondaryLabel,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        if (state.tags.isEmpty()) {
                            Text(
                                text = "可选标签…",
                                style = IosTheme.type.subheadline,
                                color = IosTheme.colors.secondaryLabel,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "+",
                                style = IosTheme.type.headline.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal),
                                color = IosTheme.colors.secondaryLabel,
                            )
                        } else {
                            Text(
                                text = state.tags.joinToString(", "),
                                style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Medium),
                                color = IosTheme.colors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "▾",
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.secondaryLabel,
                            )
                        }
                    }

                    // 下拉选择菜单（根据分类动态调取 /tags/filter/search 返回的候选标签）
                    DropdownMenu(
                        expanded = showTagDropdown,
                        onDismissRequest = {
                            showTagDropdown = false
                            tagSearchQuery = ""
                        },
                        modifier = Modifier
                            .widthIn(min = 250.dp, max = 320.dp)
                            .heightIn(max = 340.dp)
                            .background(IosTheme.colors.card),
                    ) {
                        // 顶部必选标签组提示（如果有）
                        state.requiredTagGroupName?.let { groupName ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(IosTheme.colors.accent.copy(alpha = 0.10f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = "要求至少选择 ${state.requiredTagMinCount} 个「$groupName」标签",
                                    style = IosTheme.type.caption.copy(fontSize = 11.5.sp),
                                    color = IosTheme.colors.accent,
                                )
                            }
                        }

                        // 顶部搜索过滤输入框（对齐参考图）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(IosTheme.colors.fieldBackground)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            BasicTextField(
                                value = tagSearchQuery,
                                onValueChange = { query ->
                                    tagSearchQuery = query
                                    onSearchTags(query)
                                },
                                singleLine = true,
                                textStyle = IosTheme.type.caption.copy(color = IosTheme.colors.label, fontSize = 13.sp),
                                cursorBrush = SolidColor(IosTheme.colors.accent),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerField ->
                                    Box {
                                        if (tagSearchQuery.isEmpty()) {
                                            Text(
                                                text = "搜索…",
                                                style = IosTheme.type.caption.copy(fontSize = 13.sp),
                                                color = IosTheme.colors.tertiaryLabel,
                                            )
                                        }
                                        innerField()
                                    }
                                },
                            )
                        }

                        if (state.loadingTags) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IosActivityIndicator(diameter = 14.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "加载标签中…",
                                    style = IosTheme.type.caption,
                                    color = IosTheme.colors.secondaryLabel,
                                )
                            }
                        }

                        if (filteredCandidateTags.isEmpty() && !state.loadingTags) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (tagSearchQuery.isBlank()) "当前分类无可选标签" else "未找到匹配标签",
                                        style = IosTheme.type.caption,
                                        color = IosTheme.colors.secondaryLabel,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                        } else {
                            for (tagItem in filteredCandidateTags) {
                                val isSelected = tagItem.name in state.tags
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false),
                                            ) {
                                                // 浅灰背景标签徽章块，对齐参考图
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(
                                                            if (isSelected) IosTheme.colors.accent.copy(alpha = 0.12f)
                                                            else IosTheme.colors.fieldBackground
                                                        )
                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                ) {
                                                    Text(
                                                        text = tagItem.text.ifBlank { tagItem.name },
                                                        style = IosTheme.type.subheadline.copy(
                                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                            fontSize = 13.5.sp,
                                                        ),
                                                        color = if (isSelected) IosTheme.colors.accent else IosTheme.colors.label,
                                                    )
                                                }
                                                if (tagItem.count > 0) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        text = "x${tagItem.count}",
                                                        style = IosTheme.type.caption.copy(fontSize = 12.sp),
                                                        color = IosTheme.colors.secondaryLabel,
                                                    )
                                                }
                                            }
                                            if (isSelected) {
                                                Text(
                                                    text = "✓",
                                                    style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Bold),
                                                    color = IosTheme.colors.accent,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (isSelected) onTagsChange(state.tags - tagItem.name)
                                        else onTagsChange(state.tags + tagItem.name)
                                    },
                                )
                            }
                        }

                        if (state.tags.isNotEmpty()) {
                            IosDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "清空已选标签 (${state.tags.size})",
                                        style = IosTheme.type.caption.copy(color = IosTheme.colors.destructive),
                                    )
                                },
                                onClick = { onTagsChange(emptyList()) },
                            )
                        }
                    }
                }
            }

            IosDivider()

            // 标题输入框 (带右侧 0/6 字符提示)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    textStyle = IosTheme.type.headline.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IosTheme.colors.label,
                    ),
                    cursorBrush = SolidColor(IosTheme.colors.accent),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (state.title.isEmpty()) {
                                Text(
                                    text = "输入标题，或在此处粘贴链接",
                                    style = IosTheme.type.headline.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                    color = IosTheme.colors.tertiaryLabel,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                val titleLen = state.title.length
                Text(
                    text = "$titleLen / 6",
                    style = IosTheme.type.caption.copy(fontWeight = FontWeight.Medium),
                    color = if (titleLen < 6) Color(0xFFFF453A) else IosTheme.colors.secondaryLabel,
                )
            }

            IosDivider()
        } else {
            // 回复草稿信息提示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IosTheme.colors.groupedBackground.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.title.ifBlank { "回复话题 #${state.replyTopicId ?: ""}" },
                    style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.Medium),
                    color = IosTheme.colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IosDivider()
        }

        // 正文编辑区（所见即所得图文流：基于原生 EditText + ReplacementSpan，全光标连续穿越）
        val textColorInt = IosTheme.colors.label.toArgb()
        val hintColorInt = IosTheme.colors.tertiaryLabel.toArgb()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    RichMarkdownEditorView(ctx).apply {
                        updateThemeColors(textColorInt, hintColorInt)
                        setInitialMarkdown(state.raw, state.attachments)
                        setOnMarkdownChangeListener { newRaw -> onRawChange(newRaw) }
                        setOnRemoveAttachmentListener { mediaId -> onRemoveAttachment(mediaId) }
                        setOnPreviewImageListener { previewUrl -> previewingImage = previewUrl }
                        editorView = this
                    }
                },
                update = { view ->
                    view.updateThemeColors(textColorInt, hintColorInt)
                },
            )

            // 图片上传中悬浮提示卡片
            if (state.uploading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IosTheme.colors.fieldBackground.copy(alpha = 0.95f))
                        .border(1.dp, IosTheme.colors.separator.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IosActivityIndicator(diameter = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在上传图片并插入正文…",
                            style = IosTheme.type.footnote,
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        IosDivider()

        // 底部工具栏：图片上传、文件上传、Emoji、快捷 Markdown、字数提示
        val canPickMedia = !state.uploading && !state.submitting && !state.savingDraft
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosTheme.colors.groupedBackground.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 上传图片
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = canPickMedia) {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center,
            ) {
                ComposerImageIcon(
                    tint = if (canPickMedia) IosTheme.colors.secondaryLabel else IosTheme.colors.tertiaryLabel,
                )
            }

            Spacer(Modifier.width(4.dp))

            // 上传附件/文件
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = canPickMedia) {
                        filePicker.launch(arrayOf("*/*"))
                    },
                contentAlignment = Alignment.Center,
            ) {
                ComposerAttachmentIcon(
                    tint = if (canPickMedia) IosTheme.colors.secondaryLabel else IosTheme.colors.tertiaryLabel,
                )
            }

            Spacer(Modifier.width(4.dp))

            // Emoji 面板开关
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (showEmojiPicker) IosTheme.colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                    )
                    .clickable(enabled = canPickMedia) {
                        if (!showEmojiPicker) keyboard?.hide()
                        showEmojiPicker = !showEmojiPicker
                    },
                contentAlignment = Alignment.Center,
            ) {
                ComposerEmojiIcon(
                    tint = if (showEmojiPicker) IosTheme.colors.accent
                    else if (canPickMedia) IosTheme.colors.secondaryLabel
                    else IosTheme.colors.tertiaryLabel,
                )
            }

            Spacer(Modifier.width(4.dp))

            // 粗体图标：与表情/图片/附件图标完全一致的 36.dp 规范与矢量绘制
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = canPickMedia) {
                        appendTextToken("**粗体文字**")
                    },
                contentAlignment = Alignment.Center,
            ) {
                ComposerBoldIcon(
                    tint = if (canPickMedia) IosTheme.colors.secondaryLabel else IosTheme.colors.tertiaryLabel,
                )
            }

            Spacer(Modifier.weight(1f))

            if (state.uploading) {
                IosActivityIndicator(diameter = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "上传中…",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.secondaryLabel,
                )
            } else {
                Text(
                    text = "${state.raw.length} 字",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.tertiaryLabel,
                )
            }
        }

        if (showEmojiPicker) {
            IosDivider()
            QuickEmojiPicker(
                onSelect = { token -> appendTextToken(token) },
            )
        }
    }

    // 分类选择弹窗
    if (showCategoryDialog) {
        CategoryPickerDialog(
            groups = categoryGroups,
            selectedCategoryId = state.categoryId,
            onSelect = { id ->
                val newCat = categories.firstOrNull { it.id.toInt() == id }
                val newAllowed = fixedTagsForCategory(newCat)
                val validTags = state.tags.filter { it in newAllowed }
                if (validTags != state.tags) {
                    onTagsChange(validTags)
                }
                onCategoryChange(id)
                showCategoryDialog = false
            },
            onDismiss = { showCategoryDialog = false },
        )
    }

    // 标签选择弹窗（从固定列表中勾选，不同分类不同标签）
    if (showTagDialog) {
        TagPickerDialog(
            category = selectedCategory,
            currentTags = state.tags,
            onConfirm = { newTags ->
                onTagsChange(newTags)
                showTagDialog = false
            },
            onDismiss = { showTagDialog = false },
        )
    }

    // 关闭二次确认对话框
    // 关闭二次确认对话框（参考最新 UI：右上角关闭 X，居中标题与说明，垂直全宽胶囊按钮）
    if (showCloseConfirmDialog) {
        Dialog(onDismissRequest = { showCloseConfirmDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(IosTheme.colors.card)
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 右上角关闭 ✕（点击关闭弹框，继续编辑）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { showCloseConfirmDialog = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✕",
                            style = IosTheme.type.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            color = IosTheme.colors.secondaryLabel,
                        )
                    }
                }

                Text(
                    text = "保存草稿？",
                    style = IosTheme.type.largeTitle.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    color = IosTheme.colors.label,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "当前有未保存的内容，关闭前是否\n将其保存为草稿？",
                    style = IosTheme.type.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = IosTheme.colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                // 蓝色胶囊主按钮：保存草稿并退出
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IosTheme.colors.accent)
                        .clickable {
                            showCloseConfirmDialog = false
                            editorView?.exportToMarkdown()?.let { onRawChange(it) }
                            onSaveDraft(true)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "保存草稿并退出",
                        style = IosTheme.type.headline.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                        color = Color.White,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 浅红背景红字胶囊按钮：放弃更改
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFFF453A).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFFFF453A).copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable {
                            showCloseConfirmDialog = false
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "放弃更改",
                        style = IosTheme.type.headline.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                        color = Color(0xFFFF453A),
                    )
                }
            }
        }
    }

    previewingImage?.let { previewUrl ->
        org.linuxdo.android.ui.screen.ImageViewer(url = previewUrl) { previewingImage = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPickerDialog(
    groups: List<Pair<Category, List<Category>>>,
    selectedCategoryId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(IosTheme.colors.card)
                .padding(vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "选择话题分类",
                    style = IosTheme.type.headline,
                    color = IosTheme.colors.label,
                    modifier = Modifier.weight(1f),
                )
                IosTextAction("关闭", color = IosTheme.colors.secondaryLabel, onClick = onDismiss)
            }

            Spacer(Modifier.height(8.dp))
            IosDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(groups, key = { it.first.id }) { (parent, levels) ->
                    val baseName = categoryBaseNameOf(parent.name)
                    val dotColor = parseCategoryColor(parent.color) ?: IosTheme.colors.accent
                    val variants = remember(parent, levels) {
                        (listOf(parent) + levels.sortedBy { categoryLevelOf(it) })
                            .distinctBy { it.id }
                            .filter { categoryLevelOf(it) in 0..3 }
                    }
                    val isGroupSelected = selectedCategoryId != null && variants.any { it.id.toInt() == selectedCategoryId }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isGroupSelected) IosTheme.colors.accent.copy(alpha = 0.06f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(parent.id.toInt()) }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = baseName,
                                style = IosTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = IosTheme.colors.label,
                                modifier = Modifier.weight(1f),
                            )

                            if (variants.size > 1) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for (variant in variants) {
                                        val lvl = categoryLevelOf(variant)
                                        val isVariantSelected = selectedCategoryId == variant.id.toInt()
                                        val (pillBg, _, pillFg) = levelPillColors(lvl, IosTheme.colors.isDark)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isVariantSelected) IosTheme.colors.accent else pillBg)
                                                .clickable { onSelect(variant.id.toInt()) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = "Lv.$lvl",
                                                style = IosTheme.type.caption.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = if (isVariantSelected) Color.White else pillFg,
                                            )
                                        }
                                    }
                                }
                            } else if (selectedCategoryId == parent.id.toInt()) {
                                Text(
                                    text = "✓",
                                    style = IosTheme.type.headline,
                                    color = IosTheme.colors.accent,
                                )
                            }
                        }

                        if (!parent.description.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = parent.description,
                                style = IosTheme.type.caption,
                                color = IosTheme.colors.secondaryLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IosDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPickerDialog(
    category: Category?,
    currentTags: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var workingTags by remember(currentTags) { mutableStateOf(currentTags.toSet()) }
    val availableTags = remember(category) { fixedTagsForCategory(category) }
    val categoryName = category?.let { categoryBaseNameOf(it.name) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(IosTheme.colors.card)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选择标签",
                        style = IosTheme.type.headline,
                        color = IosTheme.colors.label,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (categoryName != null) "分类「$categoryName」可选固定标签" else "可选固定标签列表",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
                IosTextAction("关闭", color = IosTheme.colors.secondaryLabel, onClick = onDismiss)
            }

            Spacer(Modifier.height(14.dp))
            IosDivider()
            Spacer(Modifier.height(14.dp))

            if (availableTags.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "当前分类暂无可关联标签",
                        style = IosTheme.type.subheadline,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (tag in availableTags) {
                            val isSelected = tag in workingTags
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) IosTheme.colors.accent
                                        else IosTheme.colors.fieldBackground,
                                    )
                                    .clickable {
                                        workingTags = if (isSelected) {
                                            workingTags - tag
                                        } else {
                                            workingTags + tag
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (isSelected) "# $tag ✓" else "# $tag",
                                    style = IosTheme.type.subheadline.copy(
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = if (isSelected) Color.White else IosTheme.colors.label,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            IosDivider()
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${workingTags.size} 个标签",
                    style = IosTheme.type.caption,
                    color = IosTheme.colors.secondaryLabel,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosTextAction("清空", color = IosTheme.colors.secondaryLabel, onClick = { workingTags = emptySet() })
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosTheme.colors.accent)
                            .clickable {
                                onConfirm(workingTags.toList())
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "确定",
                            style = IosTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// ── Discourse 官方徽章展示组件 ──
// ==========================================

enum class BadgeFilterTab(val label: String) {
    All("全部"),
    Earned("已解锁"),
    Unearned("待解锁")
}

@Composable
fun UserBadgesSectionCard(
    badges: List<UserBadgeItem>?,
    isLoading: Boolean,
    onRefresh: (() -> Unit)?,
    onBadgeClick: (UserBadgeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = IosTheme.colors.isDark
    var selectedTab by remember { mutableStateOf(BadgeFilterTab.All) }

    val earnedCount = remember(badges) { badges?.count { it.isEarned } ?: 0 }
    val totalCount = remember(badges) { badges?.size ?: 0 }

    val filteredList = remember(badges, selectedTab) {
        val list = badges.orEmpty()
        when (selectedTab) {
            BadgeFilterTab.All -> list
            BadgeFilterTab.Earned -> list.filter { it.isEarned }
            BadgeFilterTab.Unearned -> list.filter { !it.isEarned }
        }
    }

    // 玻璃感卡片背景与微光边框 (与分类页/个人中心快捷卡片高度协同)
    val glassBg = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF222428).copy(alpha = 0.88f),
                Color(0xFF1A1B1F).copy(alpha = 0.94f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFFFFFFF).copy(alpha = 0.92f),
                Color(0xFFF7F8FA).copy(alpha = 0.98f)
            )
        )
    }
    val glassBorder = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.95f),
                Color.Black.copy(alpha = 0.06f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(glassBg)
            .border(1.dp, glassBorder, RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. 卡片顶栏：左侧标题与计数，右侧切页药丸
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "徽章展示",
                        style = IosTheme.type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = IosTheme.colors.label,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isDark) Color(0xFF2C2F36) else Color(0xFFEFF1F5)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (totalCount > 0) "$earnedCount/$totalCount" else "...",
                            style = IosTheme.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = IosTheme.colors.accent,
                        )
                    }
                }

                // 右侧微型切页药丸 Tab
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDark) Color(0xFF16171A) else Color(0xFFECEEF2))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgeFilterTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSelected) {
                                        if (isDark) Color(0xFF2E3138) else Color.White
                                    } else Color.Transparent
                                )
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.label,
                                style = IosTheme.type.caption.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (isSelected) IosTheme.colors.label else IosTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 2. 徽章主体展示区 (2行3列网格，支持左右平滑滑动翻页)
            if (isLoading && badges == null) {
                // 骨架屏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(3) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(IosTheme.colors.separator.copy(alpha = 0.25f))
                            )
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(IosTheme.colors.separator.copy(alpha = 0.2f))
                            )
                        }
                    }
                }
            } else if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedTab == BadgeFilterTab.Earned) "暂无已解锁的徽章" else "暂无徽章数据",
                        style = IosTheme.type.caption,
                        color = IosTheme.colors.secondaryLabel,
                    )
                }
            } else {
                val pageItemsCount = 6 // 2 行 x 3 列
                val pageCount = (filteredList.size + pageItemsCount - 1) / pageItemsCount
                val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                    pageCount = { pageCount.coerceAtLeast(1) }
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp),
                    ) { pageIdx ->
                        val startIndex = pageIdx * pageItemsCount
                        val pageItems = filteredList.drop(startIndex).take(pageItemsCount)
                        val row1 = pageItems.take(3)
                        val row2 = pageItems.drop(3).take(3)

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 第一行 (最多 3 个)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround,
                            ) {
                                row1.forEach { badge ->
                                    SingleBadgeItemView(badge = badge, onClick = { onBadgeClick(badge) })
                                }
                                repeat(3 - row1.size) {
                                    Spacer(Modifier.width(68.dp))
                                }
                            }
                            // 第二行 (最多 3 个)
                            if (row2.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                ) {
                                    row2.forEach { badge ->
                                        SingleBadgeItemView(badge = badge, onClick = { onBadgeClick(badge) })
                                    }
                                    repeat(3 - row2.size) {
                                        Spacer(Modifier.width(68.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 底部多页小圆点指示器 (若多于 1 页)
                    if (pageCount > 1) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(pageCount) { p ->
                                val active = p == pagerState.currentPage
                                Box(
                                    modifier = Modifier
                                        .size(if (active) 6.dp else 4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (active) IosTheme.colors.accent
                                            else IosTheme.colors.tertiaryLabel.copy(alpha = 0.5f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleBadgeItemView(
    badge: UserBadgeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = IosTheme.colors.isDark

    // 金 (1)、银 (2)、铜 (3) 或待解锁深色质感圆盘
    val diskGradient = when {
        !badge.isEarned -> Brush.verticalGradient(
            listOf(
                Color(0xFF2C2D32).copy(alpha = 0.65f),
                Color(0xFF1E1F24).copy(alpha = 0.85f)
            )
        )
        badge.badgeTypeId == 1 -> Brush.linearGradient(
            listOf(Color(0xFFFFDF00), Color(0xFFFFA000), Color(0xFFFF6F00))
        )
        badge.badgeTypeId == 2 -> Brush.linearGradient(
            listOf(Color(0xFFFFFFFF), Color(0xFFCFD8DC), Color(0xFF90A4AE))
        )
        else -> Brush.linearGradient(
            listOf(Color(0xFFFFAB91), Color(0xFFFF7043), Color(0xFFD84315))
        )
    }

    val diskBorder = when {
        !badge.isEarned -> Color.White.copy(alpha = 0.10f)
        badge.badgeTypeId == 1 -> Color(0xFFFFECB3).copy(alpha = 0.75f)
        badge.badgeTypeId == 2 -> Color.White.copy(alpha = 0.85f)
        else -> Color(0xFFFFCCBC).copy(alpha = 0.70f)
    }

    Column(
        modifier = modifier
            .width(68.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(54.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 圆盘底座 (带微光渐变与边框)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(diskGradient)
                    .border(1.2.dp, diskBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DiscourseBadgeIcon(
                    iconName = badge.icon,
                    imageUrl = badge.imageUrl,
                    isEarned = badge.isEarned,
                    badgeTypeId = badge.badgeTypeId,
                    modifier = Modifier.size(26.dp)
                )
            }

            // 若未获得，右下角带有优雅微型锁定小徽标 🔒
            if (!badge.isEarned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1D21))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(9.dp)) {
                        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                            val stroke = Stroke(2.2f, cap = StrokeCap.Round)
                            // 锁身
                            drawRoundRect(
                                Color.White.copy(alpha = 0.85f),
                                topLeft = Offset(4f, 10f),
                                size = Size(16f, 12f),
                                cornerRadius = CornerRadius(2.5f, 2.5f)
                            )
                            // 锁梁
                            drawArc(
                                Color.White.copy(alpha = 0.85f),
                                startAngle = 180f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(7f, 3f),
                                size = Size(10f, 12f),
                                style = stroke
                            )
                        }
                    }
                }
            } else if (badge.count > 1) {
                // 若多次获得，右上角显示 xN 计数气泡
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-2).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(0.6.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "x${badge.count}",
                        style = IosTheme.type.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        // 官方徽章名称 (居中，截断)
        Text(
            text = badge.name,
            style = IosTheme.type.caption.copy(
                fontSize = 11.5.sp,
                fontWeight = if (badge.isEarned) FontWeight.Medium else FontWeight.Normal
            ),
            color = if (badge.isEarned) IosTheme.colors.label else IosTheme.colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DiscourseBadgeIcon(
    iconName: String?,
    imageUrl: String?,
    isEarned: Boolean,
    badgeTypeId: Int,
    modifier: Modifier = Modifier,
) {
    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = modifier.clip(CircleShape),
        )
        return
    }

    val iconColor = if (isEarned) {
        if (badgeTypeId == 2) Color(0xFF455A64) else Color.White
    } else {
        Color.White.copy(alpha = 0.45f)
    }

    val name = iconName?.lowercase(Locale.ROOT)?.removePrefix("fa-")?.removePrefix("fab-")?.removePrefix("far-").orEmpty()

    Canvas(modifier = modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when {
                name.contains("pen") || name.contains("edit") -> {
                    // 铅笔 / 编辑图标
                    val p = Path().apply {
                        moveTo(4f, 20f); lineTo(8f, 19f); lineTo(19f, 8f); lineTo(16f, 5f); lineTo(5f, 16f); close()
                    }
                    drawPath(p, iconColor, style = stroke)
                    drawLine(iconColor, Offset(13f, 8f), Offset(16f, 11f), strokeWidth = 2f)
                }
                name.contains("heart") || name.contains("like") -> {
                    // 爱心
                    val h = Path().apply {
                        moveTo(12f, 20f)
                        cubicTo(6f, 15f, 3f, 11f, 3f, 7.5f)
                        cubicTo(3f, 4.5f, 5.5f, 3f, 8f, 3f)
                        cubicTo(10f, 3f, 11.5f, 4.2f, 12f, 5.5f)
                        cubicTo(12.5f, 4.2f, 14f, 3f, 16f, 3f)
                        cubicTo(18.5f, 3f, 21f, 4.5f, 21f, 7.5f)
                        cubicTo(21f, 11f, 18f, 15f, 12f, 20f)
                        close()
                    }
                    drawPath(h, iconColor)
                }
                name.contains("link") -> {
                    // 锁链
                    val l1 = Path().apply {
                        moveTo(10f, 13f); lineTo(8f, 15f)
                        cubicTo(6.5f, 16.5f, 6.5f, 19f, 8f, 20.5f)
                        cubicTo(9.5f, 22f, 12f, 22f, 13.5f, 20.5f)
                        lineTo(15.5f, 18.5f)
                    }
                    val l2 = Path().apply {
                        moveTo(14f, 11f); lineTo(16f, 9f)
                        cubicTo(17.5f, 7.5f, 17.5f, 5f, 16f, 3.5f)
                        cubicTo(14.5f, 2f, 12f, 2f, 10.5f, 3.5f)
                        lineTo(8.5f, 5.5f)
                    }
                    drawPath(l1, iconColor, style = stroke)
                    drawPath(l2, iconColor, style = stroke)
                    drawLine(iconColor, Offset(9.5f, 14.5f), Offset(14.5f, 9.5f), strokeWidth = 2.2f, cap = StrokeCap.Round)
                }
                name.contains("smile") || name.contains("emoji") || name.contains("face") -> {
                    // 笑脸
                    drawCircle(iconColor, 8.5f, center = Offset(12f, 12f), style = stroke)
                    drawCircle(iconColor, 1.2f, center = Offset(8.5f, 9.5f))
                    drawCircle(iconColor, 1.2f, center = Offset(15.5f, 9.5f))
                    drawArc(
                        color = iconColor,
                        startAngle = 20f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(7f, 9f),
                        size = Size(10f, 8f),
                        style = stroke
                    )
                }
                name.contains("cube") || name.contains("box") || name.contains("onebox") -> {
                    // 立方体
                    val box = Path().apply {
                        moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f); lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
                    }
                    drawPath(box, iconColor, style = stroke)
                    drawLine(iconColor, Offset(12f, 3f), Offset(12f, 12f), strokeWidth = 2f)
                    drawLine(iconColor, Offset(12f, 12f), Offset(20f, 7.5f), strokeWidth = 2f)
                    drawLine(iconColor, Offset(12f, 12f), Offset(4f, 7.5f), strokeWidth = 2f)
                }
                name.contains("user") -> {
                    // 人像
                    drawCircle(iconColor, 4.5f, center = Offset(12f, 7.5f), style = stroke)
                    val body = Path().apply {
                        moveTo(4.5f, 20f); cubicTo(4.5f, 15f, 8f, 14f, 12f, 14f); cubicTo(16f, 14f, 19.5f, 15f, 19.5f, 20f)
                    }
                    drawPath(body, iconColor, style = stroke)
                }
                name.contains("flag") -> {
                    drawLine(iconColor, Offset(5f, 3f), Offset(5f, 21f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                    val f = Path().apply {
                        moveTo(5f, 4f); lineTo(17f, 7.5f); lineTo(5f, 11f); close()
                    }
                    drawPath(f, iconColor)
                }
                name.contains("book") -> {
                    drawRoundRect(iconColor, topLeft = Offset(4f, 4f), size = Size(16f, 16f), cornerRadius = CornerRadius(2.5f, 2.5f), style = stroke)
                    drawLine(iconColor, Offset(9f, 4f), Offset(9f, 20f), strokeWidth = 1.8f)
                }
                name.contains("eye") -> {
                    drawOval(iconColor, topLeft = Offset(2f, 6.5f), size = Size(20f, 11f), style = stroke)
                    drawCircle(iconColor, 3f, center = Offset(12f, 12f))
                }
                else -> {
                    // 通用精美八角星芒勋章
                    val star = Path().apply {
                        moveTo(12f, 3f); lineTo(14.5f, 7.5f); lineTo(19.5f, 8f); lineTo(16f, 12f); lineTo(17f, 17f)
                        lineTo(12f, 14.5f); lineTo(7f, 17f); lineTo(8f, 12f); lineTo(4.5f, 8f); lineTo(9.5f, 7.5f); close()
                    }
                    drawPath(star, iconColor)
                }
            }
        }
    }
}

@Composable
private fun BadgeDetailDialog(
    badge: UserBadgeItem,
    onDismiss: () -> Unit,
) {
    val isDark = IosTheme.colors.isDark

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    if (isDark) Color(0xFF1E2024) else Color.White
                )
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f),
                    RoundedCornerShape(22.dp)
                )
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 徽章大圆盘 (72dp)
            val diskGradient = when {
                !badge.isEarned -> Brush.verticalGradient(
                    listOf(Color(0xFF2C2D32), Color(0xFF1E1F24))
                )
                badge.badgeTypeId == 1 -> Brush.linearGradient(
                    listOf(Color(0xFFFFDF00), Color(0xFFFFA000), Color(0xFFFF6F00))
                )
                badge.badgeTypeId == 2 -> Brush.linearGradient(
                    listOf(Color(0xFFFFFFFF), Color(0xFFCFD8DC), Color(0xFF90A4AE))
                )
                else -> Brush.linearGradient(
                    listOf(Color(0xFFFFAB91), Color(0xFFFF7043), Color(0xFFD84315))
                )
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(diskGradient)
                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DiscourseBadgeIcon(
                    iconName = badge.icon,
                    imageUrl = badge.imageUrl,
                    isEarned = badge.isEarned,
                    badgeTypeId = badge.badgeTypeId,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // 徽章名称
            Text(
                text = badge.name,
                style = IosTheme.type.headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = IosTheme.colors.label,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            // 勋章等级标签
            val (badgeTypeName, badgeTypeColor) = when (badge.badgeTypeId) {
                1 -> Pair("🎖️ 黄金勋章", Color(0xFFFFB300))
                2 -> Pair("🥈 白银勋章", Color(0xFF90A4AE))
                else -> Pair("🥉 青铜勋章", Color(0xFFFF7043))
            }
            Text(
                text = badgeTypeName,
                style = IosTheme.type.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = badgeTypeColor,
            )

            Spacer(Modifier.height(12.dp))

            // 官方描述
            if (!badge.description.isNullOrBlank()) {
                Text(
                    text = badge.description,
                    style = IosTheme.type.body.copy(fontSize = 13.5.sp),
                    color = IosTheme.colors.label.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
            }

            // 达成状态
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (badge.isEarned) {
                            Color(0xFF34C759).copy(alpha = 0.12f)
                        } else {
                            if (isDark) Color(0xFF2B2D33) else Color(0xFFF2F4F7)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badge.isEarned) {
                        val timeStr = badge.grantedAt?.take(10) ?: ""
                        if (timeStr.isNotBlank()) "✅ 已于 $timeStr 获得" else "✅ 已达成解锁条件"
                    } else {
                        "🔒 尚未达成解锁条件"
                    },
                    style = IosTheme.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = if (badge.isEarned) Color(0xFF34C759) else IosTheme.colors.secondaryLabel,
                )
            }

            if (badge.grantCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "全站已有 ${badge.grantCount} 位佬友达成",
                    style = IosTheme.type.caption.copy(fontSize = 11.sp),
                    color = IosTheme.colors.secondaryLabel.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(16.dp))

            // 关闭按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IosTheme.colors.accent)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "我知道了",
                    style = IosTheme.type.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }
    }
}



/** 辅助悬浮球设置界面的拖拽排序三条杠手柄图标。 */
@Composable
private fun DragHandleIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val strokeWidth = 1.9.dp.toPx()
        val spacing = 4.8.dp.toPx()
        val startX = 1.dp.toPx()
        val endX = size.width - 1.dp.toPx()
        for (i in 0..2) {
            val y = i * spacing + strokeWidth / 2f + 1.dp.toPx()
            drawLine(
                color = tint,
                start = Offset(startX, y),
                end = Offset(endX, y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 辅助悬浮球设置界面的纯矢量手绘功能图标徽章。 */
@Composable
private fun FloatingActionSettingBadge(action: FloatingAction, modifier: Modifier = Modifier) {
    val (bgColor, actionTint) = when (action) {
        FloatingAction.CreateTopic -> Color(0xFFFF5722).copy(alpha = 0.15f) to Color(0xFFFF5722)
        FloatingAction.Bookmarks -> Color(0xFFFAAD14).copy(alpha = 0.15f) to Color(0xFFFAAD14)
        FloatingAction.Following -> Color(0xFF2979FF).copy(alpha = 0.15f) to Color(0xFF2979FF)
        FloatingAction.Likes -> Color(0xFFFF4D4F).copy(alpha = 0.15f) to Color(0xFFFF4D4F)
        FloatingAction.Replies -> Color(0xFF00BFA5).copy(alpha = 0.15f) to Color(0xFF00BFA5)
        FloatingAction.Sort -> Color(0xFFFFB300).copy(alpha = 0.15f) to Color(0xFFFF9800)
        FloatingAction.Drafts -> Color(0xFFAB47BC).copy(alpha = 0.15f) to Color(0xFF8E24AA)
        FloatingAction.Search -> Color(0xFF26A69A).copy(alpha = 0.15f) to Color(0xFF00897B)
        FloatingAction.Categories -> Color(0xFF29B6F6).copy(alpha = 0.15f) to Color(0xFF0288D1)
        FloatingAction.History -> Color(0xFF7E57C2).copy(alpha = 0.15f) to Color(0xFF7E57C2)
        FloatingAction.Connect -> Color(0xFF1E88E5).copy(alpha = 0.15f) to Color(0xFF1E88E5)
        FloatingAction.Chat -> Color(0xFFE91E63).copy(alpha = 0.15f) to Color(0xFFE91E63)
    }

    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                when (action) {
                    FloatingAction.CreateTopic -> {
                        val p = Path().apply { moveTo(3f, 13f); lineTo(6f, 12f); lineTo(13f, 5f); lineTo(11f, 3f); lineTo(4f, 10f); close() }
                        drawPath(p, actionTint, style = Stroke(1.8f))
                    }
                    FloatingAction.Bookmarks -> {
                        val bm = Path().apply {
                            moveTo(6.5f, 4.5f); lineTo(17.5f, 4.5f); lineTo(17.5f, 19.5f); lineTo(12f, 15.5f); lineTo(6.5f, 19.5f); close()
                        }
                        drawPath(bm, actionTint, style = stroke)
                        drawLine(actionTint, Offset(9.5f, 9.5f), Offset(14.5f, 9.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    }
                    FloatingAction.Following -> {
                        drawCircle(actionTint, 3.2f, Offset(9f, 7.5f), style = stroke)
                        val shoulder = Path().apply {
                            moveTo(3.5f, 19.5f); cubicTo(3.5f, 15f, 6.5f, 13.5f, 9f, 13.5f); cubicTo(11.5f, 13.5f, 14.5f, 15f, 14.5f, 19.5f)
                        }
                        drawPath(shoulder, actionTint, style = stroke)
                        drawLine(actionTint, Offset(18.5f, 8f), Offset(18.5f, 14f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                        drawLine(actionTint, Offset(15.5f, 11f), Offset(21.5f, 11f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    }
                    FloatingAction.Likes -> {
                        val thumb = Path().apply {
                            moveTo(4.5f, 12f); lineTo(4.5f, 19.5f); lineTo(7.5f, 19.5f); lineTo(7.5f, 12f); close()
                        }
                        drawPath(thumb, actionTint, style = stroke)
                        val palm = Path().apply {
                            moveTo(7.5f, 13f); lineTo(10.5f, 11f); lineTo(12.5f, 4.5f)
                            cubicTo(13.2f, 3.5f, 14.5f, 3.5f, 15f, 4.5f)
                            cubicTo(15.2f, 5.5f, 14.2f, 8f, 13.5f, 10.5f)
                            lineTo(17.5f, 10.5f)
                            cubicTo(19f, 10.5f, 19.5f, 11.5f, 19.5f, 12.8f)
                            lineTo(19f, 17f)
                            cubicTo(18.8f, 18.2f, 17.8f, 19.5f, 16.5f, 19.5f)
                            lineTo(7.5f, 19.5f)
                        }
                        drawPath(palm, actionTint, style = stroke)
                    }
                    FloatingAction.Replies -> {
                        val reply = Path().apply {
                            moveTo(18.5f, 18f); cubicTo(18.5f, 11.5f, 14.5f, 8.5f, 9.5f, 8.5f)
                        }
                        drawPath(reply, actionTint, style = stroke)
                        val arrowHead = Path().apply {
                            moveTo(13.5f, 5f); lineTo(9f, 8.5f); lineTo(13.5f, 12f)
                        }
                        drawPath(arrowHead, actionTint, style = stroke)
                        drawLine(actionTint, Offset(5f, 19.5f), Offset(19f, 19.5f), strokeWidth = 1.9f, cap = StrokeCap.Round)
                    }
                    FloatingAction.Sort -> {
                        drawLine(actionTint, Offset(5f, 12f), Offset(5f, 4f), 1.8f)
                        val a1 = Path().apply { moveTo(3f, 6.5f); lineTo(5f, 4f); lineTo(7f, 6.5f) }
                        drawPath(a1, actionTint, style = Stroke(1.8f))
                        drawLine(actionTint, Offset(11f, 4f), Offset(11f, 12f), 1.8f)
                        val a2 = Path().apply { moveTo(9f, 9.5f); lineTo(11f, 12f); lineTo(13f, 9.5f) }
                        drawPath(a2, actionTint, style = Stroke(1.8f))
                    }
                    FloatingAction.Drafts -> {
                        val f = Path().apply { moveTo(2f, 4f); lineTo(6f, 4f); lineTo(8f, 6f); lineTo(14f, 6f); lineTo(14f, 13f); lineTo(2f, 13f); close() }
                        drawPath(f, actionTint, style = Stroke(1.6f))
                    }
                    FloatingAction.Search -> {
                        drawCircle(actionTint, 4f, Offset(7f, 7f), style = Stroke(1.8f))
                        drawLine(actionTint, Offset(10f, 10f), Offset(13.5f, 13.5f), 2f)
                    }
                    FloatingAction.Categories -> {
                        drawRoundRect(actionTint, Offset(3f, 3f), Size(4.2f, 4.2f), CornerRadius(1f, 1f), style = Stroke(1.6f))
                        drawRoundRect(actionTint, Offset(9f, 3f), Size(4.2f, 4.2f), CornerRadius(1f, 1f), style = Stroke(1.6f))
                        drawRoundRect(actionTint, Offset(3f, 9f), Size(4.2f, 4.2f), CornerRadius(1f, 1f), style = Stroke(1.6f))
                        drawRoundRect(actionTint, Offset(9f, 9f), Size(4.2f, 4.2f), CornerRadius(1f, 1f), style = Stroke(1.6f))
                    }
                    FloatingAction.History -> {
                        drawArc(color = actionTint, startAngle = 40f, sweepAngle = 280f, useCenter = false, topLeft = Offset(4f, 4f), size = Size(16f, 16f), style = stroke)
                        val arrow = Path().apply { moveTo(9.5f, 2.5f); lineTo(4f, 4.5f); lineTo(5.5f, 10f) }
                        drawPath(arrow, actionTint, style = stroke)
                        drawLine(actionTint, Offset(12f, 12f), Offset(12f, 7.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                        drawLine(actionTint, Offset(12f, 12f), Offset(15.5f, 12f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    }
                    FloatingAction.Connect -> {
                        val l1 = Path().apply {
                            moveTo(10f, 13f); lineTo(8f, 15f); cubicTo(6.5f, 16.5f, 6.5f, 19f, 8f, 20.5f); cubicTo(9.5f, 22f, 12f, 22f, 13.5f, 20.5f); lineTo(15.5f, 18.5f)
                        }
                        val l2 = Path().apply {
                            moveTo(14f, 11f); lineTo(16f, 9f); cubicTo(17.5f, 7.5f, 17.5f, 5f, 16f, 3.5f); cubicTo(14.5f, 2f, 12f, 2f, 10.5f, 3.5f); lineTo(8.5f, 5.5f)
                        }
                        drawPath(l1, actionTint, style = stroke)
                        drawPath(l2, actionTint, style = stroke)
                        drawLine(actionTint, Offset(9.5f, 14.5f), Offset(14.5f, 9.5f), strokeWidth = 2.1f, cap = StrokeCap.Round)
                    }
                    FloatingAction.Chat -> {
                        val bubble = Path().apply {
                            moveTo(4.5f, 6f); cubicTo(4.5f, 4.5f, 5.5f, 4f, 7f, 4f); lineTo(17f, 4f)
                            cubicTo(18.5f, 4f, 19.5f, 4.5f, 19.5f, 6f); lineTo(19.5f, 14f)
                            cubicTo(19.5f, 15.5f, 18.5f, 16f, 17f, 16f); lineTo(9.5f, 16f)
                            lineTo(5.5f, 19.5f); lineTo(5.5f, 16f); lineTo(4.5f, 16f); close()
                        }
                        drawPath(bubble, actionTint, style = stroke)
                        drawCircle(actionTint, 1.1f, Offset(8.5f, 10f))
                        drawCircle(actionTint, 1.1f, Offset(12f, 10f))
                        drawCircle(actionTint, 1.1f, Offset(15.5f, 10f))
                    }
                }
            }
        }
    }
}
