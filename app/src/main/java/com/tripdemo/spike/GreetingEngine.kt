package com.tripdemo.spike

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 实时问候引擎：固定每 6 秒由采集器喂一帧进来，调 Step-1o Turbo Vision
 * 生成一句 ≤15 字的主动搭话问候，显示在预览悬浮提示上。
 * 与「行程总结」（step-3.7-flash、每 N 帧）是两条独立工作流。
 */
class GreetingEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TripGreet"

        @Volatile private var instance: GreetingEngine? = null

        fun get(context: Context): GreetingEngine =
            instance ?: synchronized(this) {
                instance ?: GreetingEngine(context.applicationContext).also { instance = it }
            }
    }

    @Volatile var busy = false
        private set

    var onGreeting: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 采集器每 6 秒喂一帧；上一发还没回就跳过（尽力而为，不排队） */
    fun onFrame(jpeg: ByteArray) {
        if (busy) return
        busy = true
        scope.launch {
            val text = try {
                if (!StepVlConfig.greetConfigured(context)) {
                    "未配置 API Key"
                } else {
                    StepVlClient().greet(
                        baseUrl = StepVlConfig.greetBaseUrl(context),
                        apiKey = StepVlConfig.greetApiKey(context),
                        model = StepVlConfig.greetModel(context),
                        jpeg = jpeg,
                        prompt = StepVlConfig.greetPrompt(context),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "greet failed", e)
                null
            }
            busy = false
            if (text != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onGreeting?.invoke(text) }
            }
        }
    }
}
