buildscript {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9.5 捆绑的 R8/D8 已原生支持最新 Kotlin class 格式，不再需要覆盖 R8 版本。
        // Kotlin Gradle Plugin 与 KSP 需显式置于 classpath：AGP 9 的内置 Kotlin
        // 只提供运行时依赖（KGP 2.2.10 基线），升到更高 Kotlin 版本靠这里覆盖。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.14")
    }
}

plugins {
    // AGP 9 起 Kotlin 编译由 AGP 内置，不再声明 org.jetbrains.kotlin.android。
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
}
