package org.linuxdo.android.net

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 原生 DoH + ECH 应用级故障感知与安全重试拦截器。
 *
 * 核心设计：
 * 1. 注册在应用级拦截器链（builder.addInterceptor，位于最外层），能完整感知 DNS、连接建立与 TLS 握手全程异常。
 * 2. 失败仅上报协调器，绝不在拦截器内部同步执行耗时重构或关闭旧 Profile。
 * 3. 严格遵循幂等性：
 *    - 仅对 GET / HEAD 且非 one-shot 的只读请求最多重试一次（附带 X-Doh-Retried 标头防循环）；
 *    - POST / PUT / DELETE 等写操作发生异常时立即抛出，绝对不自动重放！
 */
class DohFailoverInterceptor(
    private val coordinator: DohFailoverCoordinator,
) : Interceptor {

    companion object {
        private const val TAG = "DohFailover"
        private const val HEADER_RETRIED = "X-Doh-Retried"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val currentEpoch = coordinator.currentEpoch.get()

        try {
            return chain.proceed(request)
        } catch (e: IOException) {
            // 1. 判断是否符合上报资格并异步上报
            if (coordinator.isFailoverEligible(e)) {
                coordinator.reportFailure(currentEpoch.generation, currentEpoch.server.id, e)
            }

            // 2. 幂等安全重试检查
            val isIdempotent = request.method in listOf("GET", "HEAD")
            val isOneShot = request.body?.isOneShot() == true
            val hasRetried = request.header(HEADER_RETRIED) != null

            if (isIdempotent && !isOneShot && !hasRetried && coordinator.isFailoverEligible(e)) {
                Log.w(TAG, "只读请求 ${request.method} ${request.url} 遭遇网络异常: ${e.message}，等待协调器代次切换后重试...")
                // 同步等待新代次（至多 1.5 秒）
                val nextEpoch = runBlocking {
                    coordinator.awaitOrGetNextEpoch(currentEpoch.generation)
                }

                if (nextEpoch.generation > currentEpoch.generation) {
                    val retryRequest = request.newBuilder()
                        .header(HEADER_RETRIED, "1")
                        .build()
                    Log.i(TAG, "使用新代次 [gen ${nextEpoch.generation}: ${nextEpoch.server.name}] 发起单次重试")
                    return nextEpoch.client.newCall(retryRequest).execute()
                }
            }

            // 凡是不符合重试条件（如 POST 请求、已重试过、或无新代次），如实抛出原始异常
            throw e
        }
    }
}
