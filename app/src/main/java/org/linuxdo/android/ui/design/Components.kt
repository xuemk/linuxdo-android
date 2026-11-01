package org.linuxdo.android.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.linuxdo.android.net.IMAGE_AUTO_RETRY_LIMIT
import org.linuxdo.android.net.awaitImageRetrySlot
import org.linuxdo.android.net.isTransientImageError
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * LINUX DO 社区特色定制头像形状：
 * 论坛创始人/站长 neo 的头像是微圆角方形，其他用户统一为圆形。
 */
fun avatarShape(username: String?, avatarUrl: String? = null, cornerRadius: Dp = 4.dp): Shape {
    val isNeo = username?.equals("neo", ignoreCase = true) == true ||
        avatarUrl?.contains("/neo/", ignoreCase = true) == true ||
        avatarUrl?.contains("user_avatar/linux.do/neo", ignoreCase = true) == true
    return if (isNeo) RoundedCornerShape(cornerRadius) else CircleShape
}

/**
 * 构建支持高刷动图（GIF / 动态 WebP / APNG）并强制无限循环、禁用 Crossfade 冻结的 ImageRequest。
 * 对齐 FluxDO 的 NativeAnimatedImageProvider 链路逻辑。
 */
@Composable
fun rememberAnimatedImageRequest(data: Any?, retryToken: Int = 0): coil.request.ImageRequest? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(data, retryToken) {
        if (data == null || (data is String && data.isBlank())) null
        else {
            val builder = coil.request.ImageRequest.Builder(context)
                .data(data)
                .crossfade(false)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            if (retryToken > 0) {
                builder.setParameter("coil#retry_token", retryToken, memoryCacheKey = null)
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                builder.setParameter("coil#repeat_count", -1, memoryCacheKey = null)
            }
            builder.build()
        }
    }
}

/**
 * 统一的用户头像组件（全面对齐 fluxdo SmartAvatar + AnimatedAvatarStack）：
 * 1. 静态模板小图（staticBaseUrl）单通道优先打底（几 KB 秒出），杜绝同卡挂载时双 AsyncImage 同时并发请求；
 * 2. 动图（animatedOverlayUrl）仅在静态底图就绪或回退后作为 Overlay 叠加，动图首帧就绪后收起静态底图防透明 GIF 重影；
 * 3. 底层常驻用户名首字母圆标：图片未就绪、排队或失败期间始终展示清晰的首字母徽章，彻底消除空白灰块；
 * 4. 瞬时网络抖动或门禁冷却（429/timeout）时后台自动按槽位错峰重试，恢复后无感浮现。
 */
@Composable
fun UserAvatar(
    avatarUrl: String?,
    fallbackUrl: String? = null,
    username: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val staticBaseUrl = fallbackUrl?.takeIf { it.isNotBlank() } ?: avatarUrl?.takeIf { it.isNotBlank() }
    val animatedOverlayUrl = avatarUrl?.takeIf { it.isNotBlank() && it != staticBaseUrl }
    val shape = avatarShape(username, avatarUrl ?: staticBaseUrl, cornerRadius)

    var baseReady by remember(staticBaseUrl) { mutableStateOf(false) }
    var baseFailed by remember(staticBaseUrl) { mutableStateOf(false) }
    var basePendingRetry by remember(staticBaseUrl) { mutableStateOf(false) }
    var baseRetryAttempt by remember(staticBaseUrl) { mutableIntStateOf(0) }
    var baseRetryToken by remember(staticBaseUrl) { mutableIntStateOf(0) }

    var animReady by remember(animatedOverlayUrl) { mutableStateOf(false) }
    var animFailed by remember(animatedOverlayUrl) { mutableStateOf(false) }

    if (basePendingRetry && !staticBaseUrl.isNullOrBlank()) {
        LaunchedEffect(staticBaseUrl, baseRetryAttempt) {
            awaitImageRetrySlot((baseRetryAttempt - 1).coerceAtLeast(0))
            basePendingRetry = false
            baseFailed = false
            baseRetryToken++
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(IosTheme.colors.fieldBackground),
        contentAlignment = Alignment.Center,
    ) {
        // 0. 首字母常驻兜底（对齐 fluxdo CircleAvatar）：未渲染出任何有效图片帧前始终展示，绝不露空白灰块
        if (!baseReady && !animReady) {
            val initial = username?.trimStart('@')?.firstOrNull()?.uppercase() ?: "★"
            Text(
                text = initial,
                style = IosTheme.type.footnote,
                fontWeight = FontWeight.Bold,
                color = IosTheme.colors.secondaryLabel,
            )
        }

        // 1. 静态模板小图打底层（动图首帧就绪后收起，避免透明 GIF 透出底图成双影）
        if (!staticBaseUrl.isNullOrBlank() && !baseFailed && !animReady) {
            val baseRequest = rememberAnimatedImageRequest(staticBaseUrl, baseRetryToken)
            AsyncImage(
                model = baseRequest ?: staticBaseUrl,
                contentDescription = username,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = {
                    baseReady = true
                },
                onError = { state ->
                    if (isTransientImageError(state.result.throwable) && baseRetryAttempt < IMAGE_AUTO_RETRY_LIMIT) {
                        baseRetryAttempt++
                        baseFailed = true
                        basePendingRetry = true
                    } else {
                        baseFailed = true
                    }
                },
            )
        }

        // 2. 动图 Overlay 层：仅在存在独立动图且静态底图已结束首轮请求后挂载，避免单卡双请求齐发
        if (!animatedOverlayUrl.isNullOrBlank() && !animFailed && (baseReady || (baseFailed && !basePendingRetry))) {
            val animRequest = rememberAnimatedImageRequest(animatedOverlayUrl)
            AsyncImage(
                model = animRequest ?: animatedOverlayUrl,
                contentDescription = username,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = {
                    animReady = true
                },
                onError = {
                    animFailed = true
                },
            )
        }
    }
}

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) Color(0xFF34C759) else IosTheme.colors.fieldBackground, label = "switch-color")
    val thumbOffset by animateDpAsState(if (checked) 22.dp else 2.dp, label = "switch-position")
    Box(modifier.size(width = 51.dp, height = 44.dp).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track)) {
            Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(thumbOffset.roundToPx(), 0) }.size(27.dp)
                .shadow(1.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

/**
 * iOS 列表分隔线,默认左内缩不通栏。
 *
 * 厚度按 1 物理像素算而不是写死 0.33dp:后者在部分密度下会被舍入成 0,线直接消失。
 */
@Composable
fun IosDivider(startIndent: Dp = IosMetrics.screenPadding, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(hairlineThickness())
            .padding(start = startIndent)
            .background(IosTheme.colors.separator),
    )
}

/** 通栏分隔线,用于导航栏与 TabBar 的边界。 */
@Composable
fun IosFullDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(hairlineThickness())
            .background(IosTheme.colors.separator),
    )
}

@Composable
private fun hairlineThickness(): Dp = with(LocalDensity.current) { 1f.toDp() }

/**
 * iOS 分组卡片:圆角 10dp、左右留 16dp 边距、卡片底色与分组背景区分。
 * 行之间的分隔线由调用方用 [IosDivider] 插入,最后一行之后不画线。
 */
@Composable
fun IosGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = IosMetrics.screenPadding)
            .clip(RoundedCornerShape(IosMetrics.cardCorner))
            .background(IosTheme.colors.card),
        content = content,
    )
}

/**
 * iOS 列表行。自身不画背景 —— 按压高亮由主题的 indication 绘制在内容之下,
 * 底色由父层卡片或页面背景提供,这样按下时整行变灰而文字不被染色。
 */
@Composable
fun IosRow(
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = IosMetrics.rowMinHeight)
            .padding(horizontal = IosMetrics.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** iOS 的行尾箭头。自己画而不用 Material 图标,因为 SF Symbols 的箭头更细更尖。 */
@Composable
fun IosChevron(modifier: Modifier = Modifier, color: Color = IosTheme.colors.tertiaryLabel) {
    Canvas(modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = size.width / 4f
        val midY = size.height / 2f
        drawLine(color, Offset(0f, 0f), Offset(size.width, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, midY), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}

/**
 * iOS 的转圈指示器:8 条辐射线段按相位渐暗旋转。
 * 不用 Material 的 CircularProgressIndicator —— 那是一条绕圈的弧线,形态完全不同。
 */
@Composable
fun IosActivityIndicator(
    modifier: Modifier = Modifier,
    diameter: Dp = 20.dp,
    color: Color = IosTheme.colors.spinner,
) {
    val segments = 8
    val phase by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = segments.toFloat(),
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier.size(diameter)) {
        val stroke = size.minDimension / 8.2f
        val outer = size.minDimension / 2f - stroke / 2f
        val inner = outer * 0.48f
        val center = Offset(size.width / 2f, size.height / 2f)
        val head = floor(phase).toInt()
        repeat(segments) { index ->
            val radians = (index * 360.0 / segments - 90.0) * Math.PI / 180.0
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            // 距离当前头部越远越淡,形成 iOS 那种拖尾;抬高底色透明度确保浅色/深色背景下拉刷新时都清晰可见。
            val distance = (index - head + segments) % segments
            val alpha = 0.28f + 0.72f * (1f - distance.toFloat() / segments)
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 居中的加载占位,用于整页首次加载。 */
@Composable
fun IosLoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        IosActivityIndicator(diameter = 24.dp)
    }
}
