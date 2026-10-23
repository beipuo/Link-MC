package com.linklyric.mc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机回环桥接服务端。
 *
 * 用 JDK 自带的 HttpServer，不引入第三方依赖：插件包体积敏感，宿主已提供完整 JDK。
 *
 * 只绑定 127.0.0.1，绝不监听 0.0.0.0。跨机场景需另行加配对码鉴权。
 * 采用短轮询而非 WebSocket：当前只需下发数据 + 接收低频命令，
 * 升级到长连接只有在命令量增大时才划算。
 */
class BridgeServer(
    private val port: Int,
    private val sourceId: String,
) {

    private var server: HttpServer? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val srv = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
            0,
        )
        // 单线程足够：每秒一次的 JSON 序列化，全部非阻塞
        srv.executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "link-mc-http").apply { isDaemon = true }
        }
        // 主路径与命令子路径分开注册，避免路径匹配歧义
        srv.createContext(BridgeConfig.HTTP_PATH) { ex -> route(ex) }
        srv.createContext("${BridgeConfig.HTTP_PATH}/command") { ex -> handleCommand(ex) }
        srv.start()
        server = srv
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.stop(0) }
        server = null
    }

    fun boundPort(): Int = server?.address?.port ?: -1

    private fun route(exchange: HttpExchange) {
        try {
            if (!isLoopback(exchange)) {
                respond(exchange, 403, ERROR_FORBIDDEN)
                return
            }
            if (exchange.requestURI.path.endsWith("/command")) {
                handleCommand(exchange)
            } else {
                respond(exchange, 200, renderJson())
            }
        } catch (t: Throwable) {
            runCatching { respond(exchange, 500, ERROR_INTERNAL) }
        } finally {
            exchange.close()
        }
    }

    /**
     * 命令入口。仅接受 POST，动作从严白名单。
     *
     * 宿主播放控制接口只有 play/pause/previous/next/seekTo，
     * 没有「按索引播放」，因此这里也不暴露 playAt——虚报能力会让
     * MC 端显示无法实现的功能。
     */
    private fun handleCommand(exchange: HttpExchange) {
        try {
            if (!isLoopback(exchange)) {
                respond(exchange, 403, ERROR_FORBIDDEN)
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, """{"error":"method not allowed"}""")
                return
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val ok = dispatch(body)
            respond(exchange, 200, """{"ok":$ok}""")
        } catch (t: Throwable) {
            runCatching { respond(exchange, 500, ERROR_INTERNAL) }
        } finally {
            exchange.close()
        }
    }

    /**
     * 极简命令解析。
     *
     * 不引入 JSON 库（会把依赖打进插件包），因此按字段名定位值。
     * 输入来自本机回环、且动作走白名单，风险可控。
     */
    private fun dispatch(body: String): Boolean {
        val playback: WorkshopApi.Playback =
            runCatching { WorkshopApi.playback }.getOrNull() ?: return false
        val action = extractString(body, "action") ?: return false
        return try {
            when (action) {
                "playPause" -> {
                    if (PlaybackState.INSTANCE.snapshot().playing) playback.pause() else playback.play()
                    true
                }
                "play" -> { playback.play(); true }
                "pause" -> { playback.pause(); true }
                "next" -> { playback.next(); true }
                "previous" -> { playback.previous(); true }
                "seek" -> {
                    val pos = extractNumber(body, "positionMs") ?: return false
                    playback.seekTo(pos)
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            System.err.println("[Link MC] command '$action' failed: ${t.message}")
            false
        }
    }

    private fun isLoopback(exchange: HttpExchange): Boolean =
        exchange.remoteAddress?.address?.isLoopbackAddress ?: false

    private fun renderJson(): String {
        val s = PlaybackState.INSTANCE.snapshot()
        return buildString(2048) {
            append("{\"v\":").append(BridgeConfig.PROTOCOL_VERSION)
            append(",\"type\":\"snapshot\"")
            append(",\"sourceId\":\"").append(escape(sourceId)).append('"')
            append(",\"displayName\":\"").append(escape(BridgeConfig.DISPLAY_NAME)).append('"')
            append(",\"capabilities\":").append(renderCapabilities(s.hasLyric))
            append(",\"seq\":").append(s.sequence)
            append(",\"positionMs\":").append(s.positionMs)
            append(",\"playing\":").append(s.playing)
            append(",\"hostState\":\"").append(escape(s.hostState)).append('"')
            append(",\"track\":{")
            append("\"title\":\"").append(escape(s.title)).append('"')
            append(",\"artist\":\"").append(escape(s.artist)).append('"')
            append(",\"album\":\"").append(escape(s.album)).append('"')
            append(",\"durationMs\":").append(s.durationMs)
            append('}')
            append(",\"lyric\":").append(renderLyric())
            append('}')
        }
    }

    /**
     * 歌词序列化。结构对齐 MC 端 LyricLine：
     * 行含 start/end/主文本/副文本，words 为字级时间轴。
     *
     * 宿主 LyricLine 的 lyricsCells 已带每字时间，直接映射即可，
     * 无需在 MC 端二次切分。
     */
    private fun renderLyric(): String {
        val lines = PlaybackState.INSTANCE.lyrics()
        if (lines.isEmpty()) return "{\"wordLevel\":false,\"lines\":[]}"
        return buildString(lines.size * 96) {
            append("{\"wordLevel\":true,\"lines\":[")
            lines.forEachIndexed { i, line ->
                if (i > 0) append(',')
                append('{')
                append("\"text\":\"").append(escape(line.pureMainText)).append('"')
                // pureSubText 在 API 中为可空（无翻译时），必须兜底
                append(",\"translation\":\"").append(escape(line.pureSubText ?: "")).append('"')
                append(",\"startMs\":").append(line.startTime)
                append(",\"endMs\":").append(line.endTime)
                append(",\"speaker\":\"\"")
                append(",\"words\":").append(renderCells(line.lyricsCells))
                append('}')
            }
            append("]}")
        }
    }

    private fun renderCells(cells: List<PlaybackExtensionPoint.LyricsLine.Cell>?): String {
        if (cells.isNullOrEmpty()) return "[]"
        return buildString(cells.size * 32) {
            append('[')
            cells.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append("{\"text\":\"").append(escape(c.text)).append('"')
                append(",\"startMs\":").append(c.startTime)
                append(",\"endMs\":").append(c.endTime)
                append('}')
            }
            append(']')
        }
    }

    private fun renderCapabilities(hasLyric: Boolean): String {
        val base = "\"TRACK_METADATA\",\"TRACK_DURATION\",\"PLAYBACK_POSITION\",\"TRANSPORT\",\"SEEK\""
        return if (hasLyric) {
            "[$base,\"INLINE_LYRIC\",\"WORD_LEVEL_LYRIC\"]"
        } else {
            "[$base]"
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { out: OutputStream -> out.write(bytes) }
    }

    /** 最小 JSON 转义。曲目名常含引号与反斜杠，不转义会产生非法 JSON。 */
    private fun escape(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun extractString(body: String, key: String): String? {
        val marker = "\"$key\""
        val i = body.indexOf(marker)
        if (i < 0) return null
        val colon = body.indexOf(':', i + marker.length)
        if (colon < 0) return null
        val q1 = body.indexOf('"', colon + 1)
        if (q1 < 0) return null
        val q2 = body.indexOf('"', q1 + 1)
        if (q2 < 0) return null
        return body.substring(q1 + 1, q2)
    }

    private fun extractNumber(body: String, key: String): Long? {
        val marker = "\"$key\""
        val i = body.indexOf(marker)
        if (i < 0) return null
        val colon = body.indexOf(':', i + marker.length)
        if (colon < 0) return null
        val end = body.indexOfFirst(colon + 1) { it == ',' || it == '}' }
        val raw = body.substring(colon + 1, if (end < 0) body.length else end).trim()
        return raw.toLongOrNull()
    }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (idx in from until length) if (pred(this[idx])) return idx
        return -1
    }

    private companion object {
        const val ERROR_FORBIDDEN = """{"error":"loopback only"}"""
        const val ERROR_INTERNAL = """{"error":"internal"}"""
    }
}
