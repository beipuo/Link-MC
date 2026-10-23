plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.linklyric"
version = "0.1.0"

java {
    // API 由 Java 25 编译，插件必须对齐同一目标，否则无法加载宿主注入的类型
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

/**
 * 自检源码集：把主代码与自检代码一起编到独立 classpath，
 * 不参与 spmod 打包，因此不会影响插件包体积。
 */
val selfCheck: SourceSet = sourceSets.create("selfCheck") {
    kotlin.srcDir("src/selfCheck/kotlin")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    // API 与 Kotlin 标准库由 Salt Player 宿主提供，compileOnly 避免重复打入插件包
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    // 处理 @Extension 注解，生成 PF4J 扩展索引
    kapt(libs.spw.workshop.api)

    // 自检需要真实 API 类型构造歌词对象，且需要 SE 运行时（而非裁剪运行时）
    "selfCheckImplementation"(kotlin("stdlib"))
    "selfCheckImplementation"(libs.spw.workshop.api)

    // 插件本体不引入任何第三方库：桥接只用 java.base 的 ServerSocket。
    // 不能用 com.sun.net.httpserver —— 它属于 jdk.httpserver 模块，
    // 而 Salt Player 用 jlink 裁剪运行时，该模块不一定存在。
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

/**
 * 离线自检：不安装插件、不启动 Salt Player，直接验证桥接层行为。
 *
 * 用完整 SE 运行时执行，因此能及早发现「依赖了宿主没有的 JDK 模块」
 * 这类只在实机才暴露的问题。
 */
tasks.register<JavaExec>("selfCheck") {
    group = "verification"
    description = "在本地 JVM 上验证桥接层，无需安装到 Salt Player"
    dependsOn(tasks.named("selfCheckClasses"))
    mainClass.set("com.linklyric.mc.selfcheck.SelfCheck")
    classpath = selfCheck.runtimeClasspath
}

spmod {
    PluginClass = "com.linklyric.mc.LinkMcPlugin"
    PluginId = "com.linklyric.mc"
    PluginVersion = project.version.toString()
    PluginProvider = "Link Lyric"
    PluginName = "Link MC"
    PluginDescription = "把 Salt Player 的歌词、歌单与播放状态桥接给 Minecraft 端的 Link Lyric 模组。"
    PluginOpenSourceUrl = ""
    PluginHasConfig = false
}
