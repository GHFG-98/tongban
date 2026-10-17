package com.tripdemo.spike

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 总结引擎：**每 N 帧总结一次**（N 在设置页可调，默认 12）。
 * 每次总结取本次累计的 N 帧拼成拼图，调 step-3.7-flash 生成中文「行程小记」。
 * 也支持手动「立即生成一次」。
 */
class SummaryEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TripSummary"
        private const val RING_MAX = 100          // 内存环最多留 100 帧（约 4MB）

        @Volatile private var instance: SummaryEngine? = null

        /** 全 App 单例：录制页与总结页共享同一份帧缓冲 */
        /** 全 App 单例：录制页与总结页共享同一份帧缓冲 */
        fun get(context: Context): SummaryEngine =
            instance ?: synchronized(this) {
                instance ?: SummaryEngine(context.applicationContext).also {
                    instance = it
                    // 视频合成需要 cacheDir（VideoAssembler 是 object，拿不到 Context）
                    VideoAssembler.cacheDirContext = context.applicationContext
                }
            }
    }

    data class SummaryRec(val ts: Long, val text: String, val sheetName: String?, val ok: Boolean)

    private val ring = ArrayList<FrameSnap>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tripStartAt = 0L
    private var framesPerSummary = CaptureSettings.DEFAULT_FRAMES_PER_SUMMARY
    private var pendingFrames = 0          // 距上次总结已积累的帧数
    private val historyTexts = ArrayList<String>()   // 本 trip 已生成的小记（全量上下文）

    @Volatile var busy = false
        private set

    var onSummary: ((SummaryRec) -> Unit)? = null

    /** 帧清单提供者（序号+相对时间+GPS），由主界面按当前 trip 注入 */
    var manifestProvider: (() -> String)? = null

    /** 位置速览提供者（起点→终点），写入小记 JSON 供问答使用 */
    var locationDigestProvider: (() -> String)? = null

    /** 途中的语音识别文本提供者（带行程相对时间），总结时一并送上 */
    var asrTextProvider: (() -> String)? = null
    var onStatus: ((String) -> Unit)? = null

    /**
     * 批次边界挂起回调（音频切段 + 识别本批语音），由 CaptureService 注入。
     * 总结会等它完成再发——保证每批小计都带上【本批 + 之前所有】的语音。
     */
    var onBatchAsr: (suspend () -> Unit)? = null

    /** 新 trip 开始：清空旧帧，读取当前的总结频率设置 */
    fun onTripStarted(tripId: String, startedAt: Long) {
        tripStartAt = startedAt
        framesPerSummary = CaptureSettings.framesPerSummary(context)
        pendingFrames = 0
        synchronized(historyTexts) { historyTexts.clear() }
        synchronized(ring) { ring.clear() }
        Log.i(TAG, "trip $tripId started, every $framesPerSummary frames -> summary")
    }

    /** 每存一帧就喂进来；满 N 帧自动触发一次总结 */
    fun onFrame(ts: Long, tripId: String, jpeg: ByteArray) {
        synchronized(ring) {
            ring.add(FrameSnap(ts, tripId, jpeg))
            while (ring.size > RING_MAX) ring.removeAt(0)
        }
        pendingFrames++
        if (pendingFrames >= framesPerSummary) {
            pendingFrames = 0
            scope.launch {
                // 先识别本批语音，再总结——小计里一定带上这段对话（没开收音则直接跳过）
                if (CaptureSettings.audioEnabled(context)) post("正在识别本段语音…")
                try {
                    onBatchAsr?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "batch asr failed", e)
                }
                summarize("auto")
            }
        }
    }

    /** 手动触发（UI 线程可直接调，内部切 IO） */
    fun summarizeNow() {
        scope.launch { summarize("manual") }
    }

    private suspend fun summarize(source: String) {
        if (busy) {
            post("上一次总结还没返回，跳过本次（$source）")
            return
        }
        busy = true
        try {
            if (!StepVlConfig.configured(context)) {
                post("未配置 API Key：请到「API 设置」填写（stepplan step-3.7-flash）")
                return
            }
            // 取本次累计的 N 帧（不满 N 帧时用环里最后的 N 帧兜底）
            val frames = synchronized(ring) {
                val n = framesPerSummary.coerceAtMost(ring.size)
                if (n <= 0) emptyList()
                else ring.subList(ring.size - n, ring.size).toList().sortedBy { it.ts }
            }
            val manifest = manifestProvider?.invoke() ?: "（无帧清单）"
            val history = synchronized(historyTexts) { historyTexts.toList() }
            val asrText = asrTextProvider?.invoke() ?: ""
            val locDigest = locationDigestProvider?.invoke() ?: ""
            // 时间（帧清单相对时间 + 语音相对时间）、GPS（每帧坐标 + 起终点速览）、语音，一起送模型
            val fullPrompt = buildString {
                append(StepVlConfig.summaryPrompt(context))
                if (history.isNotEmpty()) {
                    append("\n\n【之前各段的小记】\n")
                    history.forEachIndexed { i, t -> append("第${i + 1}段：$t\n") }
                }
                append("\n【最新一段的帧清单（序号.相对行程开始的时间 GPS位置）】\n")
                append(manifest)
                if (locDigest.isNotBlank()) {
                    append("\n【本段位置速览（起点→终点）】\n")
                    append(locDigest)
                }
                if (asrText.isNotBlank()) {
                    append("\n\n【途中的语音（用户随行说的话，带相对时间）】\n")
                    append(asrText)
                }
            }
            // 诊断日志：确认语音/GPS/帧清单真的进了 prompt（排"没传过去"的疑问）
            Log.i(TAG, "prompt[$source] frames=${frames.size} manifest=${manifest.length}字 " +
                "asr=${asrText.length}字 history=${history.size} loc=${locDigest.length}字")

            // 拼图无论如何都生成并落盘：小计卡片要用它做缩略图
            // （原图/视频模式下以前不生成，导致卡片图片空白）
            var sheetName = ""
            val sheet: ByteArray? = try {
                post("正在生成拼图…")
                val s = ContactSheetBuilder.build(frames, tripStartAt)
                sheetName = "sheet_${System.currentTimeMillis()}.jpg"
                MediaStoreHelper.saveJpeg(context, MediaStoreHelper.SHEETS_DIR, sheetName, s)
                s
            } catch (e: Exception) {
                Log.e(TAG, "sheet build failed", e)
                null
            }

            val mode = CaptureSettings.imageSendMode(context)
            val text = try {
                when (mode) {
                    CaptureSettings.IMAGE_MODE_ALL -> {
                        // 全部原图：多图直发（限 12 帧防超限）
                        post("正在发送全部原图并总结（${frames.size} 帧）…")
                        val picked = pickEvenly(frames, 12)
                        StepVlClient().summarizeMulti(
                            baseUrl = StepVlConfig.baseUrl(context),
                            apiKey = StepVlConfig.apiKey(context),
                            model = StepVlConfig.model(context),
                            jpegs = picked.map { it.jpeg },
                            labels = picked.map { seg -> relLabel(seg.ts - tripStartAt) },
                            prompt = fullPrompt,
                        )
                    }
                    CaptureSettings.IMAGE_MODE_VIDEO -> {
                        // 小视频：帧合成 MP4 直发（实测比多图省约 8 倍 token，时序理解更好）
                        post("正在合成视频并总结（${frames.size} 帧）…")
                        val mp4 = VideoAssembler.build(frames.map { it.jpeg })
                        if (mp4 != null) {
                            StepVlClient().summarizeVideo(
                                baseUrl = StepVlConfig.baseUrl(context),
                                apiKey = StepVlConfig.apiKey(context),
                                model = StepVlConfig.model(context),
                                mp4 = mp4,
                                prompt = fullPrompt,
                            )
                        } else {
                            // 合成失败回退拼图，保证总结不中断
                            Log.w(TAG, "video build failed, fallback to sheet")
                            StepVlClient().summarize(
                                baseUrl = StepVlConfig.baseUrl(context),
                                apiKey = StepVlConfig.apiKey(context),
                                model = StepVlConfig.model(context),
                                jpeg = sheet ?: error("sheet unavailable"),
                                prompt = fullPrompt,
                            )
                        }
                    }
                    else -> {
                        // 拼图（默认）
                        post("正在生成拼图并总结（${frames.size} 帧）…")
                        StepVlClient().summarize(
                            baseUrl = StepVlConfig.baseUrl(context),
                            apiKey = StepVlConfig.apiKey(context),
                            model = StepVlConfig.model(context),
                            jpeg = sheet ?: error("sheet unavailable"),
                            prompt = fullPrompt,
                        )
                    }
                }
            } catch (e: CensoredException) {
                // 内容审核拦截：本段跳过，不污染历史、不阻塞后续批次
                Log.w(TAG, "censored, skip this batch")
                "⏭ 内容审核未通过，本段跳过"
            } catch (e: Exception) {
                Log.e(TAG, "summarize failed", e)
                "总结失败：${e.message}"
            }
            val ok = !text.startsWith("总结失败")

            val ts = System.currentTimeMillis()
            // locDigest 已在上面 prompt 构建时取过，这里直接复用
            val json = JSONObject().apply {
                put("ts", ts)
                put("frames", frames.size)
                put("sheet", sheetName)
                put("ok", ok)
                put("text", text)
                if (locDigest.isNotBlank()) put("loc", locDigest)
            }.toString()
            MediaStoreHelper.saveText(
                context, MediaStoreHelper.SUMMARIES_DIR, "summary_$ts.json", json
            )
            val skipped = text.startsWith("⏭")
            if (ok && !skipped) synchronized(historyTexts) { historyTexts.add(text) }
            Log.i(TAG, "summary($source) ok=$ok frames=${frames.size} history=${historyTexts.size}")
            onSummary?.invoke(SummaryRec(ts, text, sheetName, ok))
            post(when {
                skipped -> "本段内容审核未通过，已跳过"
                ok -> "总结完成（${frames.size} 帧）"
                else -> "总结失败，详见列表"
            })
        } finally {
            busy = false
        }
    }

    /** 等距抽 N 帧（防多图超限） */
    private fun pickEvenly(frames: List<FrameSnap>, n: Int): List<FrameSnap> {
        if (frames.size <= n) return frames
        val step = frames.size.toDouble() / n
        return (0 until n).map { frames[(it * step).toInt().coerceAtMost(frames.size - 1)] }
    }

    private fun relLabel(deltaMs: Long): String {
        val s = (deltaMs / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private suspend fun post(msg: String) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { onStatus?.invoke(msg) }
    }
}
