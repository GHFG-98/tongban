package com.tripdemo.spike

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 收音：16kHz 单声道 PCM_S16LE，边采边写文件。
 * 与 CaptureService 同生命周期；停止时由 Service 调 stop() 拿文件送 ASR。
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var outFile: File? = null

    /** 开始录音，PCM 写入 [outFile]；返回是否成功启动 */
    fun start(out: File): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufSize = minBuf * 4
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "no RECORD_AUDIO permission", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        outFile = out
        out.parentFile?.mkdirs()
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(minBuf * 2)
            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(out)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) fos.write(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "record loop failed", e)
            } finally {
                runCatching { fos?.flush(); fos?.close() }
            }
        }, "audio-record").apply { start() }
        Log.i(TAG, "started -> ${out.absolutePath}")
        return true
    }

    /** 停止并返回 PCM 文件（无录音返回 null） */
    /** 停止并返回 PCM 文件（无录音返回 null） */
    fun stop(): File? {
        if (!running) return null
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        thread?.join(1000)
        thread = null
        Log.i(TAG, "stopped")
        return outFile
    }

    /** 当前已写入的字节数（按批次切分音频时标记边界用） */
    fun writtenBytes(): Long = try {
        outFile?.length() ?: 0L
    } catch (_: Exception) {
        0L
    }

    /**
     * 读取已写入音频的 [from, to) 字节段（识别某一个批次用）。
     * 不影响正在进行的写入；越界时自动裁剪。
     */
    fun readRange(from: Long, to: Long): ByteArray? {
        val f = outFile ?: return null
        if (to <= from) return null
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = from.coerceIn(0L, len)
                val end = to.coerceIn(start, len)
                val n = (end - start).toInt()
                if (n <= 0) return null
                val buf = ByteArray(n)
                raf.seek(start)
                raf.readFully(buf)
                buf
            }
        } catch (e: Exception) {
            Log.e(TAG, "readRange failed", e)
            null
        }
    }
}
