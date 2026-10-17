package com.tripdemo.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 常驻采集服务（前台服务，相机类型）。
 * 相机绑定在本服务持有的 LifecycleOwner 上（常驻 RESUMED），
 * 因此切到总结页、设置页、小窗、甚至退到桌面，采集与问候都不中断。
 *
 * 界面（MainActivity）只做 UI：轮询 latestPreviewJpeg 当取景器、发录制指令、展示状态。
 */
class CaptureService : Service(), LifecycleOwner {

    data class State(
        val recording: Boolean,
        val seconds: Long,
        val frames: Int,
        val bytes: Long,
        val config: String,
        val saveRoot: String,
        val cameraAlive: Boolean,
    )

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val executor = Executors.newSingleThreadExecutor()
    private var capturer: IntervalCapturer? = null
    private lateinit var locationRecorder: LocationRecorder
    private var tripMeta: TripMetaWriter? = null
    private var audioRecorder: AudioRecorder? = null
    private var pcmFile: File? = null
    /** 当前音频批次的起始字节偏移 + 起始墙钟（按总结节奏切段送 ASR） */
    private var audioChunkStart = 0L
    private var audioChunkStartWall = 0L
    private var tripStartWall = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private var recording = false
    private var frameCount = 0
    private var totalBytes = 0L
    private var recordStartedAt = 0L
    private var currentTripId = ""
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: androidx.camera.core.Preview? = null
    private var useMediaStore = true
    private var saveRootLabel = ""

    companion object {
        const val ACTION_START_RECORD = "com.tripdemo.spike.START_RECORD"
        const val ACTION_STOP_RECORD = "com.tripdemo.spike.STOP_RECORD"

        /** 预览表面（Activity 的 TextureView 提供）；null 时缓存 SurfaceRequest */
        @Volatile
        var previewSurface: android.view.Surface? = null

        private var pendingRequest: androidx.camera.core.SurfaceRequest? = null

        /** 一段语音识别完成（小计页刷新「行程语音」卡用；可能在子线程调用，监听方自行切主线程） */
        @Volatile
        var onAsrSegment: (() -> Unit)? = null

        /** Activity 绑定/解绑预览表面（表面没了只解绑预览流，采集照常） */
        fun attachPreviewSurface(surface: android.view.Surface?) {
            // 表面必须无条件记录：服务可能还没起来（Activity 先于 Service 创建），
            // 之后 Service 绑定相机时 SurfaceProvider 会读到这个表面
            previewSurface = surface
            val svc = instance ?: return
            val req = pendingRequest
            if (surface != null) {
                if (req != null) {
                    req.provideSurface(surface, svc.executor) { }
                    pendingRequest = null
                } else if (svc.previewUseCase != null) {
                    // 表面晚到（旧请求已消费）：重绑预览流拿新请求
                    svc.rebindPreview()
                }
                // previewUseCase == null：首次绑定尚未发生，等 bindCamera 的 SurfaceProvider
            } else {
                pendingRequest = null
                runCatching { svc.cameraProvider?.unbind(svc.previewUseCase) }
                svc.previewUseCase = null
            }
        }

        @Volatile
        private var instance: CaptureService? = null

        @Volatile
        var state: State? = null
        val listeners = CopyOnWriteArrayList<(State) -> Unit>()

        var running = false
            private set

        fun notify(s: State) {
            state = s
            listeners.forEach { runOnMain { it(s) } }
        }

        private fun runOnMain(block: () -> Unit) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        locationRecorder = LocationRecorder(this)
        useMediaStore = probeMediaStore()
        saveRootLabel = if (useMediaStore) "/sdcard/Download/trip_demo/" else
            File(getExternalFilesDir(null), "trip_demo").apply { mkdirs() }.absolutePath + "/"

        startForegroundCompat(recording = false)
        bindCamera()
        publishState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORD -> startRecording()
            ACTION_STOP_RECORD -> stopRecording()
        }
        return START_STICKY
    }

    // ---------- 相机 ----------

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    // 目标 720x1280：HAL 实给 1280x720 横帧，经 rotationDegrees 旋转后
                    // 成为 720x1280 高分辨率竖帧——预览（备胎推送）与存帧都更清晰，
                    // 竖幅与屏幕同向，取景不再被大幅裁切（修“倍数不对”）
                    .setTargetResolution(android.util.Size(720, 1280))
                    .build()
                    .also { it.setAnalyzer(executor, buildCapturer()) }

                // 预览流：表面由 Activity 的 TextureView 提供（GPU 直出，流畅；
                // 与 always-on 之前的 PreviewView 同款路径）。表面未就绪时缓存请求。
                // 不指定目标分辨率：竖屏该 HAL 不支持，让 CameraX 选最优预览尺寸
                val preview = androidx.camera.core.Preview.Builder().build()
                preview.setSurfaceProvider { request ->
                    val s = previewSurface
                    if (s != null) {
                        request.provideSurface(s, executor) { }
                    } else {
                        pendingRequest = request
                    }
                }

                cameraProvider = provider
                previewUseCase = preview
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                publishState()
            } catch (e: Exception) {
                android.util.Log.e("CaptureService", "bindCamera failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildCapturer(): IntervalCapturer {
        val c = IntervalCapturer(
            burstCount = 1,
            onFrameSaved = { fileName, tsWall, jpeg ->
                val loc = locationRecorder.lastLocation
                val bytes = if (loc != null) writeExifGps(jpeg, loc, tsWall) else jpeg
                saveFrame(currentTripId, fileName, bytes)
                tripMeta?.add(loc, fileName, tsWall)
                flushTripMeta()
                frameCount++
                totalBytes += bytes.size
                SummaryEngine.get(applicationContext).onFrame(tsWall, currentTripId, jpeg)
                publishState()
            },
            onGreetFrame = { jpeg -> GreetingEngine.get(applicationContext).onFrame(jpeg) },
            onPreviewFrame = null,
        )
        capturer = c
        return c
    }

    /** 重绑预览流（表面重新到达时用），分析流不动 */
    private fun rebindPreview() {
        val provider = cameraProvider ?: return
        val surface = previewSurface ?: return
        val preview = androidx.camera.core.Preview.Builder().build()
        preview.setSurfaceProvider { request ->
            request.provideSurface(surface, executor) { }
        }
        runCatching {
            provider.unbind(previewUseCase)
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            previewUseCase = preview
        }
    }

    // ---------- 录制 ----------

    private fun startRecording() {
        if (recording) return
        recording = true
        frameCount = 0
        totalBytes = 0
        recordStartedAt = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        currentTripId = "spike_$now"
        tripStartWall = now
        tripMeta = TripMetaWriter(currentTripId, now)
        locationRecorder.start()
        capturer?.startTrip(currentTripId, CaptureSettings.intervalMs(this))
        SummaryEngine.get(applicationContext).let {
            it.manifestProvider = { tripMeta?.manifestText() ?: "（无帧清单）" }
            it.locationDigestProvider = { tripMeta?.locationDigest() ?: "" }
            it.asrTextProvider = { readAsrText(currentTripId) }
            // 每满 N 帧：先识别本批语音，再生成小计——小计一定带本批对话内容
            it.onBatchAsr = { cutAudioAndTranscribe() }
            it.onTripStarted(currentTripId, now)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TripSpike::record").apply {
            acquire(60 * 60 * 1000L)
        }

        // 收音（开关在设置页/主界面）：整趟写一个 PCM，按批次用字节偏移切段送 ASR
        if (CaptureSettings.audioEnabled(this)) {
            pcmFile = File(File(getExternalFilesDir(null), "trip_demo"), "trip_$currentTripId/audio/rec.pcm")
            val ok = AudioRecorder().also { audioRecorder = it }.start(pcmFile!!)
            if (ok) {
                audioChunkStart = 0L
                audioChunkStartWall = System.currentTimeMillis()
            } else {
                audioRecorder = null
                pcmFile = null
                android.util.Log.w("CaptureService", "audio record start failed")
            }
        }

        startForegroundCompat(recording = true)
        publishState()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        capturer?.stopTrip()
        locationRecorder.stop()
        flushTripMeta()

        // 收音：把最后不满一个批次的尾段也送 ASR（批次中的已在录制中实时送过）
        cutAudioChunk()
        audioRecorder?.stop()
        audioRecorder = null
        pcmFile = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        startForegroundCompat(recording = false)
        publishState()
    }

    /** 读本 trip 的 asr.json，格式化为「mm:ss '文字'」行（无则空串） */
    private fun readAsrText(tripId: String): String {
        return try {
            val f = File(File(getExternalFilesDir(null), "trip_demo"), "trip_$tripId/asr.json")
            if (!f.exists()) return ""
            val j = org.json.JSONObject(f.readText())
            val segs = j.optJSONArray("segs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val t = s.optLong("t")
                val txt = s.optString("text")
                sb.append(String.format("%02d:%02d", t / 60, t % 60))
                sb.append(" 「")
                sb.append(txt)
                sb.append("」")
                sb.append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 音频批次切分：把 [audioChunkStart, 当前写入位置) 这一段送 ASR，
     * 然后把边界推到当前位置。与照片总结同节奏（每满 N 帧一次）。
     * 在采集线程调用，只做轻量的字节读取，识别在子线程跑。
     */
    private fun cutAudioChunk() {
        val recorder = audioRecorder ?: return
        val tripId = currentTripId
        if (tripId.isEmpty()) return
        val end = recorder.writtenBytes()
        if (end <= audioChunkStart) return
        val bytes = recorder.readRange(audioChunkStart, end) ?: return
        val chunkStartWall = audioChunkStartWall
        audioChunkStart = end
        audioChunkStartWall = System.currentTimeMillis()
        transcribeAsync(bytes, chunkStartWall, tripStartWall, tripId)
    }

    /**
     * 挂起版：切一段音频并**当场识别完**（供总结等待，保证本批小计带本批语音）。
     * 在调用方协程（IO）里执行，不另起线程。
     */
    private suspend fun cutAudioAndTranscribe() {
        val recorder = audioRecorder ?: return
        val tripId = currentTripId
        if (tripId.isEmpty()) return
        val end = recorder.writtenBytes()
        if (end <= audioChunkStart) return
        val bytes = recorder.readRange(audioChunkStart, end) ?: return
        val chunkStartWall = audioChunkStartWall
        audioChunkStart = end
        audioChunkStartWall = System.currentTimeMillis()
        try {
            val result = withContext(Dispatchers.IO) {
                AsrClient().transcribe(
                    baseUrl = StepVlConfig.ASR_BASE_URL,
                    apiKey = StepVlConfig.apiKey(this@CaptureService),
                    pcm = bytes,
                )
            }
            appendAsrSegs(tripId, chunkStartWall, tripStartWall, result.segs, result.text)
            android.util.Log.i("CaptureService", "ASR chunk done: ${result.text.take(80)}")
        } catch (e: Exception) {
            android.util.Log.e("CaptureService", "ASR chunk failed", e)
            // 失败只累加计数、不清空已有语音：小计页能看到"有 N 段识别失败"
            runCatching { bumpAsrFailed(tripId) }
        }
    }

    /**
     * 异步识别一段音频，并把结果**追加**进 trip 的 asr.json（含行程相对时间）。
     * 用于停止录制时的尾段（不阻塞停止流程）；批次中的语音走挂起版。
     */
    private fun transcribeAsync(pcmBytes: ByteArray, audioStartWall: Long, tripStartWall: Long, tripId: String) {
        Thread {
            try {
                val result = AsrClient().transcribe(
                    baseUrl = StepVlConfig.ASR_BASE_URL,
                    apiKey = StepVlConfig.apiKey(this),
                    pcm = pcmBytes,
                )
                appendAsrSegs(tripId, audioStartWall, tripStartWall, result.segs, result.text)
                android.util.Log.i("CaptureService", "ASR chunk done: ${result.text.take(80)}")
            } catch (e: Exception) {
                android.util.Log.e("CaptureService", "ASR chunk failed", e)
                // 失败只累加计数、不清空已有语音：小计页能看到"有 N 段识别失败"
                runCatching { bumpAsrFailed(tripId) }
            }
        }.start()
    }

    /** asr.json 的读写锁（多个批次的识别子线程可能并发回来） */
    private val asrLock = Object()

    /** 把一段识别的 segs 合并（相邻<1.5s）后追加进 asr.json，并通知小计页刷新 */
    private fun appendAsrSegs(
        tripId: String,
        audioStartWall: Long,
        tripStartWall: Long,
        rawSegs: List<AsrSeg>,
        rawText: String,
    ) {
        // 音频内毫秒 → 行程相对秒；相邻 delta（间隔<1.5s）合并成句，避免碎片化
        val merged = ArrayList<Pair<Long, String>>()
        var lastStartMs = Long.MIN_VALUE
        for (s in rawSegs) {
            val relSec = ((audioStartWall + s.startMs) - tripStartWall) / 1000
            if (merged.isNotEmpty() && s.startMs - lastStartMs < 1500) {
                merged[merged.lastIndex] = merged.last().first to (merged.last().second + s.text)
            } else {
                merged.add(relSec.coerceAtLeast(0) to s.text)
            }
            lastStartMs = s.startMs
        }
        if (merged.isEmpty()) return
        synchronized(asrLock) {
            val dir = File(File(getExternalFilesDir(null), "trip_demo"), "trip_$tripId").apply { mkdirs() }
            val f = File(dir, "asr.json")
            val old = if (f.exists()) runCatching { org.json.JSONObject(f.readText()) }.getOrNull() else null
            val arr = old?.optJSONArray("segs") ?: org.json.JSONArray()
            val sb = StringBuilder(old?.optString("text").orEmpty())
            merged.forEach { (t, txt) ->
                arr.put(org.json.JSONObject().apply {
                    put("t", t)
                    put("text", txt)
                })
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(txt)
            }
            f.writeText(
                org.json.JSONObject().apply {
                    put("tripId", tripId)
                    put("text", sb.toString())
                    put("segs", arr)
                    put("failed", old?.optInt("failed") ?: 0)
                }.toString()
            )
        }
        onAsrSegment?.invoke()
    }

    /** 识别失败：failed 计数 +1（保留已有语音） */
    private fun bumpAsrFailed(tripId: String) {
        synchronized(asrLock) {
            val dir = File(File(getExternalFilesDir(null), "trip_demo"), "trip_$tripId").apply { mkdirs() }
            val f = File(dir, "asr.json")
            val old = if (f.exists()) runCatching { org.json.JSONObject(f.readText()) }.getOrNull() else null
            f.writeText(
                org.json.JSONObject().apply {
                    put("tripId", tripId)
                    put("text", old?.optString("text").orEmpty())
                    put("segs", old?.optJSONArray("segs") ?: org.json.JSONArray())
                    put("failed", (old?.optInt("failed") ?: 0) + 1)
                }.toString()
            )
        }
        onAsrSegment?.invoke()
    }

    // ---------- 存储 ----------

    private fun saveFrame(tripId: String, fileName: String, jpeg: ByteArray) {
        try {
            if (useMediaStore) saveViaMediaStore("trip_$tripId/frames", fileName, jpeg)
            else saveToPrivateDir("trip_$tripId/frames", fileName, jpeg)
        } catch (e: Exception) {
            try {
                saveToPrivateDir("trip_$tripId/frames", fileName, jpeg)
            } catch (_: Exception) {
            }
        }
    }

    private fun flushTripMeta() {
        val meta = tripMeta ?: return
        try {
            if (useMediaStore) {
                MediaStoreHelper.saveText(
                    this, "${MediaStoreHelper.ROOT}/trip_${meta.tripId}", "meta.json", meta.toJson()
                )
            } else {
                val dir = File(File(getExternalFilesDir(null), "trip_demo"), "trip_${meta.tripId}")
                    .apply { mkdirs() }
                File(dir, "meta.json").writeText(meta.toJson())
            }
        } catch (_: Exception) {
        }
    }

    private fun probeMediaStore(): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, ".probe")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/trip_demo")
        }
        val uri = contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        )
        if (uri != null) {
            contentResolver.delete(uri, null, null)
            true
        } else false
    } catch (e: Exception) {
        false
    }

    private fun saveViaMediaStore(tripRel: String, name: String, jpeg: ByteArray) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/trip_demo/$tripRel")
        }
        val uri = contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw java.io.IOException("insert failed")
        contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
            ?: throw java.io.IOException("openOutputStream failed")
    }

    private fun saveToPrivateDir(tripRel: String, name: String, jpeg: ByteArray) {
        val dir = File(File(getExternalFilesDir(null), "trip_demo"), tripRel).apply { mkdirs() }
        File(dir, name).writeBytes(jpeg)
    }

    private fun writeExifGps(jpeg: ByteArray, loc: Location, tsWall: Long): ByteArray {
        return try {
            val f = File(cacheDir, "exif_tmp.jpg")
            f.writeBytes(jpeg)
            val exif = androidx.exifinterface.media.ExifInterface(f)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(tsWall))
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE, toDms(loc.latitude)
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF,
                if (loc.latitude >= 0) "N" else "S"
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE, toDms(loc.longitude)
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
                if (loc.longitude >= 0) "E" else "W"
            )
            if (loc.hasAltitude()) {
                val num = (Math.abs(loc.altitude) * 100).toLong()
                exif.setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE, "${num}/100"
                )
                exif.setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
                    if (loc.altitude < 0) "1" else "0"
                )
            }
            exif.saveAttributes()
            f.readBytes()
        } catch (e: Exception) {
            jpeg
        }
    }

    private fun toDms(value: Double): String {
        val v = Math.abs(value)
        val d = v.toInt()
        val mFull = (v - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        val sNum = (s * 1000).toLong()
        return "$d/1,$m/1,$sNum/1000"
    }

    // ---------- 状态与通知 ----------

    private fun publishState() {
        val secs = if (recording) (SystemClock.elapsedRealtime() - recordStartedAt) / 1000 else 0
        val interval = CaptureSettings.intervalLabel(CaptureSettings.intervalMs(this))
        val n = CaptureSettings.framesPerSummary(this)
        notify(
            State(
                recording = recording,
                seconds = secs,
                frames = frameCount,
                bytes = totalBytes,
                config = "间隔 $interval · 每 $n 帧总结一次",
                saveRoot = saveRootLabel,
                cameraAlive = true,
            )
        )
    }

    private fun startForegroundCompat(recording: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("capture") == null) {
            nm.createNotificationChannel(
                NotificationChannel("capture", "行程采集", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP_RECORD)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, "capture")
            .setContentTitle(if (recording) "行程记录中" else "行程小记取景中")
            .setContentText(if (recording) "采集与问候持续进行" else "每 3 秒主动问候")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .apply { if (recording) addAction(android.R.drawable.ic_media_pause, "停止", stopPi) }
            .build()
        ServiceCompat.startForeground(
            this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉任务：不在录制就收尾停止，别占着相机
        if (!recording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        instance = null
        capturer?.stopTrip()
        locationRecorder.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        executor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
