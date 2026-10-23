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

dependencies {
    // API 与 Kotlin 标准库由 Salt Player 宿主提供，compileOnly 避免重复打入插件包
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    // 处理 @Extension 注解，生成 PF4J 扩展索引
    kapt(libs.spw.workshop.api)

    // 需要随插件分发的依赖用 implementation。
    // 暂不引入任何第三方库：桥接只需 JDK 自带的 Socket / NIO。
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
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
