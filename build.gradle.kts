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
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

dependencies {
    intellijPlatform {
        val type = project.property("platformType").toString()
        val version = project.property("platformVersion").toString()
        val pythonPlugin = project.property("pythonPlugin").toString()

        create(type, version, useInstaller = false)

        jetbrainsRuntime()

        bundledPlugin("org.jetbrains.plugins.terminal")

        when (type) {
            "PC" -> bundledPlugin("PythonCore")
            "PY" -> bundledPlugin("Pythonid")
            else -> plugin(pythonPlugin)
        }
    }

    implementation("org.json:json:20240303")
    implementation("io.github.java-native:jssc:2.9.6") {
        exclude("org.slf4j", "slf4j-api")
    }
    implementation("com.fazecast:jSerialComm:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Relies on a custom fork of the Java-Websocket library made for this plugin
    // https://github.com/lukaskremla/Java-WebSocket
    implementation(files(project.property("javaWebsocket").toString()))
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
            sinceBuild = project.property("sinceBuild").toString()
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
                sinceBuild = project.property("sinceBuild").toString()
            }
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runPyCharmProfessional") {
            type = IntelliJPlatformType.PyCharmProfessional
            version = project.property("testPlatformVersion").toString()
        }

        register("runPyCharmCommunity") {
            type = IntelliJPlatformType.PyCharmCommunity
            version = project.property("testPlatformVersion").toString()
        }

        register("runCLion") {
            type = IntelliJPlatformType.CLion
            version = project.property("testPlatformVersion").toString()

            plugins {
                plugin(project.property("pythonPlugin").toString())
            }
        }
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            languageVersion = KotlinVersion.DEFAULT
            apiVersion = KotlinVersion.KOTLIN_2_1
        }
    }
    withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
        from("$rootDir") {
            into("micropython-tools-jetbrains")
            include("scripts/")
            include("stubs/")
        }

        from("$rootDir/libs") {
            into("micropython-tools-jetbrains/lib")
            include(project.property("javaWebsocket").toString())
        }
    }
    test {
        testLogging.showExceptions = true
        useJUnitPlatform()
    }
}