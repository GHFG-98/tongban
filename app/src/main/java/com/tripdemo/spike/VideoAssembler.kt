package com.tripdemo.spike

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频合成：把若干张拼图（或帧）按顺序合成一个小 MP4（H.264，默认 2fps）。
 * 用于「视频理解」——比一次传多张拼图省 8 倍 token，且时序理解更好
 * （实测：187KB/18段 MP4 ≈ 1766 prompt tokens；同内容多图 ≈ 15K+）。
 *
 * 实现：MediaCodec(AVC) 编码 + MediaMuxer 封装，Bitmap 直接转 NV12 喂编码器。
 */
object VideoAssembler {

    private const val TAG = "VideoAssembler"
    private const val DEFAULT_FPS = 2
    private const val DEFAULT_FRAMES_PER_IMAGE = 2   // 每张图停留 1 秒
    private const val OUT_WIDTH = 480

    /**
     * @param jpegs 按时间顺序的图片（JPEG 字节）
     * @return MP4 字节；失败返回 null（调用方回退到多图模式）
     */
    fun build(jpegs: List<ByteArray>, fps: Int = DEFAULT_FPS, framesPerImage: Int = DEFAULT_FRAMES_PER_IMAGE): ByteArray? {
        if (jpegs.isEmpty()) return null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var outFile: File? = null
        try {
            val first = BitmapFactory.decodeByteArray(jpegs[0], 0, jpegs[0].size) ?: return null
            val height = (OUT_WIDTH.toFloat() * first.height / first.width).toInt().let {
                (it / 16) * 16   // 编码器要求 16 对齐
            }.coerceAtLeast(16)
            first.recycle()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OUT_WIDTH, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            outFile = File.createTempFile("tripvid", ".mp4", cacheDirContext!!.cacheDir)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var frameIndex = 0
            val totalFrames = jpegs.size * framesPerImage
            var inputDone = false
            var muxerDone = false

            fun drain(endOfStream: Boolean) {
                // ByteBuffer 输入模式的 EOS：队尾一个空 buffer 打 END_OF_STREAM 标记
                // （signalEndOfInputStream 仅适用于 Surface 输入模式）
                if (endOfStream) {
                    val eosIndex = encoder.dequeueInputBuffer(10_000)
                    if (eosIndex >= 0) {
                        encoder.queueInputBuffer(
                            eosIndex, 0, 0,
                            (totalFrames.coerceAtLeast(1)) * 1_000_000L / fps,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (endOfStream) continue else break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    } else if (outIndex >= 0) {
                        val encoded = encoder.getOutputBuffer(outIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && trackIndex >= 0) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            muxerDone = true
                            break
                        }
                    }
                }
            }

            for (jpeg in jpegs) {
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                val scaled = Bitmap.createScaledBitmap(bmp, OUT_WIDTH, height, true)
                if (scaled != bmp) bmp.recycle()
                val nv12 = bitmapToNv12(scaled, OUT_WIDTH, height)
                scaled.recycle()
                repeat(framesPerImage) {
                    val pts = frameIndex * 1_000_000L / fps
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = encoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        inBuf.put(nv12)
                        encoder.queueInputBuffer(inIndex, 0, nv12.size, pts, 0)
                    }
                    frameIndex++
                    drain(false)
                }
            }
            drain(true)
            if (!muxerDone) drain(false)

            muxer.stop()
            val bytes = outFile.readBytes()
            Log.i(TAG, "built ${bytes.size / 1024}KB mp4, $totalFrames frames, ${OUT_WIDTH}x$height")
            return bytes
        } catch (e: Exception) {
            Log.e(TAG, "build failed", e)
            return null
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            outFile?.delete()
        }
    }

    /** Bitmap(ARGB) → NV12（Y 平面 + VU 交织），BT.601 */
    private fun bitmapToNv12(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        var uvIndex = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = pixels[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                out[yIndex++] = y.toByte()
                // 每 2x2 块采一个 UV
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt().coerceIn(0, 255)
                    val v = (0.5 * r - 0.419 * g - 0.081 * b + 128).toInt().coerceIn(0, 255)
                    // SemiPlanar(NV12) 是 U 在前 V 在后
                    out[uvIndex++] = u.toByte()
                    out[uvIndex++] = v.toByte()
                }
            }
        }
        return out
    }

    /** 由调用方在初始化时注入（VideoAssembler 是 object，拿不到 Context） */
    @Volatile
    var cacheDirContext: android.content.Context? = null
}
