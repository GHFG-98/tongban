package com.tripdemo.spike

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** 一帧快照（内存中的 JPEG 字节 + 时间戳） */
data class FrameSnap(val ts: Long, val tripId: String, val jpeg: ByteArray)

/**
 * 拼图生成：把若干帧按时间顺序排成网格，每格左上角画行程相对时间。
 * 列数按帧数动态选择——尽量填满、不留下大片空余（如 9 帧=3×3、15 帧=5×3）。
 * 纯 Canvas 绘制，无第三方依赖。
 */
object ContactSheetBuilder {

    private val BG = Color.rgb(24, 32, 44)
    private const val GAP = 6
    private const val PAD = 6
    private const val TOTAL_W = 1140   // 拼图总宽（像素）

    fun build(frames: List<FrameSnap>, tripStartAt: Long): ByteArray {
        require(frames.isNotEmpty())
        val portrait = frames.first().jpeg.let { bytes ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            o.outHeight > o.outWidth
        }

        val n = frames.size
        val cols = bestColumns(n)
        val rows = ceil(n.toDouble() / cols).toInt()

        val cellW = (TOTAL_W - PAD * 2 - GAP * (cols - 1)) / cols
        val cellH = if (portrait) (cellW * 4 / 3) else (cellW * 3 / 4)

        val w = PAD * 2 + cols * cellW + (cols - 1) * GAP
        val h = PAD * 2 + rows * cellH + (rows - 1) * GAP
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(BG)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (cellH * 0.085f).coerceAtLeast(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 170, 200)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        frames.forEachIndexed { i, snap ->
            val r = i / cols
            val c = i % cols
            val left = PAD + c * (cellW + GAP)
            val top = PAD + r * (cellH + GAP)
            val bmp = decodeScaled(snap.jpeg, cellW, cellH)
            if (bmp != null) {
                val srcRect = centerCropRect(bmp.width, bmp.height, cellW, cellH)
                canvas.drawBitmap(bmp, srcRect, Rect(left, top, left + cellW, top + cellH), null)
                bmp.recycle()
            } else {
                canvas.drawRect(
                    left.toFloat(), top.toFloat(),
                    (left + cellW).toFloat(), (top + cellH).toFloat(),
                    Paint().apply { color = Color.rgb(50, 62, 76) }
                )
            }
            canvas.drawRect(
                left.toFloat(), top.toFloat(),
                (left + cellW).toFloat(), (top + cellH).toFloat(), framePaint
            )
            canvas.drawText(labelOf(snap.ts, tripStartAt), left + 10f, top + labelPaint.textSize + 10f, labelPaint)
        }

        val out = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 88, out)
        sheet.recycle()
        return out.toByteArray()
    }

    /**
     * 选最优列数：禁止单列（细长条没法看）；浪费空格越少越好；
     * 宽高比偏好 cols/rows ≈ 1.2（稍宽的长方形）。
     * 例：3→3×1 横条；6→3×2；9→3×3；12→4×3；15→5×3；20→5×4。
     */
    private fun bestColumns(n: Int): Int {
        if (n <= 2) return n
        if (n == 3) return 3          // 3 连帧：一行三联张
        var best = 3
        var bestScore = Float.MAX_VALUE
        for (cols in 2..minOf(n, 8)) {
            val rows = ceil(n.toDouble() / cols).toInt()
            val waste = cols * rows - n
            val score = waste + abs(cols.toFloat() / rows - 1.2f) * 2f
            if (score < bestScore) {
                bestScore = score
                best = cols
            }
        }
        return best
    }

    /** 计算居中裁剪的源矩形（保持宽高比填满目标） */
    private fun centerCropRect(bw: Int, bh: Int, dw: Int, dh: Int): Rect {
        val srcAspect = bw.toFloat() / bh
        val dstAspect = dw.toFloat() / dh
        return if (srcAspect > dstAspect) {
            val targetW = (bh * dstAspect).toInt()
            val x = (bw - targetW) / 2
            Rect(x, 0, x + targetW, bh)
        } else {
            val targetH = (bw / dstAspect).toInt()
            val y = (bh - targetH) / 2
            Rect(0, y, bw, y + targetH)
        }
    }

    /** 解码时按目标尺寸降采样，避免大图 OOM */
    private fun decodeScaled(jpeg: ByteArray, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > w * 2 || bounds.outHeight / sample > h * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private fun labelOf(ts: Long, tripStartAt: Long): String {
        val s = ((ts - tripStartAt) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}

