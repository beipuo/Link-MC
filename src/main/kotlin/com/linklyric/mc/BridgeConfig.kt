package com.linklyric.mc

/**
 * 桥接常量。与 Minecraft 端 `Protocol` 必须保持一致。
 *
 * 这些值不是自定义的，而是两侧共同遵守的契约；改动任一侧都必须同步另一侧，
 * 因此集中在此处并逐条注明来源。
 */
object BridgeConfig {

    /** 与 MC 端 `Protocol.DEFAULT_PORT` 一致。 */
    const val DEFAULT_PORT = 35400

    /** 与 MC 端 `Protocol.WS_PATH` 一致的 HTTP 路径前缀。 */
    const val HTTP_PATH = "/lyric"

    /** 载荷契约版本，对应 MC 端 `Protocol.VERSION`。 */
    const val PROTOCOL_VERSION = 2

    /** 数据源标识，对应 MC 端 `SourceProfiles.SALT_PLAYER`。 */
    const val SOURCE_ID = "salt-player"

    /** 展示名。 */
    const val DISPLAY_NAME = "Salt Player"

    /** 位置推送间隔上限。宿主约每秒回调一次，此处仅作兜底节流。 */
    const val POSITION_THROTTLE_MS = 100L
}
