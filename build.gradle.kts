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
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

dependencies {

    intellijPlatform {
        val type = project.property("platformType").toString()
        val version = project.property("platformVersion").toString()
        val pythonPlugin = project.property("pythonPlugin").toString()

        create(type, version, useInstaller = false)

        bundledPlugin("org.jetbrains.plugins.terminal")

        when (type) {
            "PC" -> bundledPlugin("PythonCore")
            "PY" -> bundledPlugin("Pythonid")
            else -> plugin(pythonPlugin)
        }
    }

    implementation("org.java-websocket:Java-WebSocket:1.5.5")
    implementation("io.github.java-native:jssc:2.9.6") {
        exclude("org.slf4j", "slf4j-api")
    }
    implementation("commons-net:commons-net:3.9.0")
    implementation("com.fazecast:jSerialComm:2.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        name = "MicroPython Tools"
    }

    instrumentCode = false

    publishing {
        val tokenFile = file("publish-token.txt")

        if (tokenFile.exists()) {
            val tokenFileContents = tokenFile.readText().toString()

            if (tokenFileContents.isNotBlank()) {
                token = tokenFileContents
            }
        }
    }

    pluginVerification {
        cliPath = file("verifier-cli-1.381-all.jar")

        ides {
            select {
                types = listOf(
                    IntelliJPlatformType.PyCharmProfessional,
                    IntelliJPlatformType.PyCharmCommunity,
                    IntelliJPlatformType.CLion
                )
                channels = listOf(
                    ProductRelease.Channel.RELEASE,
                    ProductRelease.Channel.EAP
                )
                sinceBuild = "243.*"
                untilBuild = "251.*"
            }
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runPyCharmProfessional") {
            type = IntelliJPlatformType.PyCharmProfessional
            version = project.property("platformVersion").toString()
        }

        register("runPyCharmCommunity") {
            type = IntelliJPlatformType.PyCharmCommunity
            version = project.property("platformVersion").toString()
        }

        register("runCLion") {
            type = IntelliJPlatformType.CLion
            version = project.property("platformVersion").toString()

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
            apiVersion = KotlinVersion.KOTLIN_1_9
        }
    }
    withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
        from("$rootDir") {
            into("micropython-tools-jetbrains")
            include("stubs/")
            include("scripts/")
        }
    }
    test {
        testLogging.showExceptions = true
        useJUnitPlatform()
    }
}