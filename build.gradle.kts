import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Stay within the Kotlin stdlib bundled with the lowest supported IDE (2024.3)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        // Do not copy the platform interfaces' default methods into our classes: those bridges show up as
        // deprecated/internal API usages in Plugin Verifier although the plugin never calls them
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// Machine-specific settings (not committed), e.g. platformLocalPath=/Applications/PhpStorm.app
val localProperties = Properties().apply {
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val platformLocalPath = localProperties.getProperty("platformLocalPath")
    ?: providers.gradleProperty("platformLocalPath").orNull

dependencies {
    intellijPlatform {
        // An installed IDE avoids the download; otherwise the version from gradle.properties is fetched
        if (platformLocalPath != null) local(platformLocalPath) else phpstorm(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    // The platform test framework reports JUnit 4 failures through opentest4j
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    // Browser tests of the tool window page (src/main/resources/webview)
    testImplementation("com.microsoft.playwright:playwright:1.63.0")
}

tasks.test {
    // The multi-IDE registry lives in java.io.tmpdir: tests must not see IDEs running on this machine
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    doFirst { temporaryDir.mkdirs() }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // IDEs checked by `./gradlew verifyPlugin`, e.g. -PverifyIdes=PhpStorm:2024.3,WebStorm:2025.1
    // (CI runs one IDE per job to stay within the runner's disk space)
    pluginVerification {
        ides {
            providers.gradleProperty("verifyIdes").orElse("PhpStorm:2026.2").get()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { ide ->
                    val (type, ideVersion) = ide.split(':')
                    create(IntelliJPlatformType.valueOf(type), ideVersion)
                }
        }
    }
}
