# Link MC

Salt Player 的创意工坊插件（`.spmod`）。把播放事实桥接到本机回环端口，供 Minecraft 端的 [Link Lyric](../Link%20Lyric) 模组消费。

插件不做界面、不渲染、不发声，只做数据出口。

## 按官方规范配置

工程严格对齐 [Salt Player 开发入门](https://saltplayer.com/workshop/getting-started) 的要求：

| 项目 | 值 | 来源 |
|---|---|---|
| Workshop API | `0.1.0-dev22` | 官方文档示例版本 |
| Kotlin | `2.4.20` | API 由该版本编译 |
| Java | 25 | API 由 JDK 25 编译，必须对齐 |
| Gradle Wrapper | 9.7.1 | 支持 JDK 25 |
| Workshop Gradle 插件 | `com.xuncorp.spw.workshop` `0.1.0-dev22` | 同 API 版本 |

### `useModule` 映射不能省

JitPack 把多模块发布物的 groupId 改写成 `com.github.Moriafly.spw-workshop-api`，与 Gradle 依据插件 ID 推导的标记坐标不同。只在 `pluginManagement.repositories` 加 JitPack 是不够的，`resolutionStrategy.eachPlugin` 里的 `useModule(...)` 必须保留，否则报 `UnknownPluginException`。

### 坐标陷阱

`com.github.Moriafly:spw-workshop-api` 这个坐标的 jar 只有 **308 字节**，是空壳，pom 里再指向真实模块。真正含代码的是 `com.github.Moriafly.spw-workshop-api:spw-workshop-api`（约 55 KB）。排查编译期「类找不到」时注意区分二者。

## 已核实的 API 签名

以下取自 `0.1.0-dev22` 的实际 jar（`javap` 反查），不是文档推断：

```java
interface PlaybackExtensionPoint {
    void onStateChanged(PlaybackExtensionPoint.State);        // 非空
    void onIsPlayingChanged(boolean);
    void onSeekTo(long);
    void onPositionUpdated(long);                             // 约每秒一次，毫秒
    void onLyricsLineUpdated(PlaybackExtensionPoint.LyricsLine);
    void onLyricsLinesUpdated(List<PlaybackExtensionPoint.LyricsLine>); // 非空
    String onBeforeLoadLyrics(MediaItem);
    String onAfterLoadLyrics(MediaItem);
    String updateLyrics(MediaItem);
}
```

```java
interface WorkshopApi.Playback {
    MediaItem getCurrentMediaItem();   // @SinceApi(spwVersion="1.19.0", modVersion="dev21")
    List<LyricsLine> getLyricsLines();
    void play(); void pause(); void previous(); void next();
    void seekTo(long);
    void changeExclusive(boolean);
}
```

**关键结论：`Playback` 没有播放队列，也没有按索引播放。** 因此桥接端**不声明** `QUEUE` 与 `PLAY_BY_INDEX` 能力——虚报会让 MC 端显示一个永远为空的歌单列表。

### Kotlin 调用注意

`WorkshopApi.ui()` / `playback()` 是给 Java 用的静态桥；Kotlin 侧它们是**属性**：

```kotlin
WorkshopApi.ui.toast("ok", WorkshopApi.Ui.ToastType.Success)   // 正确
WorkshopApi.ui().toast(...)                                     // 编译失败
```

`getCurrentMediaItem()` 带 `@SinceApi`，Kotlin 不合成属性，必须显式调用。

### 歌词结构

`LyricsLine` 自带行级时间与字级单元格，逐字能力是现成的：

```kotlin
LyricsLine(startTime, endTime, lyricsCells, pureMainText, pureSubText)
Cell(startTime, endTime, text)
```

`pureSubText` 可空（无翻译时），`pureMainText` 非空。空列表统一表示「当前无可用时间轴」，换曲、重载、无歌词、加载失败都会如此，不能用通知次数推断加载次数。

## 桥接端点

只绑定 `127.0.0.1:35400`，绝不监听 `0.0.0.0`。

```
GET  /lyric            当前快照（JSON，v2 契约）
POST /lyric/command    {"action":"next"} 等
```

支持的命令：`playPause` / `play` / `pause` / `next` / `previous` / `seek`（带 `positionMs`）。没有 `playAt`，因为宿主不提供按索引播放。

协议细节见同级仓库的 `docs/PROTOCOL.md`。

## 设计取舍

用 JDK 自带的 `com.sun.net.httpserver`，不引入任何第三方依赖。插件包体积敏感，宿主已提供完整 JDK，且有 JSON 序列化需求的部分只有固定几个字段——为它引入一个 JSON 库不划算。命令解析用字段名定位，输入来自本机回环且动作走白名单，风险可控。

所有宿主回调都包在 `guard` 内。它们运行在宿主的多个线程上，一次未捕获异常就可能中断 Salt Player 的播放流程。桥接数据缺失远好于打断播放。

## 构建

```powershell
.\gradlew.bat plugin
```

产物：`build/libs/plugin-com.linklyric.mc-<version>.spmod`

安装方式见 [安装与管理 Mod](https://saltplayer.com/workshop/usage.html) 的本地导入。启用后应看到「Link MC 已就绪，端口 35400」提示。

## 发布前

- `.spmod` 与封面（<1 MB）通过 Steam 启动项 `--workshop-publisher` 上传
- 新条目默认私有、新订阅默认禁用，需手动开放
- 同 `PluginId` 的本地插件与订阅插件会**同时被禁用**，调试时注意先停用其一
- 官方鼓励开源且不建议混淆插件代码

## 待验证

- 端口 35400 被占用时的降级路径（当前表现为 toast 报错，未做自动换端口）
- `onLyricsLinesUpdated` 与 `onLyricsLineUpdated` 同时启用时的时间基是否一致
- 实测 `onPositionUpdated` 的实际回调频率与抖动幅度
