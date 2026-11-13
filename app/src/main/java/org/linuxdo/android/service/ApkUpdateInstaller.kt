package org.linuxdo.android.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.linuxdo.android.App
import org.linuxdo.android.BuildConfig
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

class ApkInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ApkUpdateInstaller.onInstallResultBroadcast(context, intent)
    }
}

/**
 * 应用内下载并安装新版本 APK。
 *
 * Android 不允许第三方应用静默安装，所以这里优先通过 PackageInstaller.Session 流式写入系统会话并拉起官方确认窗，
 * 失败时自动回退到 FileProvider + ACTION_VIEW。确认弹窗与首次「安装未知应用」授权仍由用户完成。
 */
object ApkUpdateInstaller {

    private const val APK_DIR = "apk"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val ACTION_INSTALL_RESULT = "org.linuxdo.android.action.APK_INSTALL_RESULT"
    private const val INVALID_SESSION_ID = PackageInstaller.SessionInfo.INVALID_ID

    /** 进度回调的最小间隔字节数,避免每读一个 8KB 块就触发一次重组。 */
    private const val PROGRESS_STEP_BYTES = 128 * 1024L

    /** 下载响应未给 Content-Length 时的总长占位,UI 据此退化为不确定进度。 */
    const val TOTAL_UNKNOWN = -1L

    sealed interface Result {
        data class Success(val file: File) : Result
        data class Failure(val message: String) : Result
    }

    sealed interface InstallStartResult {
        data class SessionCommitted(val sessionId: Int) : InstallStartResult
        data object FallbackActivityStarted : InstallStartResult
        data object FailedToStart : InstallStartResult
    }

    sealed interface InstallEvent {
        data class PendingUserAction(val sessionId: Int, val confirmIntent: Intent) : InstallEvent
        data class MissingConfirmIntent(val sessionId: Int) : InstallEvent
        data class Succeeded(val sessionId: Int) : InstallEvent
        data class Aborted(val sessionId: Int, val message: String?) : InstallEvent
        data class Failed(val sessionId: Int, val status: Int, val message: String?) : InstallEvent
    }

    private val _installEvents = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val installEvents: SharedFlow<InstallEvent> = _installEvents.asSharedFlow()

    @Volatile
    private var activeSessionId: Int = INVALID_SESSION_ID

    @Volatile
    private var activeApkFile: File? = null

    /**
     * 流式下载安装包到 `cacheDir/apk/`,边下边算 SHA-256。
     *
     * [expectedSha256] 为空时跳过校验(发布清单没填就不阻塞升级);
     * 填了但不匹配则删除临时文件并返回失败,绝不把来源不明的包交给安装器。
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val diagnostics = (appContext as? App)?.container?.diagnostics
        val target = runCatching { prepareTargetFile(appContext, url) }.getOrElse { error ->
            return@withContext Result.Failure("无法准备下载目录：${error.javaClass.simpleName}")
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", resolveDownloadUserAgent(appContext))
                .get()
                .build()
            val response = downloadClient
                .newCall(request)
                .execute()

            response.use {
                if (!it.isSuccessful) {
                    target.delete()
                    diagnostics?.log("[应用内更新] 下载失败 HTTP=${it.code} url=$url")
                    return@withContext Result.Failure("下载失败（HTTP ${it.code}）")
                }
                val body = it.body ?: run {
                    target.delete()
                    return@withContext Result.Failure("下载失败：响应为空")
                }

                val total = body.contentLength().takeIf { length -> length > 0L } ?: TOTAL_UNKNOWN
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastReported = 0L
                onProgress(0L, total)

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            // 协程被取消(用户关闭对话框)时立刻跳出,由 catch 清理半成品。
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(downloaded, total)

                if (downloaded <= 0L) {
                    target.delete()
                    return@withContext Result.Failure("下载失败：未取到任何数据")
                }

                // 即使发布通道未提供 SHA-256（如 Releases API 回退路径），若服务端返回了明确大小，
                // 也必须拦截截断下载，避免把半成品 APK 交给系统安装器报「软件包似乎无效」。
                if (total != TOTAL_UNKNOWN && downloaded != total) {
                    target.delete()
                    diagnostics?.log("[应用内更新] 下载被截断 已接收=${downloaded}B 期望=${total}B url=$url")
                    return@withContext Result.Failure("下载未完成，连接提前中断。请重试或改用浏览器下载。")
                }

                val expected = expectedSha256?.trim()?.lowercase()?.takeIf { hash -> hash.isNotEmpty() }
                if (expected != null) {
                    val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                    if (actual != expected) {
                        target.delete()
                        diagnostics?.log("[应用内更新] SHA256 校验失败 期望=$expected 实际=$actual")
                        return@withContext Result.Failure("安装包校验失败，已终止安装。请改用浏览器下载。")
                    }
                }

                diagnostics?.log(
                    "[应用内更新] 下载完成 ${downloaded}B 校验=${if (expected != null) "通过" else "未提供"} 文件=${target.name}",
                )
                Result.Success(target)
            }
        } catch (error: Throwable) {
            target.delete()
            // CancellationException 要原样抛出,否则协程取消会被当成普通失败。
            if (error is kotlinx.coroutines.CancellationException) throw error
            diagnostics?.log("[应用内更新] 下载异常 ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
            Result.Failure(formatDownloadError(error))
        }
    }

    /**
     * 外站安装包下载专用客户端，与论坛主站 `container.httpClient` 彻底解耦：
     * - 不经过 `DohTransport`（Rustls ECH / Proxy.NO_PROXY）与 `AnycastDns`；
     * - 不挂载 `DohFailoverInterceptor`，避免 GitHub 传输抖动误触发论坛 DoH 节点切换；
     * - 遵循系统 DNS 与系统代理/VPN，并允许连接层失败自动重试。
     */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun resolveDownloadUserAgent(appContext: Context): String =
        (appContext as? App)?.container?.userAgent?.takeIf { it.isNotBlank() }
            ?: "LinuxDo-Android/${BuildConfig.VERSION_NAME}"

    private fun formatDownloadError(error: Throwable): String = when (error) {
        is UnknownHostException -> "下载失败：域名解析失败，请检查网络或代理后重试。"
        is SocketTimeoutException -> "下载失败：连接或传输超时，请重试或改用浏览器下载。"
        is ConnectException -> "下载失败：无法连接下载服务器，请检查网络或代理。"
        is SSLException -> "下载失败：安全连接建立失败，请检查网络后重试。"
        else -> "下载失败：${error.javaClass.simpleName}"
    }

    /** 清空旧安装包后返回本次写入的目标文件。 */
    private fun prepareTargetFile(appContext: Context, url: String): File {
        val dir = File(appContext.cacheDir, APK_DIR)
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, resolveFileName(url))
    }

    /** 用链接末段作为文件名,系统安装器会展示它;非法字符一律剔除。 */
    private fun resolveFileName(url: String): String {
        val raw = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "")
        return when {
            safe.endsWith(".apk", ignoreCase = true) && safe.length > 4 -> safe
            safe.isNotEmpty() -> "$safe.apk"
            else -> "update.apk"
        }
    }

    /** Android 8.0 起安装来源是单独的特殊权限,与 Manifest 声明无关。 */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    /** 跳转到本应用的「安装未知应用」开关页。 */
    fun unknownSourceSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * 优先使用 PackageInstaller.Session 流式写入系统安装会话，降低系统安装器对外部 FileProvider Uri 的校验/拦截风险；
     * 若会话创建、写入或提交阶段抛出异常，则自动回退到 FileProvider + ACTION_VIEW。
     */
    suspend fun install(context: Context, file: File): InstallStartResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext InstallStartResult.FailedToStart
        val appContext = context.applicationContext
        try {
            val sessionId = installViaPackageInstallerSession(appContext, file)
            InstallStartResult.SessionCommitted(sessionId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            logDiag(
                appContext,
                "[应用内更新] 流式安装会话提交失败，回退 ACTION_VIEW ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}",
            )
            if (installViaActionView(appContext, file)) {
                InstallStartResult.FallbackActivityStarted
            } else {
                InstallStartResult.FailedToStart
            }
        }
    }

    private fun installViaPackageInstallerSession(appContext: Context, file: File): Int {
        val packageInstaller = appContext.packageManager.packageInstaller
        abandonStaleSessions(packageInstaller)

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        var committed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0L, file.length()).use { out ->
                        input.copyTo(out)
                        out.flush()
                        session.fsync(out)
                    }
                }
                val callbackIntent = Intent(appContext, ApkInstallResultReceiver::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(appContext, sessionId, callbackIntent, flags)
                activeSessionId = sessionId
                activeApkFile = file
                session.commit(pendingIntent.intentSender)
                committed = true
            }
            logDiag(appContext, "[应用内更新] 已提交流式安装会话 sessionId=$sessionId size=${file.length()}")
            return sessionId
        } finally {
            if (!committed) {
                if (activeSessionId == sessionId) {
                    activeSessionId = INVALID_SESSION_ID
                }
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
        }
    }

    /** 清理本应用先前未结束的安装会话，避免触及系统并发会话上限。 */
    private fun abandonStaleSessions(packageInstaller: PackageInstaller) {
        runCatching {
            packageInstaller.mySessions.forEach { info ->
                runCatching { packageInstaller.abandonSession(info.sessionId) }
            }
        }
    }

    fun abandonActiveSession(context: Context) {
        val sessionId = activeSessionId
        activeSessionId = INVALID_SESSION_ID
        activeApkFile = null
        if (sessionId == INVALID_SESSION_ID) return
        runCatching {
            context.applicationContext.packageManager.packageInstaller.abandonSession(sessionId)
        }
    }

    /** FileProvider + ACTION_VIEW 传统安装通道（作为流式会话不可用或确认页未成功拉起时的兜底）。 */
    fun installViaActionView(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            logDiag(
                context,
                "[应用内更新] 拉起 ACTION_VIEW 安装器失败 ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}",
            )
            false
        }
    }

    internal fun onInstallResultBroadcast(context: Context, intent: Intent) {
        if (intent.action != null && intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, INVALID_SESSION_ID)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        val currentActiveId = activeSessionId
        if (currentActiveId != INVALID_SESSION_ID && sessionId != INVALID_SESSION_ID && sessionId != currentActiveId) {
            return
        }

        logDiag(
            context,
            "[应用内更新] 收到安装结果 status=$status sessionId=$sessionId msg=${statusMessage.orEmpty().take(140)}",
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    _installEvents.tryEmit(InstallEvent.PendingUserAction(sessionId, confirmIntent))
                } else {
                    if (sessionId != INVALID_SESSION_ID) {
                        runCatching { context.applicationContext.packageManager.packageInstaller.abandonSession(sessionId) }
                    }
                    activeSessionId = INVALID_SESSION_ID
                    _installEvents.tryEmit(InstallEvent.MissingConfirmIntent(sessionId))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                activeSessionId = INVALID_SESSION_ID
                cleanupApkFile(activeApkFile)
                activeApkFile = null
                _installEvents.tryEmit(InstallEvent.Succeeded(sessionId))
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                activeSessionId = INVALID_SESSION_ID
                _installEvents.tryEmit(InstallEvent.Aborted(sessionId, statusMessage))
            }

            else -> {
                activeSessionId = INVALID_SESSION_ID
                _installEvents.tryEmit(InstallEvent.Failed(sessionId, status, statusMessage))
            }
        }
    }

    fun formatInstallFailureMessage(status: Int, rawMessage: String?): String {
        val cleanMsg = rawMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        return when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "系统安全策略拦截了安装${suffixDetail(cleanMsg)}，请重试或改用浏览器下载。"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "与当前应用版本或签名冲突${suffixDetail(cleanMsg)}，若安装自不同签名来源请卸载旧版后重装，或改用浏览器下载。"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "安装包与当前设备系统不兼容${suffixDetail(cleanMsg)}，请前往 Release 页面核对安装包。"
            PackageInstaller.STATUS_FAILURE_INVALID ->
                "安装包解析或签名验证未通过${suffixDetail(cleanMsg)}，请重试下载或改用浏览器下载。"
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                "设备存储空间不足，清理空间后请重试。"
            PackageInstaller.STATUS_FAILURE_TIMEOUT ->
                "安装确认超时，请重试或改用浏览器下载。"
            else ->
                "系统安装未成功${suffixDetail(cleanMsg)}，请重试或改用浏览器下载。"
        }
    }

    private fun suffixDetail(detail: String?): String =
        if (detail.isNullOrBlank()) "" else "（$detail）"

    private fun cleanupApkFile(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
    }

    private fun logDiag(context: Context, message: String) {
        (context.applicationContext as? App)?.container?.diagnostics?.log(message)
    }
}
