package com.tripdemo.spike

import org.json.JSONArray
import org.json.JSONObject

/** 一帧的元数据：文件名 + 三种时间 + 定位（无定位时位置字段为 null） */
data class FrameMeta(
    val file: String,
    val tsWall: Long,        // 设备墙钟（毫秒）
    val tsRelSec: Long,      // 相对行程开始（秒）
    val lat: Double?,
    val lng: Double?,
    val alt: Double?,
    val speed: Float?,       // m/s
    val bearing: Float?,     // 度
    val acc: Float?,         // 米
)

/**
 * 每个 trip 的元数据账本：随帧累积，写入 trip 目录下的 meta.json。
 * meta.json 同时是喂给大模型的「帧清单」数据源（时间 + 位置）。
 */
class TripMetaWriter(val tripId: String, private val tripStartWall: Long) {

    val frames = ArrayList<FrameMeta>()

    @Synchronized
    fun add(loc: android.location.Location?, file: String, tsWall: Long) {
        frames.add(
            FrameMeta(
                file = file,
                tsWall = tsWall,
                tsRelSec = (tsWall - tripStartWall) / 1000,
                lat = loc?.latitude,
                lng = loc?.longitude,
                alt = loc?.takeIf { it.hasAltitude() }?.altitude,
                speed = loc?.takeIf { it.hasSpeed() }?.speed,
                bearing = loc?.takeIf { it.hasBearing() }?.bearing,
                acc = loc?.takeIf { it.hasAccuracy() }?.accuracy,
            )
        )
    }

    @Synchronized
    fun toJson(): String {
        val arr = JSONArray()
        for (f in frames) {
            arr.put(JSONObject().apply {
                put("file", f.file)
                put("ts", f.tsWall)
                put("tsRelSec", f.tsRelSec)
                if (f.lat != null && f.lng != null) {
                    put("lat", f.lat)
                    put("lng", f.lng)
                }
                if (f.alt != null) put("alt", f.alt)
                if (f.speed != null) put("speed", f.speed)
                if (f.bearing != null) put("bearing", f.bearing)
                if (f.acc != null) put("acc", f.acc)
            })
        }
        return JSONObject().apply {
            put("tripId", tripId)
            put("tripStartTs", tripStartWall)
            put("frames", arr)
        }.toString()
    }

    /** 位置速览：本 trip 首个→最后一个有定位的帧（无定位返回空串） */
    @Synchronized
    fun locationDigest(): String {
        val withLoc = frames.filter { it.lat != null && it.lng != null }
        if (withLoc.isEmpty()) return ""
        val first = withLoc.first()
        val last = withLoc.last()
        val acc = withLoc.last().acc?.let { "，精度约${it.toInt()}米" } ?: ""
        return "位置 %.5f,%.5f → %.5f,%.5f%s".format(first.lat, first.lng, last.lat, last.lng, acc)
    }

    /** 给大模型的帧清单文本：序号 + 相对时间 + GPS（无定位的帧只给时间） */
    @Synchronized
    fun manifestText(): String {
        if (frames.isEmpty()) return "（无帧）"
        val sb = StringBuilder()
        frames.forEachIndexed { i, f ->
            sb.append("#${i + 1} ")
            sb.append(relLabel(f.tsRelSec))
            if (f.lat != null && f.lng != null) {
                sb.append(" GPS(%.5f,%.5f)".format(f.lat, f.lng))
                if (f.alt != null) sb.append(" 海拔%.0fm".format(f.alt))
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun relLabel(sec: Long): String =
        String.format("%02d:%02d", sec / 60, sec % 60)
}
