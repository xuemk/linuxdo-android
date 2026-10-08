package org.linuxdo.android.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import org.linuxdo.android.ui.LoginUiState
import org.linuxdo.android.ui.design.IosActivityIndicator
import org.linuxdo.android.ui.design.IosTheme

private val GradientStart = Color(0xFFEEF2FF)
private val GradientEnd   = Color(0xFFF5F0FF)
private val AccentBlue    = Color(0xFF4B7FFF)
private val AccentBlue2   = Color(0xFF6B9FFF)
private val FieldBorder   = Color(0xFFDDE3F0)
private val FieldBg       = Color(0xFFF8FAFF)
private val TabActive     = AccentBlue
private val TabInactive   = Color(0xFF9AA5BB)

/** 原生登录页面，按设计图实现双 Tab（账号密码 / 验证码）。 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onPasswordLogin: (login: String, password: String) -> Unit,
    onSendEmailCode: (email: String) -> Unit,
    onEmailCodeLogin: (email: String, token: String) -> Unit,
    onDismissError: () -> Unit,
) {
    // Tab 选择：0=账号密码，1=验证码
    var tab by rememberSaveable { mutableStateOf(0) }
    var showForgotDialog by rememberSaveable { mutableStateOf(false) }

    // 账号密码 Tab 的状态
    var loginText by rememberSaveable { mutableStateOf("") }
    var passwordText by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // 验证码 Tab 的状态
    var emailText by rememberSaveable { mutableStateOf("") }
    var codeText by rememberSaveable { mutableStateOf("") }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 错误自动消除
    LaunchedEffect(state.error) {
        if (state.error != null) { /* 由对话框展示 */ }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientStart, GradientEnd)))
            .clickable(indication = null, interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }) { focusManager.clearFocus() }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))

            // Logo
            AppIconBadge()

            Spacer(Modifier.height(24.dp))

            // 标题
            Text(
                "欢迎登录",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A2340),
            )
            Text(
                "Welcome back",
                fontSize = 13.sp,
                color = Color(0xFF9AA5BB),
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(32.dp))

            // Tab 切换
            LoginTabBar(selected = tab, onSelect = { tab = it })

            Spacer(Modifier.height(28.dp))

            if (tab == 0) {
                // ── 账号密码登录 ──
                LoginField(
                    value = loginText,
                    onValueChange = { loginText = it },
                    placeholder = "请输入用户名/手机号/邮箱",
                    leadingIcon = { UserIcon() },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Spacer(Modifier.height(16.dp))

                LoginField(
                    value = passwordText,
                    onValueChange = { passwordText = it },
                    placeholder = "请输入密码",
                    leadingIcon = { LockIcon() },
                    trailingContent = {
                        Text(
                            if (passwordVisible) "隐藏" else "明文",
                            fontSize = 13.sp,
                            color = AccentBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { passwordVisible = !passwordVisible }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide(); focusManager.clearFocus()
                        if (loginText.isNotBlank() && passwordText.isNotBlank()) {
                            onPasswordLogin(loginText.trim(), passwordText)
                        }
                    }),
                )

                // 忘记密码
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "忘记密码？",
                        fontSize = 13.sp,
                        color = AccentBlue,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showForgotDialog = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                Spacer(Modifier.height(32.dp))

                LoginButton(
                    text = "登录",
                    loading = state.loading,
                    enabled = loginText.isNotBlank() && passwordText.isNotBlank() && !state.loading,
                    onClick = {
                        keyboard?.hide(); focusManager.clearFocus()
                        onPasswordLogin(loginText.trim(), passwordText)
                    },
                )
            } else {
                // ── 验证码登录 ──
                LoginField(
                    value = emailText,
                    onValueChange = { emailText = it },
                    placeholder = "请输入邮箱",
                    leadingIcon = { EmailIcon() },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )

                Spacer(Modifier.height(16.dp))

                // 验证码行：输入框 + 获取按钮
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoginField(
                        value = codeText,
                        onValueChange = { codeText = it },
                        placeholder = "请输入验证码",
                        leadingIcon = { ShieldIcon() },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide(); focusManager.clearFocus()
                        }),
                    )

                    Spacer(Modifier.width(10.dp))

                    // 获取验证码按钮
                    SendCodeButton(
                        loading = state.sendingCode,
                        enabled = emailText.isNotBlank() && !state.sendingCode,
                        onClick = {
                            keyboard?.hide(); focusManager.clearFocus()
                            onSendEmailCode(emailText.trim())
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 提示文字
                Text(
                    "验证码将通过邮件发送，请查收后输入链接中的 token",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA5BB),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(32.dp))

                LoginButton(
                    text = "登录",
                    loading = state.loading,
                    enabled = emailText.isNotBlank() && codeText.isNotBlank() && !state.loading,
                    onClick = {
                        keyboard?.hide(); focusManager.clearFocus()
                        onEmailCodeLogin(emailText.trim(), codeText.trim())
                    },
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 忘记密码提示对话框
    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            title = { Text("忘记密码") },
            text = { Text("请在 web 端执行忘记密码操作。\n\n访问 linux.do 网页版，使用忘记密码功能重置后再回来登录。") },
            confirmButton = {
                TextButton(onClick = { showForgotDialog = false }) { Text("知道了") }
            },
        )
    }

    // 错误提示对话框
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("登录失败") },
            text = { Text(state.error) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text("确定") }
            },
        )
    }

    // 邮件已发送提示
    if (state.emailSent) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("邮件已发送") },
            text = { Text("登录邮件已发送至您的邮箱。\n\n请打开邮件，复制链接中 /session/email-login/ 后面的 token 字符串，粘贴到验证码输入框后点击登录。") },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text("知道了") }
            },
        )
    }
}

// ── Tab 切换栏 ──────────────────────────────────────────────────────

@Composable
private fun LoginTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("账号密码登录", "验证码登录")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val isActive = selected == index
            val color by animateColorAsState(
                targetValue = if (isActive) TabActive else TabInactive,
                animationSpec = tween(200),
                label = "tab-color-$index",
            )
            Column(
                Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }) { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .fillMaxWidth(if (isActive) 0.6f else 0f)
                        .background(
                            Brush.horizontalGradient(listOf(AccentBlue, AccentBlue2)),
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}

// ── 输入框 ─────────────────────────────────────────────────────────

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        placeholder = {
            Text(placeholder, fontSize = 14.sp, color = Color(0xFFBBC4D6))
        },
        leadingIcon = { Box(Modifier.padding(start = 4.dp)) { leadingIcon() } },
        trailingIcon = trailingContent?.let { { it() } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = FieldBg,
            focusedContainerColor = Color.White,
            unfocusedBorderColor = FieldBorder,
            focusedBorderColor = AccentBlue.copy(alpha = 0.6f),
            cursorColor = AccentBlue,
        ),
    )
}

// ── 登录按钮 ───────────────────────────────────────────────────────

@Composable
private fun LoginButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .shadow(if (enabled) 8.dp else 0.dp, RoundedCornerShape(25.dp), ambientColor = AccentBlue.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(25.dp))
            .background(
                if (enabled)
                    Brush.horizontalGradient(listOf(AccentBlue, AccentBlue2))
                else
                    Brush.horizontalGradient(listOf(Color(0xFFCDD5E8), Color(0xFFCDD5E8))),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            IosActivityIndicator(diameter = 22.dp, color = Color.White)
        } else {
            Text(
                text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

// ── 获取验证码按钮 ─────────────────────────────────────────────────

@Composable
private fun SendCodeButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(54.dp)
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled)
                    Brush.verticalGradient(listOf(AccentBlue, AccentBlue2))
                else
                    Brush.verticalGradient(listOf(Color(0xFFCDD5E8), Color(0xFFCDD5E8))),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            IosActivityIndicator(diameter = 18.dp, color = Color.White)
        } else {
            Text(
                "获取验证码",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── App 图标徽章（复用 ic_launcher_foreground 路径） ───────────────

@Composable
private fun AppIconBadge() {
    Box(
        Modifier
            .size(80.dp)
            .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x334B7FFF))
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        // 绘制 ic_launcher_foreground 的两条路径
        Canvas(Modifier.size(54.dp)) {
            val scaleX = size.width / 108f
            val scaleY = size.height / 108f
            scale(scaleX, scaleY, Offset.Zero) {
                // 深色竖线（L形主体）
                val p1 = Path().apply {
                    moveTo(34f, 27f); cubicTo(34f, 24f, 37f, 24f, 37f, 24f)
                    lineTo(44f, 24f); cubicTo(47f, 24f, 47f, 27f, 47f, 27f)
                    lineTo(47f, 63f); lineTo(70f, 63f); lineTo(49f, 84f)
                    lineTo(37f, 84f); cubicTo(34f, 84f, 34f, 81f, 34f, 81f); close()
                }
                drawPath(p1, Color(0xFF202226))
                // 蓝色折角
                val p2 = Path().apply {
                    moveTo(54f, 84f); lineTo(76f, 62f)
                    cubicTo(78f, 61f, 78f, 64f, 78f, 64f)
                    lineTo(78f, 81f); cubicTo(78f, 84f, 75f, 84f, 75f, 84f); close()
                }
                drawPath(p2, Color(0xFF4055DF))
            }
        }
    }
}

// ── 小图标（Canvas 手绘，不依赖 Material Icons） ──────────────────

/** 用户图标 */
@Composable
private fun UserIcon() {
    Canvas(Modifier.size(18.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(Color(0xFFBBC4D6), radius = size.width * 0.22f, center = Offset(cx, cy * 0.7f))
        drawArc(
            color = Color(0xFFBBC4D6),
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(size.width * 0.1f, cy * 0.9f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.6f),
            style = androidx.compose.ui.graphics.drawscope.Fill,
        )
    }
}

/** 锁图标 */
@Composable
private fun LockIcon() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        // 锁体
        drawRoundRect(
            color = Color(0xFFBBC4D6),
            topLeft = Offset(w * 0.15f, h * 0.45f),
            size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
        )
        // 锁孔圆圈（白色镂空效果）
        drawCircle(Color.White, w * 0.1f, Offset(w / 2f, h * 0.67f))
        // 锁梁（弧形）- 用矩形框模拟
        drawArc(
            color = Color(0xFFBBC4D6),
            startAngle = 200f, sweepAngle = -220f, useCenter = false,
            topLeft = Offset(w * 0.25f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.5f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.13f),

        )
    }
}

/** 邮件图标 */
@Composable
private fun EmailIcon() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(
            color = Color(0xFFBBC4D6),
            topLeft = Offset(w * 0.05f, h * 0.2f),
            size = androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f),
        )
        // 信封折角
        val path = Path().apply {
            moveTo(w * 0.05f, h * 0.25f)
            lineTo(w * 0.5f, h * 0.55f)
            lineTo(w * 0.95f, h * 0.25f)
        }
        drawPath(path, Color(0xFFBBC4D6), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f))

    }
}

/** 盾牌图标（验证码） */
@Composable
private fun ShieldIcon() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.95f, h * 0.25f)
            lineTo(w * 0.95f, h * 0.6f)
            cubicTo(w * 0.95f, h * 0.85f, w * 0.5f, h * 0.97f, w * 0.5f, h * 0.97f)
            cubicTo(w * 0.5f, h * 0.97f, w * 0.05f, h * 0.85f, w * 0.05f, h * 0.6f)
            lineTo(w * 0.05f, h * 0.25f)
            close()
        }
        drawPath(path, Color(0xFFBBC4D6))
    }
}
