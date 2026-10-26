package com.linklyric.mc

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.LyricsLine
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机回环桥接服务端。
 *
 * 传输层只用 `java.base` 里的 [ServerSocket]，手写极简 HTTP/1.1。
 * 不能用 `com.sun.net.httpserver`：它属于 `jdk.httpserver` 模块，
 * 而 Salt Player 是 jlink 裁剪过的运行时，该模块可能不存在，
 * 表现为 `NoClassDefFoundError: com/sun/net/httpserver/HttpServer`。
 * `java.base` 是任何 JVM 都必然具备的模块，因此这里是唯一安全的选择。
 *
 * 只绑定 127.0.0.1，绝不监听 0.0.0.0。跨机场景需另行加配对码鉴权。
 * 每个连接处理完即关闭（`Connection: close`），不做 keep-alive——
 * 轮询频率约每秒一次，复用连接带来的收益抵不过状态机的复杂度。
 */
class BridgeServer(
    private val port: Int,
    private val sourceId: String,
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            val ss = ServerSocket()
            // backlog 取小值即可：调用方只有本机的 Minecraft
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
            // 设置超时，使 stop() 关闭后 accept 循环能及时退出
            ss.soTimeout = 1000
            serverSocket = ss
            acceptThread = Thread({ acceptLoop(ss) }, "link-mc-http").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            throw t
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // close 会让阻塞中的 accept 抛出异常，循环随即因 running=false 退出
        runCatching { serverSocket?.close() }
        runCatching { acceptThread?.interrupt() }
        serverSocket = null
        acceptThread = null
    }

    fun boundPort(): Int = serverSocket?.localPort ?: -1

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                ss.accept().use { socket -> handle(socket) }
            } catch (_: SocketTimeoutException) {
                // 正常：用于周期性检查 running 标志
            } catch (t: Throwable) {
                // 单个连接出错不得终止整个服务
                if (!running.get()) return
                System.err.println("[Link MC] accept failed: ${t.message}")
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            if (!socket.inetAddress.isLoopbackAddress) {
                writeResponse(socket.getOutputStream(), 403, ERROR_FORBIDDEN)
                return
            }
            socket.soTimeout = 5_000
            val request = readRequest(socket.getInputStream()) ?: return
            val response = route(request)
            writeResponse(socket.getOutputStream(), response.code, response.body, response.lyricEtag)
        } catch (t: Throwable) {
            runCatching { writeResponse(socket.getOutputStream(), 500, ERROR_INTERNAL) }
        }
    }

    /** 一次响应：状态码、正文，以及歌词子树的指纹（仅 /lyric 有）。 */
    private data class Response(
        val code: Int,
        val body: String,
        val lyricEtag: String? = null,
    )

    private fun route(request: Request): Response {
        val path = request.path
        return when {
            path.endsWith("/command") -> {
                if (request.method != "POST") {
                    Response(405, """{"error":"method not allowed"}""")
                } else {
                    Response(200, """{"ok":${dispatch(request.body)}}""")
                }
            }
            path.endsWith("/lyric") || path == "/" -> {
                val lyric = cachedLyric()
                val unchanged = request.ifNoneMatch != null && request.ifNoneMatch == lyric.etag
                Response(
                    code = 200,
                    body = renderSnapshot(if (unchanged) null else lyric),
                    lyricEtag = lyric.etag,
                )
            }
            else -> Response(404, """{"error":"not found"}""")
        }
    }

    /**
     * 命令分发。动作走严格白名单。
     *
     * 宿主 `Playback` 接口只有 play/pause/previous/next/seekTo，
     * 没有「按索引播放」，因此这里也不暴露 playAt。
     */
    private fun dispatch(body: String): Boolean {
        if (body.isEmpty()) return false
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

    // ---------------- 极简 HTTP/1.1 ----------------

    private data class Request(
        val method: String,
        val path: String,
        val body: String,
        /** 客户端上次收到的歌词指纹；null 表示首次请求或客户端不支持条件请求。 */
        val ifNoneMatch: String? = null,
    )

    /** 单次请求头部上限。请求来自本机，恶意放大不该被容忍。 */
    private val maxHeaderBytes = 8 * 1024

    /** 单次请求体上限。命令只含 action 与一个数字，1 KB 绰绰有余。 */
    private val maxBodyBytes = 1 * 1024

    /**
     * 读取一个完整请求。
     *
     * 逐字节读到 `\r\n\r\n` 为止，再按 `Content-Length` 读 body。
     * 不能用 BufferedReader：字符解码会吞掉 body 的原始字节。
     */
    private fun readRequest(input: InputStream): Request? {
        val header = ByteArrayOutputStream(512)
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            header.write(b)

            // 逐字节推进 \r\n\r\n 的匹配状态机
            matched = when {
                b == CR && matched == 0 -> 1
                b == LF && matched == 1 -> 2
                b == CR && matched == 2 -> 3
                b == LF && matched == 3 -> 4
                b == CR -> 1
                else -> 0
            }
            if (matched == 4) break
            if (header.size() > maxHeaderBytes) return null
        }

        val text = header.toString(StandardCharsets.ISO_8859_1.name())
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null

        val method = requestLine[0]
        val path = requestLine[1].substringBefore('?')

        var contentLength = 0
        var ifNoneMatch: String? = null
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx)
            val value = line.substring(idx + 1).trim()
            when {
                name.equals("Content-Length", ignoreCase = true) ->
                    contentLength = value.toIntOrNull() ?: 0
                name.equals("If-None-Match", ignoreCase = true) ->
                    // 去掉可能存在的弱校验前缀 W/ 与引号，只留裸指纹
                    ifNoneMatch = value.removePrefix("W/").trim('"').ifEmpty { null }
            }
        }

        val body = if (contentLength > 0) {
            val size = contentLength.coerceAtMost(maxBodyBytes)
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return Request(method, path, body, ifNoneMatch)
    }

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        body: String,
        lyricEtag: String? = null,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString(192) {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            // 原先固定发 no-store，等于明令禁止任何缓存，与条件请求直接冲突。
            // 时效性改由歌词指纹自行校验，不必再让中间层无条件丢弃整份响应。
            append("Cache-Control: no-cache\r\n")
            if (lyricEtag != null) {
                append("ETag: \"").append(lyricEtag).append("\"\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    // ---------------- JSON 序列化 ----------------

    /** 歌词子树的序列化结果与指纹。同一份内容只算一次。 */
    private data class CachedLyric(val etag: String, val json: String)

    /**
     * 歌词序列化缓存。
     *
     * <p>此前每轮轮询都完整重建一次歌词 JSON：40 行带逐字时间约 25 KB，
     * 实测 62.7 微秒，而这份内容在换曲之前是完全不变的。
     * 轮询频率 5 Hz，等于每秒白做 5 次。
     *
     * <p>只缓存这一项，不缓存整份响应：positionMs、seq 每轮都变，
     * 缓存整份响应会让位置冻结。
     *
     * <p>指纹取歌词 JSON 的哈希。它与内容一一对应，
     * 客户端据此判断是否需要重新解析时间轴。
     */
    @Volatile private var lyricCache: CachedLyric? = null
    @Volatile private var lyricCacheSource: List<PlaybackExtensionPoint.LyricsLine>? = null

    private fun cachedLyric(): CachedLyric {
        val lines = PlaybackState.INSTANCE.lyrics()
        // 宿主的歌词列表整块替换，引用相等即可判定未变；换了就重算。
        val cached = lyricCache
        if (cached != null && lyricCacheSource === lines) return cached
        val json = renderLyric(lines)
        val fresh = CachedLyric(etag = Integer.toHexString(json.hashCode()), json = json)
        lyricCacheSource = lines
        lyricCache = fresh
        return fresh
    }

    private fun renderSnapshot(lyric: CachedLyric?): String {
        val s = PlaybackState.INSTANCE.snapshot()
        return buildString(2048 + (lyric?.json?.length ?: 0)) {
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
            if (lyric == null) {
                // 客户端已有同一份歌词：明确告知不变，并宣布不再支持旧版能力，
                // 让客户端知道字段存在但被省略，而不是以为歌词消失了。
                append(",\"lyricOmitted\":true")
            } else {
                append(",\"lyricOmitted\":false")
                append(",\"lyric\":").append(lyric.json)
            }
            append('}')
        }
    }

    /**
     * 歌词序列化。结构对齐 MC 端 LyricLine：
     * 行含 start/end/主文本/副文本，words 为字级时间轴。
     *
     * 宿主 LyricsLine 的 lyricsCells 已带每字时间，直接映射，
     * 无需在 MC 端二次切分。
     */
    private fun renderLyric(lines: List<PlaybackExtensionPoint.LyricsLine>): String {
        if (lines.isEmpty()) return "{\"wordLevel\":false,\"lines\":[]}"
        return buildString(lines.size * 96) {
            append("{\"wordLevel\":true,\"lines\":[")
            lines.forEachIndexed { i, line ->
                if (i > 0) append(',')
                append('{')
                append("\"text\":\"").append(escape(line.pureMainText)).append('"')
                // pureSubText 在 API 中可空（无翻译时），必须兜底
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
        var end = -1
        for (idx in (colon + 1) until body.length) {
            val c = body[idx]
            if (c == ',' || c == '}') { end = idx; break }
        }
        val raw = body.substring(colon + 1, if (end < 0) body.length else end).trim()
        return raw.toLongOrNull()
    }

    private companion object {
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val ERROR_FORBIDDEN = """{"error":"loopback only"}"""
        const val ERROR_INTERNAL = """{"error":"internal"}"""
    }
}
