package com.linklyric.mc.selfcheck

import com.linklyric.mc.BridgeServer
import com.linklyric.mc.PlaybackState
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.net.HttpURLConnection
import java.net.URI

/**
 * 桥接层离线自检。
 *
 * 直接驱动 [BridgeServer]，不依赖 Salt Player 宿主，也不依赖 jdk.httpserver。
 * 目的：在不安装插件的前提下验证 HTTP 收发、JSON 结构、命令白名单。
 *
 * 运行：`gradlew selfCheck`
 */
object SelfCheck {

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val server = BridgeServer(port = 35499, sourceId = "selfcheck")
        server.start()
        try {
            val port = server.boundPort()
            check("端口已绑定", port > 0)

            // 1. 无曲目时的快照
            var body = get(port, "/lyric")
            check("空快照含版本号", body.contains("\"v\":2"))
            check("空快照 sourceId 正确", body.contains("\"sourceId\":\"selfcheck\""))
            check("空曲目时 title 为空", body.contains("\"title\":\"\""))
            check("空曲目时不声明歌词能力", !body.contains("INLINE_LYRIC"))

            // 2. 填入曲目与歌词
            val line = PlaybackExtensionPoint.LyricsLine(
                1000L,
                3000L,
                listOf(
                    PlaybackExtensionPoint.LyricsLine.Cell(1000L, 2000L, "你"),
                    PlaybackExtensionPoint.LyricsLine.Cell(2000L, 3000L, "好"),
                ),
                "你好",
                null, // 模拟无翻译
            )
            PlaybackState.INSTANCE.setMediaItem(
                PlaybackExtensionPoint.MediaItem("歌\t名", "艺\"术家", "专辑", "", ""),
            )
            PlaybackState.INSTANCE.setPosition(1500L)
            PlaybackState.INSTANCE.setPlaying(true)
            PlaybackState.INSTANCE.setLyrics(listOf(line))

            body = get(port, "/lyric")
            check("曲目标题已带出", body.contains("\"title\":\"歌\\t名\""))
            check("引号已转义", body.contains("艺\\\"术家"))
            check("位置已带出", body.contains("\"positionMs\":1500"))
            check("播放态为真", body.contains("\"playing\":true"))
            check("有歌词时声明逐字能力", body.contains("WORD_LEVEL_LYRIC"))
            check("歌词行文本", body.contains("\"text\":\"你好\""))
            check("无翻译时为空串", body.contains("\"translation\":\"\""))
            check("字级时间轴存在", body.contains("\"words\":[{"))
            check("字级单元格数量", countOccurrences(body, "\"startMs\":") >= 3)
            check("不声明队列能力", !body.contains("QUEUE"))
            check("不声明按索引播放", !body.contains("PLAY_BY_INDEX"))

            // 3. 空 MediaItem 必须被整体忽略（宿主 getCurrentMediaItem 会返回空对象）
            PlaybackState.INSTANCE.setMediaItem(
                PlaybackExtensionPoint.MediaItem("", "", "", "", ""),
            )
            body = get(port, "/lyric")
            check("空 MediaItem 不清掉曲目", body.contains("\"title\":\"歌\\t名\""))
            check("空 MediaItem 不清掉歌词", body.contains("\"text\":\"你好\""))

            // 4. 同一首歌重复上报不得清空歌词
            PlaybackState.INSTANCE.setMediaItem(
                PlaybackExtensionPoint.MediaItem("歌\t名", "艺\"术家", "专辑", "", ""),
            )
            body = get(port, "/lyric")
            check("同曲重复上报保留歌词", body.contains("\"text\":\"你好\""))

            // 5. 真正换曲时必须清空旧歌词
            PlaybackState.INSTANCE.setMediaItem(
                PlaybackExtensionPoint.MediaItem("新歌", "新家", "", "", ""),
            )
            body = get(port, "/lyric")
            check("换曲清空歌词", body.contains("\"lines\":[]"))
            check("换曲后不再声明歌词能力", !body.contains("INLINE_LYRIC"))
            check("换曲后曲目已更新", body.contains("\"title\":\"新歌\""))

            // 6. 条件请求：歌词未变时不再下发整棵子树
            PlaybackState.INSTANCE.setMediaItem(
                PlaybackExtensionPoint.MediaItem("条件歌", "条件家", "", "", ""),
            )
            PlaybackState.INSTANCE.setLyrics(listOf(line))
            val first = fetch(port, "/lyric", ifNoneMatch = null)
            check("首轮下发歌词", first.body.contains("\"text\":\"你好\""))
            check("首轮不带省略标记", !first.body.contains("\"lyricOmitted\":true"))
            check("首轮回 ETag", !first.etag.isNullOrEmpty())

            // 用上一轮的指纹再请求一次：服务端应只回省略标记
            val second = fetch(port, "/lyric", ifNoneMatch = first.etag)
            check("命中时不下发歌词子树", !second.body.contains("\"text\":\"你好\""))
            check("命中时声明省略", second.body.contains("\"lyricOmitted\":true"))
            check("命中时仍带位置", second.body.contains("\"positionMs\":"))
            check("命中时仍带曲目", second.body.contains("\"title\":\"条件歌\""))
            check("指纹保持不变", second.etag == first.etag)
            // 省略生效的直接判据是「歌词子树不见了」，不是压缩比。
            // 一条歌词的样本里，歌名、位置、能力声明等固定字段占大头，
            // 省掉子树只能少三成——拿单行样本断言压缩比只会测出样本太小。
            // 真实规模（40 行带逐字时间）的比值由下面的多行用例覆盖。
            check("省略后不含任何歌词行字段", !second.body.contains("\"words\":["))
            check("省略后不含翻译字段", !second.body.contains("\"translation\":"))

            // 位置变化不得影响歌词指纹，否则条件请求永远不命中
            PlaybackState.INSTANCE.setPosition(2222L)
            val afterPos = fetch(port, "/lyric", ifNoneMatch = first.etag)
            check("位置变化不改变歌词指纹", afterPos.etag == first.etag)
            check("位置变化仍能命中", afterPos.body.contains("\"lyricOmitted\":true"))
            check("命中时位置已更新", afterPos.body.contains("\"positionMs\":2222"))

            // 歌词真的变了必须重新下发
            val other = PlaybackExtensionPoint.LyricsLine(
                1000L, 3000L,
                listOf(PlaybackExtensionPoint.LyricsLine.Cell(1000L, 3000L, "换")),
                "换了",
                "译",
            )
            PlaybackState.INSTANCE.setLyrics(listOf(other))
            val changed = fetch(port, "/lyric", ifNoneMatch = first.etag)
            check("歌词变化后重新下发", changed.body.contains("\"text\":\"换了\""))
            check("歌词变化后不声明省略", !changed.body.contains("\"lyricOmitted\":true"))
            check("歌词变化后指纹更新", changed.etag != first.etag)

            // 旧客户端不发 If-None-Match：必须始终拿到完整内容
            val legacy = fetch(port, "/lyric", ifNoneMatch = null)
            check("无指纹时始终下发完整歌词", legacy.body.contains("\"text\":\"换了\""))

            // 真实规模的压缩比：40 行、每行 12 个逐字单元，与实机量级一致
            val many = (0 until 40).map { i ->
                PlaybackExtensionPoint.LyricsLine(
                    i * 3000L, (i + 1) * 3000L,
                    (0 until 12).map { k ->
                        PlaybackExtensionPoint.LyricsLine.Cell(
                            i * 3000L + k * 250L, i * 3000L + (k + 1) * 250L, "あ",
                        )
                    },
                    "あ".repeat(12),
                    "翻译".repeat(6),
                )
            }
            PlaybackState.INSTANCE.setLyrics(many)
            val big = fetch(port, "/lyric", ifNoneMatch = null)
            val bigCached = fetch(port, "/lyric", ifNoneMatch = big.etag)
            val ratio = bigCached.body.length.toDouble() / big.body.length
            println("  ..   40 行：全文=" + big.body.length + " 省略=" + bigCached.body.length
                + " 比值=" + String.format("%.1f%%", ratio * 100))
            check("大规模下省略后不到原文一成", ratio < 0.1)

            // 7. 未知路径
            check("未知路径返回 404", status(port, "/nope") == 404)

            // 7. 命令：未知动作被拒
            check("未知动作返回失败", post(port, "/lyric/command", """{"action":"playAt","index":3}""")
                .contains("\"ok\":false"))

            // 8. 命令：GET 方法被拒
            check("GET 命令返回 405", status(port, "/lyric/command") == 405)

            // 9. 畸形请求体不崩
            check("空体返回失败", post(port, "/lyric/command", "").contains("\"ok\":false"))
            check("畸形 JSON 不抛异常", post(port, "/lyric/command", "{{{").contains("\"ok\":false"))
        } finally {
            server.stop()
        }

        check("stop 后端口释放", portFree(35499))

        if (failures == 0) {
            println("\n全部自检通过")
        } else {
            println("\n失败 $failures 项")
            kotlin.system.exitProcess(1)
        }
    }

    private fun check(name: String, ok: Boolean) {
        if (ok) {
            println("  ok   $name")
        } else {
            println("  FAIL $name")
            failures++
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun get(port: Int, path: String): String {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** 一次 GET 的结果：正文与 ETag。 */
    private class Fetched(val body: String, val etag: String?)

    private fun fetch(port: Int, path: String, ifNoneMatch: String?): Fetched {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        if (ifNoneMatch != null) {
            conn.setRequestProperty("If-None-Match", "\"$ifNoneMatch\"")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return Fetched(body, conn.getHeaderField("ETag")?.trim('"'))
    }

    private fun status(port: Int, path: String): Int {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        return conn.responseCode
    }

    private fun post(port: Int, path: String, body: String): String {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun portFree(port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)) }
        true
    }.getOrDefault(false)
}
