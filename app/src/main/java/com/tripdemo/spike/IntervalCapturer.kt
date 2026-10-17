package com.tripdemo.spike

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * R1 间隔捕捉 + 实时问候采样：
 *  - ImageAnalysis (STRATEGY_KEEP_ONLY_LATEST) 持续收帧；
 *  - 墙钟调度：每 [intervalMs] 抓一帧（burstCount=1），YUV→NV12→JPEG，经 onFrameSaved 交外部落盘；
 *  - 固定每 6 秒抽一帧给实时问候（与录制状态无关，空闲也主动搭话）；
 *  - 所有帧按 rotationDegrees 旋转转正后再编码（竖屏持机时传感器出横帧）。
 */
class IntervalCapturer(
    private val burstCount: Int = 1,
    private val onFrameSaved: (fileName: String, tsWall: Long, jpeg: ByteArray) -> Unit,
    private val onGreetFrame: (ByteArray) -> Unit,
    private val onPreviewFrame: ((ByteArray) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private var intervalMs = 3_000L

    @Volatile
    var recording = false

    private var nextBurstAt = 0L
    private var nextGreetAt = 0L
    private var nextPreviewAt = 0L
    private var framesToGrab = 0
    private var savedInBurst = 0
    private var tripRel = ""

    /** 开始一段行程；intervalMs 取设置页的取帧间隔 */
    fun startTrip(tripId: String, intervalMs: Long) {
        this.intervalMs = intervalMs
        tripRel = "trip_$tripId/frames"
        nextBurstAt = 0L
        framesToGrab = 0
        savedInBurst = 0
        recording = true
    }

    fun stopTrip() {
        recording = false
    }

    override fun analyze(image: ImageProxy) {
        try {
            val rotation = image.imageInfo.rotationDegrees
            lastRotationDegrees = rotation

            // 实时问候采样：固定每 6 秒一帧，与录制状态无关（空闲时也主动搭话）
            val nowG = SystemClock.elapsedRealtime()
            if (nextGreetAt == 0L) nextGreetAt = nowG + GREET_INTERVAL_MS
            if (nowG >= nextGreetAt) {
                nextGreetAt += GREET_INTERVAL_MS
                if (nextGreetAt < nowG) nextGreetAt = nowG + GREET_INTERVAL_MS
                val (gnv, gw, gh) = rotateNv21(yuv420ToNv21(image), image.width, image.height, rotation)
                onGreetFrame(nv21ToJpeg(gnv, gw, gh, 72))
            }

            // 预览帧兜底推送（约 8fps；Preview 流正常时以它为主，这是备胎）
            onPreviewFrame?.let { push ->
                val nowP = SystemClock.elapsedRealtime()
                if (nextPreviewAt == 0L) nextPreviewAt = nowP + PREVIEW_INTERVAL_MS
                if (nowP >= nextPreviewAt) {
                    nextPreviewAt += PREVIEW_INTERVAL_MS
                    if (nextPreviewAt < nowP) nextPreviewAt = nowP + PREVIEW_INTERVAL_MS
                    val (rnv, rw, rh) = rotateNv21(yuv420ToNv21(image), image.width, image.height, rotation)
                    // 预览再降一半（720x1280 → 360x640）：编码开销降 4 倍，专治掉帧
                    val (pnv, pw, ph) = downsampleNv21(rnv, rw, rh, 2)
                    push(nv21ToJpeg(pnv, pw, ph, 70))
                }
            }

            if (!recording) return

            val now = SystemClock.elapsedRealtime()
            if (nextBurstAt == 0L) nextBurstAt = now + intervalMs
            if (now >= nextBurstAt) {
                framesToGrab = burstCount
                savedInBurst = 0
                // 防漂移：按目标时刻推进，而不是帧计数
                nextBurstAt += intervalMs
                if (nextBurstAt < now) nextBurstAt = now + intervalMs
            }
            if (framesToGrab <= 0) return

            val tsWall = System.currentTimeMillis()
            val fileName = "${tsWall}_$savedInBurst.jpg"
            val (nv, w, h) = rotateNv21(yuv420ToNv21(image), image.width, image.height, rotation)
            val jpeg = nv21ToJpeg(nv, w, h, 85)
            savedInBurst++
            framesToGrab--
            Log.i(TAG, "saved $fileName ${jpeg.size}B ${w}x$h rot=$rotation")
            onFrameSaved(fileName, tsWall, jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "TripSpike"

        /** 最近一帧的旋转角（供预览流复用：同一相机，旋转一致） */
        @Volatile
        var lastRotationDegrees = 0
        const val GREET_INTERVAL_MS = 6_000L    // 实时问候固定 6 秒一帧
        const val PREVIEW_INTERVAL_MS = 125L    // 取景预览约 8fps（兼顾流畅与负载）

        /** 旋转 NV21（90/180/270）。90/270 时宽高对调。返回 (buffer, w, h) */
        fun rotateNv21(src: ByteArray, w: Int, h: Int, degrees: Int): Triple<ByteArray, Int, Int> {
            if (degrees % 360 == 0) return Triple(src, w, h)
            val nw: Int
            val nh: Int
            when (degrees) {
                90, 270 -> {
                    nw = h; nh = w
                }
                180 -> {
                    nw = w; nh = h
                }
                else -> return Triple(src, w, h)
            }
            val out = ByteArray(src.size)
            val frameSize = w * h
            // Y 平面
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx: Int
                    val ny: Int
                    when (degrees) {
                        90 -> { nx = h - 1 - y; ny = x }
                        180 -> { nx = w - 1 - x; ny = h - 1 - y }
                        else -> { nx = y; ny = w - 1 - x }
                    }
                    out[ny * nw + nx] = src[y * w + x]
                }
            }
            // UV 交织平面（按 2x2 块整体搬移，保持 U/V 成对）
            val cw = w / 2
            val ch = h / 2
            val ncw = nw / 2
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val nx: Int
                    val ny: Int
                    when (degrees) {
                        90 -> { nx = ch - 1 - cy; ny = cx }
                        180 -> { nx = cw - 1 - cx; ny = ch - 1 - cy }
                        else -> { nx = cy; ny = cw - 1 - cx }
                    }
                    val si = frameSize + (cy * cw + cx) * 2
                    val di = nw * nh + (ny * ncw + nx) * 2
                    out[di] = src[si]
                    out[di + 1] = src[si + 1]
                }
            }
            return Triple(out, nw, nh)
        }

        /** ImageProxy(YUV_420_888) → NV21（按 rowStride/pixelStride 逐平面拷贝） */
        fun yuv420ToNv21(image: ImageProxy): ByteArray {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val out = ByteArray(width * height * 3 / 2)
            var pos = 0
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            for (row in 0 until height) {
                var col = 0
                while (col < width) {
                    out[pos++] = yBuffer.get(row * yRowStride + col * yPixStride)
                    col++
                }
            }
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val uRowStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    // NV21: V 在前 U 在后（YuvImage 要求）
                    out[pos++] = vBuffer.get(row * vRowStride + col * vPixStride)
                    out[pos++] = uBuffer.get(row * uRowStride + col * uPixStride)
                }
            }
            return out
        }

        /** android.media.Image(YUV_420_888) → NV21（ImageReader 用） */
        fun yuv420ToNv21(image: android.media.Image): ByteArray {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val out = ByteArray(width * height * 3 / 2)
            var pos = 0
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            for (row in 0 until height) {
                var col = 0
                while (col < width) {
                    out[pos++] = yBuffer.get(row * yRowStride + col * yPixStride)
                    col++
                }
            }
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val uRowStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    out[pos++] = uBuffer.get(row * uRowStride + col * uPixStride)
                    out[pos++] = vBuffer.get(row * vRowStride + col * vPixStride)
                }
            }
            return out
        }

        /** NV21 整数倍降采样（预览用，降 CPU） */
        fun downsampleNv21(src: ByteArray, w: Int, h: Int, step: Int): Triple<ByteArray, Int, Int> {
            if (step <= 1) return Triple(src, w, h)
            val nw = w / step
            val nh = h / step
            val out = ByteArray(nw * nh * 3 / 2)
            var pos = 0
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    out[pos++] = src[(y * step) * w + (x * step)]
                }
            }
            val cw = w / 2
            val ncw = nw / 2
            val nch = nh / 2
            val frameSize = w * h
            for (cy in 0 until nch) {
                for (cx in 0 until ncw) {
                    val si = frameSize + ((cy * step) * cw + (cx * step)) * 2
                    out[pos++] = src[si]
                    out[pos++] = src[si + 1]
                }
            }
            return Triple(out, nw, nh)
        }

        /** NV12 → JPEG */
        fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, baos)
            return baos.toByteArray()
        }
    }
}
