package org.linuxdo.android.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticsStats(
    val lastPath: String = "-",
    val lastStatus: Int? = null,
    val silentRevalidations: Int = 0,
    val interactiveRequired: Int = 0,
)

/**
 * 诊断记录:内存里保留最近若干行并追加写入文件,
 * 这样验收③(24 小时)期间进程被杀后仍能回看历史。
 */
class Diagnostics(private val logFile: File) {
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()
    private val recentEvents = LinkedHashMap<String, Pair<String, Long>>()

    private val _lines = MutableStateFlow(loadTail())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _stats = MutableStateFlow(DiagnosticsStats())
    val stats: StateFlow<DiagnosticsStats> = _stats.asStateFlow()

    fun log(message: String) {
        val line = synchronized(lock) {
            val formatted = "${timeFormat.format(Date())} $message"
            runCatching {
                logFile.parentFile?.mkdirs()
                logFile.appendText(formatted + "\n", Charsets.UTF_8)
                if (logFile.length() > MAX_FILE_BYTES) trimFile()
            }
            formatted
        }
        _lines.update { (it + line).takeLast(MAX_MEMORY_LINES) }
    }

    fun readLatestLines(limit: Int = MAX_MEMORY_LINES): List<String> = synchronized(lock) {
        val safeLimit = limit.coerceAtLeast(0)
        if (safeLimit == 0) return@synchronized emptyList()
        runCatching {
            if (logFile.exists()) logFile.readLines(Charsets.UTF_8).takeLast(safeLimit) else emptyList()
        }.getOrDefault(emptyList()).ifEmpty {
            _lines.value.takeLast(safeLimit)
        }
    }

    fun clear() {
        synchronized(lock) {
            recentEvents.clear()
            runCatching {
                if (logFile.exists()) {
                    logFile.writeText("", Charsets.UTF_8)
                }
            }
        }
        _lines.value = emptyList()
        _stats.update { DiagnosticsStats() }
    }

    /** 相同后台状态每分钟最多记录一次，状态变化和手动检查立即记录。 */
    fun logThrottled(key: String, message: String, force: Boolean = false) {
        val shouldLog = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val previous = recentEvents[key]
            if (!force && previous != null && previous.first == message && now - previous.second < 60_000L) {
                false
            } else {
                recentEvents[key] = message to now
                if (recentEvents.size > 128) recentEvents.remove(recentEvents.keys.first())
                true
            }
        }
        if (shouldLog) log(message)
    }

    fun recordStatus(pathName: String, status: Int, requestPath: String) {
        _stats.update { it.copy(lastPath = pathName, lastStatus = status) }
        log("[$pathName] $requestPath -> $status")
    }

    fun recordSilentRevalidation() {
        _stats.update { it.copy(silentRevalidations = it.silentRevalidations + 1) }
    }

    fun recordInteractiveRequired(reason: String) {
        _stats.update { it.copy(interactiveRequired = it.interactiveRequired + 1) }
        log("需要交互验证: $reason")
    }

    val crashFile: File get() = File(logFile.parentFile, "crash.log")

    fun hasCrashLog(): Boolean = runCatching { crashFile.exists() && crashFile.length() > 0 }.getOrDefault(false)

    fun readCrashLog(): String? = synchronized(lock) {
        runCatching {
            if (crashFile.exists()) crashFile.readText(Charsets.UTF_8) else null
        }.getOrNull()
    }

    fun clearCrashLog() = synchronized(lock) {
        runCatching {
            if (crashFile.exists()) {
                crashFile.delete()
            }
        }
    }

    /** 记录未捕获异常崩溃堆栈，强制同步刷盘 */
    fun recordCrash(thread: Thread, throwable: Throwable, context: Context? = null) {
        synchronized(lock) {
            runCatching {
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val stackTrace = Log.getStackTraceString(throwable)
                val runtime = Runtime.getRuntime()
                val freeMb = runtime.freeMemory() / (1024 * 1024f)
                val totalMb = runtime.totalMemory() / (1024 * 1024f)
                val maxMb = runtime.maxMemory() / (1024 * 1024f)
                val pkgInfo = context?.let {
                    runCatching { it.packageManager.getPackageInfo(it.packageName, 0) }.getOrNull()
                }
                val verName = pkgInfo?.versionName ?: "unknown"
                val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo?.longVersionCode ?: 0L
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo?.versionCode?.toLong() ?: 0L
                }

                val crashHeader = buildString {
                    appendLine("💥💥💥 ==================== CRASH REPORT ====================")
                    appendLine("Time: $timeStr")
                    appendLine("App Version: v$verName ($verCode)")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name} (id=${thread.id})")
                    appendLine("Memory: Free=%.1fMB, Total=%.1fMB, Max=%.1fMB".format(Locale.US, freeMb, totalMb, maxMb))
                    appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                    appendLine("-------------------- STACKTRACE --------------------")
                    appendLine(stackTrace.trimEnd())
                    appendLine("============================================================")
                }

                // 1. 同步写入专属 crash.log 文件，追加并刷盘
                crashFile.parentFile?.mkdirs()
                FileOutputStream(crashFile, true).use { fos ->
                    fos.write((crashHeader + "\n\n").toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }

                // 如果 crash.log 过大(>256KB)，截取并保留最后 128KB
                if (crashFile.length() > MAX_CRASH_FILE_BYTES) {
                    val content = crashFile.readText(Charsets.UTF_8)
                    val trimmed = content.takeLast(128 * 1024)
                    crashFile.writeText(trimmed, Charsets.UTF_8)
                }

                // 2. 将崩溃概要写入当前的 diagnostics.log 文件
                val diagLine = "${timeFormat.format(Date())} 💥 [CRASH] ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}"
                logFile.appendText(diagLine + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun loadTail(): List<String> = runCatching {
        if (logFile.exists()) logFile.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_LINES) else emptyList()
    }.getOrDefault(emptyList())

    private fun trimFile() {
        val tail = logFile.readLines(Charsets.UTF_8).takeLast(KEEP_FILE_LINES)
        logFile.writeText(tail.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private companion object {
        const val MAX_MEMORY_LINES = 300
        const val KEEP_FILE_LINES = 2_000
        const val MAX_FILE_BYTES = 512 * 1024L
        const val MAX_CRASH_FILE_BYTES = 256 * 1024L
    }
}
