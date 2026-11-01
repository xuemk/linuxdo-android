package org.linuxdo.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.linuxdo.android.push.LinuxDoPushSdk
import org.linuxdo.android.push.NativePushCore
import org.linuxdo.android.push.PushNotificationScope
import org.linuxdo.android.push.PushSessionProvider

class NotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val session = LinuxDoPushSdk.getConfig().sessionProvider(context)
        if (!session.isBackgroundNotificationEnabled() || !session.hasSession()) {
            return@withContext Result.success()
        }
        pollOnceWithLagRetry(context, session)
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "linuxdo_periodic_notification_worker"
        private const val MAX_PUSH_PER_POLL = 3
        private const val POLL_LIMIT = 30

        private val pollMutex = Mutex()
        private data class PushJsonKeys(
            val notifications: String,
            val id: String,
            val read: String,
            val notificationType: String,
            val topicId: String,
            val postNumber: String,
            val data: String,
            val topicTitle: String,
            val displayUsername: String,
            val originalUsername: String,
            val originalText: String,
            val excerpt: String,
            val cooked: String,
            val raw: String,
        )

        private fun pushJsonKeys() = PushJsonKeys(
            notifications = NativePushCore.field(NativePushCore.FIELD_NOTIFICATIONS),
            id = NativePushCore.field(NativePushCore.FIELD_ID),
            read = NativePushCore.field(NativePushCore.FIELD_READ),
            notificationType = NativePushCore.field(NativePushCore.FIELD_NOTIFICATION_TYPE),
            topicId = NativePushCore.field(NativePushCore.FIELD_TOPIC_ID),
            postNumber = NativePushCore.field(NativePushCore.FIELD_POST_NUMBER),
            data = NativePushCore.field(NativePushCore.FIELD_DATA),
            topicTitle = NativePushCore.field(NativePushCore.FIELD_TOPIC_TITLE),
            displayUsername = NativePushCore.field(NativePushCore.FIELD_DISPLAY_USERNAME),
            originalUsername = NativePushCore.field(NativePushCore.FIELD_ORIGINAL_USERNAME),
            originalText = NativePushCore.field(NativePushCore.FIELD_ORIGINAL_TEXT),
            excerpt = NativePushCore.field(NativePushCore.FIELD_EXCERPT),
            cooked = NativePushCore.field(NativePushCore.FIELD_COOKED),
            raw = NativePushCore.field(NativePushCore.FIELD_RAW),
        )

        fun isNetworkConnected(context: Context): Boolean {
            return runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.getOrDefault(false)
        }

        suspend fun pollOnceWithLagRetry(context: Context, sessionStore: PushSessionProvider) = withContext(Dispatchers.IO) {
            if (!sessionStore.hasSession()) return@withContext
            NotificationHelper.recordPushState(context, "后台拉取")
            var waitCount = 0
            while (!isNetworkConnected(context) && waitCount < 5) {
                delay(400L)
                waitCount++
            }
            if (waitCount > 0) {
                NotificationHelper.logDiagnostic(context, "push.network.wait", "[后台拉取] 等待网络=${waitCount * 400}ms 联网能力=${isNetworkConnected(context)}")
            }
            val firstOk = pollOnce(context, sessionStore, isFinalAttempt = false)
            if (!firstOk) {
                NotificationHelper.logDiagnostic(context, "push.retry", "[后台拉取] 首次失败，1.5秒后重试一次")
                delay(1500L)
                pollOnce(context, sessionStore, isFinalAttempt = true)
            }
        }

        private fun fetchPostExcerpt(
            httpClient: OkHttpClient,
            baseUrl: String,
            topicId: Long,
            postNumber: Int,
            keys: PushJsonKeys,
        ): String? {
            return try {
                val fastClient = httpClient.newBuilder()
                    .connectTimeout(5000, TimeUnit.MILLISECONDS)
                    .readTimeout(5000, TimeUnit.MILLISECONDS)
                    .build()
                val url = "$baseUrl${NativePushCore.postsByNumberPath()}$topicId/$postNumber.json"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body?.string() ?: return null
                    if (body.trimStart().startsWith("<")) return null
                    val postObj = JSONObject(body)
                    val rawCooked = postObj.optString(keys.cooked)
                        .ifBlank { postObj.optString(keys.excerpt) }
                        .ifBlank { postObj.optString(keys.raw) }
                    if (rawCooked.isBlank()) return null
                    NativePushCore.sanitizeContent(rawCooked).takeIf { it.isNotBlank() }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

        suspend fun pollOnce(
            context: Context,
            sessionStore: PushSessionProvider,
            isFinalAttempt: Boolean = true,
        ): Boolean {
            if (!pollMutex.tryLock()) {
                NotificationHelper.logDiagnostic(
                    context, "push.skip.concurrent",
                    "[后台拉取] 另一轮拉取正在执行中，跳过本次重入触发",
                )
                return true
            }
            return try {
                pollOnceExclusive(context, sessionStore, isFinalAttempt)
            } catch (ce: CancellationException) {
                NotificationHelper.logDiagnostic(
                    context, "push.cancelled",
                    "[后台拉取] 轮询协程被外部掐断(CancellationException)，游标未被污染",
                    force = true,
                )
                throw ce
            } finally {
                pollMutex.unlock()
            }
        }

        private fun countBadgeUnread(
            notifications: JSONArray,
            scope: PushNotificationScope,
            keys: PushJsonKeys,
        ): Int {
            val count = notifications.length()
            val types = IntArray(count)
            val reads = BooleanArray(count)
            for (index in 0 until count) {
                val notification = notifications.optJSONObject(index) ?: continue
                types[index] = notification.optInt(keys.notificationType, 0)
                reads[index] = notification.optBoolean(keys.read, false)
            }
            return NativePushCore.countUnread(types, reads, scope)
        }

        private fun pollOnceExclusive(
            context: Context,
            sessionStore: PushSessionProvider,
            isFinalAttempt: Boolean,
        ): Boolean {
            if (!sessionStore.hasSession()) return true
            val config = LinuxDoPushSdk.getConfig()
            val httpClient = config.httpClientProvider()
            val baseUrl = config.baseUrl
            val startedAt = SystemClock.elapsedRealtime()

            try {
                val url = "$baseUrl${NativePushCore.notificationsPath()}"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    NotificationHelper.logDiagnostic(
                        context, "push.http",
                        "[后台拉取] 通知接口 HTTP=${response.code} 最后尝试=$isFinalAttempt",
                    )
                    if (response.code in listOf(401, 403, 503)) {
                        val isLast = isFinalAttempt || response.code == 401
                        val blockedTag = if (isLast) sessionStore.getPendingTestReplayTag() else null
                        if (!blockedTag.isNullOrBlank()) {
                            sessionStore.setPendingTestReplayTag(null)
                            val reason = if (response.code == 401) "（会话失效）" else "（安全挑战）"
                            NotificationHelper.showNotification(
                                context = context,
                                notificationId = System.currentTimeMillis() % 1_000_000L,
                                title = "[$blockedTag] 后台拉取被拦截",
                                content = "服务端返回 HTTP ${response.code}$reason",
                            )
                        }
                        return response.code == 401
                    }
                    if (!response.isSuccessful) return false
                    val body = response.body?.string() ?: run {
                        NotificationHelper.logDiagnostic(context, "push.response", "[后台拉取] 响应体为空，未更新通知")
                        return true
                    }
                    if (body.trimStart().startsWith("<")) {
                        NotificationHelper.logDiagnostic(context, "push.response", "[后台拉取] 收到HTML而非JSON，可能需要安全验证；未更新通知")
                        return true
                    }

                    val keys = pushJsonKeys()
                    val root = JSONObject(body)
                    val notifications = root.optJSONArray(keys.notifications) ?: run {
                        NotificationHelper.logDiagnostic(context, "push.response", "[后台拉取] JSON缺少通知数组字段，未更新通知")
                        return true
                    }
                    val notificationScope = sessionStore.getNotificationScope()

                    val replayTag = sessionStore.getPendingTestReplayTag()
                    if (!replayTag.isNullOrBlank()) {
                        sessionStore.setPendingTestReplayTag(null)
                        val unreadReal = countBadgeUnread(notifications, notificationScope, keys)
                        val targetBadge = (unreadReal + 1).coerceAtLeast(1)
                        sessionStore.setBadgeTestHoldUntil(System.currentTimeMillis() + 180_000L)
                        sessionStore.setLastBadgeCount(targetBadge, force = true)
                        NotificationHelper.logDiagnostic(context, "push.test", "[后台拉取] 实测=$replayTag 条目=${notifications.length()} 真实未读=$unreadReal 角标=$targetBadge", true)

                        var candidate: JSONObject? = null
                        for (i in 0 until notifications.length()) {
                            val obj = notifications.optJSONObject(i) ?: continue
                            if (obj.optLong(keys.topicId, 0L) > 0L && obj.optInt(keys.postNumber, 0) > 0) {
                                candidate = obj
                                break
                            }
                        }
                        if (candidate == null && notifications.length() > 0) {
                            candidate = notifications.optJSONObject(0)
                        }

                        if (candidate != null) {
                            val id = candidate.optLong(keys.id, System.currentTimeMillis() % 1_000_000L)
                            val type = candidate.optInt(keys.notificationType, 2)
                            val dataObj = candidate.optJSONObject(keys.data)
                            val topicTitle = dataObj?.optString(keys.topicTitle)
                            val senderName = dataObj?.optString(keys.displayUsername)
                                ?.ifBlank { dataObj.optString(keys.originalUsername) }
                                ?: "社区用户"
                            val topicId = candidate.optLong(keys.topicId, 0L).takeIf { it > 0L }
                            val postNumber = candidate.optInt(keys.postNumber, 0).takeIf { it > 0 }
                            val fetchedExcerpt = if (topicId != null && postNumber != null) {
                                fetchPostExcerpt(httpClient, baseUrl, topicId, postNumber, keys)
                            } else null
                            val dataExcerpt = fetchedExcerpt
                                ?: dataObj?.optString(keys.originalText)
                                ?: dataObj?.optString(keys.excerpt)

                            val (formattedTitle, formattedContent) = NotificationHelper.formatNotification(
                                notificationType = type,
                                senderName = senderName,
                                topicTitle = topicTitle,
                                dataExcerpt = dataExcerpt,
                            )
                            NotificationHelper.showNotification(
                                context = context,
                                notificationId = id,
                                title = "[$replayTag] $formattedTitle",
                                content = formattedContent,
                                topicId = topicId,
                                postNumber = postNumber,
                                notificationType = type,
                                badgeCount = targetBadge,
                            )
                        } else {
                            NotificationHelper.showNotification(
                                context = context,
                                notificationId = System.currentTimeMillis() % 1_000_000L,
                                title = "[$replayTag] LINUX DO 实测通知",
                                content = "后台网络拉取成功",
                                badgeCount = targetBadge,
                            )
                        }
                        return true
                    }

                    val unreadTotal = countBadgeUnread(notifications, notificationScope, keys)
                    val lastSeenId = sessionStore.getLastSeenNotificationId()
                    if (lastSeenId <= 0L) {
                        val ids = LongArray(notifications.length())
                        for (i in 0 until notifications.length()) {
                            ids[i] = notifications.optJSONObject(i)?.optLong(keys.id, 0L) ?: 0L
                        }
                        val currentMaxId = NativePushCore.baselineCursor(ids)
                        if (currentMaxId > 0L) {
                            sessionStore.setLastSeenNotificationId(currentMaxId)
                        }
                        if (!sessionStore.isBadgeTestHolding()) {
                            sessionStore.setLastBadgeCount(unreadTotal)
                            NotificationHelper.updateBadgeCount(context, unreadTotal, source = "后台基线")
                        }
                        NotificationHelper.logDiagnostic(context, "push.summary", "[后台拉取] 建立基线 条目=${notifications.length()} 未读=$unreadTotal，未发送历史通知")
                        return true
                    }

                    val count = notifications.length()
                    val ids = LongArray(count)
                    val types = IntArray(count)
                    val reads = BooleanArray(count)
                    val topicIds = LongArray(count)
                    val postNumbers = IntArray(count)
                    val objects = arrayOfNulls<JSONObject>(count)

                    for (index in 0 until count) {
                        val notification = notifications.optJSONObject(index) ?: continue
                        objects[index] = notification
                        ids[index] = notification.optLong(keys.id, 0L)
                        types[index] = notification.optInt(keys.notificationType, 0)
                        reads[index] = notification.optBoolean(keys.read, false)
                        topicIds[index] = notification.optLong(keys.topicId, 0L)
                        postNumbers[index] = notification.optInt(keys.postNumber, 0)
                    }

                    // 游标自愈。
                    //
                    // 游标高于本页最大 id，意味着它指向一条已经不在列表里的通知（被删除、
                    // 或本就不出现在 recent 窗口中）。通知 id 全局自增，正常情况下新通知的
                    // id 总会更大、仍能被识别；但一旦游标因任何异常被推到远超服务端的值，
                    // 推送就会永久静默，而且全链路没有任何自恢复手段。
                    // 回退到本页最大 id 是安全的：已读条目仍会被已读标记挡掉，
                    // 未读条目正是本该补发的那些。本轮只回退不下发，交给下一轮正常处理。
                    val pageMaxId = NativePushCore.pageMaxId(ids)
                    if (pageMaxId > 0L && lastSeenId > pageMaxId) {
                        if (!sessionStore.isBadgeTestHolding()) {
                            sessionStore.setLastBadgeCount(unreadTotal)
                            NotificationHelper.updateBadgeCount(context, unreadTotal, source = "后台轮询")
                        }
                        sessionStore.setLastSeenNotificationId(pageMaxId)
                        NotificationHelper.logDiagnostic(
                            context, "push.cursor.heal",
                            "[后台拉取] 游标自愈 $lastSeenId→$pageMaxId " +
                                "（游标高于本页最大ID，疑似指向已删除的通知），本轮不下发",
                            force = true,
                        )
                        return true
                    }

                    // Native 层持有作用域筛选、去重、封顶和游标结算规则。这里不提前写入任何状态。
                    val decisionResult = NativePushCore.decide(
                        ids = ids,
                        types = types,
                        reads = reads,
                        topicIds = topicIds,
                        postNumbers = postNumbers,
                        lastCursor = lastSeenId,
                        scope = notificationScope,
                        maxPush = MAX_PUSH_PER_POLL,
                    )
                    val delivered = BooleanArray(count)
                    var pushed = 0
                    var newerUnreadCount = 0
                    var inScopeNewerUnreadCount = 0
                    var duplicateSkippedCount = 0
                    var capSkippedCount = 0

                    for (index in 0 until count) {
                        val notification = objects[index] ?: continue
                        val id = ids[index]
                        if (!reads[index] && id > lastSeenId) {
                            newerUnreadCount++
                        }

                        when (NativePushCore.decisionAt(decisionResult, index)) {
                            NativePushCore.DECISION_SCOPE -> {
                                NotificationHelper.logDiagnostic(
                                    context,
                                    "push.decision.$id.scope",
                                    "[后台拉取] 通知 id=$id type=${types[index]} 决策=不在作用域",
                                )
                            }
                            NativePushCore.DECISION_DELIVERED -> {
                                duplicateSkippedCount++
                                inScopeNewerUnreadCount++
                                NotificationHelper.logDiagnostic(
                                    context,
                                    "push.decision.$id.delivered",
                                    "[后台拉取] 通知 id=$id type=${types[index]} 决策=此前已提交",
                                )
                            }
                            NativePushCore.DECISION_CAP -> {
                                capSkippedCount++
                                inScopeNewerUnreadCount++
                                NotificationHelper.logDiagnostic(
                                    context,
                                    "push.decision.$id.cap",
                                    "[后台拉取] 通知 id=$id type=${types[index]} 决策=本轮封顶，保留待发",
                                )
                            }
                            NativePushCore.DECISION_DELIVER -> {
                                inScopeNewerUnreadCount++
                                val data = notification.optJSONObject(keys.data)
                                val topicTitle = data?.optString(keys.topicTitle)
                                val senderName = data?.optString(keys.displayUsername)
                                    ?.ifBlank { data.optString(keys.originalUsername) }
                                    ?: "社区用户"
                                val topicId = topicIds[index].takeIf { it > 0L }
                                val postNumber = postNumbers[index].takeIf { it > 0 }
                                // optString 在字段缺失时返回空串而非 null，必须显式判空，
                                // 否则 ?: 链会被空串截断，data 里的备用正文字段永远取不到。
                                val dataExcerpt = data?.optString(keys.originalText)?.takeIf { it.isNotBlank() }
                                    ?: data?.optString(keys.excerpt)?.takeIf { it.isNotBlank() }
                                // 仅当 data 内没有正文、且该类型的正文有实际阅读价值时才链式拉取单帖。
                                // 否则点赞、徽章之类无需摘要的通知也会各发一次请求。
                                val excerpt = dataExcerpt ?: if (
                                    topicId != null && postNumber != null &&
                                    NativePushCore.shouldFetchExcerpt(types[index])
                                ) {
                                    fetchPostExcerpt(httpClient, baseUrl, topicId, postNumber, keys)
                                } else {
                                    null
                                }
                                val (title, content) = NotificationHelper.formatNotification(
                                    notificationType = types[index],
                                    senderName = senderName,
                                    topicTitle = topicTitle,
                                    dataExcerpt = excerpt,
                                )
                                val badge = (sessionStore.getLastBadgeCount() + 1).coerceAtLeast(1)

                                // notify 成功前不改变游标或 Native 去重状态；取消后下一轮自然补发。
                                val posted = NotificationHelper.showNotification(
                                    context = context,
                                    notificationId = id,
                                    title = title,
                                    content = content,
                                    topicId = topicId,
                                    postNumber = postNumber,
                                    notificationType = types[index],
                                    badgeCount = badge,
                                )
                                if (!posted) {
                                    NotificationHelper.logDiagnostic(
                                        context,
                                        "push.decision.$id.manager",
                                        "[后台拉取] 通知 id=$id type=${types[index]} 决策=系统通知服务不可用，保留待发",
                                        true,
                                    )
                                    continue
                                }
                                NativePushCore.markDelivered(id, topicIds[index], postNumbers[index])
                                sessionStore.setLastBadgeCount(badge)
                                delivered[index] = true
                                pushed++
                                NotificationHelper.logDiagnostic(
                                    context,
                                    "push.decision.$id.delivered_now",
                                    "[后台拉取] 通知 id=$id type=${types[index]} 决策=已提交",
                                    true,
                                )
                            }
                        }
                    }

                    if (!sessionStore.isBadgeTestHolding()) {
                        sessionStore.setLastBadgeCount(unreadTotal)
                        NotificationHelper.updateBadgeCount(context, unreadTotal, source = "后台轮询")
                    }

                    val cursorTarget = NativePushCore.finalizeCursor(
                        ids = ids,
                        decisions = decisionResult,
                        delivered = delivered,
                        lastCursor = lastSeenId,
                    )
                    NotificationHelper.logDiagnostic(
                        context,
                        "push.summary",
                        "[后台拉取] 条目=$count 未读=$unreadTotal 新未读=$newerUnreadCount " +
                            "作用域内新未读=$inScopeNewerUnreadCount 已弹过跳过=$duplicateSkippedCount " +
                            "封顶跳过=$capSkippedCount 游标=$lastSeenId→$cursorTarget 本页最大ID=$pageMaxId " +
                            "提交通知=$pushed 作用域=$notificationScope",
                        force = true,
                    )

                    // 一条「作用域内新未读」都没有时，打出页首原始字段。这是区分三种成因的
                    // 唯一依据：服务端确实没有新通知 / 新通知带着已读标记回来 /
                    // 游标已经超前于服务端最新 id（本页最大ID < 游标 即为后者）。
                    if (inScopeNewerUnreadCount == 0) {
                        val headSnapshot = (0 until minOf(count, 5)).joinToString("; ") { index ->
                            "id=${ids[index]} type=${types[index]} read=${reads[index]}"
                        }
                        NotificationHelper.logDiagnostic(
                            context, "push.page.head",
                            "[后台拉取] 页首快照 游标=$lastSeenId [$headSnapshot]",
                            force = true,
                        )
                    }

                    if (cursorTarget > lastSeenId) {
                        sessionStore.setLastSeenNotificationId(cursorTarget)
                    }
                    return true
                }
            } catch (error: CancellationException) {
                NotificationHelper.logDiagnostic(
                    context,
                    "push.cancelled",
                    "[后台拉取] 本轮被取消 最后尝试=$isFinalAttempt " +
                        "耗时=${SystemClock.elapsedRealtime() - startedAt}ms，游标与去重状态未提交",
                    force = true,
                )
                throw error
            } catch (error: Exception) {
                NotificationHelper.logDiagnostic(context, "push.error", "[后台拉取] 异常=${error.javaClass.simpleName}", force = true)
                NotificationHelper.recordPushState(context, "后台拉取失败", force = true)
                return false
            } finally {
                // 耗时超过 10 秒意味着唤醒锁已经持了很久，必须留痕：否则排查时
                // 只看到一条孤零零的 HTTP 日志，无法判断是网络慢还是进程被冻结。
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                if (elapsedMs >= 10_000L) {
                    NotificationHelper.logDiagnostic(
                        context, "push.slow",
                        "[后台拉取] 本次耗时=${elapsedMs / 1000}s，需结合唤醒锁与网络状态检查",
                    )
                }
            }
        }

        fun schedulePeriodic(context: Context, intervalMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest,
            )
        }

        fun triggerImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<NotificationWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * 保持宿主旧调用面。实际实时轮询由 AAR 内前台服务统一接管，避免 WorkManager 与服务双轨并发。
         */
        fun syncSilentLoop(context: Context) {
            enqueue(context)
        }

        fun enqueue(context: Context) {
            val appContext = context.applicationContext
            val session = LinuxDoPushSdk.getConfig().sessionProvider(appContext)
            if (!session.isBackgroundNotificationEnabled() || !session.hasSession()) {
                cancel(appContext)
                return
            }
            cancelPeriodic(appContext)
            NotificationKeepAliveService.start(appContext)
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            cancelPeriodic(appContext)
            NotificationKeepAliveService.stop(appContext)
        }
    }
}
