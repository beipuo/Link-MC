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
 * 曲目信息优先从扩展点回调拿；[onStateChanged] 与 [onIsPlayingChanged]
 * 是可靠的「状态已变更」信号，此时再向宿主查询当前曲目，避免自己维护缓存一致性。
 */
@Extension
class PlaybackExtension : PlaybackExtensionPoint {

    private val state = PlaybackState.INSTANCE

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        guard("onStateChanged") {
            PlaybackState.INSTANCE.setHostState(state)
            // getCurrentMediaItem 带 @SinceApi(1.19.0/dev21)，Kotlin 侧以函数形式暴露，不走属性合成
            PlaybackState.INSTANCE.setMediaItem(WorkshopApi.playback.getCurrentMediaItem())
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        guard("onIsPlayingChanged") {
            this.state.setPlaying(isPlaying)
            // 首次播放时可能还没收到曲目事件，补一次查询
            if (isPlaying && PlaybackState.INSTANCE.snapshot().title.isEmpty()) {
                PlaybackState.INSTANCE.setMediaItem(WorkshopApi.playback.getCurrentMediaItem())
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
