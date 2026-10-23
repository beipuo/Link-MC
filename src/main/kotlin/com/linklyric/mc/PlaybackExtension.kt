package com.linklyric.mc

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.pf4j.Extension

/**
 * 播放事件扩展点。
 *
 * 每个回调都包在 guard 内：它们运行在宿主的多个线程上（位置更新在定时线程、
 * 歌词加载在 IO 线程），一次未捕获异常就可能中断 Salt Player 的播放流程。
 *
 * 曲目信息有两条来源，必须都接：
 * 1. [onBeforeLoadLyrics] / [onAfterLoadLyrics] —— 宿主加载歌词时**主动带上**
 *    `MediaItem`，这是最可靠的来源。
 * 2. [WorkshopApi.Playback.getCurrentMediaItem] —— 主动查询，但实测在部分
 *    状态下返回空对象（title 为空），只能作为兜底。
 *
 * 只依赖第 2 条会导致 track 恒为空，进而使 Minecraft 端无法做歌词匹配。
 */
@Extension
class PlaybackExtension : PlaybackExtensionPoint {

    private val state = PlaybackState.INSTANCE

    /**
     * 宿主即将加载歌词。
     *
     * 返回 null 表示不干预，宿主继续走默认逻辑。
     * 这里只旁路取走 `MediaItem`——它比 `getCurrentMediaItem()` 可靠。
     */
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        guard("onBeforeLoadLyrics") {
            state.setMediaItem(mediaItem)
        }
        return null
    }

    /**
     * 宿主默认逻辑未能加载歌词时的后备入口。
     *
     * 本插件不提供歌词内容，仅记录 `MediaItem`，同样返回 null。
     */
    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        guard("onAfterLoadLyrics") {
            // 仅在尚未拿到曲目时补写，避免覆盖 onBeforeLoadLyrics 的更新
            if (!state.snapshot().hasTrack()) {
                state.setMediaItem(mediaItem)
            }
        }
        return null
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        guard("onStateChanged") {
            PlaybackState.INSTANCE.setHostState(state)
            // 兜底查询：getCurrentMediaItem 带 @SinceApi(1.19.0/dev21)，
            // Kotlin 侧以函数形式暴露，不走属性合成
            val item = WorkshopApi.playback.getCurrentMediaItem()
            if (item != null && !item.title.isNullOrEmpty()) {
                PlaybackState.INSTANCE.setMediaItem(item)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        guard("onIsPlayingChanged") {
            this.state.setPlaying(isPlaying)
            // 首次播放时可能还没收到歌词回调，补一次查询
            if (isPlaying && !PlaybackState.INSTANCE.snapshot().hasTrack()) {
                val item = WorkshopApi.playback.getCurrentMediaItem()
                if (item != null && !item.title.isNullOrEmpty()) {
                    PlaybackState.INSTANCE.setMediaItem(item)
                }
            }
        }
    }

    override fun onPositionUpdated(position: Long) {
        guard("onPositionUpdated") {
            state.setPosition(position)
        }
    }

    override fun onSeekTo(position: Long) {
        guard("onSeekTo") {
            state.setPosition(position)
        }
    }

    /**
     * 完整歌词时间轴。
     *
     * 空列表统一表示「当前无可用时间轴」：换曲、重载、无歌词、加载失败
     * 都会出现空列表，不能用通知次数推断加载次数。
     */
    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        guard("onLyricsLinesUpdated") {
            state.setLyrics(lyricsLines)
        }
    }

    private inline fun guard(name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            // 静默降级：桥接数据缺失远好于打断宿主播放
            System.err.println("[Link MC] $name failed: ${t.message}")
        }
    }
}
