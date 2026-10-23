pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            // JitPack 将多模块发布物 groupId 改写为 com.github.Moriafly.spw-workshop-api，
            // 与 Gradle 依据插件 ID 推导的标记坐标不同。缺少此映射会报 UnknownPluginException。
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "link-mc"
