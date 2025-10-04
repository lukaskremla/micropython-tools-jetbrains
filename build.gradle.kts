import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/maven-central")
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
        snapshots()
    }
}

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

dependencies {
    intellijPlatform {
        val type = providers.gradleProperty("platformType").get()
        val version = providers.gradleProperty("platformVersion").get()
        val pythonPlugin = providers.gradleProperty("pythonPlugin").get()

        create(type, version) {
            useInstaller.set(false)
        }

        jetbrainsRuntime()

        bundledPlugin("org.jetbrains.plugins.terminal")

        when (type) {
            "PC" -> bundledPlugin("PythonCore")
            "PY" -> bundledPlugin("Pythonid")
            else -> plugin(pythonPlugin)
        }
    }

    implementation("org.json:json:20250517")
    implementation("io.github.java-native:jssc:2.10.2") {
        exclude("org.slf4j", "slf4j-api")
    }
    implementation("com.fazecast:jSerialComm:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Relies on a custom fork of the Java-Websocket library made for this plugin
    // https://github.com/lukaskremla/Java-WebSocket
    implementation(files("oss/libs/Java-WebSocket-1.6.1-CUSTOM_FIX_ver2.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild").get()
        }
    }

    instrumentCode = false

    publishing {
        val tokenFile = file("publish-token.txt")

        if (tokenFile.exists()) {
            val tokenFileContents = tokenFile.readText()

            if (tokenFileContents.isNotBlank()) {
                token = tokenFileContents
            }
        }
    }

    pluginVerification {
        ides {
            select {
                types = listOf(
                    IntelliJPlatformType.PyCharmCommunity
                )
                channels = listOf(
                    ProductRelease.Channel.RELEASE,
                    ProductRelease.Channel.EAP
                )
                sinceBuild = providers.gradleProperty("sinceBuild").get()
            }
        }
    }
}

@Suppress("unused")
intellijPlatformTesting {
    val testPlatformVersion = providers.gradleProperty("testPlatformVersion")

    val runPyCharmProfessional by runIde.registering {
        type = IntelliJPlatformType.PyCharmProfessional
        version = testPlatformVersion
    }

    val runPyCharmCommunity by runIde.registering {
        type = IntelliJPlatformType.PyCharmCommunity
        version = testPlatformVersion
    }

    val runCLion by runIde.registering {
        type = IntelliJPlatformType.CLion
        version = testPlatformVersion
        plugins {
            plugin(providers.gradleProperty("pythonPlugin"))
        }
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            languageVersion = KotlinVersion.DEFAULT
            apiVersion = KotlinVersion.KOTLIN_2_2
        }
    }
    named<ProcessResources>("processResources") {
        from("scripts") {
            into("scripts")
            include("**/*")
        }
    }
    test {
        testLogging.showExceptions = true
        useJUnitPlatform()
    }
}