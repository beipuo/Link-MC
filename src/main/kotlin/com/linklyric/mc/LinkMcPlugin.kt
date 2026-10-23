package com.linklyric.mc

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * Link MC 插件主类。
 *
 * 职责：把 Salt Player 的播放事实转发到本机回环端口，供 Minecraft 端的
 * Link Lyric 模组消费。插件不渲染界面，只做数据出口。
 *
 * 生命周期：插件可能被反复启停，[start] 必须能重复建立全部资源，
 * [stop] 必须完整释放，否则端口会被占死、线程会泄漏。
 */
class LinkMcPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private var bridge: BridgeServer? = null

    override fun start() {
        try {
            val server = BridgeServer(
                port = BridgeConfig.DEFAULT_PORT,
                sourceId = BridgeConfig.SOURCE_ID,
            )
            server.start()
            bridge = server
            // Kotlin 侧 ui 是属性；ui() 仅为 Java 调用方提供的静态桥
            WorkshopApi.ui.toast(
                "Link MC 已就绪，端口 ${server.boundPort()}",
                WorkshopApi.Ui.ToastType.Success,
            )
        } catch (t: Throwable) {
            // 插件运行在宿主进程内，未捕获异常会波及播放器本身。
            // 失败时降级为提示，不影响 Salt Player 可用。
            runCatching { bridge?.stop() }
            bridge = null
            runCatching {
                WorkshopApi.ui.toast(
                    "Link MC 启动失败：${t.message}",
                    WorkshopApi.Ui.ToastType.Error,
                )
            }
        }
    }

    override fun stop() {
        runCatching { bridge?.stop() }
        bridge = null
    }

    override fun delete() {
        stop()
    }
}
