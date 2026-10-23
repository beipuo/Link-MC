package com.linklyric.mc

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局播放状态容器。
 *
 * 写入方是宿主回调线程（位置每秒一次、歌词在 IO 线程），读取方是 HTTP 线程。
 * 因此不逐字段加锁，而是 volatile 字段 + 读取时组装不可变快照：
 * 位置类数据允许几十毫秒不一致（对渲染无影响），换曲由序号判断新鲜度。
 *
 * 歌词时间轴单独持有：它只在换曲/重载时变化，体积远大于其它字段，
 * 不能放进 [Snapshot] 一起复制。
 */
class PlaybackState private constructor() {

    companion object {
        val INSTANCE = PlaybackState()
    }

    /** 一次读取得到的不可变快照，不含歌词正文（歌词另行读取）。 */
    data class Snapshot(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val hostState: String,
        val hasLyric: Boolean,
        val sequence: Long,
        val updatedAtMs: Long,
    ) {
        /** 是否已拿到可用的曲目信息。Minecraft 端据此判断要不要发起歌词匹配。 */
        fun hasTrack(): Boolean = title.isNotEmpty() || artist.isNotEmpty()
    }

    @Volatile private var title: String = ""
    @Volatile private var artist: String = ""
    @Volatile private var album: String = ""
    @Volatile private var durationMs: Long = 0L
    @Volatile private var positionMs: Long = 0L
    @Volatile private var playing: Boolean = false
    @Volatile private var hostState: String = ""
    @Volatile private var sequence: Long = 0L
    @Volatile private var updatedAtMs: Long = System.currentTimeMillis()

    /** 歌词时间轴。整块替换，不原地修改，避免读到半更新的列表。 */
    @Volatile private var lyrics: List<PlaybackExtensionPoint.LyricsLine> = emptyList()

    fun setPlaying(value: Boolean) {
        playing = value
        bump()
    }

    fun setPosition(value: Long) {
        positionMs = if (value < 0L) 0L else value
        bump()
    }

    fun setHostState(value: PlaybackExtensionPoint.State) {
        hostState = value.name
    }

    /** 是否已拿到可用的曲目信息。用于判断空查询结果该不该被采纳。 */
    fun hasTrack(): Boolean = title.isNotEmpty() || artist.isNotEmpty()

    /**
     * 更新曲目信息。
     *
     * 两条纪律：
     * 1. 空对象（无 title 也无 artist）视为「本次查询无结果」，**整体忽略**。
     *    宿主 `getCurrentMediaItem()` 在部分状态下会返回这种空对象，
     *    若照单全收会在每轮状态变化时清掉刚拿到的曲目与歌词。
     * 2. 只有曲目标识真的变化时才清空歌词。同一首歌重复上报不该触发清空，
     *    否则歌词会在播放中反复消失又重建。
     */
    fun setMediaItem(item: PlaybackExtensionPoint.MediaItem?) {
        if (item == null) return

        val newTitle = item.title
        val newArtist = item.artist
        if (newTitle.isEmpty() && newArtist.isEmpty()) return

        val trackChanged = newTitle != title || newArtist != artist
        title = newTitle
        artist = newArtist
        album = item.album
        durationMs = if (item.duration < 0L) 0L else item.duration

        // 换曲必须清空旧歌词，否则新歌会短暂显示上一首的内容
        if (trackChanged) {
            lyrics = emptyList()
        }
        bump()
    }

    fun setLyrics(lines: List<PlaybackExtensionPoint.LyricsLine>?) {
        lyrics = lines ?: emptyList()
        bump()
    }

    fun lyrics(): List<PlaybackExtensionPoint.LyricsLine> = lyrics

    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            positionMs = positionMs,
            playing = playing,
            hostState = hostState,
            hasLyric = lyrics.isNotEmpty(),
            sequence = sequence,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun bump() = synchronized(this) {
        sequence++
        updatedAtMs = System.currentTimeMillis()
    }
}
