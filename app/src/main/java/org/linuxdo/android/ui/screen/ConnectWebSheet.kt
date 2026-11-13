package org.linuxdo.android.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.linuxdo.android.App
import org.linuxdo.android.Config
import androidx.compose.ui.graphics.Brush
import org.linuxdo.android.data.LdcUserInfo
import org.linuxdo.android.ui.LdcUiState
import org.linuxdo.android.data.TrustBarMetric
import org.linuxdo.android.data.TrustQuotaMetric
import org.linuxdo.android.data.TrustReport
import org.linuxdo.android.data.TrustRingMetric
import org.linuxdo.android.data.TrustVetoMetric
import org.linuxdo.android.ui.TrustReportUiState
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.sheetScrollable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectWebSheet(
    state: TrustReportUiState = TrustReportUiState(),
    ldcState: LdcUiState = LdcUiState(),
    onLoad: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onLoadLdc: () -> Unit = {},
    onRefreshLdc: () -> Unit = {},
    onAuthorizeLdc: () -> Unit = {},
    onOpenWebUrl: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: 升级进度, 1: 我的积分

    LaunchedEffect(Unit) {
        onLoad()
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            onLoadLdc()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler {
            onDismiss()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F10))
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // 顶栏: 返回 + 品牌 + 刷新 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(4.dp))
                // LINUX DO 品牌图标
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (selectedTab == 0) Color(0xFFE5A00D) else Color(0xFF007AFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "等级积分",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                val currentCachedAt = if (selectedTab == 0) state.cachedAt else ldcState.cachedAt
                val isRefreshing = if (selectedTab == 0) state.loading else ldcState.loading
                if (currentCachedAt > 0L) {
                    Text(
                        text = "更新于 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(currentCachedAt)),
                        fontSize = 11.5.sp,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        if (selectedTab == 0) onRefresh() else onRefreshLdc()
                    },
                    enabled = !isRefreshing,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (isRefreshing) {
                        IosActivityIndicator(diameter = 16.dp, color = Color(0xFF007AFF))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color(0xFF9E9E9E))
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFF9E9E9E))
                }
            }

            // 选项卡切换: [ 升级进度 ]  [ 我的积分 ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedTab == 0) Color(0xFF2C2C2E) else Color.Transparent)
                        .clickable { selectedTab = 0 }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (selectedTab == 0) Color(0xFFE5A00D) else Color(0xFF8E8E93))
                        )
                        Text(
                            text = "升级进度",
                            fontSize = 13.5.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selectedTab == 0) Color.White else Color(0xFF8E8E93),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedTab == 1) Color(0xFF2C2C2E) else Color.Transparent)
                        .clickable { selectedTab = 1 }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (selectedTab == 1) Color(0xFF007AFF) else Color(0xFF8E8E93))
                        )
                        Text(
                            text = "我的积分",
                            fontSize = 13.5.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selectedTab == 1) Color.White else Color(0xFF8E8E93),
                        )
                    }
                }
            }

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when {
                        state.loading && state.report == null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IosActivityIndicator(diameter = 24.dp, color = Color(0xFF00BA7C))
                            }
                        }
                        state.error != null && state.report == null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(state.error, color = Color(0xFFFF453A), fontSize = 14.sp)
                                TextButton(onClick = onRefresh) {
                                    Text("重新加载", color = Color(0xFF00BA7C))
                                }
                            }
                        }
                        state.report != null -> {
                            NativeConnectCard(report = state.report)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when {
                        ldcState.loading && ldcState.userInfo == null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IosActivityIndicator(diameter = 24.dp, color = Color(0xFF007AFF))
                            }
                        }
                        ldcState.isExpired -> {
                            NativeCreditExpiredCard(
                                onAuthorize = onAuthorizeLdc,
                                onRetry = onRefreshLdc,
                            )
                        }
                        ldcState.error != null && ldcState.userInfo == null -> {
                            NativeCreditErrorCard(
                                error = ldcState.error,
                                onRetry = onRefreshLdc,
                                onAuthorize = onAuthorizeLdc,
                            )
                        }
                        ldcState.userInfo != null -> {
                            NativeCreditBalanceCard(userInfo = ldcState.userInfo)
                            NativeCreditMetricsGrid(userInfo = ldcState.userInfo)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NativeCreditBalanceCard(
    userInfo: LdcUserInfo,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF1E3A8A), Color(0xFF2563EB), Color(0xFF38BDF8))
                )
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("💳", fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "LINUX DO Credits",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.95f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "可用余额",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Text(
                        text = userInfo.availableBalance,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                val todayIncome = userInfo.todayIncomeDisplay
                val dailyGrowth = userInfo.dailyGrowthLdc
                if (todayIncome != null || dailyGrowth != null) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "今日: ${todayIncome ?: "--"}LDC",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            CreditGrowthArrow()
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "待结: ${dailyGrowth?.toString() ?: "--"}LDC",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            CreditGrowthArrow()
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "@${userInfo.username}",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f),
                )
                if (userInfo.payScore > 0) {
                    Text(
                        text = "信用分: ${userInfo.payScore}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditGrowthArrow(
    modifier: Modifier = Modifier.size(13.dp),
    color: Color = Color(0xFF6EE7B7),
) {
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.8f),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.2f),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.2f),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun NativeCreditMetricsGrid(userInfo: LdcUserInfo) {
    val items = listOf(
        "累计收款" to userInfo.totalReceive,
        "累计支出" to userInfo.totalPayment,
        "累计转账" to userInfo.totalTransfer,
        "社区发放" to userInfo.totalCommunity,
        "单日限额" to if (userInfo.dailyLimit > 0) "${userInfo.dailyLimit}" else "不限",
        "剩余额度" to userInfo.remainQuota,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "财务明细与额度",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
        )

        for (chunk in items.chunked(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for ((label, value) in chunk) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1C1C1E))
                            .border(0.5.dp, Color(0xFF2C2C2E), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.5.sp,
                            color = Color(0xFF8E8E93),
                        )
                        Text(
                            text = value.ifBlank { "0" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeCreditExpiredCard(
    onAuthorize: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color(0xFF3A3A3C), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFA000).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("🔐", fontSize = 24.sp)
        }
        Text(
            text = "积分授权已失效或未登录",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = "访问积分账户需要先在积分站登录授权。点击下方按钮即可在应用内自动一键授权，无需跳出应用。",
            fontSize = 13.sp,
            color = Color(0xFF8E8E93),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onAuthorize,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF007AFF)
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("一键授权并登录", color = Color.White)
            }
            TextButton(onClick = onRetry) {
                Text("重试刷新", color = Color(0xFF007AFF))
            }
        }
    }
}

@Composable
private fun NativeCreditErrorCard(
    error: String,
    onRetry: () -> Unit,
    onAuthorize: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color(0xFF3A3A3C), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "加载积分信息失败",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = error,
            fontSize = 13.sp,
            color = Color(0xFFFF453A),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onAuthorize,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("一键重新授权", color = Color.White)
            }
            TextButton(onClick = onRetry) {
                Text("重新加载", color = Color(0xFF007AFF))
            }
        }
    }
}

@Composable
private fun NativeConnectCard(report: TrustReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181A))
            .border(1.dp, Color(0xFF262628), RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 标题行与未达到/已达到徽章
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = report.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                if (report.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = report.subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF8E8E93),
                    )
                }
            }
            if (report.isStatusMet) {
                // 1、已到达-保持不变
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x2634C759))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = report.statusLabel.ifBlank { "已达到" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF34C759),
                    )
                }
            } else {
                // 2、未达到则按照进度条显示，根据各项指标综合计算出进度情况
                TrustCompletionRing(report = report)
            }
        }

        // 1. 活跃程度 (3 环形进度)
        if (report.rings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("活跃程度", fontSize = 13.sp, color = Color(0xFF8E8E93))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    report.rings.forEach { ring ->
                        RingProgressItem(ring = ring)
                    }
                }
            }
        }

        // 2. 互动参与 (进度条)
        if (report.bars.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("互动参与", fontSize = 13.sp, color = Color(0xFF8E8E93))
                report.bars.forEach { bar ->
                    BarProgressItem(bar = bar)
                }
            }
        }

        // 3. 合规记录 (槽位与一票否决)
        if (report.quotas.isNotEmpty() || report.vetos.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("合规记录", fontSize = 13.sp, color = Color(0xFF8E8E93))
                report.quotas.forEach { quota ->
                    QuotaSlotItem(quota = quota)
                }
                report.vetos.forEach { veto ->
                    VetoCardItem(veto = veto)
                }
            }
        }

        // 4. 底部说明与加油提示
        if (report.footerHint.isNotBlank()) {
            Text(
                text = report.footerHint,
                fontSize = 11.sp,
                color = Color(0xFF636366),
            )
        }
        if (report.statusNotice.isNotBlank()) {
            Text(
                text = report.statusNotice,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (report.isStatusMet) Color(0xFF34C759) else Color(0xFFFF453A),
            )
        }
    }
}

@Composable
private fun RingProgressItem(ring: TrustRingMetric) {
    val progress = (ring.current.toFloat() / ring.max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "ringProgress")
    val ringColor = if (ring.isMet) Color(0xFF00BA7C) else Color(0xFFFF9F0A)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(72.dp)) {
                // 背景环
                drawArc(
                    color = Color(0xFF2C2C2E),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                )
                // 进度环
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ring.current.toString(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ringColor,
                )
                Text(
                    text = "/ ${ring.max}",
                    fontSize = 10.sp,
                    color = Color(0xFF8E8E93),
                )
            }
        }
        Text(
            text = ring.label,
            fontSize = 12.sp,
            color = Color(0xFFEBEBF5),
        )
    }
}

@Composable
private fun BarProgressItem(bar: TrustBarMetric) {
    val animatedProgress by animateFloatAsState(targetValue = bar.progress, label = "barProgress")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(bar.label, fontSize = 13.sp, color = Color(0xFFEBEBF5))
            Text(
                text = "${bar.current}/${bar.max}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF00BA7C),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF2C2C2E)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF00BA7C)),
            )
        }
    }
}

@Composable
private fun QuotaSlotItem(quota: TrustQuotaMetric) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF242426))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(quota.label, fontSize = 13.sp, color = Color(0xFFEBEBF5))
            Text(quota.value.ifBlank { "${quota.usedSlots} / ${quota.totalSlots}" }, fontSize = 12.sp, color = Color(0xFF8E8E93))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(quota.totalSlots) { index ->
                val used = index < quota.usedSlots
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (used) Color(0xFFFF453A) else Color(0xFF00BA7C)),
                )
            }
        }
    }
}

@Composable
private fun VetoCardItem(veto: TrustVetoMetric) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF14291E))
            .border(1.dp, Color(0xFF1D4A32), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFF00BA7C)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(veto.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (veto.desc.isNotBlank()) {
                Text(veto.desc, fontSize = 11.sp, color = Color(0xFF8E8E93))
            }
        }
        Text(
            text = veto.value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00BA7C),
        )
    }
}

@Composable
private fun TrustCompletionRing(
    report: TrustReport,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val progress = report.overallProgress
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "overallProgress",
    )
    val percent = report.overallPercent
    val metCount = report.metMetricsCount
    val unmetCount = report.unmetMetricsCount

    Box(
        modifier = modifier
            .size(54.dp)
            .clip(CircleShape)
            .clickable {
                val tip = if (unmetCount > 0) {
                    "升级完成度 $percent%（已达标 $metCount 项，待完成 $unmetCount 项）"
                } else {
                    "升级完成度 $percent%"
                }
                android.widget.Toast.makeText(context, tip, android.widget.Toast.LENGTH_SHORT).show()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(50.dp)) {
            // 背景底圈轨道
            drawArc(
                color = Color(0xFF2C2C2E),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round),
            )
            // 根据不同完成度阶段动态切换色阶：
            // - 起步阶段 (<40%): 暖橙色系，提示起步积累
            // - 进阶阶段 (40%~74%): 科技天蓝，平稳爬坡
            // - 冲刺阶段 (>=75%): 翡翠青绿，即将达标冲线
            val ringColors = when {
                percent < 40 -> listOf(Color(0xFFFF9F0A), Color(0xFFFFB340), Color(0xFFFF9F0A))
                percent < 75 -> listOf(Color(0xFF38BDF8), Color(0xFF007AFF), Color(0xFF38BDF8))
                else -> listOf(Color(0xFF34D399), Color(0xFF10B981), Color(0xFF34D399))
            }
            val brush = Brush.sweepGradient(ringColors)
            drawArc(
                brush = brush,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val textColor = when {
                percent < 40 -> Color(0xFFFFD180)
                percent < 75 -> Color.White
                else -> Color(0xFFA7F3D0)
            }
            Text(
                text = "$percent%",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                lineHeight = 14.sp,
            )
            Text(
                text = "完成度",
                fontSize = 8.5.sp,
                color = Color(0xFF8E8E93),
                lineHeight = 10.sp,
            )
        }
    }
}

