package org.linuxdo.android.data

import java.io.File
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

    private fun loadTail(): List<String> = runCatching {
        if (logFile.exists()) logFile.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_LINES) else emptyList()
    }.getOrDefault(emptyList())

    private fun trimFile() {
        val tail = logFile.readLines(Charsets.UTF_8).takeLast(KEEP_FILE_LINES)
        logFile.writeText(tail.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private companion object {
        const val MAX_MEMORY_LINES = 80
        const val KEEP_FILE_LINES = 2_000
        const val MAX_FILE_BYTES = 512 * 1024L
    }
}
