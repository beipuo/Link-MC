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

            // 3. 换曲必须清空旧歌词
            PlaybackState.INSTANCE.setMediaItem(
                PlaybackExtensionPoint.MediaItem("新歌", "新家", "", "", ""),
            )
            body = get(port, "/lyric")
            check("换曲清空歌词", body.contains("\"lines\":[]"))
            check("换曲后不再声明歌词能力", !body.contains("INLINE_LYRIC"))

            // 4. 未知路径
            check("未知路径返回 404", status(port, "/nope") == 404)

            // 5. 命令：未知动作被拒
            check("未知动作返回失败", post(port, "/lyric/command", """{"action":"playAt","index":3}""")
                .contains("\"ok\":false"))

            // 6. 命令：GET 方法被拒
            check("GET 命令返回 405", status(port, "/lyric/command") == 405)

            // 7. 畸形请求体不崩
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
