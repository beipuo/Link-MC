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

## 传输层：只能用 `java.base`

**这是本工程最容易踩死的坑。** `com.sun.net.httpserver.HttpServer` 看起来是 JDK 自带的常规工具，但它属于 `jdk.httpserver` 模块，不在 `java.base` 里。Salt Player 用 jlink 裁剪过运行时，该模块被剔除了，插件加载时直接抛：

```
Link MC 启动失败：com/sun/net/httpserver/HttpServer
```

换成 `ServerSocket` 手写极简 HTTP/1.1 后正常。`java.base` 是任何 JVM 都必然具备的唯一模块，凡是不确定的 JDK 类都应先假定它可能不在。

验证方式：

```powershell
# 从 spmod 解出 class 后检查模块归属
jdeps -summary --multi-release 25 -cp <解出的目录> <解出的目录>
# 期望输出只有 java.base
```

`BridgeServer` 的网络类全部来自 `java.net`，已用 `jdeps` 逐类确认。

## 离线自检

不需要安装到 Salt Player 就能验证桥接层：

```powershell
.\gradlew.bat selfCheck
```

覆盖 24 项：端口绑定、空快照结构、曲目字段、JSON 转义（引号与制表符）、位置与播放态、歌词能力动态声明、字级单元格数量、换曲清空旧歌词、404/405、命令白名单、畸形请求不崩、`stop()` 后端口释放。

自检跑在完整 SE 运行时上，所以它能提前暴露「依赖了宿主没有的 JDK 模块」这类问题——但也**只能**覆盖到模块层面，jlink 裁剪的其它差异仍需实机验证。

## 桥接端点

只绑定 `127.0.0.1:35400`，绝不监听 `0.0.0.0`。

```
GET  /lyric            当前快照（JSON，v2 契约）
POST /lyric/command    {"action":"next"} 等
```

支持的命令：`playPause` / `play` / `pause` / `next` / `previous` / `seek`（带 `positionMs`）。没有 `playAt`，因为宿主不提供按索引播放。

协议细节见同级仓库的 `docs/PROTOCOL.md`。

## 设计取舍

用 `java.base` 的 `ServerSocket` 手写极简 HTTP/1.1，不引入任何第三方依赖。插件包体积敏感，且有 JSON 序列化需求的部分只有固定几个字段——为它引入 Web 服务器或 JSON 库不划算，何况宿主运行时可能根本没有那些模块。

每连接一请求即关闭（`Connection: close`），不做 keep-alive。轮询约每秒一次，复用连接的收益抵不过状态机的复杂度。

命令解析用字段名定位，输入来自本机回环且动作走白名单。

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
- 裁剪运行时里是否还缺其它非 `java.base` 模块（已确认的只有 `jdk.httpserver`）
