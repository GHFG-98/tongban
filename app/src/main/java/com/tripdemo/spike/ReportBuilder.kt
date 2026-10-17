package com.tripdemo.spike

import android.util.Base64

/** 精美 HTML 行程报告生成（自包含：图片 base64 内嵌，浏览器直接打开/分享/打印PDF） */
object ReportBuilder {

    data class Section(val label: String, val text: String, val imageJpeg: ByteArray? = null)

    fun build(
        title: String,
        narrative: String,
        sections: List<Section>,
        videoBytes: ByteArray? = null,
        cartoonImage: ByteArray? = null,
        cartoonNote: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append(
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>
              :root { --blue:#007AFF; --ink:#1C1C1E; --sub:#8E8E93; --bg:#F2F2F7; --card:#FFFFFF; }
              * { box-sizing: border-box; margin:0; padding:0; }
              body { font-family:-apple-system,"PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif;
                     background:var(--bg); color:var(--ink); padding:32px 20px 64px; }
              .wrap { max-width:720px; margin:0 auto; }
              .badge { display:inline-block; font-size:12px; color:#fff; background:var(--blue);
                       border-radius:999px; padding:5px 14px; letter-spacing:.5px; margin-bottom:14px; }
              h1 { font-size:30px; letter-spacing:-.5px; margin-bottom:8px; }
              .meta { color:var(--sub); font-size:13px; margin-bottom:28px; }
              .hero { background:linear-gradient(135deg,#0B3B6F,#1FA8C9); border-radius:22px;
                      padding:28px 26px; color:#fff; margin-bottom:26px;
                      box-shadow:0 8px 24px rgba(11,59,111,.18); }
              .hero .label { font-size:12px; opacity:.75; letter-spacing:1px; margin-bottom:10px; }
              .hero p { font-size:16px; line-height:1.85; }
              .sec { background:var(--card); border-radius:16px; padding:10px 10px 12px;
                     box-shadow:0 2px 10px rgba(0,0,0,.05); }
              .grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:8px; }
              .sec .t { color:var(--sub); font-size:11px; margin-bottom:8px; letter-spacing:.3px;
                       text-align:center; }
              .sec img { width:100%; height:148px; object-fit:cover; border-radius:10px;
                        display:block; cursor:zoom-in; background:#0B3B6F; }
              .hint { text-align:center; color:var(--sub); font-size:11px; margin:2px 0 20px; }
              .cartoon { background:var(--card); border-radius:18px; padding:20px; margin-bottom:16px;
                      box-shadow:0 2px 10px rgba(0,0,0,.05); }
              .cartoon .t { color:var(--sub); font-size:12px; margin-bottom:10px; letter-spacing:.4px; }
              .cartoon img { width:100%; border-radius:12px; display:block; cursor:zoom-in; }
              video { width:100%; border-radius:18px; display:block; margin-bottom:26px; background:#000; }
              #viewer { position:fixed; inset:0; background:rgba(0,0,0,.92); display:none;
                       align-items:center; justify-content:center; z-index:99; cursor:zoom-out; }
              #viewer img { max-width:96%; max-height:92%; border-radius:8px; }
              @media print { body{background:#fff;padding:0;} .hero,.sec,.cartoon{box-shadow:none;} }
            </style>
            </head>
            <body><div class="wrap">
            <div class="badge">TRIP REPORT</div>
            <h1>$title</h1>
            <div class="meta">由「行程小记」生成 · 共 ${sections.size} 段${
                if (sections.isNotEmpty()) " · ${sections.first().label} → ${sections.last().label}" else ""
            }</div>
            <div class="hero"><div class="label">全程回顾</div><p>${escape(narrative).replace("\n", "<br>")}</p></div>
            ${if (cartoonImage != null) "<div class=\"cartoon\"><div class=\"t\">卡通集合 · 所选旅程</div><img src=\"data:image/png;base64,${Base64.encodeToString(cartoonImage, Base64.NO_WRAP)}\"></div>" else ""}
            ${if (videoBytes != null) "<video controls playsinline src=\"data:video/mp4;base64,${Base64.encodeToString(videoBytes, Base64.NO_WRAP)}\"></video>" else ""}
            """.trimIndent()
        )
        // 拼图双列网格（统一高度缩略图，点按看大图）；文字由上方「全程回顾」统一叙述，
        // 避免每段小计自带历史上下文而导致的大段重复
        sb.append("<div class=\"grid\">")
        for (s in sections.withIndex()) {
            val img = s.value.imageJpeg
            if (img != null) {
                val b64 = Base64.encodeToString(img, Base64.NO_WRAP)
                sb.append("<div class=\"sec\"><div class=\"t\">第${s.index + 1} 段 · ${escape(s.value.label)}</div>")
                sb.append("<img src=\"data:image/jpeg;base64,$b64\" onclick=\"showViewer(this.src)\" alt=\"第${s.index + 1}段拼图\">")
                sb.append("</div>")
            }
        }
        sb.append("</div>")
        if (sections.any { it.imageJpeg != null }) {
            sb.append("<div class=\"hint\">点按拼图可查看原图</div>")
        }
        sb.append(
            """
            <div id="viewer" onclick="this.style.display='none'"><img id="viewerImg" src=""></div>
            <script>
              function showViewer(src){var v=document.getElementById('viewer');
                document.getElementById('viewerImg').src=src;v.style.display='flex';}
            </script>
            <div class="foot">行程回看器 Demo · 端侧抽帧 + 云端多模态理解</div>
            </div></body></html>
            """.trimIndent()
        )
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
